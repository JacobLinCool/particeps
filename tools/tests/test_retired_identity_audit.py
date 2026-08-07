from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.retired_identity_audit import (
    APPLICATION_ID,
    AuditError,
    audit,
    audit_fresh_install_boundary,
    audit_tracked_files,
)

REPOSITORY = Path(__file__).resolve().parents[2]

BUILD_FILE = f"""
android {{
    namespace = "{APPLICATION_ID}"
    defaultConfig {{
        applicationId = "{APPLICATION_ID}"
    }}
}}
"""


def _repository(root: Path, files: dict[str, str]) -> Path:
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)
    return root


class RetiredIdentityAuditTest(unittest.TestCase):
    def test_this_repository_passes(self) -> None:
        audit(REPOSITORY)

    def test_a_retired_spelling_outside_the_allow_list_fails(self) -> None:
        for name, text in {
            "product name": 'val label = "Android Data Collector"',
            "package segment": "package cool.linc.androiddatacollector.core",
            "configuration magic": 'val magic = "ADCCFG01"',
            "export magic": 'val magic = "ADCEXP01"',
            "storage magic": 'val magic = "ADCMET01"',
            "configuration extension": 'val name = "study.adccfg"',
            "export extension": 'val name = "bundle.adcexp"',
            "segment extension": 'val name = "events-00000001.adcs"',
            "join scheme": 'val prefix = "adc://join/v1?"',
            "bundle format": 'val format = "research-bundle-v1"',
            "media type": 'val type = "application/vnd.adc.research-bundle"',
            "routing header": 'val header = "X-ADC-Bundle-Id"',
            "python package": "from adc_analysis import bundle",
            "bare token": 'val note = "the adc contract"',
        }.items():
            with self.subTest(spelling=name), tempfile.TemporaryDirectory() as directory:
                root = _repository(
                    Path(directory),
                    {"app/build.gradle.kts": BUILD_FILE, "src/Sample.kt": text},
                )
                problems = audit_tracked_files(root, {})
                self.assertTrue(problems, f"{name} was not detected")
                self.assertIn("src/Sample.kt:1", problems[0])

    def test_an_allowed_file_may_carry_a_retired_spelling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _repository(
                Path(directory),
                {
                    "app/build.gradle.kts": BUILD_FILE,
                    "tests/hostile.spec.ts": "const retired = 'ADCEXP01';",
                },
            )
            allowed = {"tests/hostile.spec.ts": "retired-identity rejection fixture"}
            self.assertEqual([], audit_tracked_files(root, allowed))

    def test_reverting_the_application_id_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _repository(
                Path(directory),
                {
                    "app/build.gradle.kts": BUILD_FILE.replace(
                        f'applicationId = "{APPLICATION_ID}"',
                        'applicationId = "cool.linc.androiddatacollector"',
                    )
                },
            )
            problems = audit_fresh_install_boundary(root)
            self.assertTrue(any("applicationId" in problem for problem in problems))

    def test_reading_the_retired_namespace_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = _repository(
                Path(directory),
                {
                    "app/build.gradle.kts": BUILD_FILE,
                    "src/Restore.kt": 'val legacy = "cool.linc.androiddatacollector"',
                    "src/AlsoRestore.kt": 'val previous = "cool.linc.particeps"',
                },
            )
            problems = audit_fresh_install_boundary(root)
            # Both retired namespaces, not just the first: an application that has been renamed
            # twice has two dead namespaces, and reading either is the same mistake.
            self.assertEqual(2, sum("retired namespace" in problem for problem in problems))

    def test_every_allow_list_entry_still_exists_and_still_needs_the_exception(self) -> None:
        from tools.retired_identity_audit import ALLOWED

        for name, reason in ALLOWED.items():
            with self.subTest(path=name):
                self.assertTrue((REPOSITORY / name).is_file(), f"{name} no longer exists")
                self.assertTrue(reason.strip(), f"{name} has no stated reason")
                without = audit_tracked_files(
                    REPOSITORY, {n: r for n, r in ALLOWED.items() if n != name}
                )
                self.assertTrue(
                    any(problem.startswith(f"{name}:") for problem in without),
                    msg=f"{name} no longer carries a retired spelling; drop it from ALLOWED",
                )


if __name__ == "__main__":
    unittest.main()
