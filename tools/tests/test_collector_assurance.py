from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools import collector_assurance


@unittest.skipUnless(shutil.which("javac"), "javac is required for class-file assurance tests")
class CollectorAssuranceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = collector_assurance.load_policy(collector_assurance.DEFAULT_POLICY)

    def compile_references(self, body: str) -> collector_assurance.ClassReferences:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Fixture.java"
            source.write_text(f"public final class Fixture {{ {body} }}", encoding="utf-8")
            subprocess.run(
                ["javac", "-d", str(root), str(source)],
                check=True,
                capture_output=True,
                text=True,
            )
            return collector_assurance.parse_class((root / "Fixture.class").read_bytes())

    def test_harmless_class_passes(self) -> None:
        references = self.compile_references("public int value() { return 7; }")
        self.assertEqual([], collector_assurance.violations(references, self.policy))

    def test_file_api_is_rejected_from_bytecode(self) -> None:
        references = self.compile_references(
            'public boolean value() { return new java.io.File("x").exists(); }'
        )
        self.assertTrue(
            any("java/io/File" in item for item in collector_assurance.violations(references, self.policy))
        )

    def test_file_type_in_a_method_descriptor_is_rejected(self) -> None:
        references = self.compile_references(
            "public void value(java.io.BufferedWriter writer) {}"
        )
        self.assertTrue(
            any(
                "java/io/BufferedWriter" in item
                for item in collector_assurance.violations(references, self.policy)
            )
        )

    def test_network_api_is_rejected_from_bytecode(self) -> None:
        references = self.compile_references(
            'public String value() throws Exception { return new java.net.URL("https://example.invalid").getHost(); }'
        )
        self.assertTrue(
            any("java/net/URL" in item for item in collector_assurance.violations(references, self.policy))
        )

    def test_dynamic_loading_is_rejected_from_bytecode(self) -> None:
        references = self.compile_references(
            'public Class<?> value() throws Exception { return Class.forName("Fixture"); }'
        )
        self.assertTrue(
            any("Class.forName" in item for item in collector_assurance.violations(references, self.policy))
        )

    def test_context_file_database_and_event_log_bypasses_are_rejected(self) -> None:
        references = collector_assurance.ClassReferences(
            classes=frozenset(
                {
                    "android/app/DownloadManager",
                    "android/util/EventLog",
                }
            ),
            methods=frozenset(
                {
                    collector_assurance.MemberReference(
                        "android/content/Context", "deleteFile"
                    ),
                    collector_assurance.MemberReference(
                        "android/content/ContextWrapper", "fileList"
                    ),
                    collector_assurance.MemberReference(
                        "android/app/Application", "deleteDatabase"
                    ),
                    collector_assurance.MemberReference(
                        "android/util/EventLog", "writeEvent"
                    ),
                }
            ),
        )

        violations = collector_assurance.violations(references, self.policy)

        self.assertTrue(any("DownloadManager" in item for item in violations))
        self.assertTrue(any("EventLog" in item for item in violations))
        self.assertTrue(any("Context.deleteFile" in item for item in violations))
        self.assertTrue(any("ContextWrapper.fileList" in item for item in violations))
        self.assertTrue(any("Application.deleteDatabase" in item for item in violations))

    def test_unrecognized_gradle_dependency_form_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module / "src/main").mkdir(parents=True)
            (module / "build.gradle.kts").write_text(
                'dependencies { implementation(files("collector.jar")) }\n',
                encoding="utf-8",
            )
            errors = collector_assurance._source_violations(module, self.policy)
        self.assertTrue(any("unrecognized dependency declaration" in item for item in errors))

    def test_junit_is_the_only_allowed_collector_test_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module / "src/main").mkdir(parents=True)
            build_file = module / "build.gradle.kts"
            build_file.write_text(
                "dependencies { testImplementation(libs.junit4) }\n",
                encoding="utf-8",
            )
            junit_errors = collector_assurance._source_violations(module, self.policy)
            build_file.write_text(
                "dependencies { testImplementation(libs.mockk) }\n",
                encoding="utf-8",
            )
            arbitrary_errors = collector_assurance._source_violations(module, self.policy)

        self.assertEqual([], junit_errors)
        self.assertTrue(any("forbidden test dependency libs.mockk" in item for item in arbitrary_errors))


if __name__ == "__main__":
    unittest.main()
