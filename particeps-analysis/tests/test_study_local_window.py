import json
import unittest
from datetime import datetime
from pathlib import Path
from typing import ClassVar

from particeps_analysis.automation import (
    AutomationCheckpoint,
    ReducerClock,
    ReducerInput,
    ResearchTime,
    compile_automation_program,
    reduce_automation_batch,
)
from particeps_analysis.automation_timers import study_local_window
from particeps_analysis.errors import ValidationError
from particeps_analysis.registry import EventSourceRegistry


def ms(value):
    return int(datetime.fromisoformat(value).timestamp() * 1000)


class StudyLocalWindowTest(unittest.TestCase):
    condition: ClassVar[dict] = {"type": "study_local_window", "first_day": 3, "last_day": 5, "start_local_time": "12:00", "end_local_time": "17:00"}

    def test_participant_dates_and_exclusive_end(self):
        start = ms("2026-09-06T02:15:00Z")
        for now, active, boundary in [
            ("2026-09-07T06:00:00Z", False, "2026-09-08T04:00:00Z"),
            ("2026-09-08T04:00:00Z", True, "2026-09-08T09:00:00Z"),
            ("2026-09-08T09:00:00Z", False, "2026-09-09T04:00:00Z"),
            ("2026-09-10T09:00:00Z", False, None),
        ]:
            self.assertEqual((active, ms(boundary) if boundary else None), study_local_window(self.condition, start, ms(now), "Asia/Taipei"))
        self.assertEqual((False, None), study_local_window(self.condition, None, start, "Asia/Taipei"))

    def test_dst_gap_and_overlap(self):
        start = ms("2026-03-06T15:00:00Z")
        self.assertEqual((False, ms("2026-03-08T16:00:00Z")), study_local_window(self.condition, start, start, "America/New_York"))
        gap = dict(self.condition, last_day=3, start_local_time="02:30", end_local_time="04:00")
        self.assertEqual((False, None), study_local_window(gap, start, start, "America/New_York"))
        fall = ms("2026-10-30T14:00:00Z")
        overlap = dict(self.condition, last_day=3, start_local_time="01:30", end_local_time="02:30")
        self.assertEqual((False, ms("2026-11-01T05:30:00Z")), study_local_window(overlap, fall, fall, "America/New_York"))

    def test_all_apps_requires_explicit_scope(self):
        configuration = json.loads((Path(__file__).parents[2] / "researcher-tools/examples/five-day-speed-study.json").read_text())
        self.assertEqual("all", configuration["traffic_shaping"]["target_packages"])
        compile_automation_program(configuration)
        for invalid in [[], "ALL", None, ["all"]]:
            configuration["traffic_shaping"]["target_packages"] = invalid
            with self.assertRaises(ValidationError):
                compile_automation_program(configuration)

    def test_complete_study_and_profile_type_validation(self):
        registry = EventSourceRegistry()
        self.assertEqual({"include_bandwidth_estimates": True}, registry.validate_profile("network_state.v1", {"include_bandwidth_estimates": True}))
        with self.assertRaises(ValidationError):
            registry.validate_profile("network_state.v1", {"include_bandwidth_estimates": "true"})
        configuration = json.loads((Path(__file__).parents[2] / "researcher-tools/examples/five-day-speed-study.json").read_text())
        program = compile_automation_program(configuration)
        checkpoint = AutomationCheckpoint()
        start = ms("2026-09-06T02:15:00Z")
        sequence = 0

        def run(time, state):
            nonlocal checkpoint, sequence
            sequence += 1
            now = ms(time)
            elapsed = (now - start) * 1_000_000
            clock = ReducerClock(ResearchTime(now, elapsed, "study-boot"), 0, elapsed, "Asia/Taipei")
            result = reduce_automation_batch(program, checkpoint, [ReducerInput("LIFECYCLE", sequence, clock, state=state)])
            checkpoint = result.checkpoint
            return result

        run("2026-09-06T02:15:00Z", "ACTIVATING")
        self.assertFalse(run("2026-09-06T02:15:00Z", "RUNNING").action_requests)
        for day in ["08", "09", "10"]:
            run(f"2026-09-{day}T04:00:00Z", "RUNNING")
            self.assertEqual(1, len(run(f"2026-09-{day}T09:00:00Z", "RUNNING").action_requests))
            run(f"2026-09-{day}T09:01:00Z", "PAUSING")
            run(f"2026-09-{day}T09:01:00Z", "PAUSED")
            self.assertFalse(run(f"2026-09-{day}T09:02:00Z", "ACTIVATING").action_requests)
            self.assertFalse(run(f"2026-09-{day}T09:02:00Z", "RUNNING").action_requests)
