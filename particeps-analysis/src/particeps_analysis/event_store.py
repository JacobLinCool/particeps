"""Owner-only SQLite spill storage for verified event reassembly."""

from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import tempfile
from collections.abc import Iterator, Mapping
from pathlib import Path

from .errors import ValidationError
from .filesystem import private_directory
from .models import (
    BootSession,
    EventProvenance,
    SamplingGroup,
    SurveyLifecycleCount,
    VerifiedEvent,
)

EVENT_COLUMNS = (
    "experiment_id, configuration_id, participant_instance_id, sequence_number, "
    "assigned_participant_id, source_id, schema_version, event_type, "
    "condition_epoch_id, source_condition_epoch_id, boot_session_id, "
    "monotonic_time_nanos, wall_time_utc_millis, fields_json, canonical_bytes, "
    "source_ciphertext_sha256, source_bundle_id, source_configuration_sha256, "
    "source_object, source_commit_sequence, source_observation_sequence, content_sha256"
)
EVENT_PLACEHOLDERS = ",".join("?" for _ in range(22))


class EventDatabase:
    def __init__(self, directory: Path):
        directory = private_directory(directory)
        fd, name = tempfile.mkstemp(prefix="particeps-reassembly-", suffix=".sqlite3", dir=directory)
        os.close(fd)
        self.path = Path(name)
        os.chmod(self.path, 0o600)
        self.connection = sqlite3.connect(self.path)
        self.connection.execute("PRAGMA trusted_schema = OFF")
        self.connection.execute("PRAGMA synchronous = FULL")
        self.connection.execute("PRAGMA journal_mode = DELETE")
        self.connection.execute(f"CREATE TABLE candidates ({_column_definitions()})")

    def add(self, event: VerifiedEvent) -> None:
        self.connection.execute(
            f"INSERT INTO candidates ({EVENT_COLUMNS}) VALUES ({EVENT_PLACEHOLDERS})",
            _event_row(event),
        )

    def finish_candidates(self) -> None:
        self.connection.commit()
        self.connection.execute(
            "CREATE INDEX candidate_identity ON candidates "
            "(experiment_id, configuration_id, participant_instance_id, sequence_number, "
            "content_sha256, source_ciphertext_sha256, source_object, source_bundle_id)"
        )
        self.connection.execute(f"CREATE TABLE accepted ({_column_definitions()})")
        self.connection.commit()

    def candidate_rows(self):
        return self.connection.execute(
            f"SELECT {EVENT_COLUMNS} FROM candidates ORDER BY experiment_id, "
            "configuration_id, participant_instance_id, sequence_number, content_sha256, "
            "canonical_bytes, source_ciphertext_sha256, source_object, source_bundle_id"
        )

    def accept(self, row: tuple) -> None:
        self.connection.execute(
            f"INSERT INTO accepted ({EVENT_COLUMNS}) VALUES ({EVENT_PLACEHOLDERS})", row
        )

    def seal(self) -> DiskEventCollection:
        self.connection.commit()
        self.connection.execute(
            "CREATE INDEX accepted_identity ON accepted "
            "(experiment_id, configuration_id, participant_instance_id, sequence_number)"
        )
        self.connection.execute(
            "CREATE INDEX accepted_partition ON accepted "
            "(experiment_id, configuration_id, source_id, schema_version, event_type, "
            "participant_instance_id, sequence_number)"
        )
        count = self.connection.execute("SELECT COUNT(*) FROM accepted").fetchone()[0]
        self.connection.commit()
        self.connection.close()
        return DiskEventCollection(self.path, count)

    def abort(self) -> None:
        self.connection.close()
        self.path.unlink(missing_ok=True)


class DiskEventCollection:
    def __init__(self, path: Path, count: int):
        self.path, self.count, self.closed = path, count, False

    def __len__(self) -> int:
        return self.count

    def __iter__(self) -> Iterator[VerifiedEvent]:
        yield from self._query(
            "experiment_id, configuration_id, participant_instance_id, sequence_number"
        )

    def iter_partitioned(self) -> Iterator[VerifiedEvent]:
        yield from self._query(
            "experiment_id, configuration_id, source_id, schema_version, event_type, "
            "participant_instance_id, sequence_number"
        )

    def iter_boot_sessions(self) -> Iterator[BootSession]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, boot_session_id "
            "FROM accepted GROUP BY experiment_id, configuration_id, participant_instance_id, "
            "boot_session_id ORDER BY experiment_id, configuration_id, participant_instance_id, "
            "boot_session_id"
        )
        for row in self._raw_query(query):
            yield BootSession(*row)

    def iter_sampling_groups(
        self, source_clock_fields: Mapping[tuple[str, int, str], str]
    ) -> Iterator[SamplingGroup]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, source_id, "
            "boot_session_id, schema_version, event_type, fields_json FROM accepted ORDER BY "
            "experiment_id, configuration_id, participant_instance_id, source_id, "
            "boot_session_id, sequence_number"
        )
        current_key = None
        current_field, first, last, count = "", 0, 0, 0
        for row in self._raw_query(query):
            field = source_clock_fields.get((row[3], row[5], row[6]))
            if field is None:
                continue
            timestamp = json.loads(row[7]).get(field)
            if isinstance(timestamp, bool) or not isinstance(timestamp, int):
                raise ValidationError("sampling source clock is not an integer")
            key = row[:5]
            if current_key is not None and key != current_key:
                yield SamplingGroup(*current_key, current_field, first, last, count)
                count = 0
            if count == 0:
                current_key, current_field, first, last = key, field, timestamp, timestamp
            else:
                if field != current_field:
                    raise ValidationError("source uses inconsistent sampling clocks")
                first, last = min(first, timestamp), max(last, timestamp)
            count += 1
        if current_key is not None:
            yield SamplingGroup(*current_key, current_field, first, last, count)

    def iter_survey_lifecycle_counts(self) -> Iterator[SurveyLifecycleCount]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, event_type, "
            "COUNT(*) FROM accepted WHERE source_id = 'interventions.v1' AND event_type LIKE "
            "'SURVEY_%' GROUP BY experiment_id, configuration_id, participant_instance_id, "
            "event_type ORDER BY experiment_id, configuration_id, participant_instance_id, event_type"
        )
        for row in self._raw_query(query):
            yield SurveyLifecycleCount(*row)

    def close(self) -> None:
        if not self.closed:
            self.path.unlink(missing_ok=True)
            self.closed = True

    def __del__(self) -> None:
        try:
            self.close()
        except OSError:
            pass

    def _query(self, ordering: str) -> Iterator[VerifiedEvent]:
        if self.closed:
            raise ValidationError("reassembled event store is closed")
        connection = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        try:
            for row in connection.execute(f"SELECT {EVENT_COLUMNS} FROM accepted ORDER BY {ordering}"):
                yield _row_event(row)
        finally:
            connection.close()

    def _raw_query(self, query: str):
        if self.closed:
            raise ValidationError("reassembled event store is closed")
        connection = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
        try:
            yield from connection.execute(query)
        finally:
            connection.close()


def _column_definitions() -> str:
    return (
        "experiment_id TEXT NOT NULL, configuration_id TEXT NOT NULL, "
        "participant_instance_id TEXT NOT NULL, sequence_number INTEGER NOT NULL, "
        "assigned_participant_id TEXT, source_id TEXT NOT NULL, schema_version INTEGER NOT NULL, "
        "event_type TEXT NOT NULL, condition_epoch_id TEXT, source_condition_epoch_id TEXT, "
        "boot_session_id TEXT NOT NULL, monotonic_time_nanos INTEGER NOT NULL, "
        "wall_time_utc_millis INTEGER NOT NULL, fields_json TEXT NOT NULL, canonical_bytes BLOB NOT NULL, "
        "source_ciphertext_sha256 TEXT NOT NULL, source_bundle_id TEXT NOT NULL, "
        "source_configuration_sha256 TEXT NOT NULL, source_object TEXT NOT NULL, "
        "source_commit_sequence INTEGER NOT NULL, source_observation_sequence INTEGER, "
        "content_sha256 TEXT NOT NULL"
    )


def _event_row(event: VerifiedEvent) -> tuple:
    fields = json.dumps(event.fields, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":"))
    provenance = event.provenance
    return (
        event.experiment_id, event.configuration_id, event.participant_instance_id,
        event.sequence_number, event.assigned_participant_id, event.source_id,
        event.schema_version, event.event_type, event.condition_epoch_id,
        event.source_condition_epoch_id, event.boot_session_id, event.monotonic_time_nanos,
        event.wall_time_utc_millis, fields, event.canonical_bytes,
        provenance.source_ciphertext_sha256, provenance.source_bundle_id,
        provenance.source_configuration_sha256, provenance.source_object,
        provenance.source_commit_sequence, provenance.source_observation_sequence,
        hashlib.sha256(event.canonical_bytes).hexdigest(),
    )


def _row_event(row: tuple) -> VerifiedEvent:
    return VerifiedEvent(
        row[0], row[1], row[2], row[4], row[3], row[5], row[6], row[7], row[8],
        row[9], row[10], row[11], row[12], json.loads(row[13]), bytes(row[14]),
        EventProvenance(row[15], row[16], row[17], row[18], row[19], row[20]),
    )
