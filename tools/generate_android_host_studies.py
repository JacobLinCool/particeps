#!/usr/bin/env python3
"""Regenerate emulator-only signed studies through the production researcher CLI.

Build the CLI first with ./gradlew :researcher-tools:installDist. The signing key is the
explicitly public INSECURE demo fixture, never a study's private key.
"""

import argparse
import base64
import copy
import json
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CAPS = (64, 512, 4096)


def configurations(base: dict) -> list[tuple[str, dict]]:
    result = [("host_harness_study_envelope.txt", base)]
    for cap in CAPS:
        configuration = copy.deepcopy(base)
        configuration["configuration_id"] = f"android-host-vpn-fixed-{cap}-2026"
        profile = next(profile for profile in configuration["traffic_shaping"]["profiles"] if profile["uplink_kbps"] == cap)
        configuration["traffic_shaping"]["profiles"] = [profile]
        binding = next(item for item in configuration["automations"] if item["id"] == "bind-traffic-shaping")
        binding["cases"] = [{"condition": {"type": "study_session_active"}, "profile_id": profile["id"]}]
        binding["default_profile_id"] = profile["id"]
        result.append((f"host_fixed_{cap}_study_envelope.txt", configuration))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--researcher-tools", type=Path, default=ROOT / "researcher-tools/build/install/researcher-tools/bin/researcher-tools")
    arguments = parser.parse_args()
    base = json.loads((ROOT / "test-fixtures/host-study.json").read_text())
    outputs = []
    with tempfile.TemporaryDirectory(prefix="particeps-host-studies-") as temporary:
        directory = Path(temporary)
        for index, (name, configuration) in enumerate(configurations(base)):
            source = directory / f"{index}.json"
            canonical = directory / f"{index}.canonical.json"
            envelope = directory / f"{index}.partcfg"
            source.write_text(json.dumps(configuration, indent=2) + "\n")
            subprocess.run([
                str(arguments.researcher_tools), "canonicalize", "--input", str(source), "--output", str(canonical),
            ], check=True)
            subprocess.run([
                str(arguments.researcher_tools), "sign", "--config", str(canonical),
                "--key-id", "demo-signer-2026", "--private", str(ROOT / "researcher-tools/examples/INSECURE-demo-signing-private.key"),
                "--output", str(envelope),
            ], check=True)
            outputs.append((name, base64.b64encode(envelope.read_bytes()).decode("ascii") + "\n"))
    # Do not partly replace the asset set when canonicalization/signature validation fails.
    for name, content in outputs:
        (ROOT / "app/src/androidTest/assets" / name).write_text(content)


if __name__ == "__main__":
    main()
