"""Owner-only SQLite spill storage for authenticated event reassembly."""

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
    "assigned_participant_id, collector_id, payload_schema_version, payload_type, "
    "boot_session_id, monotonic_time_nanos, wall_time_utc_millis, fields_json, "
    "canonical_bytes, source_ciphertext_sha256, source_bundle_id, "
    "source_configuration_sha256, source_object, content_sha256"
)
EVENT_PLACEHOLDERS = ",".join("?" for _ in range(18))


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
        self.connection.execute(
            f"CREATE TABLE candidates ({_column_definitions()})"
        )

    def add(self, event: VerifiedEvent) -> None:
        self.connection.execute(
            f"INSERT INTO candidates ({EVENT_COLUMNS}) VALUES ({EVENT_PLACEHOLDERS})",
            _event_row(event),
        )

    def finish_candidates(self) -> None:
        self.connection.commit()
        self.connection.execute(
            "CREATE INDEX candidate_identity ON candidates "
            "(experiment_id, configuration_id, participant_instance_id, "
            "sequence_number, content_sha256, source_ciphertext_sha256, "
            "source_object, source_bundle_id)"
        )
        self.connection.execute(f"CREATE TABLE accepted ({_column_definitions()})")
        self.connection.commit()

    def candidate_rows(self):
        return self.connection.execute(
            f"SELECT {EVENT_COLUMNS} FROM candidates ORDER BY "
            "experiment_id, configuration_id, participant_instance_id, "
            "sequence_number, content_sha256, canonical_bytes, "
            "source_ciphertext_sha256, "
            "source_object, source_bundle_id"
        )

    def accept(self, row: tuple) -> None:
        self.connection.execute(
            f"INSERT INTO accepted ({EVENT_COLUMNS}) VALUES ({EVENT_PLACEHOLDERS})",
            row,
        )

    def seal(self) -> DiskEventCollection:
        self.connection.commit()
        self.connection.execute(
            "CREATE INDEX accepted_identity ON accepted "
            "(experiment_id, configuration_id, participant_instance_id, sequence_number)"
        )
        self.connection.execute(
            "CREATE INDEX accepted_partition ON accepted "
            "(experiment_id, configuration_id, collector_id, "
            "payload_schema_version, payload_type, participant_instance_id, "
            "sequence_number)"
        )
        count = self.connection.execute("SELECT COUNT(*) FROM accepted").fetchone()[0]
        self.connection.commit()
        self.connection.close()
        return DiskEventCollection(self.path, count)

    def abort(self) -> None:
        self.connection.close()
        self.path.unlink(missing_ok=True)


class DiskEventCollection:
    """Repeatable, query-ordered event iterable backed by a private database."""

    def __init__(self, path: Path, count: int):
        self.path = path
        self.count = count
        self.closed = False

    def __len__(self) -> int:
        return self.count

    def __iter__(self) -> Iterator[VerifiedEvent]:
        yield from self._query(
            "experiment_id, configuration_id, participant_instance_id, sequence_number"
        )

    def iter_partitioned(self) -> Iterator[VerifiedEvent]:
        yield from self._query(
            "experiment_id, configuration_id, collector_id, "
            "payload_schema_version, payload_type, participant_instance_id, "
            "sequence_number"
        )

    def iter_boot_sessions(self) -> Iterator[BootSession]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, "
            "boot_session_id FROM accepted GROUP BY experiment_id, configuration_id, "
            "participant_instance_id, boot_session_id ORDER BY experiment_id, "
            "configuration_id, participant_instance_id, boot_session_id"
        )
        for row in self._raw_query(query):
            yield BootSession(*row)

    def iter_sampling_groups(
        self,
        source_clock_fields: Mapping[tuple[str, int, str], str],
    ) -> Iterator[SamplingGroup]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, "
            "collector_id, boot_session_id, payload_schema_version, payload_type, "
            "fields_json FROM accepted ORDER BY experiment_id, configuration_id, "
            "participant_instance_id, collector_id, boot_session_id, sequence_number"
        )
        current_key: tuple[str, str, str, str, str] | None = None
        current_field = ""
        first = 0
        last = 0
        count = 0
        for row in self._raw_query(query):
            field = source_clock_fields.get((row[3], row[5], row[6]))
            if field is None:
                continue
            fields = json.loads(row[7])
            timestamp = fields.get(field)
            if isinstance(timestamp, bool) or not isinstance(timestamp, int):
                raise ValidationError("sampling source clock is not an integer")
            key = row[:5]
            if current_key is not None and key != current_key:
                yield SamplingGroup(*current_key, current_field, first, last, count)
                count = 0
            if count == 0:
                current_key = key
                current_field = field
                first = timestamp
                last = timestamp
            else:
                if field != current_field:
                    raise ValidationError(
                        "collector uses inconsistent sampling source clocks"
                    )
                first = min(first, timestamp)
                last = max(last, timestamp)
            count += 1
        if current_key is not None:
            yield SamplingGroup(*current_key, current_field, first, last, count)

    def iter_survey_lifecycle_counts(self) -> Iterator[SurveyLifecycleCount]:
        query = (
            "SELECT experiment_id, configuration_id, participant_instance_id, "
            "payload_type, COUNT(*) FROM accepted WHERE collector_id = "
            "'interventions.v1' AND payload_type LIKE 'SURVEY_%' GROUP BY "
            "experiment_id, configuration_id, participant_instance_id, payload_type "
            "ORDER BY experiment_id, configuration_id, participant_instance_id, "
            "payload_type"
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
            cursor = connection.execute(
                f"SELECT {EVENT_COLUMNS} FROM accepted ORDER BY {ordering}"
            )
            for row in cursor:
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
        "assigned_participant_id TEXT, collector_id TEXT NOT NULL, "
        "payload_schema_version INTEGER NOT NULL, payload_type TEXT NOT NULL, "
        "boot_session_id TEXT NOT NULL, monotonic_time_nanos INTEGER NOT NULL, "
        "wall_time_utc_millis INTEGER NOT NULL, fields_json TEXT NOT NULL, "
        "canonical_bytes BLOB NOT NULL, source_ciphertext_sha256 TEXT NOT NULL, "
        "source_bundle_id TEXT NOT NULL, source_configuration_sha256 TEXT NOT NULL, "
        "source_object TEXT NOT NULL, content_sha256 TEXT NOT NULL"
    )


def _event_row(event: VerifiedEvent) -> tuple:
    fields = json.dumps(
        event.fields,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return (
        event.experiment_id,
        event.configuration_id,
        event.participant_instance_id,
        event.sequence_number,
        event.assigned_participant_id,
        event.collector_id,
        event.payload_schema_version,
        event.payload_type,
        event.boot_session_id,
        event.monotonic_time_nanos,
        event.wall_time_utc_millis,
        fields,
        event.canonical_bytes,
        event.provenance.source_ciphertext_sha256,
        event.provenance.source_bundle_id,
        event.provenance.source_configuration_sha256,
        event.provenance.source_object,
        hashlib.sha256(event.canonical_bytes).hexdigest(),
    )


def _row_event(row: tuple) -> VerifiedEvent:
    return VerifiedEvent(
        row[0],
        row[1],
        row[2],
        row[4],
        row[3],
        row[5],
        row[6],
        row[7],
        row[8],
        row[9],
        row[10],
        json.loads(row[11]),
        bytes(row[12]),
        EventProvenance(row[13], row[14], row[15], row[16]),
    )
