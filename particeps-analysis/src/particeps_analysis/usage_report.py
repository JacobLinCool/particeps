"""Conservative foreground-use summaries from an authenticated, materialized dataset.

Raw usage rows and reconstructed intervals stay in a private SQLite spill database. Query
coverage is evidence about successful queries, never proof of Android event completeness.
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import shutil
import sqlite3
import tempfile
from contextlib import closing
from datetime import UTC, datetime, time, timedelta
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import pyarrow as pa
import pyarrow.parquet as pq

from .errors import ValidationError
from .filesystem import rename_noreplace
from .registry import EventSourceRegistry
from .traffic_report import (
    TRAFFIC_EVENTS,
    build_traffic_intervals,
    create_traffic_tables,
    load_traffic_receipt,
    traffic_window,
)

USAGE = "usage_events.v1"
ACTIVITIES = {"ACTIVITY_RESUMED", "ACTIVITY_PAUSED", "ACTIVITY_STOPPED"}
CLOCK_GAPS = {"CLOCK_DISCONTINUITY", "WALL_CLOCK_CHANGED"}
IDENTITY = ("experiment_id", "configuration_id", "participant_instance_id")
CSV_FIELDS = [
    *IDENTITY,
    "local_date",
    "period",
    "scope",
    "package_name",
    "start_utc_millis",
    "end_utc_millis",
    "planned_millis",
    "reached_millis",
    "query_covered_millis",
    "query_coverage_of_reached",
    "query_coverage_of_planned",
    "usage_status",
    "paired_foreground_millis",
    "observed_resumed_count",
    "covered_resumed_count",
    "censored_resumes",
    "unmatched_closes",
    "outside_coverage_events",
]
TRAFFIC_CSV_FIELDS = [
    *IDENTITY,
    "local_date",
    "period",
    "start_utc_millis",
    "end_utc_millis",
    "planned_millis",
    "reached_millis",
    "not_yet_reached_millis",
    "runtime_limited_millis",
    "runtime_500_500_millis",
    "runtime_unlimited_millis",
    "unknown_profile_millis",
]


def usage_report(
    dataset: Path, output: Path, timezone_name: str, duration_hours: int = 120
) -> Path:
    """Publish a create-only directory containing CSV, participant JSONL and provenance."""
    if not 1 <= duration_hours <= 24 * 366:
        raise ValidationError("duration-hours must be between 1 and 8784")
    try:
        zone = ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValidationError("timezone must be an explicit IANA timezone") from error
    dataset, output = Path(dataset).absolute(), Path(output).absolute()
    if dataset.is_symlink() or not dataset.is_dir():
        raise ValidationError("dataset must be a real directory")
    if output.exists() or output.is_symlink():
        raise ValidationError("usage report destination already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}-", dir=output.parent))
    os.chmod(temporary, 0o700)
    try:
        manifest, manifest_bytes = _document(dataset, "dataset-manifest.json")
        quality, quality_bytes = _document(dataset, "quality-summary.json")
        if manifest.get("dataset_format") != "particeps-parquet-dataset-v1":
            raise ValidationError("unsupported dataset format")
        if manifest.get("event_source_registry_sha256") != EventSourceRegistry().digest:
            raise ValidationError("dataset event registry does not match this analyzer")
        if manifest.get("validation_failures"):
            raise ValidationError("dataset contains validation failures")
        if (
            manifest.get("quality_summary_sha256")
            != hashlib.sha256(quality_bytes).hexdigest()
        ):
            raise ValidationError(
                "quality-summary digest missing or mismatched; materialize the dataset again"
            )
        participants = quality["commit_chain_verification"]["participants"]
        identities = [
            tuple(record[name] for name in IDENTITY) for record in participants
        ]
        if len(set(identities)) != len(identities):
            raise ValidationError("duplicate participant quality records")
        source_identities = {
            tuple(record[name] for name in IDENTITY)
            for record in manifest["source_ciphertexts"]
        }
        if set(identities) != source_identities:
            raise ValidationError(
                "participant quality records do not match the dataset manifest"
            )
        db_path = temporary / "usage.sqlite"
        with closing(sqlite3.connect(db_path)) as db:
            os.chmod(db_path, 0o600)
            _create_tables(db)
            create_traffic_tables(db)
            identity_ids = {
                identity: index for index, identity in enumerate(identities)
            }
            _load_events(db, dataset, manifest, identity_ids)
            with (
                (temporary / "windows.csv").open(
                    "w", newline="", encoding="utf-8"
                ) as csv_file,
                (temporary / "participants.jsonl").open("w", encoding="utf-8") as jsonl,
                (temporary / "traffic-conditions.csv").open(
                    "w", newline="", encoding="utf-8"
                ) as traffic_file,
            ):
                writer = csv.DictWriter(csv_file, fieldnames=CSV_FIELDS)
                writer.writeheader()
                traffic_writer = csv.DictWriter(
                    traffic_file, fieldnames=TRAFFIC_CSV_FIELDS
                )
                traffic_writer.writeheader()
                for pid, participant in enumerate(participants):
                    summary = _participant(
                        db,
                        pid,
                        participant,
                        zone,
                        duration_hours,
                        writer,
                        traffic_writer,
                    )
                    jsonl.write(
                        json.dumps(summary, ensure_ascii=False, sort_keys=True) + "\n"
                    )
            db.commit()
        db_path.unlink()
        metadata = {
            "report_format": "particeps-usage-report-v1",
            "dataset_manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            "quality_summary_sha256": hashlib.sha256(quality_bytes).hexdigest(),
            "analysis_reference_timezone": timezone_name,
            "duration_hours": duration_hours,
            "participant_count": len(participants),
            "periods": {
                "before": "00:00–12:00",
                "during": "12:00–17:00",
                "after": "17:00–24:00",
            },
            "interpretation": [
                "Timezone is a fixed analysis reference, not the device's changing treatment timezone.",
                "Foreground milliseconds are the union of Activity intervals paired from delivered events, not verified true usage time.",
                "Missing intermediate pause/resume events can overestimate a paired interval; missing endpoints can underestimate usage.",
                "Zero means no fully paired duration was observed, not no use. Blank duration means no verified query coverage.",
                "Query coverage does not prove that Android delivered every event or that the participant was actively interacting.",
                "Unclosed resumes are censored; no duration is imputed to a subsequent unrelated event or the report end.",
                "Horizon reached is not evidence of uninterrupted collection; future time is excluded from the reached denominator.",
                "Traffic conditions describe runtime-applied profiles, not proof of actual network speed or participant demand.",
                "Runtime profile intervals require APPLIED plus a matching SNAPSHOT; a matching REMOVED bounds a closed epoch.",
                "Without a verified removal, including process recovery and open epochs, the tail after the last traffic snapshot is unknown.",
                "Missing applied receipts and overlapping epoch wall-time intervals are unknown, never assumed baseline.",
                "This descriptive report does not estimate causal effects or cross-device substitution.",
            ],
        }
        (temporary / "report.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n"
        )
        for file in temporary.iterdir():
            os.chmod(file, 0o600)
        rename_noreplace(temporary, output)
        return output
    except (KeyError, TypeError, ValueError, sqlite3.Error, pa.ArrowException) as error:
        raise ValidationError("invalid usage-report dataset structure") from error
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def _safe_file(root: Path, relative: str) -> Path:
    path = Path(relative)
    if (
        path.is_absolute()
        or not path.parts
        or any(part in {".", ".."} for part in path.parts)
    ):
        raise ValidationError("dataset manifest contains an unsafe path")
    current = root
    for part in path.parts:
        current = current / part
        if current.is_symlink():
            raise ValidationError("dataset files must not be symbolic links")
    if not current.is_file():
        raise ValidationError("dataset manifest file is missing")
    return current


def _document(root: Path, name: str) -> tuple[dict[str, Any], bytes]:
    data = _safe_file(root, name).read_bytes()
    return json.loads(data), data


def _create_tables(db: sqlite3.Connection) -> None:
    db.executescript("""
        PRAGMA temp_store=FILE;
        PRAGMA cache_size=-8192;
        CREATE TABLE starts(pid INTEGER, t INTEGER, boot TEXT, mono INTEGER);
        CREATE TABLE events(pid INTEGER, boot TEXT, epoch TEXT, t INTEGER, seq INTEGER,
            package TEXT, token TEXT, kind TEXT, segment INTEGER, cut INTEGER);
        CREATE INDEX event_times ON events(pid,t,package);
        CREATE TABLE breaks(pid INTEGER, seq INTEGER, reason TEXT);
        CREATE INDEX break_sequences ON breaks(pid,seq);
        CREATE TABLE coverage(pid INTEGER, boot TEXT, epoch TEXT, start INTEGER, end INTEGER, segment INTEGER);
        CREATE INDEX coverage_lookup ON coverage(pid,boot,epoch,start,end);
        CREATE TABLE sessions(pid INTEGER, package TEXT, start INTEGER, end INTEGER);
        CREATE INDEX session_times ON sessions(pid,package,start,end);
        CREATE TABLE censored(pid INTEGER, package TEXT, t INTEGER, kind TEXT);
        CREATE INDEX censored_times ON censored(pid,t,package);
    """)


def _load_events(
    db: sqlite3.Connection, dataset: Path, manifest: dict, identities: dict
) -> None:
    listed: set[str] = set()
    partition_names = [
        "experiment_id",
        "configuration_id",
        "source_id",
        "schema_version",
        "event_type",
    ]
    for partition in manifest["partitions"]:
        relative = partition["file"]
        if relative in listed:
            raise ValidationError("duplicate manifest partition")
        listed.add(relative)
        path = _safe_file(dataset, relative)
        parts = Path(relative).parts
        if len(parts) != 6 or not parts[-1].endswith(".parquet"):
            raise ValidationError("invalid Parquet partition path")
        dimensions = {}
        for name, part in zip(partition_names, parts[:-1], strict=True):
            prefix = name + "="
            if not part.startswith(prefix) or not part[len(prefix) :]:
                raise ValidationError("invalid Parquet partition dimensions")
            dimensions[name] = part[len(prefix) :]
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != partition["sha256"]:
                raise ValidationError("Parquet partition digest mismatch")
            stream.seek(0)
            parquet = pq.ParquetFile(stream)
            if parquet.metadata.num_rows != int(partition["row_count"]):
                raise ValidationError("Parquet partition row count mismatch")
            schema_metadata = parquet.schema_arrow.metadata or {}
            for name in ("source_id", "schema_version", "event_type"):
                if (
                    schema_metadata.get(f"particeps.{name}".encode())
                    != dimensions[name].encode()
                ):
                    raise ValidationError("Parquet schema and partition path disagree")
            source, kind = dimensions["source_id"], dimensions["event_type"]
            if not (
                (source == USAGE and kind in ACTIVITIES)
                or (source == "traffic_shaping.v1" and kind in TRAFFIC_EVENTS)
                or (
                    source == "study_runtime.v1"
                    and kind in {"STUDY_STARTED", "SOURCE_QUALITY_GAP"}
                )
            ):
                continue
            for batch in parquet.iter_batches(batch_size=4096):
                for row in batch.to_pylist():
                    identity = (
                        dimensions["experiment_id"],
                        dimensions["configuration_id"],
                        row["participant_instance_id"],
                    )
                    if identity not in identities:
                        raise ValidationError(
                            "Parquet participant missing from quality summary"
                        )
                    pid = identities[identity]
                    if source == "traffic_shaping.v1":
                        load_traffic_receipt(db, pid, kind, row)
                    elif kind == "STUDY_STARTED":
                        db.execute(
                            "INSERT INTO starts VALUES(?,?,?,?)",
                            (
                                pid,
                                int(row["observed_wall_time_utc_millis"]),
                                row["observed_boot_session_id"],
                                int(row["observed_monotonic_time_nanos"]),
                            ),
                        )
                    elif kind == "SOURCE_QUALITY_GAP":
                        if (
                            row["payload_source_id"] in {USAGE, "study_runtime.v1"}
                            or row["reason"] in CLOCK_GAPS
                        ):
                            db.execute(
                                "INSERT INTO breaks VALUES(?,?,?)",
                                (pid, int(row["sequence_number"]), row["reason"]),
                            )
                    else:
                        db.execute(
                            "INSERT INTO events VALUES(?,?,?,?,?,?,?,?,NULL,NULL)",
                            (
                                pid,
                                row["observed_boot_session_id"],
                                row["source_condition_epoch_id"],
                                int(row["source_time_utc_millis"]),
                                int(row["sequence_number"]),
                                row["package_name"],
                                row["activity_component_token"],
                                kind,
                            ),
                        )
    actual = {str(path.relative_to(dataset)) for path in dataset.rglob("*.parquet")}
    if actual != listed:
        raise ValidationError("dataset Parquet inventory differs from its manifest")
    db.commit()


def _participant(
    db: sqlite3.Connection,
    pid: int,
    participant: dict,
    zone: ZoneInfo,
    hours: int,
    writer: csv.DictWriter,
    traffic_writer: csv.DictWriter,
) -> dict:
    identity = {name: participant[name] for name in IDENTITY}
    if "usage_coverage" not in participant or "latest_committed_at" not in participant:
        raise ValidationError(
            "usage coverage metadata missing; materialize the dataset again"
        )
    starts = list(db.execute("SELECT t,boot,mono FROM starts WHERE pid=?", (pid,)))
    if not starts:
        return {**identity, "status": "not_started", "planned_duration_hours": hours}
    if len(starts) != 1:
        raise ValidationError("participant must have exactly one STUDY_STARTED event")
    start, _, _ = starts[0]
    end = start + hours * 3_600_000
    latest = int(participant["latest_committed_at"]["wall_time_utc_millis"])
    reached = max(start, min(end, latest))
    _coverage(db, pid, participant, start, reached)
    build_traffic_intervals(db, pid, participant, start, reached)
    db.execute(
        """UPDATE events SET segment=(SELECT segment FROM coverage c
        WHERE c.pid=events.pid AND c.boot=events.boot AND c.epoch=events.epoch
        AND c.start<=events.t AND events.t<c.end LIMIT 1),
        cut=COALESCE((SELECT MAX(seq) FROM breaks b WHERE b.pid=events.pid AND b.seq<events.seq),0)
        WHERE pid=?""",
        (pid,),
    )
    _pair(db, pid)
    packages = [
        row[0]
        for row in db.execute(
            "SELECT DISTINCT package FROM events WHERE pid=? ORDER BY package", (pid,)
        )
    ]
    for date, period, left, right in _windows(start, end, zone):
        planned = right - left
        arrived = max(0, min(right, reached) - left)
        traffic_writer.writerow(
            {
                **identity,
                "local_date": date,
                "period": period,
                "start_utc_millis": left,
                "end_utc_millis": right,
                "planned_millis": planned,
                "reached_millis": arrived,
                "not_yet_reached_millis": planned - arrived,
                **traffic_window(db, pid, left, left + arrived),
            }
        )
        covered = _union(
            db.execute(
                "SELECT start,end FROM coverage WHERE pid=? AND start<? AND end>? ORDER BY start,end",
                (pid, right, left),
            ),
            left,
            right,
        )
        for package in [None, *packages]:
            predicate, params = (
                ("", ()) if package is None else (" AND package=?", (package,))
            )
            paired = _union(
                db.execute(
                    "SELECT start,end FROM sessions WHERE pid=? AND start<? AND end>?"
                    + predicate
                    + " ORDER BY start,end",
                    (pid, right, left, *params),
                ),
                left,
                right,
            )
            counts = db.execute(
                """SELECT
                SUM(kind='ACTIVITY_RESUMED'), SUM(kind='ACTIVITY_RESUMED' AND segment IS NOT NULL),
                SUM(segment IS NULL) FROM events WHERE pid=? AND t>=? AND t<?"""
                + predicate,
                (pid, left, right, *params),
            ).fetchone()
            censor = dict(
                db.execute(
                    "SELECT kind,COUNT(*) FROM censored WHERE pid=? AND t>=? AND t<?"
                    + predicate
                    + " GROUP BY kind",
                    (pid, left, right, *params),
                )
            )
            writer.writerow(
                {
                    **identity,
                    "local_date": date,
                    "period": period,
                    "scope": "all_apps" if package is None else "app",
                    "package_name": package or "",
                    "start_utc_millis": left,
                    "end_utc_millis": right,
                    "planned_millis": planned,
                    "reached_millis": arrived,
                    "query_covered_millis": covered,
                    "query_coverage_of_reached": covered / arrived if arrived else None,
                    "query_coverage_of_planned": covered / planned,
                    "usage_status": "not_yet_reached"
                    if not arrived
                    else "no_query_coverage"
                    if not covered
                    else "observed_paired_intervals",
                    "paired_foreground_millis": paired if covered else None,
                    "observed_resumed_count": counts[0] or 0,
                    "covered_resumed_count": counts[1] or 0,
                    "outside_coverage_events": counts[2] or 0,
                    "censored_resumes": censor.get("resume", 0),
                    "unmatched_closes": censor.get("close", 0),
                }
            )
    covered_total = _union(
        db.execute(
            "SELECT start,end FROM coverage WHERE pid=? ORDER BY start,end", (pid,)
        ),
        start,
        end,
    )
    clock_gaps = db.execute(
        "SELECT COUNT(*) FROM breaks WHERE pid=? AND reason IN ('CLOCK_DISCONTINUITY','WALL_CLOCK_CHANGED')",
        (pid,),
    ).fetchone()[0]
    return {
        **identity,
        "status": "partial" if latest < end else "horizon_reached",
        "study_start_utc_millis": start,
        "planned_end_utc_millis": end,
        "latest_committed_utc_millis": latest,
        "planned_duration_hours": hours,
        "reached_millis": reached - start,
        "query_covered_millis": covered_total,
        "query_coverage_of_reached": covered_total / (reached - start)
        if reached > start
        else None,
        "query_coverage_of_planned": covered_total / (end - start),
        "clock_discontinuity_count": clock_gaps,
        "calendar_horizon_interpretation": "wall_clock_affected"
        if clock_gaps
        else "fixed_reference_wall_time",
        "censored_resumes": db.execute(
            "SELECT COUNT(*) FROM censored WHERE pid=? AND kind='resume'", (pid,)
        ).fetchone()[0],
        "unmatched_closes": db.execute(
            "SELECT COUNT(*) FROM censored WHERE pid=? AND kind='close'", (pid,)
        ).fetchone()[0],
        "complete_collection_proven": False,
    }


def _coverage(
    db: sqlite3.Connection, pid: int, participant: dict, start: int, end: int
) -> None:
    epochs = {
        item["condition_epoch_id"]: item for item in participant["condition_epochs"]
    }
    previous: tuple[str, int, str] | None = None
    segment = 0
    # Accumulator order is commit/epoch order, not wall-time order, so a reversed clock cannot
    # accidentally reconnect two separate portions merely because their wall times overlap.
    for item in participant["usage_coverage"]:
        epoch = epochs[item["condition_epoch_id"]]
        boot = item["boot_session_id"]
        if boot != epoch["activated_at"]["boot_session_id"]:
            raise ValidationError("usage coverage boot differs from its activation")
        left = max(
            start,
            int(item["start_utc_millis"]),
            int(epoch["activated_at"]["wall_time_utc_millis"]),
        )
        right = min(end, int(item["end_utc_millis"]))
        if epoch["deactivated_at"] is not None:
            right = min(right, int(epoch["deactivated_at"]["wall_time_utc_millis"]))
        if right <= left:
            continue
        if previous is None or previous[0] != boot or previous[1] != left:
            segment += 1
        db.execute(
            "INSERT INTO coverage VALUES(?,?,?,?,?,?)",
            (pid, boot, item["condition_epoch_id"], left, right, segment),
        )
        previous = (boot, right, item["condition_epoch_id"])


def _pair(db: sqlite3.Connection, pid: int) -> None:
    active = None
    previous_key = None
    last_close = None
    query = """SELECT boot,segment,cut,package,token,t,kind FROM events
        WHERE pid=? AND segment IS NOT NULL
        ORDER BY boot,segment,cut,package,token,t,seq"""
    for boot, segment, cut, package, token, timestamp, kind in db.execute(
        query, (pid,)
    ):
        key = (boot, segment, cut, package, token)
        if key != previous_key:
            if active is not None:
                db.execute(
                    "INSERT INTO censored VALUES(?,?,?,'resume')",
                    (pid, previous_key[3], active),
                )
            active, last_close, previous_key = None, None, key
        if kind == "ACTIVITY_RESUMED":
            if active is not None:
                db.execute(
                    "INSERT INTO censored VALUES(?,?,?,'resume')",
                    (pid, package, active),
                )
            active, last_close = timestamp, None
        elif active is not None:
            db.execute(
                "INSERT INTO sessions VALUES(?,?,?,?)",
                (pid, package, active, timestamp),
            )
            active, last_close = None, kind
        else:
            # Android normally follows PAUSED with STOPPED for the same activity. Only that
            # immediate follow-up is redundant; another orphan PAUSED is new missing-start evidence.
            if not (kind == "ACTIVITY_STOPPED" and last_close == "ACTIVITY_PAUSED"):
                db.execute(
                    "INSERT INTO censored VALUES(?,?,?,'close')",
                    (pid, package, timestamp),
                )
            last_close = kind
    if active is not None:
        db.execute(
            "INSERT INTO censored VALUES(?,?,?,'resume')",
            (pid, previous_key[3], active),
        )


def _union(intervals, left: int, right: int) -> int:
    total, previous_end = 0, left
    for start, end in intervals:
        start, end = max(left, start), min(right, end)
        if end > max(start, previous_end):
            total += end - max(start, previous_end)
            previous_end = end
    return total


def _windows(start: int, end: int, zone: ZoneInfo):
    date = datetime.fromtimestamp(start / 1000, zone).date()
    while True:
        boundaries = []
        for day, hour in [
            (date, 0),
            (date, 12),
            (date, 17),
            (date + timedelta(days=1), 0),
        ]:
            local = datetime.combine(day, time(hour), zone)
            utc = local.astimezone(UTC)
            if utc.astimezone(zone).replace(tzinfo=None) != local.replace(tzinfo=None):
                raise ValidationError(
                    "analysis period boundary does not exist in the chosen timezone"
                )
            boundaries.append(int(utc.timestamp() * 1000))
        for period, left, right in zip(
            ("before", "during", "after"), boundaries, boundaries[1:]
        ):
            left, right = max(start, left), min(end, right)
            if right > left:
                yield date.isoformat(), period, left, right
        if boundaries[-1] >= end:
            return
        date += timedelta(days=1)
