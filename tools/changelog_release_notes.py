from __future__ import annotations

import argparse
import datetime as dt
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CHANGELOG = ROOT / "CHANGELOG.md"
TAG_PATTERN = re.compile(
    r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?"
)


class ChangelogError(ValueError):
    pass


def extract_release_notes(changelog: str, tag: str) -> str:
    if TAG_PATTERN.fullmatch(tag) is None:
        raise ChangelogError(f"Invalid release tag: {tag}")

    lines = changelog.splitlines()
    heading_prefix = f"## {tag} "
    matches = [index for index, line in enumerate(lines) if line.startswith(heading_prefix)]
    if len(matches) != 1:
        raise ChangelogError(f"Expected exactly one changelog section for {tag}; found {len(matches)}")

    start = matches[0]
    heading = lines[start]
    heading_match = re.fullmatch(rf"## {re.escape(tag)} — ([0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}})", heading)
    if heading_match is None:
        raise ChangelogError(f"Malformed changelog heading for {tag}: {heading}")
    try:
        dt.date.fromisoformat(heading_match.group(1))
    except ValueError as failure:
        raise ChangelogError(f"Invalid changelog date for {tag}") from failure

    end = next(
        (index for index in range(start + 1, len(lines)) if lines[index].startswith("## ")),
        len(lines),
    )
    notes = "\n".join(lines[start + 1 : end]).strip()
    if not notes:
        raise ChangelogError(f"Changelog section for {tag} is empty")
    return f"{notes}\n"


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract the exact CHANGELOG section used as GitHub Release notes.",
    )
    parser.add_argument("tag")
    parser.add_argument("destination", type=Path)
    parser.add_argument("--changelog", type=Path, default=DEFAULT_CHANGELOG)
    arguments = parser.parse_args()

    notes = extract_release_notes(arguments.changelog.read_text(encoding="utf-8"), arguments.tag)
    arguments.destination.write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    main()
