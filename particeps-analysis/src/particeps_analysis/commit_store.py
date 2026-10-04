"""Private disk spools for authenticated commits and replay evidence.

SQLite's page cache is bounded; payloads are read one commit or event at a time.
These files are temporary plaintext and must stay in the private analysis workspace.
"""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
from collections.abc import Iterator
from dataclasses import asdict, replace
from pathlib import Path
from typing import TYPE_CHECKING

from .errors import ValidationError
from .filesystem import private_directory
from .jcs import parse

if TYPE_CHECKING:
    from .engine import EngineCommit, RecordedEvent, SourceObservation
    from .registry import EventSourceRegistry


class PrivateDatabase:
    def __init__(self, directory: Path, prefix: str):
        directory = private_directory(directory)
        fd, name = tempfile.mkstemp(prefix=prefix, suffix=".sqlite3", dir=directory)
        os.close(fd)
        self.path = Path(name)
        self.closed = False
        try:
            os.chmod(self.path, 0o600)
            self.connection = sqlite3.connect(self.path)
            self.connection.execute("PRAGMA trusted_schema = OFF")
            self.connection.execute("PRAGMA journal_mode = DELETE")
            self.connection.execute("PRAGMA synchronous = FULL")
            self.connection.execute("PRAGMA cache_size = -2048")
            self.connection.execute("PRAGMA temp_store = FILE")
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        if not getattr(self, "closed", True):
            self.closed = True
            connection = getattr(self, "connection", None)
            if connection is not None:
                connection.close()
            self.path.unlink(missing_ok=True)
            self.path.with_name(self.path.name + "-journal").unlink(missing_ok=True)

    def __del__(self) -> None:
        try:
            self.close()
        except (OSError, sqlite3.Error):
            pass


class CommitSpool(PrivateDatabase):
    """An ordered, reiterable sequence owning its temporary authenticated commits."""

    def __init__(self, directory: Path, registry: EventSourceRegistry):
        super().__init__(directory, "particeps-commits-")
        self.registry = registry
        self.count = 0
        self.sealed = False
        self.connection.execute(
            "CREATE TABLE commits (ordinal INTEGER PRIMARY KEY, payload BLOB NOT NULL)"
        )

    def append(self, commit: EngineCommit) -> None:
        if self.closed or self.sealed:
            raise ValidationError("commit spool is not writable")
        self.connection.execute(
            "INSERT INTO commits VALUES (?, ?)", (self.count, commit.canonical_bytes)
        )
        self.count += 1

    def seal(self) -> None:
        if self.closed or self.sealed:
            raise ValidationError("commit spool is not writable")
        self.connection.commit()
        self.connection.close()
        self.connection = None
        self.sealed = True

    def __len__(self) -> int:
        return self.count

    def __iter__(self) -> Iterator[EngineCommit]:
        from .engine import EngineCommitParser

        if self.closed or not self.sealed:
            raise ValidationError("commit spool is not readable")
        parser = EngineCommitParser(self.registry)
        connection = sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True)
        cursor = connection.execute("SELECT ordinal, payload FROM commits ORDER BY ordinal")
        count = 0
        try:
            for ordinal, payload in cursor:
                if ordinal != count:
                    raise ValidationError("commit spool has a missing ordinal")
                yield parser.parse(parse(bytes(payload)))
                count += 1
            if count != self.count:
                raise ValidationError("commit spool count changed")
        finally:
            cursor.close()
            connection.close()


class ReplayHistory(PrivateDatabase):
    """Exact evidence retained until every epoch's closing bound is known."""

    def __init__(self, directory: Path):
        super().__init__(directory, "particeps-replay-")
        self.connection.executescript(
            "CREATE TABLE observations (ordinal INTEGER PRIMARY KEY, payload TEXT NOT NULL);"
            "CREATE TABLE event_epochs (sequence INTEGER PRIMARY KEY, epoch TEXT NOT NULL);"
            "CREATE TABLE events (sequence INTEGER PRIMARY KEY, payload BLOB NOT NULL, epoch TEXT);"
        )

    def add_observation(self, observation: SourceObservation) -> None:
        self.connection.execute(
            "INSERT INTO observations(payload) VALUES (?)",
            (json.dumps(asdict(observation), separators=(",", ":")),),
        )

    def observations(self) -> Iterator[SourceObservation]:
        from .engine import SourceCoverage, SourceObservation

        cursor = self.connection.execute("SELECT payload FROM observations ORDER BY ordinal")
        try:
            for (payload,) in cursor:
                value = json.loads(payload)
                if value["coverage"] is not None:
                    value["coverage"] = SourceCoverage(**value["coverage"])
                yield SourceObservation(**value)
        finally:
            cursor.close()

    def set_event_epoch(self, sequence: int, epoch: str) -> None:
        self.connection.execute("INSERT INTO event_epochs VALUES (?, ?)", (sequence, epoch))

    def event_epoch(self, sequence: int) -> str | None:
        row = self.connection.execute(
            "SELECT epoch FROM event_epochs WHERE sequence = ?", (sequence,)
        ).fetchone()
        return None if row is None else row[0]

    def add_event(self, event: RecordedEvent) -> None:
        self.connection.execute(
            "INSERT INTO events VALUES (?, ?, ?)",
            (event.sequence_number, event.canonical_bytes, event.source_condition_epoch_id),
        )

    def events(self, registry: EventSourceRegistry) -> Iterator[RecordedEvent]:
        from .engine import EngineCommitParser

        self.connection.commit()
        parser = EngineCommitParser(registry)
        cursor = self.connection.execute("SELECT payload, epoch FROM events ORDER BY sequence")
        try:
            for payload, epoch in cursor:
                yield replace(parser._event(parse(bytes(payload))), source_condition_epoch_id=epoch)
        finally:
            cursor.close()
