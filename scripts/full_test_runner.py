#!/usr/bin/env python3
"""Run the local Home KTV validation suites concurrently.

The default ``core`` profile selects the user-critical behavior across each
client and keeps a small PostgreSQL smoke set. ``extended`` runs the complete
existing suites and build/release checks. Legacy ``fast`` and ``full`` names
remain aliases for those profiles. ``--backend local`` explicitly removes
Testcontainers checks from either profile.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
from contextlib import ExitStack
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence
from xml.etree import ElementTree


DOCKER_BACKEND_TEST_CLASSES: tuple[str, ...] = (
    "AiPlaylistIntegrationTest",
    "AdminServiceIntegrationTest",
    "CollaborativeArtistIntegrationTest",
    "LargeExternalLibraryScanTest",
    "LargeLibraryMemoryTest",
    "LibraryScanIntegrationTest",
    "QueuePlaybackIntegrationTest",
    "SearchLargeLibraryPerformanceTest",
    "SearchIntegrationTest",
    "DiscoveryIntegrationTest",
    "PartyLoadIntegrationTest",
    "PersistenceIntegrationTest",
    "WebSocketIntegrationTest",
    "FlywaySpringBootUpgradeIntegrationTest",
    "FlywayUpgradeCompatibilityTest",
)

CORE_BACKEND_UNIT_TESTS: tuple[str, ...] = (
    "AdminAuthInterceptorTest",
    "ExternalReadOnlyLibraryTest",
    "ExternalLibraryPermissionScanTest",
    "ExternalReadOnlyWriteGuardsTest",
    "IncrementalLibraryScanTest",
    "LibraryModePolicyTest",
    "LibraryModeDefaultTest",
    "LibraryScanCoordinatorTest",
    "LibraryScanFileTypeTest",
    "LibraryScanIdentityPolicyTest",
    "LibraryScanLeaseTest",
    "LibraryScanMemoryLifecycleTest",
    "LibraryScanProgressTest",
    "LibraryScanSidecarTest",
    "LibraryScanStateStoreTest",
    "LibrarySnapshotPolicyTest",
    "MediaImportServiceTest",
    "SongAvailabilityPolicyTest",
    "AudioLayoutResolverTest",
    "AudioLayoutTest",
    "MediaProbeStageTest",
    "MediaTranscoderTest",
    "MediaProcessRunnerTest",
    "OutputPathReservationTest",
    "PlaybackVariantServiceTest",
    "PlaybackVariantLifecycleTest",
    "PlaybackVariantStreamControllerTest",
    "PlaybackServiceTest",
    "PlaybackResolutionServiceTest",
    "PlaybackPressureMonitorTest",
    "SnapshotServiceTest",
    "StreamControllerTest",
    "TranscodeAsyncLifecycleInterceptorTest",
    "LiveTranscodeCommandBuilderTest",
    "LiveTranscodeSessionTest",
    "SettingServiceSecurityTest",
    "SettingServiceTest",
    "AdminAuthServiceTest",
    "AdminScanControllerTest",
    "AdminScanControllerDeleteGuardTest",
    "AdminScanControllerVocalTrackGuardTest",
    "AudioLayoutSerializationTest",
    "QueueCapacityTest",
    "QueueServiceTest",
    "QueueServiceConcurrencyTest",
    "KtvWebSocketHandlerTest",
    "SharedProtocolFixtureTest",
)

CORE_BACKEND_TESTCONTAINER_TESTS: tuple[str, ...] = (
    "FlywaySpringBootUpgradeIntegrationTest#springBootContextStartsSuccessfullyOnLegacyDatabase",
    "FlywayUpgradeCompatibilityTest#failedPostgresMigrationRollsBackEveryStatementInThatMigration",
    "LibraryScanIntegrationTest#scanClassifiesAndIngests",
    "QueuePlaybackIntegrationTest",
    "WebSocketIntegrationTest",
)

CORE_H5_TEST_FILES: tuple[str, ...] = (
    "src/App.test.js",
    "src/api/client.test.js",
    "src/api/sharedProtocolFixture.test.js",
    "src/api/ws.test.js",
    "src/composables/useQueuedSongIds.test.js",
    "src/stores/player.test.js",
    "src/stores/user.test.js",
    "src/views/QueueView.test.js",
    "src/views/RemoteView.test.js",
    "src/views/latestRequest.test.js",
    "src/views/admin/audioLayout.test.js",
    "src/stores/adminAuth.test.js",
    "src/views/admin/AdminLoginView.test.js",
    "src/views/admin/libraryMode.test.js",
    "src/views/admin/SourceLibraryView.test.js",
    "src/views/admin/librarySource.test.js",
    "src/views/admin/scanProgress.test.js",
    "src/views/admin/progressPollGate.test.js",
    "src/views/admin/settingsState.test.js",
    "src/views/admin/AudioLayoutEditor.test.js",
)

CORE_ANDROID_TEST_CLASSES: tuple[str, ...] = (
    "com.homektv.tv.controller.ControllerStateReducerTest",
    "com.homektv.tv.controller.RegistrationStateTest",
    "com.homektv.tv.net.AppConfigCredentialScopeContractTest",
    "com.homektv.tv.net.AppConfigStateTest",
    "com.homektv.tv.net.AppConfigSessionScopeTest",
    "com.homektv.tv.net.AppConfigProtocolTest",
    "com.homektv.tv.net.CredentialMigrationTest",
    "com.homektv.tv.net.DiscoveryProtocolTest",
    "com.homektv.tv.net.InboundDispatchQueueTest",
    "com.homektv.tv.net.KtvHttpTransportTest",
    "com.homektv.tv.net.KtvSocketProtocolTest",
    "com.homektv.tv.net.KtvTransportTest",
    "com.homektv.tv.net.LibraryApiTest",
    "com.homektv.tv.net.PlaybackResolverTest",
    "com.homektv.tv.net.SharedProtocolFixtureTest",
    "com.homektv.tv.net.SyncChunkAssemblerTest",
    "com.homektv.tv.player.AudioTrackIndexResolverTest",
    "com.homektv.tv.player.AudioLayoutCapabilitiesTest",
    "com.homektv.tv.player.AudioPlaybackRouteTest",
    "com.homektv.tv.player.DualEnginePlaybackRouterTest",
    "com.homektv.tv.player.KtvChannelAudioProcessorTest",
    "com.homektv.tv.player.PlaybackCoordinatorBranchCoverageTest",
    "com.homektv.tv.player.PlaybackCoordinatorTest",
    "com.homektv.tv.player.PlaybackDecodeRecoveryPolicyTest",
    "com.homektv.tv.player.PlaybackEngineFallbackContractTest",
    "com.homektv.tv.net.PlaybackErrorContextTest",
    "com.homektv.tv.net.PlaybackErrorOutboxTest",
    "com.homektv.tv.player.PlaybackFallbackRecoveryPolicyTest",
    "com.homektv.tv.player.PlaybackIdentityGateTest",
    "com.homektv.tv.player.PlaybackLoadGateTest",
    "com.homektv.tv.player.PlaybackLoadProjectionTest",
    "com.homektv.tv.player.PlaybackRequestIdentityTest",
    "com.homektv.tv.player.PlaybackRetryGateTest",
    "com.homektv.tv.player.PlaybackRetryStateTest",
    "com.homektv.tv.player.PlaybackSeekGateTest",
    "com.homektv.tv.net.PlaybackSnapshotBridgeTest",
    "com.homektv.tv.player.VideoPlaybackWatchdogTest",
    "com.homektv.tv.controller.QueuePermissionPolicyTest",
    "com.homektv.tv.net.QueueSnapshotSerializationTest",
    "com.homektv.tv.net.SyncReadyGateTest",
)

CORE_WINDOWS_TEST_CLASSES: tuple[str, ...] = (
    "AudioLayoutMapperTests",
    "KtvWebSocketClientTests",
    "MpvJsonIpcSessionTests",
    "MpvControllerTests",
    "MpvIpcProtocolTests",
    "MpvProcessSessionFactoryTests",
    "MpvExecutableResolverTests",
    "PlaybackCoordinatorTests",
    "PlaybackFailurePolicyTests",
    "PendingPlaybackReportTests",
    "PlaybackProgressLoopTests",
    "PlaybackRecoveryPolicyTests",
    "PlaybackReportAckPolicyTests",
    "PlaybackSnapshotPumpTests",
    "PlaybackDescriptorReadinessTests",
    "PlaybackTerminalTests",
    "ReliablePlaybackOutboxTests",
    "SharedProtocolFixtureTests",
    "SyncChunkAssemblerTests",
    "ServerProtocolTests",
)

# Source-file allowlists for the maintenance branches that define the core
# safety, playback, and cross-platform protocol contract. Coverage attributed
# to these files includes async state-machine outcomes mapped back to source;
# unrelated and generated source files stay outside this denominator.
CORE_BRANCH_COVERAGE_FILES: dict[str, tuple[str, ...]] = {
    "backend": (
        "com/homektv/library/LibraryScanService.java",
        "com/homektv/library/LibraryIngestionWriter.java",
        "com/homektv/library/MediaImportService.java",
        "com/homektv/library/LibraryScanCoordinator.java",
        "com/homektv/library/AudioLayoutResolver.java",
        "com/homektv/library/MediaTranscoder.java",
        "com/homektv/web/StreamController.java",
        "com/homektv/playback/PlaybackVariantService.java",
        "com/homektv/queue/QueueService.java",
        "com/homektv/ws/KtvWebSocketHandler.java",
    ),
    "android": (
        "com/homektv/tv/controller/ControllerState.kt",
        "com/homektv/tv/player/PlaybackCoordinator.kt",
        "com/homektv/tv/player/PlaybackLoadProjection.kt",
        "com/homektv/tv/player/PlaybackDecodeRecoveryPolicy.kt",
        "com/homektv/tv/net/SyncChunkAssembler.kt",
        "com/homektv/tv/net/AppConfig.kt",
    ),
    "windows": (
        "ServerConnection/KtvWebSocketClient.cs",
        "ServerConnection/PendingPlaybackReport.cs",
        "ServerConnection/ReliablePlaybackOutbox.cs",
        "Protocol/SyncChunkAssembler.cs",
        "Playback/PlaybackCoordinator.cs",
        "Playback/PlaybackSnapshotPump.cs",
        "Mpv/MpvJsonIpcSession.cs",
    ),
}


def _coverage_report_path(module: str, coverage_dir: Path) -> Path | None:
    if module == "backend":
        return coverage_dir / "report" / "jacoco.xml"
    if module == "android":
        return coverage_dir / "build" / "reports" / "coverage" / "test" / "debug" / "report.xml"
    if module == "h5":
        return coverage_dir / "cobertura-coverage.xml"
    if module == "scripts":
        return coverage_dir / "coverage.xml"
    return None


def _normalized_source_path(value: str) -> str:
    normalized = value.replace("\\", "/").lstrip("./").lower()
    while "//" in normalized:
        normalized = normalized.replace("//", "/")
    return normalized


def _matches_source_allowlist(report_path: str, expected_path: str) -> bool:
    actual = _normalized_source_path(report_path)
    expected = _normalized_source_path(expected_path)
    return actual == expected or actual.endswith("/" + expected)


def _jacoco_source_counts(report: ElementTree.Element, expected_sources: Sequence[str]) -> tuple[dict[str, tuple[int, int]], list[str]]:
    counts: dict[str, tuple[int, int]] = {}
    for package in report.findall("package"):
        package_name = package.attrib.get("name", "")
        for source in package.findall("sourcefile"):
            source_path = f"{package_name}/{source.attrib.get('name', '')}"
            expected = next(
                (item for item in expected_sources if _matches_source_allowlist(source_path, item)),
                None,
            )
            if expected is None:
                continue

            method_covered = 0
            method_missed = 0
            has_method_branch_data = False
            source_filename = source.attrib.get("name", "")
            for class_node in package.findall("class"):
                if class_node.attrib.get("sourcefilename") != source_filename:
                    continue
                for method in class_node.findall("method"):
                    descriptor = method.attrib.get("desc", "")
                    if (
                        method.attrib.get("name") == "<init>"
                        and "Lkotlin/jvm/internal/DefaultConstructorMarker;" in descriptor
                    ):
                        # Kotlin emits mask-check branches for data-class default arguments
                        # into this synthetic constructor; callers cannot execute those
                        # branches as maintained application logic.
                        continue
                    for counter in method.findall("counter"):
                        if counter.attrib.get("type") != "BRANCH":
                            continue
                        try:
                            method_missed += int(counter.attrib["missed"])
                            method_covered += int(counter.attrib["covered"])
                        except (KeyError, ValueError):
                            continue
                        has_method_branch_data = True

            if has_method_branch_data:
                counts[expected] = (method_covered, method_missed)
                continue

            # Keep compatibility with minimal JaCoCo reports that omit method nodes.
            for counter in source.findall("counter"):
                if counter.attrib.get("type") != "BRANCH":
                    continue
                try:
                    missed = int(counter.attrib["missed"])
                    covered = int(counter.attrib["covered"])
                except (KeyError, ValueError):
                    continue
                counts[expected] = (covered, missed)
    missing = [source for source in expected_sources if source not in counts]
    return counts, missing


def _cobertura_source_counts(
    report: ElementTree.Element,
    expected_sources: Sequence[str],
) -> tuple[dict[str, tuple[int, int]], list[str], list[str]]:
    counts: dict[str, tuple[int, int]] = {}
    invalid_conditions: list[str] = []
    for class_node in report.iter("class"):
        source_path = class_node.attrib.get("filename", "")
        for expected in expected_sources:
            if not _matches_source_allowlist(source_path, expected):
                continue
            covered = 0
            missed = 0
            found_branch = False
            for line in class_node.findall("./lines/line"):
                if line.attrib.get("branch", "false").lower() != "true":
                    continue
                found_branch = True
                condition_coverage = line.attrib.get("condition-coverage", "")
                match = re.search(r"\((\d+)\s*/\s*(\d+)\)", condition_coverage)
                if match is None:
                    invalid_conditions.append(
                        f"{expected} line {line.attrib.get('number', '?')} has unreadable condition coverage"
                    )
                    continue
                line_covered, line_total = (int(part) for part in match.groups())
                covered += line_covered
                missed += line_total - line_covered
            if found_branch:
                prior_covered, prior_missed = counts.get(expected, (0, 0))
                counts[expected] = (prior_covered + covered, prior_missed + missed)
    missing = [source for source in expected_sources if source not in counts]
    return counts, missing, invalid_conditions


def validate_core_coverage(module: str, coverage_dir: Path) -> list[str]:
    """Fail closed when the current core run lacks complete allowlisted branch evidence."""

    if module in CORE_BRANCH_COVERAGE_FILES:
        report_path = _coverage_report_path(module, coverage_dir)
        if module == "windows":
            reports = tuple((coverage_dir / "TestResults").rglob("coverage.cobertura.xml"))
            if len(reports) != 1:
                return [f"Windows coverage report missing or ambiguous (found {len(reports)})"]
            report_path = reports[0]
        if report_path is None or not report_path.is_file():
            return [f"{module} coverage report missing: {report_path or coverage_dir}"]
        try:
            report = ElementTree.parse(report_path).getroot()
        except (ElementTree.ParseError, OSError) as error:
            return [f"{module} coverage report unreadable: {error}"]

        if module in {"backend", "android"}:
            counts, missing = _jacoco_source_counts(report, CORE_BRANCH_COVERAGE_FILES[module])
            invalid_conditions: list[str] = []
        else:
            counts, missing, invalid_conditions = _cobertura_source_counts(
                report,
                CORE_BRANCH_COVERAGE_FILES[module],
            )
        findings = [f"{module} core source missing branch data: {source}" for source in missing]
        findings.extend(
            f"{module} core source has invalid branch data: {detail}"
            for detail in invalid_conditions
        )
        for source, (covered, missed) in counts.items():
            if covered + missed == 0:
                findings.append(f"{module} core source has no branch outcomes: {source}")
            elif missed:
                findings.append(f"{module} core source branch coverage {covered}/{covered + missed}, missed={missed}: {source}")
        return findings

    if module not in {"h5", "scripts"}:
        return [f"no core coverage policy defined for module: {module}"]
    report_path = _coverage_report_path(module, coverage_dir)
    if report_path is None or not report_path.is_file():
        return [f"{module} coverage report missing: {report_path or coverage_dir}"]
    try:
        report = ElementTree.parse(report_path).getroot()
        covered = int(report.attrib["branches-covered"])
        total = int(report.attrib["branches-valid"])
    except (ElementTree.ParseError, KeyError, ValueError, OSError) as error:
        return [f"{module} coverage branch totals missing or unreadable: {error}"]
    if total <= 0:
        return [f"{module} coverage report has no branch outcomes"]
    if covered != total:
        return [f"{module} core branch coverage {covered}/{total}"]
    return []

PROFILE_ALIASES = {"fast": "core", "full": "extended"}


def _canonical_profile(profile: str) -> str:
    """Normalize the former profile names while retaining their CLI support."""

    return PROFILE_ALIASES.get(profile, profile)


@dataclass(frozen=True)
class CommandSpec:
    """One subprocess invocation belonging to a module task."""

    argv: tuple[str, ...]
    label: str


@dataclass(frozen=True)
class TaskSpec:
    name: str
    cwd: Path
    commands: tuple[CommandSpec, ...]
    scope: str
    coverage: bool = False
    android_emulator_serial: str | None = None
    profile: str = "extended"


@dataclass(frozen=True)
class TaskResult:
    name: str
    scope: str
    returncode: int
    elapsed_seconds: float
    log_path: Path


def default_parallel_jobs(cpu_count: int | None, task_count: int) -> int:
    """Choose a conservative module-level worker count.

    There are five independent modules.  Capping at five prevents the runner
    from creating more top-level processes than useful work, while retaining
    two workers on machines where Python cannot report a CPU count.
    """

    if task_count <= 0:
        return 0
    if cpu_count is None or cpu_count <= 0:
        return min(task_count, 2)
    if cpu_count == 1:
        return 1
    return min(task_count, max(2, min(5, cpu_count)))


def _windows() -> bool:
    return os.name == "nt"


def _executable(name: str) -> str:
    if _windows() and name in {"npm", "dotnet"}:
        # dotnet is an executable on Windows; npm is a cmd shim.
        return "npm.cmd" if name == "npm" else name
    return name


def _script_command(script: Path, *args: str) -> CommandSpec:
    return CommandSpec((sys.executable, str(script), *args), script.name)


def _coverage_command(command: CommandSpec, *, append: bool) -> CommandSpec:
    argv = [
        sys.executable,
        "-m",
        "coverage",
        "run",
        "--rcfile=scripts/.coveragerc",
        "--branch",
    ]
    if append:
        argv.append("--append")
    argv.extend(command.argv[1:])
    return CommandSpec(tuple(argv), command.label)


def _maven_command(backend: Path, args: Sequence[str]) -> CommandSpec:
    wrapper = backend / ("mvnw.cmd" if _windows() else "mvnw")
    return CommandSpec((str(wrapper), *args), "backend Maven tests")


def _backend_command(
    backend: Path,
    scope: str,
    parallelism: int | None,
    profile: str,
    coverage: bool = False,
) -> CommandSpec:
    profile = _canonical_profile(profile)
    args: list[str] = [
        "--batch-mode",
        "-DrunLargeLibraryScanTest=false",
        "-DrunLargeLibraryTest=false",
        "-DrunSearchPerformanceTest=false",
    ]
    if coverage:
        args.append("-Pcoverage")
    if parallelism is not None:
        if parallelism <= 0:
            raise ValueError("backend_parallelism must be positive")
        args.append(
            "-Djunit.jupiter.execution.parallel.config.fixed.parallelism="
            + str(parallelism)
        )
    if profile == "core":
        selectors = list(CORE_BACKEND_UNIT_TESTS)
        if scope == "all":
            selectors.extend(CORE_BACKEND_TESTCONTAINER_TESTS)
        args.append("-Dtest=" + ",".join(selectors))
    elif scope == "local":
        excluded = "!" + ",!".join(DOCKER_BACKEND_TEST_CLASSES)
        args.append(f"-Dtest={excluded}")
    args.append("test")
    return _maven_command(backend, args)


def _android_command(
    android: Path,
    no_daemon: bool,
    profile: str,
    coverage: bool = False,
    emulator_serial: str | None = None,
) -> CommandSpec:
    profile = _canonical_profile(profile)
    if emulator_serial is not None and not emulator_serial.startswith("emulator-"):
        raise ValueError("Android instrumentation is restricted to emulator-* serials")
    wrapper = android / ("gradlew.bat" if _windows() else "gradlew")
    args = [str(wrapper), ":app:testDebugUnitTest"]
    if profile == "core":
        for test_class in CORE_ANDROID_TEST_CLASSES:
            args.extend(("--tests", test_class))
    else:
        args.extend((":app:lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"))
    if coverage:
        args.append(":app:createDebugUnitTestCoverageReport")
    if emulator_serial is not None:
        args.append(":app:connectedDebugAndroidTest")
        if coverage:
            args.append(":app:createDebugAndroidTestCoverageReport")
    args.append("--console=plain")
    if no_daemon:
        args.insert(1, "--no-daemon")
    label = "Android core unit tests" if profile == "core" else "Android extended unit, lint and debug APK checks"
    return CommandSpec(tuple(args), label)


def build_task_specs(
    root: Path,
    *,
    profile: str = "core",
    backend_scope: str = "all",
    backend_parallelism: int | None = None,
    android_no_daemon: bool = False,
    coverage: bool = False,
    android_emulator_serial: str | None = None,
) -> tuple[TaskSpec, ...]:
    """Build the explicit module plan without starting any processes."""

    profile = _canonical_profile(profile)
    if profile not in {"core", "extended"}:
        raise ValueError("profile must be 'core' or 'extended' (aliases: 'fast' and 'full')")
    if backend_scope not in {"all", "local"}:
        raise ValueError("backend_scope must be 'all' or 'local'")

    root = root.resolve()
    backend = root / "backend"
    h5 = root / "h5"
    android = root / "android-tv"
    windows_test_project = (
        root / "windows-player" / "tests" / "HomeKtv.Windows.Tests" / "HomeKtv.Windows.Tests.csproj"
    )
    scripts = root / "scripts"

    h5_executable = _executable("npm")
    h5_test_args: list[str] = [h5_executable, "test"]
    if coverage or profile == "core":
        h5_test_args.append("--")
        if coverage:
            h5_test_args.append("--coverage")
        if profile == "core":
            h5_test_args.extend(CORE_H5_TEST_FILES)
    h5_test = tuple(h5_test_args)
    h5_commands = [CommandSpec(h5_test, "H5 tests and coverage" if coverage else "H5 tests")]
    if profile == "extended":
        h5_commands.append(
            CommandSpec((h5_executable, "run", "build"), "H5 production build")
        )

    script_commands = [
        _script_command(scripts / "repo_portability_test.py"),
    ]
    if profile == "core":
        script_commands.extend(
            (
                _script_command(scripts / "ci_workflow_test.py"),
                _script_command(scripts / "compose_config_test.py"),
            )
        )
    else:
        script_commands.extend(
            (
                _script_command(scripts / "ci_workflow_test.py"),
                _script_command(scripts / "compose_config_test.py"),
                _script_command(scripts / "release_manifest_test.py"),
                _script_command(scripts / "release_workflow_test.py"),
                _script_command(scripts / "merge_gate_contract_test.py"),
            )
        )
    script_commands.append(
        CommandSpec(
            (sys.executable, "-m", "unittest", "discover", "scripts/tests"),
            "Python unit tests",
        )
    )
    if coverage:
        script_commands = [
            _coverage_command(command, append=index > 0)
            for index, command in enumerate(script_commands)
        ]

    backend_command = _backend_command(backend, backend_scope, backend_parallelism, profile, coverage)
    android_command = _android_command(
        android,
        android_no_daemon,
        profile,
        coverage,
        android_emulator_serial,
    )
    windows_args = [
        _executable("dotnet"),
        "test",
        str(windows_test_project),
        "--nologo",
    ]
    if profile == "core":
        test_filter = "|".join(
            f"FullyQualifiedName~{test_class}" for test_class in CORE_WINDOWS_TEST_CLASSES
        )
        windows_args.extend(("--filter", test_filter))
    if coverage:
        windows_args.append("--collect:XPlat Code Coverage")
    windows_command = CommandSpec(
        tuple(windows_args),
        "Windows core tests and coverage" if coverage and profile == "core" else (
            "Windows Player tests and coverage" if coverage else "Windows core tests" if profile == "core" else "Windows Player tests"
        ),
    )
    return (
        TaskSpec(
            "backend",
            backend,
            (backend_command,),
            f"{backend_scope} backend tests",
            coverage,
            profile=profile,
        ),
        TaskSpec(
            "h5",
            h5,
            tuple(h5_commands),
            "H5 core tests" if profile == "core" else "H5 tests and build",
            coverage,
            profile=profile,
        ),
        TaskSpec(
            "android",
            android,
            (android_command,),
            "Android core unit tests" if profile == "core" else "Android debug checks",
            coverage,
            android_emulator_serial,
            profile,
        ),
        TaskSpec(
            "windows",
            root,
            (windows_command,),
            "Windows Player tests",
            coverage,
            profile=profile,
        ),
        TaskSpec(
            "scripts",
            root,
            tuple(script_commands),
            "core script checks" if profile == "core" else "portability and release contract tests",
            coverage,
            profile=profile,
        ),
    )


def _process_argv(argv: Sequence[str]) -> list[str]:
    """Invoke Windows cmd/bat wrappers without relying on shell quoting."""

    if not _windows() or not argv:
        return list(argv)
    suffix = Path(argv[0]).suffix.lower()
    if suffix not in {".cmd", ".bat"}:
        return list(argv)
    return [
        os.environ.get("ComSpec", "cmd.exe"),
        "/d",
        "/c",
        subprocess.list2cmdline(list(argv)),
    ]


def _safe_log_name(name: str) -> str:
    return "".join(character if character.isalnum() else "_" for character in name)


def _task_environment(root: Path) -> dict[str, str]:
    env = os.environ.copy()
    task_temp = root / "backend" / "target" / "full-test-temp"
    task_temp.mkdir(parents=True, exist_ok=True)
    for variable in ("TEMP", "TMP", "TMPDIR"):
        env[variable] = str(task_temp)
    # Keep local Maven/Gradle caches inside the workspace when they already
    # exist.  This avoids C:\.m2/C:\.android permission surprises while not
    # forcing CI to ignore its normal dependency cache.
    local_maven = root / ".maven-local"
    if local_maven.is_dir():
        env.setdefault("KTV_MAVEN_REPO", str(local_maven))
    local_gradle = root / ".gradle-local"
    if local_gradle.is_dir():
        env.setdefault("GRADLE_USER_HOME", str(local_gradle))
    return env


def _mockito_agent(repo: Path) -> Path | None:
    """Find the newest cached Mockito jar for startup agent loading on Windows."""

    candidates = tuple((repo / "org" / "mockito" / "mockito-core").glob("*/mockito-core-*.jar"))
    if not candidates:
        return None

    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(
            int("".join(character for character in part if character.isdigit()) or "0")
            for part in path.parent.name.split(".")
        )

    return max(candidates, key=version_key)


def _run_task(
    task: TaskSpec,
    log_dir: Path,
    root: Path,
    coverage_dir: Path | None = None,
) -> TaskResult:
    log_path = log_dir / f"{_safe_log_name(task.name)}.log"
    started = time.monotonic()
    environment = _task_environment(root)
    if task.name == "h5":
        environment["KTV_TEST_PROFILE"] = task.profile
    if task.name == "backend":
        maven_repo = Path(environment.get("KTV_MAVEN_REPO", Path.home() / ".m2" / "repository"))
        mockito_agent = _mockito_agent(maven_repo)
        if mockito_agent is not None:
            java_tool_options = environment.get("JAVA_TOOL_OPTIONS", "").strip()
            if str(mockito_agent) not in java_tool_options:
                environment["JAVA_TOOL_OPTIONS"] = " ".join(
                    part for part in (java_tool_options, f'-javaagent:"{mockito_agent}"') if part
                )
    if task.android_emulator_serial is not None:
        if not task.android_emulator_serial.startswith("emulator-"):
            raise ValueError("Android instrumentation is restricted to emulator-* serials")
        environment["ANDROID_SERIAL"] = task.android_emulator_serial
    module_coverage_dir = None
    if task.coverage and coverage_dir is not None:
        module_coverage_dir = coverage_dir / task.name
        module_coverage_dir.mkdir(parents=True, exist_ok=True)
        environment["KTV_COVERAGE_DIR"] = str(module_coverage_dir)
        if task.name == "scripts":
            environment["COVERAGE_FILE"] = str(module_coverage_dir / ".coverage")

    with ExitStack() as temporary_outputs:
        h5_build_output = None
        if task.name == "h5" and any(command.label == "H5 production build" for command in task.commands):
            build_parent = root / "backend" / "target" / "full-test-builds"
            build_parent.mkdir(parents=True, exist_ok=True)
            h5_build_output = temporary_outputs.enter_context(
                tempfile.TemporaryDirectory(prefix="h5-build-", dir=build_parent)
            )

        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            log.write(f"task={task.name}\nscope={task.scope}\ncwd={task.cwd}\n\n")
            returncode = 0
            for command in task.commands:
                log.write(f"===== {command.label} =====\n")
                log.write("$ " + " ".join(command.argv) + "\n")
                log.flush()
                argv = list(command.argv)
                if task.name == "h5" and command.label == "H5 production build":
                    argv.extend(("--", "--outDir", str(h5_build_output), "--emptyOutDir"))
                if task.name == "android" and module_coverage_dir is not None:
                    argv.insert(1, f"-Pktv.coverage.buildDir={module_coverage_dir / 'build'}")
                if task.name == "windows" and module_coverage_dir is not None:
                    results_dir = module_coverage_dir / "TestResults"
                    results_dir.mkdir(parents=True, exist_ok=True)
                    argv.extend(("--results-directory", str(results_dir)))
                if task.name == "backend" and module_coverage_dir is not None:
                    argv[1:1] = [
                        f"-Djacoco.exec.path={module_coverage_dir / 'jacoco.exec'}",
                        f"-Djacoco.outputDirectory={module_coverage_dir / 'report'}",
                    ]
                # Maven accepts a repository override without changing the test
                # process's user.home.  The override is only used with an existing cache.
                if task.name == "backend" and environment.get("KTV_MAVEN_REPO"):
                    argv.insert(1, f"-Dmaven.repo.local={environment['KTV_MAVEN_REPO']}")
                process = subprocess.Popen(
                    _process_argv(argv),
                    cwd=task.cwd,
                    env=environment,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                )
                returncode = process.wait()
                log.write(f"exit={returncode}\n\n")
                log.flush()
                if returncode != 0:
                    break

            if task.name == "scripts" and module_coverage_dir is not None and returncode == 0:
                for report_args in (
                    ("report", "--rcfile=scripts/.coveragerc", "--show-missing"),
                    ("xml", "--rcfile=scripts/.coveragerc", "-o", str(module_coverage_dir / "coverage.xml")),
                ):
                    argv = [sys.executable, "-m", "coverage", *report_args]
                    log.write("===== Python coverage report =====\n$ " + " ".join(argv) + "\n")
                    log.flush()
                    process = subprocess.Popen(
                        argv,
                        cwd=root,
                        env=environment,
                        stdout=log,
                        stderr=subprocess.STDOUT,
                    )
                    returncode = process.wait()
                    log.write(f"exit={returncode}\n\n")
                    log.flush()
                    if returncode != 0:
                        break
            if task.profile == "core" and task.coverage and module_coverage_dir is not None and returncode == 0:
                findings = validate_core_coverage(task.name, module_coverage_dir)
                log.write("===== Core branch coverage gate =====\n")
                if findings:
                    for finding in findings:
                        log.write(f"FAIL: {finding}\n")
                    returncode = 1
                else:
                    log.write("PASS: current-run core branch coverage evidence is complete\n")
                log.flush()
    return TaskResult(
        task.name,
        task.scope,
        returncode,
        time.monotonic() - started,
        log_path,
    )


def run_tasks(
    tasks: Iterable[TaskSpec],
    *,
    root: Path,
    log_dir: Path,
    jobs: int,
    coverage_dir: Path | None = None,
) -> tuple[TaskResult, ...]:
    task_list = tuple(tasks)
    log_dir.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=max(1, jobs)) as executor:
        futures: dict[Future[TaskResult], TaskSpec] = {
            executor.submit(_run_task, task, log_dir, root, coverage_dir): task for task in task_list
        }
        results: list[TaskResult] = []
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            status = "PASS" if result.returncode == 0 else "FAIL"
            print(
                f"[{status}] {result.name}: {result.elapsed_seconds:.2f}s "
                f"log={result.log_path}"
            )
    return tuple(sorted(results, key=lambda result: result.name))


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profile",
        choices=("core", "extended", "fast", "full"),
        default="core",
        help="core selects critical tests; extended runs all tests/build checks; fast/full are compatibility aliases",
    )
    parser.add_argument(
        "--backend",
        choices=("all", "local"),
        default=None,
        help="backend scope; local explicitly excludes only Docker/Testcontainers classes",
    )
    parser.add_argument(
        "--jobs",
        type=int,
        default=0,
        help="module processes to run concurrently (default: up to five)",
    )
    parser.add_argument(
        "--backend-parallelism",
        type=int,
        default=0,
        help="optional JUnit class parallelism override (for example 8); default keeps project setting",
    )
    parser.add_argument(
        "--sequential",
        action="store_true",
        help="run module tasks one at a time for constrained machines",
    )
    parser.add_argument(
        "--no-daemon",
        action="store_true",
        help="disable the Android Gradle daemon; useful for CI-style isolation",
    )
    parser.add_argument(
        "--coverage",
        action="store_true",
        help="collect per-module branch coverage reports for the selected profile",
    )
    parser.add_argument(
        "--android-emulator-serial",
        help="run Android instrumentation only on this emulator-* serial (never a physical device)",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument(
        "--log-dir",
        type=Path,
        help="directory for per-module logs (default: a temporary directory)",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    root = args.root.resolve()
    profile = _canonical_profile(args.profile)
    backend_scope = args.backend or ("local" if args.profile == "fast" else "all")
    if args.backend_parallelism < 0:
        print("--backend-parallelism must be non-negative", file=sys.stderr)
        return 2
    tasks = build_task_specs(
        root,
        profile=profile,
        backend_scope=backend_scope,
        backend_parallelism=args.backend_parallelism or None,
        android_no_daemon=args.no_daemon,
        coverage=args.coverage,
        android_emulator_serial=args.android_emulator_serial,
    )
    if args.jobs < 0:
        print("--jobs must be non-negative", file=sys.stderr)
        return 2
    jobs = 1 if args.sequential else (args.jobs or default_parallel_jobs(os.cpu_count(), len(tasks)))
    jobs = max(1, min(jobs, len(tasks)))
    if args.log_dir is None:
        run_id = datetime.now(timezone.utc).strftime("ktv-full-tests-%Y%m%dT%H%M%SZ-")
        log_parent = root / "backend" / "target" / "full-test-runs"
        log_parent.mkdir(parents=True, exist_ok=True)
        log_dir = Path(tempfile.mkdtemp(prefix=run_id, dir=log_parent))
    else:
        log_dir = args.log_dir.resolve()
        log_dir.mkdir(parents=True, exist_ok=True)

    coverage_dir = None
    if args.coverage:
        coverage_dir = Path(tempfile.mkdtemp(prefix="coverage-", dir=log_dir))

    print(
        f"Running {len(tasks)} module tasks with jobs={jobs}; "
        f"profile={profile}; backend_scope={backend_scope}; logs={log_dir}; "
        f"coverage={'enabled' if args.coverage else 'disabled'}"
    )
    if backend_scope == "local":
        print(
            "Dockerless backend gate excludes: "
            + ", ".join(DOCKER_BACKEND_TEST_CLASSES)
        )
    if profile == "core":
        print("Core profile runs selected critical tests; large-library, performance and release checks are optional extended work.")
    started = time.monotonic()
    results = run_tasks(tasks, root=root, log_dir=log_dir, jobs=jobs, coverage_dir=coverage_dir)
    total = time.monotonic() - started
    failed = [result for result in results if result.returncode != 0]
    print(f"TOTAL elapsed={total:.2f}s passed={len(results) - len(failed)} failed={len(failed)}")
    if failed:
        print("Failed task logs:")
        for result in failed:
            print(f"- {result.name}: {result.log_path}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
