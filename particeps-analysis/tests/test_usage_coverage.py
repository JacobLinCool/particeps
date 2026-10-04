from __future__ import annotations

import unittest
from dataclasses import replace

from particeps_analysis.engine import (
    ConditionEpoch,
    ResearchTime,
    SourceCoverage,
    SourceObservation,
)
from particeps_analysis.errors import ValidationError
from particeps_analysis.usage_coverage import UsageCoverageAccumulator


def observation(start: int, end: int, epoch: str = "epoch-1") -> SourceObservation:
    return SourceObservation(
        1, "usage_events.v1", 1, 1, "NORMAL", 1, epoch, 0, None, None,
        SourceCoverage("SOURCE_WALL_TIME", str(start), str(end)), "0" * 64,
    )


class UsageCoverageTest(unittest.TestCase):
    def test_merges_queries_but_keeps_gaps_epochs_and_boots_distinct(self) -> None:
        coverage = UsageCoverageAccumulator()
        clock = ResearchTime(100, 100, "boot-1")
        coverage.add(observation(10, 20))
        coverage.add(observation(20, 30))
        coverage.add(observation(40, 50))
        coverage.add(observation(50, 60, "epoch-2"))
        coverage.add(observation(60, 70, "epoch-3"))
        epochs = {
            name: ConditionEpoch(name, "a" * 64, "b" * 64, replace(clock, boot_session_id=boot))
            for name, boot in (("epoch-1", "boot-1"), ("epoch-2", "boot-1"), ("epoch-3", "boot-2"))
        }
        intervals = coverage.intervals(epochs)
        self.assertEqual(
            [(x["start_utc_millis"], x["end_utc_millis"]) for x in intervals],
            [("10", "30"), ("40", "50"), ("50", "60"), ("60", "70")],
        )
        self.assertEqual(["boot-1", "boot-1", "boot-1", "boot-2"], [x["boot_session_id"] for x in intervals])

    def test_source_boot_must_come_from_a_verified_owning_epoch(self) -> None:
        coverage = UsageCoverageAccumulator()
        coverage.add(observation(10, 20))
        with self.assertRaisesRegex(ValidationError, "no verified source epoch"):
            coverage.intervals({})
        epoch = ConditionEpoch("epoch-1", "a" * 64, "b" * 64, ResearchTime(1, 1, "source-boot"))
        self.assertEqual("source-boot", coverage.intervals({epoch.id: epoch})[0]["boot_session_id"])

    def test_other_sources_and_empty_flush_do_not_create_usage_coverage(self) -> None:
        coverage = UsageCoverageAccumulator()
        coverage.add(replace(observation(10, 20), source_id="screen_state.v1"))
        coverage.add(observation(20, 20))
        self.assertEqual(coverage.intervals({}), [])


if __name__ == "__main__":
    unittest.main()
