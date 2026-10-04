"""Compact, source-specific query coverage for research quality checks.

Coverage means that the collector committed a successful query over an interval. It does not
prove that Android delivered every event, or that an activity occupied the entire interval.
"""

from __future__ import annotations

from collections.abc import Mapping

from .engine import ConditionEpoch, SourceObservation
from .errors import ValidationError


class UsageCoverageAccumulator:
    """Merge source intervals, then bind their boot to the verified owning epoch."""

    def __init__(self) -> None:
        self._intervals: list[dict[str, str]] = []

    def add(self, observation: SourceObservation) -> None:
        if observation.source_id != "usage_events.v1" or observation.coverage is None:
            return
        coverage = observation.coverage
        if coverage.clock_basis != "SOURCE_WALL_TIME":
            raise ValidationError("usage coverage must use source wall time")
        start, end = int(coverage.start_inclusive), int(coverage.end_exclusive)
        if end < start:
            raise ValidationError("usage coverage interval is reversed")
        if end == start:
            return
        previous = self._intervals[-1] if self._intervals else None
        if (
            previous is not None
            and previous["condition_epoch_id"] == observation.condition_epoch_id
            and start == int(previous["end_utc_millis"])
        ):
            previous["end_utc_millis"] = str(end)
            return
        self._intervals.append(
            {
                "condition_epoch_id": observation.condition_epoch_id,
                "start_utc_millis": str(start),
                "end_utc_millis": str(end),
            }
        )

    def intervals(self, verified_epochs: Mapping[str, ConditionEpoch]) -> list[dict[str, str]]:
        result = []
        for interval in self._intervals:
            epoch_id = interval["condition_epoch_id"]
            epoch = verified_epochs.get(epoch_id)
            if epoch is None or epoch.id != epoch_id:
                raise ValidationError("usage coverage has no verified source epoch")
            # Recovery can commit durable pending input in a later boot. Its source
            # interval still belongs to the boot of the epoch that admitted it.
            result.append({**interval, "boot_session_id": epoch.activated_at.boot_session_id})
        return result
