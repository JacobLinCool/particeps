"""Shared access to real runtime exports: the frozen RC13 and branch fixtures under
fixtures/runtime-bundles, and any other directory of bundles the runtime wrote."""

from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
import os
import shutil
import tempfile
from functools import cache
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq

from particeps_analysis.bundle import BundleVerifier
from particeps_analysis.cli import main
from particeps_analysis.models import InventoryObject
from particeps_analysis.pipeline import load_private_keys
from particeps_analysis.registry import EventSourceRegistry

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "runtime-bundles"
MANIFEST: dict[str, Any] = json.loads((FIXTURES / "manifest.json").read_text())
FIXTURE_ENTRIES: dict[str, dict[str, Any]] = {
    entry["file"]: entry for entry in MANIFEST["fixtures"]
}
REPOSITORY = FIXTURES.parents[3]
DEMO_HPKE_PRIVATE_KEY = (
    REPOSITORY / "researcher-tools" / "examples" / "INSECURE-demo-hpke-private.key"
)


def private_keys_file(directory: Path) -> Path:
    """Copy the checked-in demo key file to a mode-0600 file, as the CLI requires."""

    path = directory / "keys.json"
    shutil.copyfile(FIXTURES / MANIFEST["keys_file"], path)
    os.chmod(path, 0o600)
    return path


def decrypt_bundle(
    path: Path, keys: dict[str, bytes]
) -> tuple[dict[str, Any], str, tuple[dict[str, Any], ...]]:
    """Verify one bundle and return its configuration, digest and commit documents."""

    data = path.read_bytes()
    with tempfile.TemporaryDirectory() as temporary:
        bundle = BundleVerifier(EventSourceRegistry(), keys, Path(temporary) / "staging").verify(
            InventoryObject(
                path.as_uri(), hashlib.sha256(data).hexdigest(), len(data), path, None
            )
        )
    commits = tuple(json.loads(commit.canonical_bytes) for commit in bundle.commits)
    return dict(bundle.configuration), bundle.configuration_sha256, commits


@cache
def _decrypted(name: str) -> tuple[dict[str, Any], str, tuple[dict[str, Any], ...]]:
    with tempfile.TemporaryDirectory() as temporary:
        keys = load_private_keys(private_keys_file(Path(temporary)))
    return decrypt_bundle(FIXTURES / name, keys)


def decrypted_chain(name: str) -> tuple[dict[str, Any], str, list[dict[str, Any]]]:
    """Return the signed configuration, its digest, and a mutable copy of the commit documents."""

    configuration, digest, commits = _decrypted(name)
    return copy.deepcopy(configuration), digest, copy.deepcopy(list(commits))


def materialize_directory(inbox: Path, keys_file: Path, root: Path) -> Path:
    """Run the CLI inventory and materialize commands, as a researcher does, over [inbox]."""

    workspace = root / "workspace"
    output = root / "dataset"
    with contextlib.redirect_stdout(io.StringIO()):
        if main(["inventory", "--workspace", str(workspace), "--local", str(inbox)]) != 0:
            raise AssertionError("inventory failed")
        if main([
            "materialize", "--workspace", str(workspace), "--keys", str(keys_file),
            "--output", str(output),
        ]) != 0:
            raise AssertionError("materialize failed")
    return output


def read_partitions(dataset: Path) -> dict[str, Any]:
    tables = {}
    for path in sorted(dataset.rglob("*.parquet")):
        source_id = path.parts[-4].removeprefix("source_id=")
        event_type = path.parts[-2].removeprefix("event_type=")
        tables[f"{source_id}/{event_type}"] = pq.read_table(path)
    return tables


RETROSPECTIVE_SOURCES = EventSourceRegistry().retrospective_collector_source_ids


def is_automation_timer(event: dict[str, Any]) -> bool:
    key = event["fields"]["producer_key"]
    return event["source_id"] == "timer.v1" and key != "study-deadline" and not key.startswith(
        "resource-audit:"
    )


def detect_shapes(commits: list[dict[str, Any]]) -> set[str]:
    """Name the Protocol v1 shapes, among those the manifest labels, that a chain records."""

    shapes: set[str] = set()
    activations: dict[str, dict[str, str]] = {}
    retired_deadlines: set[tuple[str, str]] = set()
    previous: dict[str, Any] | None = None
    for commit in commits:
        kind = commit["input_kind"]
        events = commit["events"]
        successor = commit["successor_projection"]
        types = {(event["source_id"], event["event_type"]) for event in events}
        closes = ("study_condition.v1", "CONDITION_EPOCH_DEACTIVATED") in types
        pausing = any(event["fields"].get("current_state") == "PAUSING" for event in events)
        reducer_retirements = {
            event["fields"]["retirement_reason"]
            for event in events
            if event["event_type"] == "TIMER_RETIRED" and is_automation_timer(event)
        }
        deadline_events = [
            event for event in events
            if event["source_id"] == "timer.v1" and event["fields"]["producer_key"] == "study-deadline"
        ]
        automation_due = any(
            event["event_type"] == "TIMER_DUE" and is_automation_timer(event) for event in events
        )
        if successor["state"] in {"IMPORTED", "CONFIG_VERIFIED", "CONSENT_PENDING", "ACCESS_SETUP", "READY"}:
            shapes.add("setup-checkpoint")
        for observation in commit["source_observations"]:
            coverage = observation["coverage"]
            if observation["admission_kind"] == "BARRIER_FLUSH":
                if observation["event_count"] > 0:
                    shapes.add("barrier-flush-events")
                if coverage["start_inclusive"] == coverage["end_exclusive"]:
                    shapes.add("empty-barrier-flush")
            if observation["admission_kind"] == "BARRIER_FLUSH" and previous is not None:
                before = previous["source_checkpoints"].get(observation["source_id"])
                after = successor["source_checkpoints"].get(observation["source_id"])
                if before and after and before["cursor"] != after["cursor"]:
                    shapes.add("barrier-flush-cursor")
            epoch = activations.get(observation["condition_epoch_id"])
            if (
                coverage is not None
                and coverage["clock_basis"] == "SOURCE_WALL_TIME"
                and epoch is not None
                and int(coverage["start_inclusive"]) < int(epoch["wall_time_utc_millis"])
            ):
                shapes.add("preparation-bound-coverage")
        if kind == "TIMER_WAKE" and automation_due and closes and "CANCELLED" in reducer_retirements:
            shapes.add("barrier-retires-due-timer-cancelled")
        if "FIRED" in reducer_retirements:
            shapes.add("due-timer-retires-fired")
        if kind == "LIFECYCLE_COMMAND" and "LIFECYCLE_ENDED" in reducer_retirements:
            shapes.add("lifecycle-command-retirements")
        if (
            kind == "TIMER_WAKE"
            and pausing
            and "LIFECYCLE_ENDED" in reducer_retirements
            and any(
                event["event_type"] == "TIMER_RETIRED" and event["fields"]["retirement_reason"] == "FIRED"
                for event in deadline_events
            )
        ):
            shapes.add("deadline-stop-retirements")
        if (
            successor["state"] == "PAUSING"
            and types & {
                ("study_runtime.v1", "STUDY_COMPLETE_REQUESTED"),
                ("study_runtime.v1", "STUDY_WITHDRAW_REQUESTED"),
            }
            and any(
                mutation["component_kind"] == "STUDY_DEADLINE_TIMER" and mutation["operation"] == "REMOVE"
                for mutation in commit["mutations"]
            )
        ):
            shapes.add("terminal-request-retires-deadline")
        if any(event["condition_epoch_id"] is not None for event in deadline_events):
            shapes.add("deadline-events-in-closing-epoch")
        for event in deadline_events:
            identity = (event["fields"]["timer_id"], event["fields"]["generation"])
            if event["event_type"] == "TIMER_RETIRED":
                retired_deadlines.add(identity)
            elif event["event_type"] == "TIMER_SCHEDULED" and identity in retired_deadlines:
                # The deadline retired by a terminal request is re-armed at generation 1.
                shapes.add("deadline-rearmed-after-terminal-request")
        if any(
            event["event_type"] == "STUDY_SAFETY_PAUSE_REQUESTED"
            and event["fields"]["previous_state"] == "PAUSING"
            and event["fields"]["current_state"] == "PAUSING"
            for event in events
        ):
            shapes.add(
                "recovery-while-pausing" if kind == "RECOVERY" else "safety-pause-while-pausing"
            )
        for event in events:
            if event["event_type"] == "CONDITION_EPOCH_ACTIVATED":
                activations[event["fields"]["condition_epoch_id"]] = json.loads(
                    event["fields"]["boundary_research_time"]
                )
            if event["event_type"] == "CONDITION_EPOCH_DEACTIVATED" and kind in {
                "RECOVERY", "SAFETY_FAILURE",
            }:
                vector = json.loads(event["fields"]["resource_vector_json"])["resources"]
                if any(
                    item["kind"] == "actuator" and item["status"] == "APPLIED" for item in vector
                ) and not any(source == "traffic_shaping.v1" for source, _type in types):
                    shapes.add(
                        "recovery-close-without-traffic-audit"
                        if kind == "RECOVERY"
                        else "safety-close-without-traffic-audit"
                    )
            if event["event_type"] == "CONDITION_EPOCH_DEACTIVATED" and kind == "RECOVERY":
                boundary = json.loads(event["fields"]["boundary_research_time"])
                activated = activations[event["fields"]["condition_epoch_id"]]
                if boundary["boot_session_id"] != activated["boot_session_id"]:
                    shapes.add("recovery-close-in-new-boot")
                if boundary["boot_session_id"] != commit["committed_at"]["boot_session_id"]:
                    # The clock could not advance across the reboot without trusted UTC, so the
                    # commit keeps the prior anchor while the close lies at the recovery instant.
                    shapes.add("recovery-close-without-trusted-clock")
            if event["source_id"] == "interventions.v1":
                shapes.add("intervention-request-epoch")
        if kind == "RECOVERY" and previous is not None and (
            set(previous["source_checkpoints"]) - set(successor["source_checkpoints"])
        ) & RETROSPECTIVE_SOURCES:
            shapes.add("recovery-drops-retrospective-checkpoint")
        if previous is not None and any(
            event["event_type"] == "SOURCE_QUALITY_GAP" and event["fields"]["reason"] == "WALL_CLOCK_CHANGED"
            for event in events
        ):
            if previous["state"] == "RUNNING" and closes:
                shapes.add("wall-clock-gap-while-running")
            if previous["state"] == "PAUSED" and successor["state"] == "PAUSED":
                shapes.add("wall-clock-gap-while-paused")
        previous = successor
    return shapes
