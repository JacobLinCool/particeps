"""Typed dataset extension point and the sole supported Parquet implementation."""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from collections.abc import Mapping
from itertools import groupby
from pathlib import Path
from typing import Any, Protocol

import pyarrow as pa
import pyarrow.parquet as pq

from . import __version__
from .catalog import CollectorCatalog, PayloadSchema
from .errors import ConflictError, ValidationError
from .filesystem import rename_noreplace
from .jcs import canonicalize
from .models import PartitionedVerifiedEvents, VerifiedEvent
from .reassembly import ReassemblyResult
from .summary import BoundedExamples

PARQUET_BATCH_MAX_ROWS = 65_536
PARQUET_BATCH_MAX_ESTIMATED_BYTES = 16 * 1024 * 1024


class DatasetSink(Protocol):
    """Extension point for validated events; this release implements only Parquet."""

    def write(
        self,
        result: ReassemblyResult,
        destination: Path,
        *,
        validation_failures: tuple[Mapping[str, str], ...] = (),
    ) -> Path: ...


class ParquetSink:
    def __init__(self, catalog: CollectorCatalog):
        self.catalog = catalog

    def write(
        self,
        result: ReassemblyResult,
        destination: Path,
        *,
        validation_failures: tuple[Mapping[str, str], ...] = (),
    ) -> Path:
        if result.has_conflicts:
            raise ConflictError(
                "conflicting authenticated identities; dataset was not materialized"
            )
        destination = Path(destination).absolute()
        if destination.is_symlink():
            raise ValidationError("dataset destination must not be a symbolic link")
        if destination.exists():
            raise ValidationError("dataset destination already exists")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = Path(
            tempfile.mkdtemp(prefix=f".{destination.name}-", dir=destination.parent)
        )
        try:
            partitions = self._write_partitions(result.events, temporary)
            quality = dict(result.quality)
            quality["observations"] = _observations(
                result.events,
                self.catalog.sampling_clock_fields,
            )
            _write_file(temporary / "quality-summary.json", canonicalize(quality))
            manifest = {
                "dataset_format": "adc-parquet-dataset-v1",
                "parser_version": __version__,
                "partitions": partitions,
                "source_ciphertexts": [
                    {
                        "bundle_id": bundle.bundle_id,
                        "bundle_kind": bundle.bundle_kind,
                        "byte_count": str(bundle.source.byte_count),
                        "configuration_id": bundle.configuration_id,
                        "configuration_sha256": bundle.configuration_sha256,
                        "event_count": str(bundle.event_count),
                        "experiment_id": bundle.experiment_id,
                        "first_sequence_number": str(bundle.first_sequence_number),
                        "last_sequence_number": str(bundle.last_sequence_number),
                        "participant_instance_id": bundle.participant_instance_id,
                        "receiver_received_at_utc_untrusted": (
                            bundle.source.metadata["received_at_utc"]
                            if bundle.source.metadata is not None
                            else None
                        ),
                        "sha256": bundle.source.sha256,
                        "source_object": bundle.source.source_uri,
                    }
                    for bundle in result.bundles
                ],
                "validation_failures": [dict(item) for item in validation_failures],
            }
            _write_file(temporary / "dataset-manifest.json", canonicalize(manifest))
            rename_noreplace(temporary, destination)
            return destination
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)

    def _write_partitions(
        self, events: PartitionedVerifiedEvents, root: Path
    ) -> list[dict[str, str]]:
        ordered = events.iter_partitioned()
        manifest: list[dict[str, str]] = []
        for key, partition_events in groupby(ordered, key=_partition_key):
            experiment, configuration, collector, version, payload_type = key
            schema = self.catalog.payload(collector, version, payload_type)
            arrow_schema = _arrow_schema(schema)
            directory = (
                root
                / f"experiment_id={experiment}"
                / f"configuration_id={configuration}"
                / f"collector_id={collector}"
                / f"payload_schema_version={version}"
                / f"payload_type={payload_type}"
            )
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / "part-00000.parquet"
            writer = pq.ParquetWriter(
                path,
                arrow_schema,
                compression="zstd",
                use_dictionary=False,
                write_statistics=True,
                version="2.6",
            )
            row_count = 0
            rows: list[dict[str, Any]] = []
            estimated_bytes = 0
            try:
                for event in partition_events:
                    event_bytes = _estimated_row_bytes(event)
                    if rows and (
                        len(rows) >= PARQUET_BATCH_MAX_ROWS
                        or estimated_bytes + event_bytes
                        > PARQUET_BATCH_MAX_ESTIMATED_BYTES
                    ):
                        row_count += _write_rows(writer, rows, arrow_schema)
                        rows.clear()
                        estimated_bytes = 0
                    rows.append(_row(event, schema))
                    estimated_bytes += event_bytes
                    if (
                        len(rows) >= PARQUET_BATCH_MAX_ROWS
                        or estimated_bytes >= PARQUET_BATCH_MAX_ESTIMATED_BYTES
                    ):
                        row_count += _write_rows(writer, rows, arrow_schema)
                        rows.clear()
                        estimated_bytes = 0
                if rows:
                    row_count += _write_rows(writer, rows, arrow_schema)
            finally:
                writer.close()
            relative = str(path.relative_to(root))
            manifest.append(
                {
                    "file": relative,
                    "row_count": str(row_count),
                    "sha256": _sha256(path),
                }
            )
        return manifest


def _arrow_schema(schema: PayloadSchema) -> pa.Schema:
    fields = [
        pa.field("participant_instance_id", pa.string(), nullable=False),
        pa.field("assigned_participant_id", pa.string(), nullable=True),
        pa.field("sequence_number", pa.int64(), nullable=False),
        pa.field("observed_wall_time_utc_millis", pa.int64(), nullable=False),
        pa.field("observed_monotonic_time_nanos", pa.int64(), nullable=False),
        pa.field("observed_boot_session_id", pa.string(), nullable=False),
    ]
    reserved = {field.name for field in fields} | {
        "experiment_id",
        "configuration_id",
        "collector_id",
        "payload_schema_version",
        "payload_type",
        "source_ciphertext_sha256",
        "source_bundle_id",
        "source_configuration_sha256",
        "source_object",
        "parser_version",
    }
    for name, descriptor in sorted(schema.fields.items()):
        if name in reserved:
            raise ValidationError(
                f"payload field collides with dataset provenance: {name}"
            )
        metadata = {
            b"adc.meaning": str(descriptor["meaning"]).encode(),
            b"adc.type": descriptor["type"].encode(),
            b"adc.unit": str(descriptor.get("unit", "none")).encode(),
        }
        if descriptor.get("clock_basis") is not None:
            metadata[b"adc.clock_basis"] = str(descriptor["clock_basis"]).encode()
        fields.append(
            pa.field(
                name,
                _arrow_type(descriptor["type"]),
                nullable=not descriptor["required"],
                metadata=metadata,
            )
        )
    fields.extend(
        [
            pa.field("source_ciphertext_sha256", pa.string(), nullable=False),
            pa.field("source_bundle_id", pa.string(), nullable=False),
            pa.field("source_configuration_sha256", pa.string(), nullable=False),
            pa.field("source_object", pa.string(), nullable=False),
            pa.field("parser_version", pa.string(), nullable=False),
        ]
    )
    return pa.schema(
        fields,
        metadata={
            b"adc.collector_id": schema.collector_id.encode(),
            b"adc.payload_schema_version": str(schema.schema_version).encode(),
            b"adc.payload_type": schema.payload_type.encode(),
        },
    )


def _arrow_type(kind: str) -> pa.DataType:
    return {
        "boolean": pa.bool_(),
        "decimal_string": pa.int64(),
        "enum": pa.string(),
        "float32": pa.float32(),
        "float64": pa.float64(),
        "int32": pa.int32(),
        "json_string": pa.string(),
        "string": pa.string(),
    }[kind]


def _row(event: VerifiedEvent, schema: PayloadSchema) -> dict[str, Any]:
    row = {
        "participant_instance_id": event.participant_instance_id,
        "assigned_participant_id": event.assigned_participant_id,
        "sequence_number": event.sequence_number,
        "observed_wall_time_utc_millis": event.wall_time_utc_millis,
        "observed_monotonic_time_nanos": event.monotonic_time_nanos,
        "observed_boot_session_id": event.boot_session_id,
        "source_ciphertext_sha256": event.provenance.source_ciphertext_sha256,
        "source_bundle_id": event.provenance.source_bundle_id,
        "source_configuration_sha256": event.provenance.source_configuration_sha256,
        "source_object": event.provenance.source_object,
        "parser_version": __version__,
    }
    for name in schema.fields:
        row[name] = event.fields.get(name)
    return row


def _partition_key(event: VerifiedEvent) -> tuple[str, str, str, int, str]:
    return (
        event.experiment_id,
        event.configuration_id,
        event.collector_id,
        event.payload_schema_version,
        event.payload_type,
    )


def _write_rows(
    writer: pq.ParquetWriter,
    rows: list[dict[str, Any]],
    schema: pa.Schema,
) -> int:
    writer.write_table(
        pa.Table.from_pylist(rows, schema=schema),
        row_group_size=len(rows),
    )
    return len(rows)


def _estimated_row_bytes(event: VerifiedEvent) -> int:
    """Conservatively bound each in-memory Arrow construction batch."""

    strings = (
        event.experiment_id,
        event.configuration_id,
        event.participant_instance_id,
        event.assigned_participant_id or "",
        event.collector_id,
        event.payload_type,
        event.boot_session_id,
        event.provenance.source_ciphertext_sha256,
        event.provenance.source_bundle_id,
        event.provenance.source_configuration_sha256,
        event.provenance.source_object,
        __version__,
    )
    return len(event.canonical_bytes) + sum(len(value.encode()) for value in strings) + 512


def _observations(
    events: PartitionedVerifiedEvents,
    source_clock_fields: Mapping[tuple[str, int, str], str],
) -> dict[str, Any]:
    boot_sessions = BoundedExamples[dict[str, Any]]()
    for participant, participant_sessions in groupby(
        events.iter_boot_sessions(),
        key=lambda item: (
            item.experiment_id,
            item.configuration_id,
            item.participant_instance_id,
        ),
    ):
        if boot_sessions.has_capacity:
            session_ids = BoundedExamples[str]()
            for session in participant_sessions:
                session_ids.add(session.boot_session_id)
            boot_sessions.add(
                {
                    "boot_session_ids": session_ids.document(),
                    "configuration_id": participant[1],
                    "experiment_id": participant[0],
                    "participant_instance_id": participant[2],
                }
            )
        else:
            for _ in participant_sessions:
                pass
            boot_sessions.add_count(1)

    achieved = BoundedExamples[dict[str, Any]]()
    for group in events.iter_sampling_groups(source_clock_fields):
        duration_nanos = max(
            0,
            group.last_monotonic_time_nanos
            - group.first_monotonic_time_nanos,
        )
        interval_count = max(0, group.event_count - 1)
        achieved.add(
            {
                "boot_session_id": group.boot_session_id,
                "clock_basis": "continuous_monotonic_since_boot",
                "collector_id": group.collector_id,
                "configuration_id": group.configuration_id,
                "duration_monotonic_nanos": str(duration_nanos),
                "event_count": str(group.event_count),
                "experiment_id": group.experiment_id,
                "mean_sampling_rate_millihertz": _mean_rate_millihertz(
                    interval_count,
                    duration_nanos,
                ),
                "participant_instance_id": group.participant_instance_id,
                "sampling_interval_count": str(interval_count),
                "source_clock_field": group.source_clock_field,
            }
        )

    survey_lifecycle = BoundedExamples[dict[str, str]]()
    for group in events.iter_survey_lifecycle_counts():
        survey_lifecycle.add(
            {
                "configuration_id": group.configuration_id,
                "event_count": str(group.event_count),
                "experiment_id": group.experiment_id,
                "participant_instance_id": group.participant_instance_id,
                "payload_type": group.payload_type,
            }
        )

    temporal_changes = BoundedExamples[dict[str, str]]()
    for event in events:
        if event.collector_id == "temporal_context.v1":
            temporal_changes.add(
                {
                    "change_reason": str(event.fields.get("change_reason", "")),
                    "configuration_id": event.configuration_id,
                    "experiment_id": event.experiment_id,
                    "participant_instance_id": event.participant_instance_id,
                    "sequence_number": str(event.sequence_number),
                    "timezone_id": str(event.fields.get("timezone_id", "")),
                }
            )
    return {
        "achieved_sampling_observations": achieved.document(),
        "boot_sessions": boot_sessions.document(),
        "survey_lifecycle_counts": survey_lifecycle.document(),
        "temporal_context_events": temporal_changes.document(),
    }


def _mean_rate_millihertz(interval_count: int, duration_nanos: int) -> str | None:
    if interval_count == 0 or duration_nanos == 0:
        return None
    numerator = interval_count * 1_000_000_000_000
    return str((numerator + duration_nanos // 2) // duration_nanos)


def _write_file(path: Path, data: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
