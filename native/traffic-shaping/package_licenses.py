#!/usr/bin/env python3
"""Verify and stage every pinned Go module license for the Android artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


SAFE_LICENSE_FILE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


def asset_name(module: str) -> str:
    normalized = re.sub(r"[^A-Za-z0-9._-]", "_", module)
    return f"assets/particeps/licenses/{normalized}.txt"


def module_directory(module: str, version: str, module_root: Path) -> Path:
    result = subprocess.run(
        ["go", "list", "-m", "-json", f"{module}@{version}"],
        check=True,
        capture_output=True,
        text=True,
        cwd=module_root,
    )
    value = json.loads(result.stdout)
    if value.get("Path") != module or value.get("Version") != version or not value.get("Dir"):
        raise ValueError(f"Go resolved an unexpected module for {module}@{version}")
    return Path(value["Dir"])


def stage(sbom: Path, output: Path) -> tuple[str, ...]:
    document = json.loads(sbom.read_text(encoding="utf-8"))
    components = document.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError("SBOM has no components")
    output.mkdir(parents=True, exist_ok=True)
    staged: list[str] = []
    seen_assets: set[str] = set()
    for component in components:
        module = component["module"]
        version = component["version"]
        filename = component["license_file"]
        expected_sha256 = component["license_sha256"]
        if SAFE_LICENSE_FILE.fullmatch(filename) is None:
            raise ValueError(f"Unsafe license filename for {module}")
        asset = asset_name(module)
        if asset in seen_assets:
            raise ValueError(f"Duplicate normalized license asset for {module}")
        seen_assets.add(asset)
        source = module_directory(module, version, sbom.parent) / filename
        encoded = source.read_bytes()
        actual_sha256 = hashlib.sha256(encoded).hexdigest()
        if actual_sha256 != expected_sha256:
            raise ValueError(
                f"License digest mismatch for {module}@{version}: "
                f"expected {expected_sha256}, found {actual_sha256}",
            )
        destination = output / asset
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        staged.append(asset)
    return tuple(staged)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    for asset in stage(arguments.sbom, arguments.output):
        print(asset)


if __name__ == "__main__":
    main()
