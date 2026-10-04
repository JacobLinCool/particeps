#!/usr/bin/env python3
"""Validate debug-only applied-profile observations against the signed host fixture."""

import argparse
import base64
import hashlib
import json
import re
from pathlib import Path


def fixture_configuration(asset: Path) -> dict:
    envelope = base64.b64decode(asset.read_text().strip(), validate=True)
    if envelope[:8] != b"PTCCFG01" or len(envelope) < 14:
        raise ValueError("Invalid host fixture envelope")
    key_length = int.from_bytes(envelope[8:10], "big")
    config_length = int.from_bytes(envelope[10:14], "big")
    start = 14 + key_length
    if len(envelope) != start + config_length + 64:
        raise ValueError("Invalid host fixture envelope length")
    # The production app verifies the signature at import, and the JVM asset test verifies every
    # checked-in envelope. Here the same signed payload supplies the expected profile digest.
    return json.loads(envelope[start:start + config_length])


def observation_from_query(output: str) -> dict:
    value = json.loads(output)
    if not isinstance(value, dict):
        raise ValueError("Applied-profile observation must be an object")
    return value


def verified_profile(observation: dict, configuration: dict, expected_profile: str) -> bool:
    if type(observation.get("schema_version")) is not int or observation["schema_version"] != 1 or observation.get("state") != "RUNNING":
        raise ValueError("Profile query requires the RUNNING study and supported schema")
    for field in ("revision", "active_running_elapsed_millis"):
        if type(observation.get(field)) is not int or not 0 <= observation[field] <= 2**63 - 1:
            raise ValueError(f"Invalid {field}")
    if observation.get("status") == "PENDING":
        return False
    if observation.get("status") != "VERIFIED" or observation.get("admission_open") is not True or observation["revision"] == 0:
        raise ValueError("Profile query did not confirm verified open admission")
    for field in ("applied_resource_vector_sha256", "applied_profile_sha256"):
        if not isinstance(observation.get(field), str) or not re.fullmatch(r"[0-9a-f]{64}", observation[field]):
            raise ValueError(f"Invalid {field}")
    if not isinstance(observation.get("condition_epoch_id"), str) or not re.fullmatch(
        r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", observation["condition_epoch_id"],
    ):
        raise ValueError("Invalid condition epoch")
    if type(observation.get("resource_generation")) is not int or not 0 < observation["resource_generation"] <= 2**64 - 1:
        raise ValueError("Invalid resource generation")
    profiles = configuration["traffic_shaping"]["profiles"]
    matched = [profile for profile in profiles if profile["id"] == observation.get("profile_id")]
    if len(matched) != 1:
        raise ValueError("Observed profile is absent from the signed fixture")
    digest = hashlib.sha256(json.dumps(matched[0], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    if observation["applied_profile_sha256"] != digest:
        raise ValueError("Applied profile digest does not match the signed fixture")
    ids = [profile["id"] for profile in profiles]
    if expected_profile not in ids:
        raise ValueError("Expected profile is absent from the signed fixture")
    if ids.index(observation["profile_id"]) > ids.index(expected_profile):
        raise ValueError("Dynamic fixture already advanced beyond the expected profile")
    return observation["profile_id"] == expected_profile


def compare_observations(before: dict, after: dict, *, transition: bool) -> None:
    for field in ("revision", "active_running_elapsed_millis"):
        if after[field] < before[field]:
            raise ValueError(f"{field} moved backwards")
    fields = ("condition_epoch_id", "applied_resource_vector_sha256", "profile_id", "applied_profile_sha256", "resource_generation")
    if transition:
        if any(before[field] == after[field] for field in fields):
            raise ValueError("Profile transition requires a new verified epoch, vector, profile and generation")
        if after["resource_generation"] <= before["resource_generation"] or after["revision"] <= before["revision"]:
            raise ValueError("Profile transition did not advance durable revision/generation")
    elif any(before[field] != after[field] for field in fields):
        raise ValueError("Fixed-profile measurement changed verified resource epoch")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    query = commands.add_parser("query")
    query.add_argument("--observation", type=Path, required=True)
    query.add_argument("--asset", type=Path, required=True)
    query.add_argument("--profile", required=True)
    query.add_argument("--output", type=Path, required=True)
    compare = commands.add_parser("compare")
    compare.add_argument("--before", type=Path, required=True)
    compare.add_argument("--after", type=Path, required=True)
    compare.add_argument("--transition", action="store_true")
    arguments = parser.parse_args()
    if arguments.operation == "query":
        observation = observation_from_query(arguments.observation.read_text())
        if not verified_profile(observation, fixture_configuration(arguments.asset), arguments.profile):
            raise SystemExit(3)
        arguments.output.write_text(json.dumps(observation, sort_keys=True, separators=(",", ":")) + "\n")
    else:
        compare_observations(json.loads(arguments.before.read_text()), json.loads(arguments.after.read_text()), transition=arguments.transition)


if __name__ == "__main__":
    main()
