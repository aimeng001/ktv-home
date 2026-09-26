import re
import unittest
from pathlib import Path

from scripts.full_test_runner import (
    DOCKER_BACKEND_TEST_CLASSES,
    build_task_specs,
    default_parallel_jobs,
)


ROOT = Path(__file__).resolve().parents[2]


class FullTestRunnerPlanTest(unittest.TestCase):
    def test_local_exclude_set_matches_all_testcontainers_classes(self):
        discovered = set()
        test_root = ROOT / "backend" / "src" / "test" / "java"
        for source in test_root.rglob("*.java"):
            text = source.read_text(encoding="utf-8")
            if "@Testcontainers" not in text:
                continue
            match = re.search(r"\bclass\s+(\w+)", text)
            self.assertIsNotNone(match, str(source))
            discovered.add(match.group(1))

        self.assertEqual(discovered, set(DOCKER_BACKEND_TEST_CLASSES))

    def test_local_backend_scope_excludes_only_known_testcontainers_classes(self):
        tasks = build_task_specs(ROOT, backend_scope="local")
        backend = next(task for task in tasks if task.name == "backend")
        command_text = " ".join(backend.commands[0].argv)

        for class_name in DOCKER_BACKEND_TEST_CLASSES:
            self.assertIn(class_name, command_text)
        self.assertIn("-Dtest=", command_text)

    def test_default_plan_keeps_full_backend_and_uses_gradle_daemon(self):
        tasks = build_task_specs(ROOT, backend_scope="all")
        self.assertEqual(
            {"backend", "h5", "android", "windows", "scripts"},
            {task.name for task in tasks},
        )

        backend = next(task for task in tasks if task.name == "backend")
        self.assertNotIn("-Dtest=", " ".join(backend.commands[0].argv))
        android = next(task for task in tasks if task.name == "android")
        self.assertNotIn("--no-daemon", android.commands[0].argv)

    def test_fast_plan_keeps_only_feedback_checks(self):
        tasks = build_task_specs(ROOT, profile="fast", backend_scope="local")

        backend = next(task for task in tasks if task.name == "backend")
        self.assertIn("-DexcludedGroups=extended", backend.commands[0].argv)

        h5 = next(task for task in tasks if task.name == "h5")
        self.assertEqual(("H5 tests",), tuple(command.label for command in h5.commands))

        android = next(task for task in tasks if task.name == "android")
        android_argv = android.commands[0].argv
        self.assertIn(":app:testDebugUnitTest", android_argv)
        self.assertNotIn(":app:lintDebug", android_argv)
        self.assertNotIn(":app:assembleDebug", android_argv)
        self.assertNotIn(":app:assembleDebugAndroidTest", android_argv)

        scripts = next(task for task in tasks if task.name == "scripts")
        script_labels = tuple(command.label for command in scripts.commands)
        self.assertEqual(
            ("repo_portability_test.py", "Python unit tests"),
            script_labels,
        )

    def test_unknown_profile_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "profile"):
            build_task_specs(ROOT, profile="unknown")

    def test_backend_parallelism_is_an_explicit_optional_override(self):
        tasks = build_task_specs(
            ROOT,
            backend_scope="local",
            backend_parallelism=8,
        )
        backend = next(task for task in tasks if task.name == "backend")
        self.assertIn(
            "-Djunit.jupiter.execution.parallel.config.fixed.parallelism=8",
            backend.commands[0].argv,
        )

    def test_parallel_job_count_is_bounded_by_module_count(self):
        self.assertEqual(default_parallel_jobs(16, 5), 5)
        self.assertEqual(default_parallel_jobs(1, 5), 1)
        self.assertEqual(default_parallel_jobs(0, 5), 2)

    def test_windows_maven_wrapper_handles_a_regular_m2_directory(self):
        wrapper = ROOT / "backend" / "mvnw.cmd"
        source = wrapper.read_text(encoding="utf-8")
        self.assertIn("$mavenM2Target", source)
        self.assertIn("$null -eq $mavenM2Target", source)

    def test_nas_compose_uses_configurable_image_registry(self):
        compose = (ROOT / "docker-compose.nas.yml").read_text(encoding="utf-8")
        self.assertIn('${KTV_IMAGE_REGISTRY:-docker.m.daocloud.io}/library/postgres:16-alpine', compose)
        self.assertNotIn("image: docker.m.daocloud.io/library/postgres:16-alpine", compose)


if __name__ == "__main__":
    unittest.main()
