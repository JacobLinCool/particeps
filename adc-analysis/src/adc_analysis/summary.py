"""Deterministic bounded summaries for arbitrarily large validated datasets."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Generic, TypeVar

SUMMARY_EXAMPLE_LIMIT = 100
T = TypeVar("T")


@dataclass(slots=True)
class BoundedExamples(Generic[T]):
    """Count every occurrence while retaining only a fixed number of examples."""

    count: int = 0
    examples: list[T] = field(default_factory=list)

    def add(self, example: T) -> None:
        self.count += 1
        if len(self.examples) < SUMMARY_EXAMPLE_LIMIT:
            self.examples.append(example)

    def add_count(self, count: int) -> None:
        if count < 0:
            raise ValueError("summary count increment must be non-negative")
        self.count += count

    def add_example(self, example: T) -> None:
        if len(self.examples) < SUMMARY_EXAMPLE_LIMIT:
            self.examples.append(example)

    @property
    def has_capacity(self) -> bool:
        return len(self.examples) < SUMMARY_EXAMPLE_LIMIT

    def document(self) -> dict[str, object]:
        return {
            "count": str(self.count),
            "examples": self.examples,
            "examples_truncated": self.count > len(self.examples),
        }
