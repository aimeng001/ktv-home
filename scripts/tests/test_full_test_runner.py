import contextlib
import io
import re
import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from xml.etree import ElementTree

from scripts.full_test_runner import (
    CommandSpec,
    DOCKER_BACKEND_TEST_CLASSES,
    TaskResult,
    TaskSpec,
    _android_command,
    _run_task,
    _executable,
    _process_argv,
    _task_environment,
    _parse_args,
    build_task_specs,
    CORE_ANDROID_TEST_CLASSES,
    CORE_BACKEND_TESTCONTAINER_TESTS,
    CORE_BACKEND_UNIT_TESTS,
    CORE_H5_TEST_FILES,
    CORE_WINDOWS_TEST_CLASSES,
    CORE_BRANCH_COVERAGE_FILES,
    _jacoco_source_counts,
    default_parallel_jobs,
    main,
    run_tasks,
    validate_core_coverage,
)


ROOT = Path(__file__).resolve().parents[2]


class CoreCoverageGateTest(unittest.TestCase):
    def write_jacoco_report(self, path, module="backend", *, omit=(), missed=0):
        package_files = []
        for source in CORE_BRANCH_COVERAGE_FILES[module]:
            if source in omit:
                continue
            package, filename = source.rsplit("/", 1)
            package_files.append(
                f'<package name="{package}"><sourcefile name="{filename}">'
                '<counter type="LINE" missed="0" covered="1"/>'
                f'<counter type="BRANCH" missed="{missed}" covered="{2 - missed}"/>'
                "</sourcefile></package>"
            )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<report>" + "".join(package_files) + "</report>", encoding="utf-8")

    def test_backend_gate_requires_every_allowlisted_source_and_all_branches(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "backend"
            report = coverage_root / "report" / "jacoco.xml"
            self.write_jacoco_report(report)

            self.assertEqual([], validate_core_coverage("backend", coverage_root))

            first_source = CORE_BRANCH_COVERAGE_FILES["backend"][0]
            self.write_jacoco_report(report, missed=1)
            findings = validate_core_coverage("backend", coverage_root)
            self.assertTrue(any(first_source in finding and "miss" in finding for finding in findings))

            self.write_jacoco_report(report, omit=(first_source,))
            findings = validate_core_coverage("backend", coverage_root)
            self.assertTrue(any(first_source in finding and "missing" in finding for finding in findings))

    def test_android_gate_reads_current_run_jacoco_report(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "android"
            report = coverage_root / "build" / "reports" / "coverage" / "test" / "debug" / "report.xml"
            self.write_jacoco_report(report, "android")

            self.assertEqual([], validate_core_coverage("android", coverage_root))

            source = CORE_BRANCH_COVERAGE_FILES["android"][0]
            self.write_jacoco_report(report, "android", omit=(source,))
            findings = validate_core_coverage("android", coverage_root)
            self.assertTrue(any(source in finding and "missing" in finding for finding in findings))

    def test_android_coverage_excludes_only_kotlin_default_constructor_branches(self):
        source = "com/homektv/tv/controller/ControllerState.kt"
        report = ElementTree.fromstring(
            '<report><package name="com/homektv/tv/controller">'
            '<sourcefile name="ControllerState.kt">'
            '<counter type="BRANCH" missed="1" covered="1"/>'
            '</sourcefile>'
            '<class name="com/homektv/tv/controller/ControllerState" sourcefilename="ControllerState.kt">'
            '<method name="&lt;init&gt;" desc="(Lkotlin/jvm/internal/DefaultConstructorMarker;)V">'
            '<counter type="BRANCH" missed="1" covered="0"/>'
            '</method>'
            '<method name="withDomainFailure" desc="()Z">'
            '<counter type="BRANCH" missed="0" covered="1"/>'
            '</method>'
            '</class></package></report>'
        )

        counts, missing = _jacoco_source_counts(report, (source,))

        self.assertEqual([], missing)
        self.assertEqual((1, 0), counts[source])

        report.find(".//method[@name='withDomainFailure']/counter").set("missed", "1")
        report.find(".//method[@name='withDomainFailure']/counter").set("covered", "0")
        counts, missing = _jacoco_source_counts(report, (source,))

        self.assertEqual([], missing)
        self.assertEqual((0, 1), counts[source])

    def test_jacoco_source_counts_ignore_unallowlisted_sources_and_nonbranch_counters(self):
        source = CORE_BRANCH_COVERAGE_FILES["backend"][0]
        package, filename = source.rsplit("/", 1)
        report = ElementTree.fromstring(
            f'<report><package name="{package}">'
            f'<sourcefile name="{filename}"><counter type="BRANCH" missed="0" covered="2"/>'
            '</sourcefile><sourcefile name="Untracked.java">'
            '<counter type="BRANCH" missed="1" covered="0"/></sourcefile>'
            '<class name="Untracked" sourcefilename="Untracked.java"><method>'
            '<counter type="BRANCH" missed="1" covered="0"/></method></class>'
            f'<class name="Expected" sourcefilename="{filename}"><method>'
            '<counter type="LINE" missed="1" covered="0"/>'
            '<counter type="BRANCH" missed="0" covered="2"/>'
            '</method></class></package></report>'
        )

        counts, missing = _jacoco_source_counts(report, (source,))

        self.assertEqual([], missing)
        self.assertEqual((2, 0), counts[source])

    def test_windows_gate_checks_allowlisted_source_branch_conditions(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "windows"
            report = coverage_root / "TestResults" / "coverage.cobertura.xml"
            report.parent.mkdir(parents=True)

            def write_report(partial_index=None, invalid_index=None, no_branch_index=None):
                classes = []
                for index, source in enumerate(CORE_BRANCH_COVERAGE_FILES["windows"]):
                    coverage = "50% (1/2)" if index == partial_index else "100% (2/2)"
                    filename = f"drive//{source.replace('/', '//')}" if index == 1 else source
                    branch_line = (
                        "<line number=\"1\" branch=\"false\"/>"
                        if index == no_branch_index
                        else '<line number="1" branch="true" condition-coverage="invalid"/>'
                        if index == invalid_index
                        else f'<line number="1" branch="true" condition-coverage="{coverage}"/>'
                    )
                    classes.append(f'<class name="Core{index}" filename="{filename}"><lines>{branch_line}</lines></class>')
                report.write_text(
                    "<coverage><packages><package><classes>"
                    + "".join(classes)
                    + "</classes></package></packages></coverage>",
                    encoding="utf-8",
                )

            write_report()
            self.assertEqual([], validate_core_coverage("windows", coverage_root))
            write_report(partial_index=0)

            findings = validate_core_coverage("windows", coverage_root)

        self.assertTrue(any(CORE_BRANCH_COVERAGE_FILES["windows"][0] in finding and "missed=1" in finding for finding in findings))

        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "windows"
            report = coverage_root / "TestResults" / "coverage.cobertura.xml"
            self.write_windows_report(report, no_branch_index=0)
            findings = validate_core_coverage("windows", coverage_root)
        self.assertTrue(any(CORE_BRANCH_COVERAGE_FILES["windows"][0] in finding and "missing branch data" in finding for finding in findings))

        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "windows"
            report = coverage_root / "TestResults" / "coverage.cobertura.xml"
            self.write_windows_report(report, invalid_index=0)
            findings = validate_core_coverage("windows", coverage_root)
        self.assertTrue(any(CORE_BRANCH_COVERAGE_FILES["windows"][0] in finding and "invalid branch data" in finding for finding in findings))

    def test_windows_gate_aggregates_generated_types_by_source_file(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "windows"
            report = coverage_root / "TestResults" / "coverage.cobertura.xml"
            classes = []
            first_source = CORE_BRANCH_COVERAGE_FILES["windows"][0]
            for index, source in enumerate(CORE_BRANCH_COVERAGE_FILES["windows"]):
                if index == 0:
                    classes.append(
                        f'<class name="Core{index}" filename="{source}"><lines>'
                        '<line number="1" branch="true" condition-coverage="50% (1/2)"/>'
                        '</lines></class>'
                    )
                    classes.append(
                        f'<class name="GeneratedCore{index}" filename="{source}"><lines>'
                        '<line number="2" branch="true" condition-coverage="100% (2/2)"/>'
                        '</lines></class>'
                    )
                else:
                    classes.append(
                        f'<class name="Core{index}" filename="{source}"><lines>'
                        '<line number="1" branch="true" condition-coverage="100% (2/2)"/>'
                        '</lines></class>'
                    )
            report.parent.mkdir(parents=True)
            report.write_text(
                "<coverage><packages><package><classes>"
                + "".join(classes)
                + "</classes></package></packages></coverage>",
                encoding="utf-8",
            )

            findings = validate_core_coverage("windows", coverage_root)

        self.assertTrue(any(first_source in finding and "missed=1" in finding for finding in findings))

    @staticmethod
    def write_windows_report(report, *, no_branch_index=None, invalid_index=None):
        report.parent.mkdir(parents=True, exist_ok=True)
        classes = []
        for index, source in enumerate(CORE_BRANCH_COVERAGE_FILES["windows"]):
            branch_line = (
                '<line number="1" branch="false"/>'
                if index == no_branch_index
                else '<line number="1" branch="true" condition-coverage="invalid"/>'
                if index == invalid_index
                else '<line number="1" branch="true" condition-coverage="100% (2/2)"/>'
            )
            classes.append(
                f'<class name="Core{index}" filename="{source}"><lines>'
                f'{branch_line}</lines></class>'
            )
        report.write_text(
            "<coverage><packages><package><classes>"
            + "".join(classes)
            + "</classes></package></packages></coverage>",
            encoding="utf-8",
        )

    def test_core_task_fails_when_current_run_coverage_is_incomplete(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "scripts",
                root,
                (CommandSpec((sys.executable, "-c", "pass"), "script tests"),),
                "scripts core checks",
                coverage=True,
                profile="core",
            )
            process = Mock()
            process.wait.return_value = 0
            with patch("scripts.full_test_runner.subprocess.Popen", return_value=process), patch(
                "scripts.full_test_runner.validate_core_coverage",
                return_value=["scripts core branch coverage 1/2"],
            ):
                result = _run_task(task, log_dir, root, root / "coverage")

            log_text = result.log_path.read_text(encoding="utf-8")

        self.assertEqual(1, result.returncode)
        self.assertIn("FAIL: scripts core branch coverage 1/2", log_text)

    def test_core_task_logs_pass_when_current_run_coverage_is_complete(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = Path(temporary) / "repo"
            root.mkdir()
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "scripts",
                root,
                (CommandSpec((sys.executable, "-c", "pass"), "script tests"),),
                "scripts core checks",
                coverage=True,
                profile="core",
            )
            process = Mock()
            process.wait.return_value = 0
            with patch("scripts.full_test_runner.subprocess.Popen", return_value=process), patch(
                "scripts.full_test_runner.validate_core_coverage",
                return_value=[],
            ):
                result = _run_task(task, log_dir, root, root / "coverage")

            log_text = result.log_path.read_text(encoding="utf-8")

        self.assertEqual(0, result.returncode)
        self.assertIn("PASS: current-run core branch coverage evidence is complete", log_text)

    def test_cobertura_gate_requires_nonempty_complete_branch_totals(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "scripts"
            report = coverage_root / "coverage.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<coverage branches-covered="2" branches-valid="2" branch-rate="1"/>',
                encoding="utf-8",
            )
            self.assertEqual([], validate_core_coverage("scripts", coverage_root))

            report.write_text(
                '<coverage branches-covered="1" branches-valid="2" branch-rate="0.5"/>',
                encoding="utf-8",
            )
            findings = validate_core_coverage("scripts", coverage_root)
            self.assertTrue(any("1/2" in finding for finding in findings))

            report.write_text(
                '<coverage branches-covered="0" branches-valid="0" branch-rate="0"/>',
                encoding="utf-8",
            )
            findings = validate_core_coverage("scripts", coverage_root)
            self.assertTrue(any("no branch outcomes" in finding for finding in findings))

    def test_h5_coverage_uses_its_core_report_and_requires_all_branches(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "h5"
            report = coverage_root / "cobertura-coverage.xml"
            report.parent.mkdir(parents=True)
            report.write_text(
                '<coverage branches-covered="2" branches-valid="2" branch-rate="1"/>',
                encoding="utf-8",
            )
            self.assertEqual([], validate_core_coverage("h5", coverage_root))

    def test_missing_report_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary)
            findings = validate_core_coverage("android", coverage_root)
            windows_findings = validate_core_coverage("windows", coverage_root / "windows")

        self.assertTrue(any("report" in finding.lower() and "missing" in finding.lower() for finding in findings))
        self.assertTrue(any("missing or ambiguous" in finding.lower() for finding in windows_findings))

        self.assertTrue(validate_core_coverage("unknown", Path("missing")))

        with patch("scripts.full_test_runner._coverage_report_path", return_value=None):
            h5_findings = validate_core_coverage("h5", Path("missing"))
        self.assertTrue(any("report missing" in finding.lower() for finding in h5_findings))

    def test_malformed_report_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix="ktv-coverage-gate-", dir=ROOT) as temporary:
            coverage_root = Path(temporary) / "scripts"
            report = coverage_root / "coverage.xml"
            report.parent.mkdir(parents=True)
            report.write_text("<coverage", encoding="utf-8")

            findings = validate_core_coverage("scripts", coverage_root)

        self.assertTrue(any("unreadable" in finding.lower() for finding in findings))


class FullTestRunnerPlanTest(unittest.TestCase):
    def make_temp_root(self, temporary):
        root = Path(temporary) / "repo"
        root.mkdir()
        return root

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

    def test_full_profile_runs_the_ci_workflow_contract_test(self):
        tasks = build_task_specs(ROOT, profile="full", backend_scope="local")
        scripts = next(task for task in tasks if task.name == "scripts")

        script_names = {
            Path(argument).name
            for command in scripts.commands
            for argument in command.argv
            if argument.endswith(".py")
        }
        self.assertIn("ci_workflow_test.py", script_names)
        self.assertIn("compose_config_test.py", script_names)

    def test_extended_local_backend_scope_excludes_only_known_testcontainers_classes(self):
        tasks = build_task_specs(ROOT, profile="extended", backend_scope="local")
        backend = next(task for task in tasks if task.name == "backend")
        command_text = " ".join(backend.commands[0].argv)

        for class_name in DOCKER_BACKEND_TEST_CLASSES:
            self.assertIn(class_name, command_text)
        self.assertIn("-Dtest=", command_text)

    def test_default_plan_uses_core_backend_and_gradle_daemon(self):
        tasks = build_task_specs(ROOT, backend_scope="all")
        self.assertEqual(
            {"backend", "h5", "android", "windows", "scripts"},
            {task.name for task in tasks},
        )

        backend = next(task for task in tasks if task.name == "backend")
        backend_selector = next(arg for arg in backend.commands[0].argv if arg.startswith("-Dtest="))
        self.assertIn("PlaybackServiceTest", backend_selector)
        self.assertIn("QueuePlaybackIntegrationTest", backend_selector)
        android = next(task for task in tasks if task.name == "android")
        self.assertNotIn("--no-daemon", android.commands[0].argv)

    def test_core_is_the_default_and_selects_only_critical_test_suites(self):
        self.assertEqual("core", _parse_args([]).profile)
        tasks = build_task_specs(ROOT, profile="core", backend_scope="all")

        backend = next(task for task in tasks if task.name == "backend")
        backend_argv = backend.commands[0].argv
        backend_test_filter = next(arg for arg in backend_argv if arg.startswith("-Dtest="))
        self.assertIn("SongAvailabilityPolicyTest", backend_test_filter)
        self.assertIn("MediaImportServiceTest", backend_test_filter)
        self.assertIn("LibraryScanCoordinatorTest", backend_test_filter)
        self.assertIn("AdminAuthInterceptorTest", backend_test_filter)
        self.assertIn("PlaybackVariantStreamControllerTest", backend_test_filter)
        self.assertIn("QueueServiceConcurrencyTest", backend_test_filter)
        self.assertIn("QueuePlaybackIntegrationTest", backend_test_filter)
        self.assertIn("WebSocketIntegrationTest", backend_test_filter)
        self.assertIn("-DrunLargeLibraryScanTest=false", backend_argv)

        h5 = next(task for task in tasks if task.name == "h5")
        h5_argv = h5.commands[0].argv
        self.assertIn("src/api/sharedProtocolFixture.test.js", h5_argv)
        self.assertIn("src/stores/player.test.js", h5_argv)
        self.assertIn("src/stores/adminAuth.test.js", h5_argv)
        self.assertIn("src/views/admin/AdminLoginView.test.js", h5_argv)
        self.assertIn("src/views/admin/libraryMode.test.js", h5_argv)
        self.assertNotIn("run", h5_argv)
        self.assertEqual(1, len(h5.commands))

        android = next(task for task in tasks if task.name == "android")
        android_argv = android.commands[0].argv
        self.assertIn(":app:testDebugUnitTest", android_argv)
        self.assertIn("--tests", android_argv)
        self.assertIn("com.homektv.tv.player.PlaybackCoordinatorTest", android_argv)
        self.assertIn("com.homektv.tv.net.AppConfigCredentialScopeContractTest", android_argv)
        self.assertIn("com.homektv.tv.player.KtvChannelAudioProcessorTest", android_argv)
        self.assertIn("com.homektv.tv.net.PlaybackErrorOutboxTest", android_argv)
        self.assertNotIn(":app:lintDebug", android_argv)
        self.assertNotIn(":app:assembleDebug", android_argv)
        self.assertNotIn(":app:assembleDebugAndroidTest", android_argv)

        windows = next(task for task in tasks if task.name == "windows")
        windows_argv = windows.commands[0].argv
        self.assertIn("--filter", windows_argv)
        self.assertIn("KtvWebSocketClientTests", " ".join(windows_argv))
        self.assertIn("PlaybackFailurePolicyTests", " ".join(windows_argv))
        self.assertIn("MpvIpcProtocolTests", " ".join(windows_argv))

        scripts = next(task for task in tasks if task.name == "scripts")
        script_labels = tuple(command.label for command in scripts.commands)
        self.assertEqual(
            (
                "repo_portability_test.py",
                "ci_workflow_test.py",
                "compose_config_test.py",
                "Python unit tests",
            ),
            script_labels,
        )

    def test_core_selectors_resolve_to_existing_test_sources(self):
        backend_test_sources = tuple((ROOT / "backend/src/test/java").rglob("*.java"))
        backend_sources = {path.stem: path.read_text(encoding="utf-8") for path in backend_test_sources}
        self.assertIn("MediaProbeStageTest", CORE_BACKEND_UNIT_TESTS)
        self.assertIn("QueueServiceTest", CORE_BACKEND_UNIT_TESTS)
        self.assertIn("PendingPlaybackReportTests", CORE_WINDOWS_TEST_CLASSES)
        self.assertEqual(
            [], [name for name in CORE_BACKEND_UNIT_TESTS if name not in backend_sources],
            "missing Backend test classes",
        )

        for selector in CORE_BACKEND_TESTCONTAINER_TESTS:
            class_name, _, method_name = selector.partition("#")
            self.assertIn(class_name, backend_sources, selector)
            if method_name:
                self.assertIsNotNone(
                    re.search(rf"\b{re.escape(method_name)}\s*\(", backend_sources[class_name]),
                    selector,
                )

        for relative_path in CORE_H5_TEST_FILES:
            self.assertTrue((ROOT / "h5" / relative_path).is_file(), relative_path)

        android_test_sources = tuple((ROOT / "android-tv/app/src/test").rglob("*.kt"))
        android_classes = {
            f"{package}.{match.group(1)}"
            for path in android_test_sources
            for source in (path.read_text(encoding="utf-8"),)
            for package in re.findall(r"^package\s+([\w.]+)", source, re.MULTILINE)
            for match in re.finditer(r"\bclass\s+(\w+)", source)
        }
        self.assertEqual(
            [], [name for name in CORE_ANDROID_TEST_CLASSES if name not in android_classes],
            "missing Android test classes",
        )

        windows_test_names = {path.stem for path in (ROOT / "windows-player/tests/HomeKtv.Windows.Tests").glob("*.cs")}
        self.assertEqual(
            [], [name for name in CORE_WINDOWS_TEST_CLASSES if name not in windows_test_names],
            "missing Windows test classes",
        )

    def test_local_core_scope_omits_only_testcontainers_smoke_cases(self):
        tasks = build_task_specs(ROOT, profile="core", backend_scope="local")
        backend = next(task for task in tasks if task.name == "backend")
        command_text = " ".join(backend.commands[0].argv)

        self.assertIn("SongAvailabilityPolicyTest", command_text)
        self.assertNotIn("QueuePlaybackIntegrationTest", command_text)
        self.assertNotIn("WebSocketIntegrationTest", command_text)

    def test_extended_profile_runs_the_complete_existing_suites(self):
        tasks = build_task_specs(ROOT, profile="extended", backend_scope="all")
        by_name = {task.name: task for task in tasks}

        self.assertNotIn("-Dtest=", " ".join(by_name["backend"].commands[0].argv))
        self.assertEqual(("H5 tests", "H5 production build"), tuple(
            command.label for command in by_name["h5"].commands
        ))
        self.assertIn(":app:lintDebug", by_name["android"].commands[0].argv)
        self.assertIn("release_workflow_test.py", " ".join(
            arg for command in by_name["scripts"].commands for arg in command.argv
        ))

    def test_legacy_profiles_map_to_core_and_extended(self):
        core = build_task_specs(ROOT, profile="core", backend_scope="all")
        self.assertEqual(core, build_task_specs(ROOT, profile="fast", backend_scope="all"))

        extended = build_task_specs(ROOT, profile="extended", backend_scope="all")
        self.assertEqual(extended, build_task_specs(ROOT, profile="full", backend_scope="all"))

    def test_coverage_plan_collects_reports_for_every_module(self):
        tasks = build_task_specs(ROOT, profile="full", backend_scope="all", coverage=True)
        by_name = {task.name: task for task in tasks}

        backend_argv = by_name["backend"].commands[0].argv
        self.assertIn("-Pcoverage", backend_argv)
        self.assertIn("-DrunLargeLibraryScanTest=false", backend_argv)
        self.assertIn("-DrunLargeLibraryTest=false", backend_argv)
        self.assertIn("-DrunSearchPerformanceTest=false", backend_argv)
        self.assertNotIn("-DlargeLibraryRows=10000", backend_argv)
        self.assertNotIn("-DsearchLibraryRows=10000", backend_argv)

        h5_argv = by_name["h5"].commands[0].argv
        self.assertIn("--coverage", h5_argv)

        android_argv = by_name["android"].commands[0].argv
        self.assertIn(":app:createDebugUnitTestCoverageReport", android_argv)
        self.assertNotIn(":app:createDebugAndroidTestCoverageReport", android_argv)

        windows_argv = by_name["windows"].commands[0].argv
        self.assertIn("--collect:XPlat Code Coverage", windows_argv)
        self.assertIn(
            str(ROOT / "windows-player" / "tests" / "HomeKtv.Windows.Tests" / "HomeKtv.Windows.Tests.csproj"),
            windows_argv,
        )
        self.assertNotIn(str(ROOT / "windows-player" / "HomeKtv.Windows.sln"), windows_argv)

        script_argv = [arg for command in by_name["scripts"].commands for arg in command.argv]
        self.assertIn("coverage", script_argv)
        self.assertIn("--branch", script_argv)

    def test_android_instrumented_coverage_requires_an_emulator_serial(self):
        tasks = build_task_specs(
            ROOT,
            profile="full",
            coverage=True,
            android_emulator_serial="emulator-5554",
        )
        android = next(task for task in tasks if task.name == "android")

        self.assertEqual("emulator-5554", android.android_emulator_serial)
        self.assertIn(":app:createDebugAndroidTestCoverageReport", android.commands[0].argv)
        self.assertIn(":app:connectedDebugAndroidTest", android.commands[0].argv)

        with self.assertRaisesRegex(ValueError, "emulator"):
            build_task_specs(ROOT, coverage=True, android_emulator_serial="R58M123456")

    def test_android_runner_pins_adb_to_the_selected_emulator(self):
        task = TaskSpec(
            "android",
            ROOT / "android-tv",
            (CommandSpec(("gradlew", "connectedDebugAndroidTest"), "instrumented tests"),),
            "Android emulator tests",
            android_emulator_serial="emulator-5554",
        )
        process = Mock()
        process.wait.return_value = 0
        observed = {}

        def capture_process(argv, **kwargs):
            observed["env"] = kwargs["env"]
            return process

        with tempfile.TemporaryDirectory(prefix="ktv-android-runner-test-", dir=ROOT) as log_dir:
            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture_process):
                result = _run_task(task, Path(log_dir), ROOT)

        self.assertEqual(0, result.returncode)
        self.assertEqual("emulator-5554", observed["env"]["ANDROID_SERIAL"])

    def test_android_command_enables_instrumentation_and_no_daemon_for_emulator_run(self):
        command = _android_command(
            ROOT / "android-tv",
            no_daemon=True,
            profile="full",
            coverage=False,
            emulator_serial="emulator-5554",
        )

        self.assertIn("--no-daemon", command.argv)
        self.assertIn(":app:connectedDebugAndroidTest", command.argv)

    def test_run_task_rejects_physical_android_serial_before_starting_process(self):
        task = TaskSpec(
            "android",
            ROOT / "android-tv",
            (CommandSpec(("gradlew", "connectedDebugAndroidTest"), "instrumented tests"),),
            "Android emulator tests",
            android_emulator_serial="physical-device",
        )
        with patch("scripts.full_test_runner.subprocess.Popen") as start_process:
            with self.assertRaisesRegex(ValueError, "emulator"):
                _run_task(task, ROOT / "backend" / "target", ROOT)
        start_process.assert_not_called()

    def test_h5_production_build_is_redirected_away_from_server_assets(self):
        task = TaskSpec(
            "h5",
            ROOT / "h5",
            (CommandSpec(("npm", "run", "build"), "H5 production build"),),
            "H5 build",
        )
        process = Mock()
        process.wait.return_value = 0
        observed = {}

        def capture_process(argv, **kwargs):
            observed["argv"] = argv
            observed["cwd"] = kwargs["cwd"]
            return process

        with tempfile.TemporaryDirectory(prefix="ktv-h5-runner-test-", dir=ROOT) as log_dir:
            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture_process):
                result = _run_task(task, Path(log_dir), ROOT)

        command = " ".join(str(part) for part in observed["argv"])
        self.assertEqual(0, result.returncode)
        self.assertIn("--outDir", command)
        self.assertNotIn("backend/src/main/resources/static/m", command)

    def test_run_task_passes_the_selected_profile_to_h5_coverage(self):
        task = TaskSpec(
            "h5",
            ROOT / "h5",
            (CommandSpec(("npm", "test"), "H5 tests"),),
            "H5 core tests",
            profile="core",
        )
        process = Mock()
        process.wait.return_value = 0
        observed = {}

        def capture_process(_argv, **kwargs):
            observed["env"] = kwargs["env"]
            return process

        with tempfile.TemporaryDirectory(prefix="ktv-h5-profile-runner-test-", dir=ROOT) as log_dir:
            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture_process):
                result = _run_task(task, Path(log_dir), ROOT)

        self.assertEqual(0, result.returncode)
        self.assertEqual("core", observed["env"]["KTV_TEST_PROFILE"])

    def test_task_environment_uses_workspace_temp_for_sandboxed_processes(self):
        environment = _task_environment(ROOT)
        temporary_directory = Path(environment["TEMP"]).resolve()

        self.assertEqual(str(temporary_directory), environment["TMP"])
        self.assertEqual(str(temporary_directory), environment["TMPDIR"])
        self.assertTrue(temporary_directory.is_relative_to(ROOT.resolve()))

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

    def test_default_parallel_jobs_handles_empty_unknown_and_large_cpu_counts(self):
        self.assertEqual(0, default_parallel_jobs(16, 0))
        self.assertEqual(2, default_parallel_jobs(None, 5))
        self.assertEqual(2, default_parallel_jobs(-2, 5))
        self.assertEqual(5, default_parallel_jobs(64, 8))

    def test_backend_and_android_command_builders_reject_invalid_overrides(self):
        with self.assertRaisesRegex(ValueError, "backend_parallelism"):
            build_task_specs(ROOT, backend_parallelism=0)
        with self.assertRaisesRegex(ValueError, "backend_scope"):
            build_task_specs(ROOT, backend_scope="unexpected")

    def test_windows_command_wrapping_and_executable_selection(self):
        with patch("scripts.full_test_runner._windows", return_value=True), patch.dict(
            "os.environ", {"ComSpec": "test-cmd.exe"}
        ):
            wrapped = _process_argv(("build.cmd", "argument with spaces"))
            self.assertEqual("test-cmd.exe", wrapped[0])
            self.assertEqual(["/d", "/c"], wrapped[1:3])
            self.assertIn('"argument with spaces"', wrapped[3])

        self.assertEqual([], _process_argv(()))
        with patch("scripts.full_test_runner._windows", return_value=True):
            self.assertEqual("npm.cmd", _executable("npm"))
            self.assertEqual("dotnet", _executable("dotnet"))
            self.assertEqual("python", _executable("python"))

    def test_run_task_logs_commands_and_stops_after_the_first_failure(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "sample",
                root,
                (
                    CommandSpec((sys.executable, "-c", "print('started'); raise SystemExit(7)"), "failing"),
                    CommandSpec((sys.executable, "-c", "print('must not run')"), "later"),
                ),
                "sample task",
            )

            result = _run_task(task, log_dir, root)

            log = result.log_path.read_text(encoding="utf-8")
        self.assertEqual(7, result.returncode)
        self.assertIn("started", log)
        self.assertIn("exit=7", log)
        self.assertNotIn("must not run", log)

    def test_run_task_uses_workspace_maven_cache_for_backend(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            maven = root / ".maven-local"
            mockito_agent = maven / "org" / "mockito" / "mockito-core" / "5.17.0" / "mockito-core-5.17.0.jar"
            mockito_agent.parent.mkdir(parents=True)
            mockito_agent.touch()
            task = TaskSpec(
                "backend",
                root,
                (CommandSpec(("mvnw.cmd", "test"), "backend tests"),),
                "backend",
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(argv, **kwargs):
                observed["argv"] = argv
                observed["env"] = kwargs["env"]
                return process

            with patch.dict("os.environ", {"KTV_MAVEN_REPO": str(maven)}), patch(
                "scripts.full_test_runner.subprocess.Popen", side_effect=capture
            ):
                result = _run_task(task, root / "logs", root)

        self.assertEqual(0, result.returncode)
        self.assertIn(f"-Dmaven.repo.local={maven}", " ".join(observed["argv"]))
        self.assertEqual(str(maven), observed["env"]["KTV_MAVEN_REPO"])
        self.assertIn(f'-javaagent:"{mockito_agent}"', observed["env"]["JAVA_TOOL_OPTIONS"])

    def test_backend_runner_keeps_existing_java_options_when_mockito_agent_is_missing(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            maven = root / "empty-maven"
            task = TaskSpec(
                "backend",
                root,
                (CommandSpec(("mvnw.cmd", "test"), "backend tests"),),
                "backend",
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(_argv, **kwargs):
                observed["env"] = kwargs["env"]
                return process

            with patch.dict("os.environ", {
                "KTV_MAVEN_REPO": str(maven),
                "JAVA_TOOL_OPTIONS": "-Dexisting-option=true",
            }), patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root)

        self.assertEqual(0, result.returncode)
        self.assertEqual("-Dexisting-option=true", observed["env"]["JAVA_TOOL_OPTIONS"])

    def test_backend_runner_does_not_duplicate_an_existing_mockito_agent(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            maven = root / ".maven-local"
            mockito_agent = maven / "org" / "mockito" / "mockito-core" / "5.17.0" / "mockito-core-5.17.0.jar"
            mockito_agent.parent.mkdir(parents=True)
            mockito_agent.touch()
            java_options = f"-javaagent:{mockito_agent} -Dexisting-option=true"
            task = TaskSpec(
                "backend",
                root,
                (CommandSpec(("mvnw.cmd", "test"), "backend tests"),),
                "backend",
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(_argv, **kwargs):
                observed["env"] = kwargs["env"]
                return process

            with patch.dict("os.environ", {
                "KTV_MAVEN_REPO": str(maven),
                "JAVA_TOOL_OPTIONS": java_options,
            }), patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root)

        self.assertEqual(0, result.returncode)
        self.assertEqual(java_options, observed["env"]["JAVA_TOOL_OPTIONS"])

    def test_backend_coverage_uses_a_run_scoped_jacoco_data_file(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            coverage_dir = root / "coverage"
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "backend",
                root,
                (CommandSpec(("mvnw.cmd", "test"), "backend tests"),),
                "backend",
                coverage=True,
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(argv, **_kwargs):
                observed["argv"] = argv
                return process

            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root, coverage_dir)

        expected_data_file = coverage_dir / "backend" / "jacoco.exec"
        expected_report_dir = coverage_dir / "backend" / "report"
        self.assertEqual(0, result.returncode)
        self.assertIn(f"-Djacoco.exec.path={expected_data_file}", " ".join(observed["argv"]))
        self.assertIn(f"-Djacoco.outputDirectory={expected_report_dir}", " ".join(observed["argv"]))

    def test_android_coverage_uses_a_run_scoped_gradle_build_directory(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            coverage_dir = root / "coverage"
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "android",
                root / "android-tv",
                (CommandSpec(("gradlew.bat", ":app:testDebugUnitTest"), "Android tests"),),
                "android",
                coverage=True,
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(argv, **_kwargs):
                observed["argv"] = argv
                return process

            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root, coverage_dir)

        expected_build_dir = coverage_dir / "android" / "build"
        self.assertEqual(0, result.returncode)
        self.assertIn(f"-Pktv.coverage.buildDir={expected_build_dir}", " ".join(observed["argv"]))

    def test_windows_coverage_task_adds_a_test_results_directory(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            coverage_dir = root / "coverage"
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "windows",
                root,
                (CommandSpec(("dotnet", "test"), "windows tests"),),
                "windows",
                coverage=True,
            )
            process = Mock()
            process.wait.return_value = 0
            observed = {}

            def capture(argv, **_kwargs):
                observed["argv"] = argv
                return process

            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root, coverage_dir)
            results_dir = coverage_dir / "windows" / "TestResults"
            results_dir_exists = results_dir.is_dir()

        self.assertEqual(0, result.returncode)
        self.assertIn("--results-directory", observed["argv"])
        self.assertTrue(results_dir_exists)
        self.assertIn(str(results_dir), " ".join(observed["argv"]))

    def test_script_coverage_task_runs_reports_and_sets_coverage_file(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            coverage_dir = root / "coverage"
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "scripts",
                root,
                (CommandSpec((sys.executable, "-c", "pass"), "script tests"),),
                "scripts",
                coverage=True,
            )
            process = Mock()
            process.wait.return_value = 0
            observed = []

            def capture(argv, **kwargs):
                observed.append((argv, kwargs["env"]))
                return process

            with patch("scripts.full_test_runner.subprocess.Popen", side_effect=capture):
                result = _run_task(task, log_dir, root, coverage_dir)

        self.assertEqual(0, result.returncode)
        self.assertEqual(3, len(observed))
        self.assertEqual(str(coverage_dir / "scripts" / ".coverage"), observed[0][1]["COVERAGE_FILE"])
        self.assertIn("report", observed[1][0])
        self.assertIn("xml", observed[2][0])

    def test_script_coverage_task_stops_when_report_generation_fails(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            task = TaskSpec(
                "scripts",
                root,
                (CommandSpec((sys.executable, "-c", "pass"), "script tests"),),
                "scripts",
                coverage=True,
            )
            processes = [Mock(), Mock()]
            processes[0].wait.return_value = 0
            processes[1].wait.return_value = 2
            with patch(
                "scripts.full_test_runner.subprocess.Popen",
                side_effect=processes,
            ) as start_process:
                result = _run_task(task, log_dir, root, root / "coverage")

        self.assertEqual(2, result.returncode)
        self.assertEqual(2, start_process.call_count)

    def test_run_tasks_sorts_results_and_reports_pass_and_fail(self):
        tasks = (
            TaskSpec("zeta", ROOT, (), "zeta"),
            TaskSpec("alpha", ROOT, (), "alpha"),
        )
        results = {
            "zeta": TaskResult("zeta", "zeta", 1, 1.0, ROOT / "zeta.log"),
            "alpha": TaskResult("alpha", "alpha", 0, 0.5, ROOT / "alpha.log"),
        }
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            with patch(
                "scripts.full_test_runner._run_task",
                side_effect=lambda task, *_args: results[task.name],
            ), contextlib.redirect_stdout(io.StringIO()) as stdout:
                actual = run_tasks(tasks, root=ROOT, log_dir=Path(temporary) / "logs", jobs=2)

        self.assertEqual(["alpha", "zeta"], [result.name for result in actual])
        self.assertIn("[PASS] alpha", stdout.getvalue())
        self.assertIn("[FAIL] zeta", stdout.getvalue())

    def test_main_rejects_negative_limits_before_running_tasks(self):
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(2, main(["--backend-parallelism", "-1", "--root", str(ROOT)]))
        self.assertIn("backend-parallelism must be non-negative", stderr.getvalue())

        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(2, main(["--jobs", "-1", "--root", str(ROOT)]))
        self.assertIn("--jobs must be non-negative", stderr.getvalue())

    def test_main_uses_default_log_and_coverage_directories(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            observed = {}

            def fake_run_tasks(tasks, **kwargs):
                observed["tasks"] = tuple(tasks)
                observed["coverage_dir"] = kwargs["coverage_dir"]
                return ()

            with patch("scripts.full_test_runner.run_tasks", side_effect=fake_run_tasks), contextlib.redirect_stdout(io.StringIO()):
                result = main(["--profile", "fast", "--backend", "local", "--coverage", "--root", str(root)])

            generated_runs = root / "backend" / "target" / "full-test-runs"
            self.assertEqual(0, result)
            self.assertTrue(generated_runs.is_dir())
            self.assertTrue(observed["coverage_dir"].is_dir())
            self.assertEqual(5, len(observed["tasks"]))

    def test_main_reports_failed_task_logs_with_nonzero_exit(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            failed = TaskResult("backend", "backend", 1, 1.0, root / "logs" / "backend.log")
            with patch("scripts.full_test_runner.run_tasks", return_value=(failed,)), contextlib.redirect_stdout(io.StringIO()) as stdout:
                result = main([
                    "--profile", "fast", "--backend", "local", "--jobs", "1",
                    "--root", str(root), "--log-dir", str(root / "logs"),
                ])

        self.assertEqual(1, result)
        self.assertIn("passed=0 failed=1", stdout.getvalue())
        self.assertIn(str(failed.log_path), stdout.getvalue())

    def test_main_full_profile_all_backend_skips_local_and_fast_notices(self):
        with tempfile.TemporaryDirectory(prefix="ktv-runner-test-", dir=ROOT) as temporary:
            root = self.make_temp_root(temporary)
            with patch("scripts.full_test_runner.run_tasks", return_value=()), contextlib.redirect_stdout(
                io.StringIO()
            ) as stdout:
                result = main([
                    "--profile", "full", "--backend", "all", "--jobs", "1",
                    "--root", str(root), "--log-dir", str(root / "logs"),
                ])

        self.assertEqual(0, result)
        self.assertNotIn("Dockerless backend gate", stdout.getvalue())
        self.assertNotIn("Fast profile excludes", stdout.getvalue())

    def test_script_entrypoint_supports_help_without_running_suites(self):
        script = ROOT / "scripts" / "full_test_runner.py"
        with patch("sys.argv", [str(script), "--help"]), contextlib.redirect_stdout(io.StringIO()) as stdout:
            with self.assertRaises(SystemExit) as exit_result:
                runpy.run_path(str(script), run_name="__main__")

        self.assertEqual(0, exit_result.exception.code)
        self.assertIn("--profile", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
