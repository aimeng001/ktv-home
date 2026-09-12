#!/usr/bin/env python3
"""Run the local Home KTV validation suites concurrently.

The runner keeps each module's test process isolated, writes one log per
module, and reports the exact scope that was executed.  The default backend
scope is ``all`` and the default profile is ``full``.  ``--profile fast`` is
an explicit feedback gate: it keeps unit/cheap checks and leaves build,
integration, large-library, resource-budget and release checks to the full
profile.  ``--backend local`` is an explicit Dockerless gate and excludes
only the Testcontainers classes listed below.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import time
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence


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
)


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


def _maven_command(backend: Path, args: Sequence[str]) -> CommandSpec:
    wrapper = backend / ("mvnw.cmd" if _windows() else "mvnw")
    return CommandSpec((str(wrapper), *args), "backend Maven tests")


def _backend_command(
    backend: Path,
    scope: str,
    parallelism: int | None,
    profile: str,
) -> CommandSpec:
    args: list[str] = ["--batch-mode"]
    if parallelism is not None:
        if parallelism <= 0:
            raise ValueError("backend_parallelism must be positive")
        args.append(
            "-Djunit.jupiter.execution.parallel.config.fixed.parallelism="
            + str(parallelism)
        )
    if scope == "local":
        excluded = "!" + ",!".join(DOCKER_BACKEND_TEST_CLASSES)
        args.append(f"-Dtest={excluded}")
    if profile == "fast":
        args.append("-DexcludedGroups=extended")
    args.append("test")
    return _maven_command(backend, args)


def _android_command(
    android: Path,
    no_daemon: bool,
    profile: str,
) -> CommandSpec:
    wrapper = android / ("gradlew.bat" if _windows() else "gradlew")
    args = [str(wrapper), ":app:testDebugUnitTest"]
    if profile == "full":
        args.extend((":app:lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"))
    args.append("--console=plain")
    if no_daemon:
        args.insert(1, "--no-daemon")
    label = "Android unit tests" if profile == "fast" else "Android unit, lint and debug APK checks"
    return CommandSpec(tuple(args), label)


def build_task_specs(
    root: Path,
    *,
    profile: str = "full",
    backend_scope: str = "all",
    backend_parallelism: int | None = None,
    android_no_daemon: bool = False,
) -> tuple[TaskSpec, ...]:
    """Build the explicit module plan without starting any processes."""

    if profile not in {"fast", "full"}:
        raise ValueError("profile must be 'fast' or 'full'")
    if backend_scope not in {"all", "local"}:
        raise ValueError("backend_scope must be 'all' or 'local'")

    root = root.resolve()
    backend = root / "backend"
    h5 = root / "h5"
    android = root / "android-tv"
    windows_solution = root / "windows-player" / "HomeKtv.Windows.sln"
    scripts = root / "scripts"

    h5_executable = _executable("npm")
    h5_commands = [CommandSpec((h5_executable, "test"), "H5 tests")]
    if profile == "full":
        h5_commands.append(
            CommandSpec((h5_executable, "run", "build"), "H5 production build")
        )

    script_commands = [
        _script_command(scripts / "repo_portability_test.py"),
    ]
    if profile == "full":
        script_commands.extend(
            (
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

    return (
        TaskSpec(
            "backend",
            backend,
            (_backend_command(backend, backend_scope, backend_parallelism, profile),),
            f"{backend_scope} backend tests",
        ),
        TaskSpec(
            "h5",
            h5,
            tuple(h5_commands),
            "H5 tests" if profile == "fast" else "H5 tests and build",
        ),
        TaskSpec(
            "android",
            android,
            (_android_command(android, android_no_daemon, profile),),
            "Android unit tests" if profile == "fast" else "Android debug checks",
        ),
        TaskSpec(
            "windows",
            root,
            (
                CommandSpec(
                    (
                        _executable("dotnet"),
                        "test",
                        str(windows_solution),
                        "--no-restore",
                        "--nologo",
                    ),
                    "Windows Player tests",
                ),
            ),
            "Windows Player tests",
        ),
        TaskSpec(
            "scripts",
            root,
            tuple(script_commands),
            "fast script checks" if profile == "fast" else "portability and contract tests",
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


def _run_task(task: TaskSpec, log_dir: Path, root: Path) -> TaskResult:
    log_path = log_dir / f"{_safe_log_name(task.name)}.log"
    started = time.monotonic()
    environment = _task_environment(root)
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        log.write(f"task={task.name}\nscope={task.scope}\ncwd={task.cwd}\n\n")
        returncode = 0
        for command in task.commands:
            log.write(f"===== {command.label} =====\n")
            log.write("$ " + " ".join(command.argv) + "\n")
            log.flush()
            argv = list(command.argv)
            # Maven accepts a repository override without changing the test
            # process's user.home.  The wrapper remains the source of truth on
            # clean machines; the override is only used with an existing local
            # cache.
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
) -> tuple[TaskResult, ...]:
    task_list = tuple(tasks)
    log_dir.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=max(1, jobs)) as executor:
        futures: dict[Future[TaskResult], TaskSpec] = {
            executor.submit(_run_task, task, log_dir, root): task for task in task_list
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
        choices=("fast", "full"),
        default="full",
        help="test profile; fast keeps feedback checks, full includes builds and extended gates",
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
    backend_scope = args.backend or ("local" if args.profile == "fast" else "all")
    if args.backend_parallelism < 0:
        print("--backend-parallelism must be non-negative", file=sys.stderr)
        return 2
    tasks = build_task_specs(
        root,
        profile=args.profile,
        backend_scope=backend_scope,
        backend_parallelism=args.backend_parallelism or None,
        android_no_daemon=args.no_daemon,
    )
    if args.jobs < 0:
        print("--jobs must be non-negative", file=sys.stderr)
        return 2
    jobs = 1 if args.sequential else (args.jobs or default_parallel_jobs(os.cpu_count(), len(tasks)))
    jobs = max(1, min(jobs, len(tasks)))
    if args.log_dir is None:
        run_id = datetime.now(timezone.utc).strftime("ktv-full-tests-%Y%m%dT%H%M%SZ-")
        log_dir = Path(tempfile.mkdtemp(prefix=run_id))
    else:
        log_dir = args.log_dir.resolve()

    print(
        f"Running {len(tasks)} module tasks with jobs={jobs}; "
        f"profile={args.profile}; backend_scope={backend_scope}; logs={log_dir}"
    )
    if backend_scope == "local":
        print(
            "Dockerless backend gate excludes: "
            + ", ".join(DOCKER_BACKEND_TEST_CLASSES)
        )
    if args.profile == "fast":
        print(
            "Fast profile excludes: H5 production build, Android lint/APK tasks, "
            "release scripts, and JUnit @Tag(extended)"
        )
    started = time.monotonic()
    results = run_tasks(tasks, root=root, log_dir=log_dir, jobs=jobs)
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
