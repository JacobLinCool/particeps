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


class CollectorAndroidTestDependencyAssuranceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = collector_assurance.load_policy(collector_assurance.DEFAULT_POLICY)

    def dependency_errors(self, declarations: str) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            (module / "build.gradle.kts").write_text(
                f"dependencies {{\n{declarations}\n}}\n", encoding="utf-8"
            )
            return collector_assurance._source_violations(module, self.policy)

    def test_android_instrumentation_runner_and_junit_are_allowed(self) -> None:
        self.assertEqual(
            [],
            self.dependency_errors(
                "implementation(libs.coroutines.android)\n"
                "testImplementation(libs.junit4)\n"
                "androidTestImplementation(libs.androidx.test.runner)\n"
                "androidTestImplementation(libs.androidx.test.junit)"
            ),
        )

    def test_other_android_test_catalog_dependencies_are_rejected(self) -> None:
        for dependency in ("libs.mockk", "libs.junit4", "libs.coroutines.android"):
            with self.subTest(dependency=dependency):
                errors = self.dependency_errors(f"androidTestImplementation({dependency})")
                self.assertEqual(1, len(errors))
                self.assertIn(f"forbidden Android test dependency {dependency}", errors[0])

    def test_android_test_artifacts_cannot_enter_production_dependencies(self) -> None:
        for scope in ("api", "implementation", "compileOnly", "runtimeOnly"):
            for dependency in ("libs.androidx.test.runner", "libs.androidx.test.junit"):
                with self.subTest(scope=scope, dependency=dependency):
                    errors = self.dependency_errors(f"{scope}({dependency})")
                    self.assertEqual(1, len(errors))
                    self.assertIn(f"forbidden catalog dependency {dependency}", errors[0])

    def test_android_test_allowlist_does_not_extend_jvm_test_allowlist(self) -> None:
        for dependency in ("libs.androidx.test.runner", "libs.androidx.test.junit"):
            with self.subTest(dependency=dependency):
                errors = self.dependency_errors(f"testImplementation({dependency})")
                self.assertEqual(1, len(errors))
                self.assertIn(f"forbidden test dependency {dependency}", errors[0])

    def test_unrecognized_android_test_dependency_forms_are_rejected(self) -> None:
        for declaration in (
            'androidTestImplementation("androidx.test:runner:1.7.0")',
            'androidTestImplementation(project(":core:collector-api"))',
            'androidTestImplementation(files("collector.jar"))',
            "androidTestApi(libs.androidx.test.runner)",
            "androidTestRuntimeOnly(libs.androidx.test.runner)",
        ):
            with self.subTest(declaration=declaration):
                errors = self.dependency_errors(declaration)
                self.assertEqual(1, len(errors))
                self.assertIn("unrecognized dependency declaration", errors[0])


if __name__ == "__main__":
    unittest.main()
