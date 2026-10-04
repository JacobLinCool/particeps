"""Bound recorded traffic profiles by verified traffic receipts, never a planned schedule."""

from __future__ import annotations

import json
import sqlite3

from .errors import ValidationError

TRAFFIC_EVENTS = {
    "TRAFFIC_SHAPING_PROFILE_APPLIED",
    "TRAFFIC_SHAPING_SNAPSHOT",
    "TRAFFIC_SHAPING_PROFILE_REMOVED",
}


def create_traffic_tables(db: sqlite3.Connection) -> None:
    db.executescript("""
        CREATE TABLE traffic_receipts(pid INTEGER, epoch TEXT, kind TEXT, profile TEXT,
            generation INTEGER, vpn TEXT, boot TEXT, t INTEGER, mono INTEGER,
            logical_t INTEGER, logical_mono INTEGER, uplink INTEGER, downlink INTEGER);
        CREATE INDEX traffic_receipt_epoch ON traffic_receipts(pid,epoch,kind);
        CREATE TABLE traffic_intervals(pid INTEGER, epoch TEXT, start INTEGER, end INTEGER, kind TEXT);
        CREATE INDEX traffic_interval_times ON traffic_intervals(pid,start,end);
    """)


def _time(value: str) -> tuple[str, int, int]:
    time = json.loads(value)
    return (
        time["boot_session_id"],
        int(time["wall_time_utc_millis"]),
        int(time["monotonic_time_nanos"]),
    )


def load_traffic_receipt(
    db: sqlite3.Connection, pid: int, kind: str, row: dict
) -> None:
    field = {
        "TRAFFIC_SHAPING_PROFILE_APPLIED": "activation_research_time",
        "TRAFFIC_SHAPING_SNAPSHOT": "observation_research_time",
        "TRAFFIC_SHAPING_PROFILE_REMOVED": "boundary_research_time",
    }[kind]
    boot, timestamp, mono = _time(row[field])
    logical_t = logical_mono = None
    if kind == "TRAFFIC_SHAPING_SNAPSHOT":
        logical_boot, logical_t, logical_mono = _time(
            row["logical_deadline_research_time"]
        )
        if logical_boot != boot:
            raise ValidationError("traffic snapshot deadline crosses a boot")
    elif kind == "TRAFFIC_SHAPING_PROFILE_APPLIED":
        verification_boot, _, _ = _time(row["verification_completed_research_time"])
        if verification_boot != boot:
            raise ValidationError("traffic profile verification crosses a boot")
    db.execute(
        "INSERT INTO traffic_receipts VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (
            pid,
            row["payload_condition_epoch_id"],
            kind,
            row["profile_id"],
            int(row["resource_generation"]),
            row["vpn_generation_id"],
            boot,
            timestamp,
            mono,
            logical_t,
            logical_mono,
            row.get("uplink_kbps"),
            row.get("downlink_kbps"),
        ),
    )


def build_traffic_intervals(
    db: sqlite3.Connection, pid: int, participant: dict, start: int, end: int
) -> None:
    """An unclosed/recovered epoch stops at its last actual traffic snapshot, not recovery time."""
    for epoch in participant["condition_epochs"]:
        epoch_id = epoch["condition_epoch_id"]
        applied = db.execute(
            """SELECT profile,generation,vpn,boot,t,mono,uplink,downlink
            FROM traffic_receipts WHERE pid=? AND epoch=? AND kind='TRAFFIC_SHAPING_PROFILE_APPLIED'""",
            (pid, epoch_id),
        ).fetchall()
        if not applied:
            continue
        if len(applied) != 1:
            raise ValidationError("traffic epoch has more than one applied receipt")
        profile, generation, vpn, boot, applied_at, applied_mono, up, down = applied[0]
        activation = epoch["activated_at"]
        if (boot, applied_at, applied_mono) != (
            activation["boot_session_id"],
            int(activation["wall_time_utc_millis"]),
            int(activation["elapsed_realtime_nanos"]),
        ):
            raise ValidationError(
                "traffic applied receipt differs from epoch activation"
            )
        identity = (pid, epoch_id, profile, generation, vpn, boot)
        clause = "pid=? AND epoch=? AND profile=? AND generation=? AND vpn=? AND boot=?"
        snapshots = db.execute(
            f"""SELECT t,mono FROM traffic_receipts WHERE {clause}
            AND kind='TRAFFIC_SHAPING_SNAPSHOT' AND mono>=? ORDER BY mono""",
            (*identity, applied_mono),
        )
        # An APPLIED receipt alone is a point observation, not evidence for an elapsed interval.
        proven_end = applied_at
        clock_changed = False
        for snapshot_at, snapshot_mono in snapshots:
            if not _same_clock_segment(
                applied_at, applied_mono, snapshot_at, snapshot_mono
            ):
                # Never reconnect later snapshots even if a second clock adjustment restores
                # the initial offset: the intervening treatment interval remains unproven.
                clock_changed = True
                break
            proven_end = snapshot_at
        removal = db.execute(
            f"""SELECT t,mono FROM traffic_receipts WHERE {clause}
            AND kind='TRAFFIC_SHAPING_PROFILE_REMOVED' ORDER BY mono DESC LIMIT 1""",
            identity,
        ).fetchone()
        closed = epoch["deactivated_at"]
        if removal is not None:
            boundary_snapshot = db.execute(
                f"""SELECT 1 FROM traffic_receipts WHERE {clause}
                AND kind='TRAFFIC_SHAPING_SNAPSHOT' AND logical_t=? AND logical_mono=? LIMIT 1""",
                (*identity, *removal),
            ).fetchone()
            if (
                boundary_snapshot is None
                or closed is None
                or (boot, *removal)
                != (
                    closed["boot_session_id"],
                    int(closed["wall_time_utc_millis"]),
                    int(closed["elapsed_realtime_nanos"]),
                )
            ):
                raise ValidationError(
                    "traffic removal lacks its matching epoch boundary snapshot"
                )
            if not clock_changed and _same_clock_segment(
                applied_at, applied_mono, *removal
            ):
                proven_end = removal[0]
        left, right = max(start, applied_at), min(end, proven_end)
        if (
            closed is not None
            and closed["boot_session_id"] == boot
            and _same_clock_segment(
                applied_at,
                applied_mono,
                int(closed["wall_time_utc_millis"]),
                int(closed["elapsed_realtime_nanos"]),
            )
        ):
            right = min(right, int(closed["wall_time_utc_millis"]))
        if right <= left:
            continue
        category = (
            "unlimited"
            if up is None and down is None
            else "500"
            if up == down == 500
            else "limited"
        )
        db.execute(
            "INSERT INTO traffic_intervals VALUES(?,?,?,?,?)",
            (pid, epoch_id, left, right, category),
        )


def _same_clock_segment(
    start_wall: int, start_mono: int, end_wall: int, end_mono: int
) -> bool:
    # Wall time is integer milliseconds; monotonic time is nanoseconds. Permit only their
    # sub-millisecond quantization difference, not an unexplained elapsed-time discrepancy.
    return (
        end_mono >= start_mono
        and abs((end_wall - start_wall) * 1_000_000 - (end_mono - start_mono))
        <= 1_000_000
    )


def traffic_window(
    db: sqlite3.Connection, pid: int, left: int, right: int
) -> dict[str, int]:
    """Overlapping epochs after a wall-clock change are unknown, including equal-profile overlaps."""
    totals = {"500": 0, "limited": 0, "unlimited": 0}
    active = {key: 0 for key in totals}
    previous = left
    edges = db.execute(
        """SELECT MAX(start,?),kind,1 FROM traffic_intervals
        WHERE pid=? AND start<? AND end>?
        UNION ALL SELECT MIN(end,?),kind,-1 FROM traffic_intervals
        WHERE pid=? AND start<? AND end>? ORDER BY 1""",
        (left, pid, right, left, right, pid, right, left),
    )
    for timestamp, category, change in edges:
        if sum(active.values()) == 1:
            only = next(key for key, count in active.items() if count == 1)
            totals[only] += timestamp - previous
        active[category] += change
        previous = timestamp
    known = sum(totals.values())
    return {
        "runtime_limited_millis": totals["500"] + totals["limited"],
        "runtime_500_500_millis": totals["500"],
        "runtime_unlimited_millis": totals["unlimited"],
        "unknown_profile_millis": max(0, right - left) - known,
    }
