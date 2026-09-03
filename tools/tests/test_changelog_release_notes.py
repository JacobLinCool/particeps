from __future__ import annotations

import unittest

from tools import changelog_release_notes


class ChangelogReleaseNotesTest(unittest.TestCase):
    def test_current_release_notes_describe_destructive_update_and_fresh_install(self) -> None:
        changelog = changelog_release_notes.DEFAULT_CHANGELOG.read_text(encoding="utf-8")

        notes = changelog_release_notes.extract_release_notes(changelog, "v1.0.0-rc.8")

        self.assertIn("Coming from `v1.0.0-rc.7`", notes)
        self.assertIn("do not uninstall rc.7 first", notes)
        self.assertIn("an rc.7 study cannot resume", notes)
        self.assertIn("Fresh install", notes)

    def test_repository_release_notes_include_update_instructions(self) -> None:
        changelog = changelog_release_notes.DEFAULT_CHANGELOG.read_text(encoding="utf-8")

        notes = changelog_release_notes.extract_release_notes(changelog, "v1.0.0-rc.6")

        self.assertIn("Coming from `v1.0.0-rc.5`", notes)
        self.assertIn("Do not uninstall rc.5 first", notes)
        self.assertNotIn("## v1.0.0-rc.5", notes)

    def test_exact_section_is_selected(self) -> None:
        changelog = """# Changelog

## v1.2.0 — 2026-08-09

Current notes.

## v1.1.0 — 2026-08-08

Older notes.
"""

        self.assertEqual(
            changelog_release_notes.extract_release_notes(changelog, "v1.2.0"),
            "Current notes.\n",
        )

    def test_missing_or_duplicate_section_is_rejected(self) -> None:
        missing = "# Changelog\n\n## Unreleased\n"
        duplicate = """## v1.2.0 — 2026-08-09
First.
## v1.2.0 — 2026-08-09
Second.
"""

        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "found 0"):
            changelog_release_notes.extract_release_notes(missing, "v1.2.0")
        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "found 2"):
            changelog_release_notes.extract_release_notes(duplicate, "v1.2.0")

    def test_invalid_tag_heading_date_and_empty_section_are_rejected(self) -> None:
        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "Invalid release tag"):
            changelog_release_notes.extract_release_notes("", "release-latest")
        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "Malformed"):
            changelog_release_notes.extract_release_notes(
                "## v1.2.0 - 2026-08-09\nNotes.\n",
                "v1.2.0",
            )
        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "Invalid changelog date"):
            changelog_release_notes.extract_release_notes(
                "## v1.2.0 — 2026-02-30\nNotes.\n",
                "v1.2.0",
            )
        with self.assertRaisesRegex(changelog_release_notes.ChangelogError, "empty"):
            changelog_release_notes.extract_release_notes(
                "## v1.2.0 — 2026-08-09\n\n## v1.1.0 — 2026-08-08\nNotes.\n",
                "v1.2.0",
            )


if __name__ == "__main__":
    unittest.main()
