"""Mathematical foreground-duration examples and authenticated dataset boundary checks."""

import csv
import hashlib
import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from particeps_analysis.errors import ValidationError
from particeps_analysis.registry import EventSourceRegistry
from particeps_analysis.usage_report import usage_report

BASE = int(datetime.fromisoformat("2026-10-05T00:00:00+00:00").timestamp() * 1000)
MINUTE = 60_000


def at(hour, minute=0):
    return BASE + (hour * 60 + minute) * MINUTE


def research_time(timestamp, boot="boot"):
    return {
        "wall_time_utc_millis": str(timestamp),
        "elapsed_realtime_nanos": str((timestamp - BASE) * 1_000_000),
        "boot_session_id": boot,
    }


def epoch(name="epoch", left=BASE, right=None, boot="boot"):
    return {
        "condition_epoch_id": name,
        "activated_at": research_time(left, boot),
        "preparation_bound": research_time(BASE, boot),
        "deactivated_at": research_time(right, boot) if right is not None else None,
    }


def coverage(left=BASE, right=None, name="epoch", boot="boot"):
    return {
        "condition_epoch_id": name,
        "boot_session_id": boot,
        "start_utc_millis": str(left),
        "end_utc_millis": str(right if right is not None else at(24)),
    }


def activity(
    kind, timestamp, package="app.a", token="activity", name="epoch", boot="boot"
):
    return {
        "kind": kind,
        "source_time_utc_millis": timestamp,
        "package_name": package,
        "activity_component_token": token,
        "source_condition_epoch_id": name,
        "observed_boot_session_id": boot,
        "observed_wall_time_utc_millis": timestamp,
    }


def traffic_receipts(name, start, snapshot=None, removed=False, cap=500, boot="boot"):
    def wire_time(timestamp):
        value = research_time(timestamp, boot)
        value["monotonic_time_nanos"] = value.pop("elapsed_realtime_nanos")
        return json.dumps(value)

    common = {
        "payload_condition_epoch_id": name,
        "profile_id": name,
        "resource_generation": 1,
        "vpn_generation_id": "vpn",
        "participant_instance_id": "person",
    }
    records = [
        {
            **common,
            "kind": "TRAFFIC_SHAPING_PROFILE_APPLIED",
            "activation_research_time": wire_time(start),
            "verification_completed_research_time": wire_time(start),
            "uplink_kbps": cap,
            "downlink_kbps": cap,
        }
    ]
    if snapshot is not None:
        records.append(
            {
                **common,
                "kind": "TRAFFIC_SHAPING_SNAPSHOT",
                "observation_research_time": wire_time(snapshot),
                "logical_deadline_research_time": wire_time(snapshot),
            }
        )
        if removed:
            records.append(
                {
                    **common,
                    "kind": "TRAFFIC_SHAPING_PROFILE_REMOVED",
                    "boundary_research_time": wire_time(snapshot),
                }
            )
    return records


class UsageReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def dataset(
        self,
        activities=(),
        intervals=None,
        epochs=None,
        latest=None,
        gaps=(),
        traffic=(),
        study_start=BASE,
    ):
        dataset = self.root / "dataset"
        dataset.mkdir()
        events = [
            (
                "study_runtime.v1",
                "STUDY_STARTED",
                {
                    "participant_instance_id": "person",
                    "sequence_number": 1,
                    "observed_wall_time_utc_millis": study_start,
                    "observed_monotonic_time_nanos": 0,
                    "observed_boot_session_id": "boot",
                },
            )
        ]
        for index, event in enumerate(activities, 2):
            event = dict(event)
            kind = event.pop("kind")
            event.update(
                participant_instance_id="person",
                sequence_number=event.get("sequence_number", index),
            )
            events.append(("usage_events.v1", kind, event))
        for sequence, reason in gaps:
            events.append(
                (
                    "study_runtime.v1",
                    "SOURCE_QUALITY_GAP",
                    {
                        "participant_instance_id": "person",
                        "sequence_number": sequence,
                        "payload_source_id": "study_runtime.v1",
                        "reason": reason,
                    },
                )
            )
        for receipt in traffic:
            row = dict(receipt)
            events.append(("traffic_shaping.v1", row.pop("kind"), row))
        grouped = {}
        for source, kind, row in events:
            grouped.setdefault((source, kind), []).append(row)
        partitions = []
        for (source, kind), rows in grouped.items():
            relative = f"experiment_id=experiment/configuration_id=config/source_id={source}/schema_version=1/event_type={kind}/part-00000.parquet"
            path = dataset / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            table = pa.Table.from_pylist(rows).replace_schema_metadata(
                {
                    b"particeps.source_id": source.encode(),
                    b"particeps.schema_version": b"1",
                    b"particeps.event_type": kind.encode(),
                }
            )
            pq.write_table(table, path)
            partitions.append(
                {
                    "file": relative,
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "row_count": str(len(rows)),
                }
            )
        identity = {
            "experiment_id": "experiment",
            "configuration_id": "config",
            "participant_instance_id": "person",
        }
        participant = {
            **identity,
            "condition_epochs": epochs if epochs is not None else [epoch()],
            "usage_coverage": intervals if intervals is not None else [coverage()],
            "latest_committed_at": research_time(
                latest if latest is not None else at(24)
            ),
        }
        quality = {"commit_chain_verification": {"participants": [participant]}}
        quality_bytes = json.dumps(quality).encode()
        (dataset / "quality-summary.json").write_bytes(quality_bytes)
        manifest = {
            "dataset_format": "particeps-parquet-dataset-v1",
            "validation_failures": [],
            "event_source_registry_sha256": EventSourceRegistry().digest,
            "quality_summary_sha256": hashlib.sha256(quality_bytes).hexdigest(),
            "source_ciphertexts": [identity],
            "partitions": partitions,
        }
        (dataset / "dataset-manifest.json").write_text(json.dumps(manifest))
        return dataset

    def report(self, dataset, duration=24):
        output = usage_report(dataset, self.root / "report", "UTC", duration)
        with (output / "windows.csv").open() as stream:
            rows = list(csv.DictReader(stream))
        participant = json.loads((output / "participants.jsonl").read_text())
        return rows, participant

    def row(self, rows, period, package=""):
        return next(
            row
            for row in rows
            if row["period"] == period and row["package_name"] == package
        )

    def test_cross_boundaries_continuous_epochs_and_app_union(self):
        data = self.dataset(
            [
                activity("ACTIVITY_RESUMED", at(11, 50), name="first"),
                activity("ACTIVITY_RESUMED", at(11, 55), token="second", name="first"),
                activity("ACTIVITY_RESUMED", at(12), package="app.b", name="second"),
                activity("ACTIVITY_PAUSED", at(12, 10), name="second"),
                activity("ACTIVITY_STOPPED", at(12, 11), name="second"),
                activity("ACTIVITY_PAUSED", at(12, 20), token="second", name="second"),
                activity("ACTIVITY_PAUSED", at(12, 30), package="app.b", name="second"),
                activity("ACTIVITY_RESUMED", at(16, 50), name="second"),
                activity("ACTIVITY_PAUSED", at(17, 10), name="second"),
            ],
            intervals=[
                coverage(BASE, at(12), "first"),
                coverage(at(12), at(24), "second"),
            ],
            epochs=[epoch("first", BASE, at(12)), epoch("second", at(12))],
        )
        rows, summary = self.report(data)
        self.assertEqual(
            10 * MINUTE, int(self.row(rows, "before")["paired_foreground_millis"])
        )
        self.assertEqual(
            40 * MINUTE, int(self.row(rows, "during")["paired_foreground_millis"])
        )
        self.assertEqual(
            30 * MINUTE,
            int(self.row(rows, "during", "app.a")["paired_foreground_millis"]),
        )
        self.assertEqual(
            30 * MINUTE,
            int(self.row(rows, "during", "app.b")["paired_foreground_millis"]),
        )
        self.assertEqual(
            10 * MINUTE, int(self.row(rows, "after")["paired_foreground_millis"])
        )
        self.assertEqual(0, summary["censored_resumes"])
        self.assertEqual(0, summary["unmatched_closes"])

    def test_gap_does_not_pair_and_unclosed_tail_never_imputes_duration(self):
        rows, summary = self.report(
            self.dataset(
                [
                    activity("ACTIVITY_RESUMED", at(10, 30)),
                    activity("ACTIVITY_PAUSED", at(12, 30)),
                    activity("ACTIVITY_RESUMED", at(12, 45)),
                ],
                intervals=[coverage(at(10), at(11)), coverage(at(12), at(13))],
            )
        )
        self.assertEqual("0", self.row(rows, "during")["paired_foreground_millis"])
        self.assertEqual(2, summary["censored_resumes"])
        self.assertEqual(1, summary["unmatched_closes"])
        self.assertEqual(120 * MINUTE, summary["query_covered_millis"])

    def test_boot_change_and_clock_discontinuity_break_pairs(self):
        first = activity("ACTIVITY_RESUMED", at(10))
        first["sequence_number"] = 2
        close = activity("ACTIVITY_PAUSED", at(11))
        close["sequence_number"] = 4
        rows, summary = self.report(
            self.dataset([first, close], gaps=[(3, "CLOCK_DISCONTINUITY")])
        )
        self.assertEqual("0", self.row(rows, "before")["paired_foreground_millis"])
        self.assertEqual(1, summary["censored_resumes"])
        self.assertEqual(1, summary["unmatched_closes"])
        self.assertEqual(
            "wall_clock_affected", summary["calendar_horizon_interpretation"]
        )

    def test_different_boots_never_pair_even_with_same_token(self):
        rows, summary = self.report(
            self.dataset(
                [
                    activity("ACTIVITY_RESUMED", at(11), name="first"),
                    activity("ACTIVITY_PAUSED", at(13), name="second", boot="new-boot"),
                ],
                intervals=[
                    coverage(BASE, at(12), "first"),
                    coverage(at(12), at(24), "second", "new-boot"),
                ],
                epochs=[
                    epoch("first", BASE, at(12)),
                    epoch("second", at(12), boot="new-boot"),
                ],
            )
        )
        self.assertEqual(1, summary["censored_resumes"])
        self.assertEqual(1, summary["unmatched_closes"])
        self.assertEqual("0", self.row(rows, "during")["paired_foreground_millis"])

    def test_wall_clock_change_also_marks_reference_time_as_affected(self):
        _, summary = self.report(self.dataset(gaps=[(2, "WALL_CLOCK_CHANGED")]))
        self.assertEqual(1, summary["clock_discontinuity_count"])
        self.assertEqual(
            "wall_clock_affected", summary["calendar_horizon_interpretation"]
        )

    def test_missing_usage_is_unknown_and_future_is_not_missing(self):
        rows, summary = self.report(
            self.dataset(intervals=[], latest=at(15)), duration=120
        )
        self.assertEqual("", self.row(rows, "before")["paired_foreground_millis"])
        self.assertEqual("no_query_coverage", self.row(rows, "before")["usage_status"])
        self.assertEqual("not_yet_reached", self.row(rows, "after")["usage_status"])
        self.assertEqual("", self.row(rows, "after")["query_coverage_of_reached"])
        self.assertEqual(15 * 60 * MINUTE, summary["reached_millis"])
        self.assertEqual("partial", summary["status"])

    def test_partial_coverage_uses_reached_and_planned_denominators(self):
        rows, summary = self.report(
            self.dataset(intervals=[coverage(BASE, at(15))], latest=at(15))
        )
        during = self.row(rows, "during")
        self.assertEqual("1.0", during["query_coverage_of_reached"])
        self.assertEqual("0.6", during["query_coverage_of_planned"])
        self.assertEqual(15 / 24, summary["query_coverage_of_planned"])
        self.assertFalse(summary["complete_collection_proven"])

    def test_partial_first_and_last_days_sum_to_the_planned_horizon(self):
        rows, summary = self.report(
            self.dataset(
                study_start=at(10),
                latest=at(22),
                epochs=[epoch(left=at(10))],
                intervals=[coverage(at(10), at(22))],
            )
        )
        self.assertEqual(4, len(rows))
        self.assertEqual(
            24 * 60 * MINUTE, sum(int(row["planned_millis"]) for row in rows)
        )
        self.assertEqual(
            12 * 60 * MINUTE, sum(int(row["reached_millis"]) for row in rows)
        )
        self.assertEqual(
            summary["query_covered_millis"],
            sum(int(row["query_covered_millis"]) for row in rows),
        )
        self.assertEqual(120 * MINUTE, int(rows[0]["planned_millis"]))
        self.assertEqual("not_yet_reached", rows[-1]["usage_status"])
        with (self.root / "report" / "traffic-conditions.csv").open() as stream:
            traffic = list(csv.DictReader(stream))
        for row in traffic:
            self.assertEqual(
                int(row["reached_millis"]),
                sum(
                    int(row[name])
                    for name in (
                        "runtime_limited_millis",
                        "runtime_unlimited_millis",
                        "unknown_profile_millis",
                    )
                ),
            )
            self.assertEqual(
                int(row["planned_millis"]),
                int(row["reached_millis"]) + int(row["not_yet_reached_millis"]),
            )

    def test_wall_clock_rollback_never_connects_pre_and_post_change_events(self):
        resumed = activity("ACTIVITY_RESUMED", at(13, 30), name="first")
        resumed["sequence_number"] = 2
        paused = activity("ACTIVITY_PAUSED", at(13, 45), name="second")
        paused["sequence_number"] = 4
        rows, summary = self.report(
            self.dataset(
                [resumed, paused],
                gaps=[(3, "WALL_CLOCK_CHANGED")],
                epochs=[epoch("first", BASE, at(14)), epoch("second", at(13))],
                intervals=[
                    coverage(at(12), at(14), "first"),
                    coverage(at(13), at(16), "second"),
                ],
            )
        )
        self.assertEqual("0", self.row(rows, "during")["paired_foreground_millis"])
        self.assertEqual(1, summary["censored_resumes"])
        self.assertEqual(1, summary["unmatched_closes"])
        self.assertEqual(4 * 60 * MINUTE, summary["query_covered_millis"])

    def test_each_orphan_pause_is_counted_but_its_followup_stop_is_not(self):
        _, summary = self.report(
            self.dataset(
                [
                    activity("ACTIVITY_PAUSED", at(10)),
                    activity("ACTIVITY_STOPPED", at(10, 1)),
                    activity("ACTIVITY_PAUSED", at(11)),
                    activity("ACTIVITY_STOPPED", at(11, 1)),
                ]
            )
        )
        self.assertEqual(2, summary["unmatched_closes"])

    def test_preparation_slice_is_excluded_and_duplicate_resume_is_censored(self):
        rows, summary = self.report(
            self.dataset(
                [
                    activity("ACTIVITY_RESUMED", at(0, 30)),
                    activity("ACTIVITY_PAUSED", at(1, 30)),
                    activity("ACTIVITY_RESUMED", at(2)),
                    activity("ACTIVITY_RESUMED", at(2, 30)),
                    activity("ACTIVITY_PAUSED", at(3)),
                ],
                epochs=[epoch(left=at(1))],
            )
        )
        before = self.row(rows, "before")
        self.assertEqual(30 * MINUTE, int(before["paired_foreground_millis"]))
        self.assertEqual("1", before["outside_coverage_events"])
        self.assertEqual("3", before["observed_resumed_count"])
        self.assertEqual("2", before["covered_resumed_count"])
        self.assertEqual(1, summary["censored_resumes"])
        self.assertEqual(23 * 60 * MINUTE, summary["query_covered_millis"])

    def test_mismatched_quality_or_parquet_never_publishes(self):
        dataset = self.dataset()
        quality = dataset / "quality-summary.json"
        original = quality.read_bytes()
        quality.write_bytes(original + b" ")
        with self.assertRaisesRegex(ValidationError, "digest"):
            self.report(dataset)
        self.assertFalse((self.root / "report").exists())
        quality.write_bytes(original)
        parquet = next(dataset.rglob("*.parquet"))
        with parquet.open("ab") as stream:
            stream.write(b"tampered")
        with self.assertRaisesRegex(ValidationError, "digest"):
            self.report(dataset)
        self.assertFalse((self.root / "report").exists())
        self.assertEqual([], list(self.root.glob(".report-*")))

    def test_manifest_path_traversal_and_symlink_are_rejected(self):
        dataset = self.dataset()
        path = dataset / "dataset-manifest.json"
        manifest = json.loads(path.read_text())
        original = manifest["partitions"][0]["file"]
        manifest["partitions"][0]["file"] = "../outside.parquet"
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValidationError, "unsafe path"):
            self.report(dataset)
        manifest["partitions"][0]["file"] = original
        path.write_text(json.dumps(manifest))
        target = dataset / original
        external = self.root / "external.parquet"
        target.rename(external)
        target.symlink_to(external)
        with self.assertRaisesRegex(ValidationError, "symbolic"):
            self.report(dataset)

    def test_output_is_create_only(self):
        dataset = self.dataset()
        self.report(dataset)
        report = self.root / "report" / "report.json"
        before = report.read_bytes()
        with self.assertRaisesRegex(ValidationError, "already exists"):
            self.report(dataset)
        self.assertEqual(before, report.read_bytes())

    def traffic_rows(self):
        with (self.root / "report" / "traffic-conditions.csv").open() as stream:
            return {row["period"]: row for row in csv.DictReader(stream)}

    def test_runtime_late_switch_uses_receipts_instead_of_noon_and_seventeen(self):
        self.report(
            self.dataset(
                epochs=[
                    epoch("first", BASE, at(12, 20)),
                    epoch("second", at(12, 20), at(17, 10)),
                    epoch("third", at(17, 10), at(24)),
                ],
                intervals=[],
                traffic=[
                    *traffic_receipts("first", BASE, at(12, 20), True, None),
                    *traffic_receipts("second", at(12, 20), at(17, 10), True),
                    *traffic_receipts("third", at(17, 10), at(24), True, None),
                ],
            )
        )
        rows = self.traffic_rows()
        self.assertEqual(280 * MINUTE, int(rows["during"]["runtime_limited_millis"]))
        self.assertEqual(280 * MINUTE, int(rows["during"]["runtime_500_500_millis"]))
        self.assertEqual(20 * MINUTE, int(rows["during"]["runtime_unlimited_millis"]))
        self.assertEqual(10 * MINUTE, int(rows["after"]["runtime_limited_millis"]))
        self.assertEqual(410 * MINUTE, int(rows["after"]["runtime_unlimited_millis"]))
        self.assertEqual("0", rows["during"]["unknown_profile_millis"])

    def test_runtime_on_time_switch_and_other_caps(self):
        self.report(
            self.dataset(
                epochs=[
                    epoch("first", BASE, at(12)),
                    epoch("second", at(12), at(17)),
                    epoch("third", at(17), at(24)),
                ],
                intervals=[],
                traffic=[
                    *traffic_receipts("first", BASE, at(12), True, None),
                    *traffic_receipts("second", at(12), at(17), True, 750),
                    *traffic_receipts("third", at(17), at(24), True, None),
                ],
            )
        )
        rows = self.traffic_rows()
        self.assertEqual(300 * MINUTE, int(rows["during"]["runtime_limited_millis"]))
        self.assertEqual("0", rows["during"]["runtime_500_500_millis"])
        self.assertEqual("0", rows["during"]["unknown_profile_millis"])

    def _clock_jump_traffic(self, jump_wall, return_to_anchor=False):
        closing = at(14) if return_to_anchor else jump_wall
        closing_mono = at(14) if return_to_anchor else at(13)
        receipt = traffic_receipts("epoch", at(12), closing, True)
        stable = traffic_receipts("epoch", at(12), at(12, 30))[1]
        jump = traffic_receipts("epoch", at(12), jump_wall)[1]
        for field in ("observation_research_time", "logical_deadline_research_time"):
            time = json.loads(jump[field])
            time["monotonic_time_nanos"] = str((at(13) - BASE) * 1_000_000)
            jump[field] = json.dumps(time)
        if not return_to_anchor:
            for record in receipt[1:]:
                for field in (
                    "observation_research_time",
                    "logical_deadline_research_time",
                    "boundary_research_time",
                ):
                    if field in record:
                        time = json.loads(record[field])
                        time["monotonic_time_nanos"] = str(
                            (closing_mono - BASE) * 1_000_000
                        )
                        record[field] = json.dumps(time)
        closed_epoch = epoch("epoch", at(12), closing)
        closed_epoch["deactivated_at"]["elapsed_realtime_nanos"] = str(
            (closing_mono - BASE) * 1_000_000
        )
        self.report(
            self.dataset(
                epochs=[closed_epoch],
                intervals=[],
                gaps=[(2, "WALL_CLOCK_CHANGED")],
                traffic=[receipt[0], stable, jump, *receipt[1:]],
            )
        )
        return self.traffic_rows()["during"]

    def test_forward_clock_jump_does_not_turn_one_elapsed_hour_into_five(self):
        during = self._clock_jump_traffic(at(17))
        self.assertEqual(30 * MINUTE, int(during["runtime_500_500_millis"]))
        self.assertEqual(270 * MINUTE, int(during["unknown_profile_millis"]))

    def test_backward_clock_jump_preserves_only_pre_jump_snapshot_evidence(self):
        during = self._clock_jump_traffic(at(11))
        self.assertEqual(30 * MINUTE, int(during["runtime_500_500_millis"]))
        self.assertEqual(270 * MINUTE, int(during["unknown_profile_millis"]))

    def test_clock_returning_to_original_offset_cannot_reconnect_unknown_tail(self):
        during = self._clock_jump_traffic(at(17), return_to_anchor=True)
        self.assertEqual(30 * MINUTE, int(during["runtime_500_500_millis"]))
        self.assertEqual(270 * MINUTE, int(during["unknown_profile_millis"]))

    def test_traffic_clock_comparison_only_allows_millisecond_quantization(self):
        from particeps_analysis.traffic_report import _same_clock_segment

        self.assertTrue(_same_clock_segment(0, 500_000, 1000, 1_000_000_000))
        self.assertTrue(_same_clock_segment(0, 0, 1000, 1_001_000_000))
        self.assertFalse(_same_clock_segment(0, 0, 1000, 1_001_000_001))
        self.assertFalse(_same_clock_segment(0, 1_000_000, 0, 999_999))

    def test_missing_intermediate_lifecycle_events_are_not_claimed_as_a_lower_bound(
        self,
    ):
        # The observed endpoints also fit two true sessions 10:00–10:10 and 10:50–11:00,
        # if Android omitted the intervening pause/resume. The report cannot distinguish them.
        rows, _ = self.report(
            self.dataset(
                [
                    activity("ACTIVITY_RESUMED", at(10)),
                    activity("ACTIVITY_PAUSED", at(11)),
                ]
            )
        )
        before = self.row(rows, "before")
        self.assertEqual(60 * MINUTE, int(before["paired_foreground_millis"]))
        self.assertEqual("observed_paired_intervals", before["usage_status"])
        metadata = json.loads((self.root / "report" / "report.json").read_text())
        self.assertTrue(
            any("overestimate" in value for value in metadata["interpretation"])
        )

    def test_missing_applied_receipt_is_unknown_even_with_snapshot(self):
        self.report(
            self.dataset(
                traffic=traffic_receipts("epoch", BASE, at(24))[1:],
                intervals=[],
                latest=at(15),
            )
        )
        during = self.traffic_rows()["during"]
        self.assertEqual("0", during["runtime_unlimited_millis"])
        self.assertEqual(180 * MINUTE, int(during["unknown_profile_millis"]))
        self.assertEqual(120 * MINUTE, int(during["not_yet_reached_millis"]))

    def test_process_recovery_tail_stops_at_last_snapshot_not_recovery(self):
        interrupted = epoch("epoch", BASE, at(16))
        interrupted["deactivated_at"]["boot_session_id"] = "new-boot"
        self.report(
            self.dataset(
                epochs=[interrupted],
                intervals=[],
                gaps=[(3, "PROCESS_RECOVERY")],
                traffic=traffic_receipts("epoch", BASE, at(14)),
            )
        )
        during = self.traffic_rows()["during"]
        self.assertEqual(120 * MINUTE, int(during["runtime_500_500_millis"]))
        self.assertEqual(180 * MINUTE, int(during["unknown_profile_millis"]))

    def test_applied_without_snapshot_does_not_prove_a_duration(self):
        self.report(self.dataset(traffic=traffic_receipts("epoch", BASE), intervals=[]))
        during = self.traffic_rows()["during"]
        self.assertEqual("0", during["runtime_limited_millis"])
        self.assertEqual(300 * MINUTE, int(during["unknown_profile_millis"]))

    def test_overlapping_epoch_wall_times_are_unknown(self):
        self.report(
            self.dataset(
                epochs=[epoch("first", BASE, at(14)), epoch("second", at(13), at(24))],
                intervals=[],
                traffic=[
                    *traffic_receipts("first", BASE, at(14), True),
                    *traffic_receipts("second", at(13), at(24), True, None),
                ],
                gaps=[(3, "CLOCK_DISCONTINUITY")],
            )
        )
        during = self.traffic_rows()["during"]
        self.assertEqual(60 * MINUTE, int(during["runtime_limited_millis"]))
        self.assertEqual(180 * MINUTE, int(during["runtime_unlimited_millis"]))
        self.assertEqual(60 * MINUTE, int(during["unknown_profile_millis"]))

    def test_real_runtime_fixture_smoke_through_cli(self):
        from test_runtime_bundle_fixtures import materialize

        from particeps_analysis.cli import main

        dataset = materialize(["rc13-jvm-pilot-process-restart.partexp"], self.root)
        output = self.root / "usage"
        self.assertEqual(
            0,
            main(
                [
                    "usage-report",
                    "--dataset",
                    str(dataset),
                    "--output",
                    str(output),
                    "--timezone",
                    "Asia/Taipei",
                    "--duration-hours",
                    "120",
                ]
            ),
        )
        summary = json.loads((output / "participants.jsonl").read_text())
        self.assertGreater(summary["query_covered_millis"], 0)
        self.assertFalse(summary["complete_collection_proven"])
        self.assertFalse((output / "usage.sqlite").exists())


if __name__ == "__main__":
    unittest.main()
