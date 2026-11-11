#!/usr/bin/env python3
"""Headless baseline/probe harness for the approved real-MV UI fixture.

This initial harness deliberately exposes only D-pad events, captures, and
hierarchy inspection. It reuses the repository's isolated AVD lifecycle code.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import shutil
import stat
import sys
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAX_EMULATOR_LOG_BYTES = 5 * 1024 * 1024
CAPTURE_SOURCE = ROOT / "参考" / "capture_tab_variants.py"
spec = importlib.util.spec_from_file_location("capture_tab_variants", CAPTURE_SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Cannot import capture helper: {CAPTURE_SOURCE}")
capture_helper = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = capture_helper
spec.loader.exec_module(capture_helper)


def preferences_xml(server_host: str) -> str:
    # AppConfig reads server_host directly. All credential/mode state is created
    # inside this run's blank AVD userdata by the debug app itself.
    return (
        '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
        '<map><string name="server_host">'
        + server_host
        + "</string></map>"
    )


def install_and_start(session, apk: Path, stage: Path, server_host: str) -> str:
    install = session.run(["install", "-r", str(apk)], timeout=max(180, session.default_timeout))
    if "success" not in install.lower():
        raise RuntimeError(f"APK installation did not report Success: {install}")
    local_config = stage / "ktv_tv_fixture_config.xml"
    remote_config = "/data/local/tmp/ktv-home-fixture-config.xml"
    local_config.write_text(preferences_xml(server_host), encoding="utf-8")
    try:
        session.run(["push", str(local_config), remote_config], timeout=30)
        session.run(["shell", "chmod", "644", remote_config])
        session.run(["shell", "run-as", capture_helper.PACKAGE_NAME, "mkdir", "-p", "shared_prefs"])
        session.run(["shell", "run-as", capture_helper.PACKAGE_NAME, "cp", remote_config, "shared_prefs/ktv_tv.xml"])
        prefs = session.run(["shell", "run-as", capture_helper.PACKAGE_NAME, "cat", "shared_prefs/ktv_tv.xml"])
        if server_host not in prefs or 'name="server_host"' not in prefs:
            raise RuntimeError("Fixture host preference was not retained in the isolated debug app")
    finally:
        session.run(["shell", "rm", "-f", remote_config])
        if local_config.exists():
            local_config.unlink()

    started = session.run([
        "shell", "am", "start", "-W", "-n", f"{capture_helper.PACKAGE_NAME}/{capture_helper.ACTIVITY_NAME}",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LEANBACK_LAUNCHER",
    ], timeout=max(90, session.default_timeout))
    if "Status: ok" not in started:
        raise RuntimeError(f"MainActivity did not launch: {started}")
    return started


def start_emulator_bounded(
    tools, *, run_id: str, stage: Path, runtime_root: Path, reuse_runtime: bool = False
):
    """Reuse the project AVD helpers while bounding emulator log growth."""
    current = capture_helper.parse_adb_devices(
        capture_helper.run_checked([str(tools.adb), "devices", "-l"], timeout=20)
    )
    port = capture_helper.select_emulator_port(current.keys())
    serial = f"emulator-{port}"
    adb_port = capture_helper.select_adb_server_port()
    avd_home = runtime_root / "android-user" / "avd"
    avd_name = f"KT_{run_id}"
    if reuse_runtime:
        owner_file = runtime_root / ".ktv-home-ui-audit-owner"
        avd_ini = avd_home / f"{avd_name}.ini"
        avd_config = avd_home / f"{avd_name}.avd" / "config.ini"
        if not (
            owner_file.is_file()
            and owner_file.read_text(encoding="utf-8") == run_id
            and avd_ini.is_file()
            and avd_config.is_file()
        ):
            raise RuntimeError("refusing runtime reuse without matching owner marker and isolated AVD files")
    else:
        capture_helper.create_isolated_avd(
            sdk_root=tools.sdk,
            template_name=tools.avd_template,
            avd_home=avd_home,
            avd_name=avd_name,
            runtime_root=runtime_root,
            run_id=run_id,
        )
    env = os.environ.copy()
    isolated_user_home = str(runtime_root / "android-user")
    env["ANDROID_SDK_ROOT"] = str(tools.sdk)
    env["ANDROID_HOME"] = str(tools.sdk)
    env["ANDROID_USER_HOME"] = isolated_user_home
    env["ANDROID_EMULATOR_HOME"] = isolated_user_home
    env["ANDROID_AVD_HOME"] = str(avd_home)
    capture_helper.configure_isolated_adb_environment(env, adb_port)
    capture_helper.configure_cpu_emulation_environment(env, tools.software_cpu_emulation)
    emulator_args = capture_helper.build_emulator_args(
        tools.emulator, avd_name, port, tools.software_cpu_emulation
    )
    if "-no-window" not in emulator_args or "-no-audio" not in emulator_args:
        raise AssertionError("headless/no-audio launch flags are mandatory")
    capture_helper.ensure_no_existing_emulator(tools.adb)
    latest = capture_helper.parse_adb_devices(
        capture_helper.run_checked([str(tools.adb), "devices", "-l"], timeout=20)
    )
    if serial in latest:
        raise RuntimeError(f"selected emulator serial became occupied: {serial}")

    capture_helper.start_isolated_adb_server(tools.adb, serial, env)
    log_path = stage / "emulator.log"
    log_stream = log_path.open("wb")
    try:
        process = subprocess.Popen(
            emulator_args,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=log_stream,
            stderr=subprocess.STDOUT,
        )
    finally:
        log_stream.close()
    log_state = {"bytes": 0, "truncated": False, "limitBytes": MAX_EMULATOR_LOG_BYTES}
    stop_watcher = threading.Event()

    def watch_log_size() -> None:
        while not stop_watcher.wait(0.025):
            try:
                size = log_path.stat().st_size
            except FileNotFoundError:
                continue
            log_state["bytes"] = size
            if size > MAX_EMULATOR_LOG_BYTES:
                log_state["truncated"] = True
                if process.poll() is None:
                    subprocess.run(
                        ["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                        capture_output=True,
                        timeout=10,
                        check=False,
                    )
                return
            if process.poll() is not None:
                return

    watcher = threading.Thread(target=watch_log_size, name=f"{run_id}-emulator-log-limit", daemon=True)
    watcher.start()
    return process, port, env, watcher, stop_watcher, log_state


def summarize_nodes(session) -> list[dict[str, str]]:
    nodes = session.hierarchy()
    return [
        {
            key: node.get(key, "")
            for key in ("resource-id", "text", "content-desc", "bounds", "focused", "selected", "clickable", "focusable", "enabled")
        }
        for node in nodes
        if node.get("focused") == "true"
        or node.get("clickable") == "true"
        or node.get("selected") == "true"
    ]


def focused_state(session) -> dict[str, object]:
    nodes = session.hierarchy()
    focused = [
        {
            key: node.get(key, "")
            for key in ("resource-id", "text", "content-desc", "bounds", "focused", "selected")
        }
        for node in nodes
        if node.get("focused") == "true"
    ]
    selected_tabs = [
        {"resource-id": node.get("resource-id", ""), "text": node.get("text", "")}
        for node in nodes
        if node.get("selected") == "true" and "btnTab" in node.get("resource-id", "")
    ]
    screen_text = list(dict.fromkeys(
        node.get("text", "").strip()
        for node in nodes
        if node.get("text", "").strip()
    ))[:24]
    return {"focused": focused, "selectedTabs": selected_tabs, "visibleText": screen_text}


def save_capture(session, stage: Path, name: str) -> dict[str, object]:
    record = capture_helper.capture(
        session,
        stage,
        f"{name}.png",
        route_label="interactive real-MV fixture probe",
        reference=None,
        route_assertion=None,
        input_action="interactive D-pad harness",
    )
    record["backendMode"] = "local-read-only-fixture"
    return record


def remove_owned_runtime(runtime_root: Path, runtime_parent: Path, run_id: str) -> str:
    """Remove only this harness's isolated AVD, including read-only emulator snapshots."""
    resolved_parent = runtime_parent.resolve()
    resolved_runtime = runtime_root.resolve()
    owner_file = resolved_runtime / ".ktv-home-ui-audit-owner"
    if (
        resolved_runtime.parent != resolved_parent
        or not owner_file.is_file()
        or owner_file.read_text(encoding="utf-8") != run_id
    ):
        raise RuntimeError("refusing to remove runtime without matching parent and ownership marker")

    def make_writable_and_retry(function, path, exc_info):
        os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
        function(path)

    shutil.rmtree(resolved_runtime, onerror=make_writable_and_retry)
    return "removed-after-owned-process-exit"


def interactive_loop(session, stage: Path) -> None:
    keycodes = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "OK": 23, "ENTER": 66, "MENU": 82, "BACK": 4}
    print("INTERACTIVE_READY: commands are KEY <UP|DOWN|LEFT|RIGHT|OK|MENU|BACK>, DUMP, CAPTURE <name>, LOGCAT, QUIT", flush=True)
    for line in sys.stdin:
        parts = line.strip().split(maxsplit=1)
        if not parts:
            continue
        command = parts[0].upper()
        try:
            if command == "KEY" and len(parts) == 2:
                token = parts[1].upper()
                code = int(token) if token.isdigit() else keycodes[token]
                session.keyevent(code)
                time.sleep(0.4 if not session.slow_emulation else 1.5)
                print(json.dumps({"key": token, "state": focused_state(session)}, ensure_ascii=False), flush=True)
            elif command == "DUMP":
                print(json.dumps(focused_state(session), ensure_ascii=False), flush=True)
            elif command == "CAPTURE" and len(parts) == 2:
                name = parts[1]
                if not name.replace("-", "").replace("_", "").isalnum():
                    raise ValueError("capture name must contain only letters, digits, '-' or '_'")
                record = save_capture(session, stage, name)
                print(json.dumps({
                    "capture": record.get("actualFile"),
                    "width": record.get("width"),
                    "height": record.get("height"),
                    "focusedResourceId": record.get("focusedResourceId"),
                    "hierarchyFile": record.get("hierarchyFile"),
                }, ensure_ascii=False), flush=True)
            elif command == "LOGCAT":
                output = session.run(["logcat", "-d", "-v", "time"], timeout=max(30, session.default_timeout))
                path = stage / "logcat-interactive.txt"
                path.write_text(output, encoding="utf-8")
                print(json.dumps({"logcat": path.name, "bytes": path.stat().st_size}, ensure_ascii=False), flush=True)
            elif command == "QUIT":
                break
            else:
                print(json.dumps({"error": f"unsupported command: {line.strip()}"}), flush=True)
        except Exception as exc:
            print(json.dumps({"command": line.strip(), "error": str(exc)}, ensure_ascii=False), flush=True)


def run(args: argparse.Namespace) -> int:
    stage = Path(args.output_dir).resolve()
    stage.mkdir(parents=True, exist_ok=False)
    apk = Path(args.apk).resolve(strict=True)
    runtime_root = (Path(args.runtime_parent).resolve() / args.run_id)
    if runtime_root.exists() and not args.reuse_runtime:
        raise FileExistsError(f"isolated runtime already exists: {runtime_root}")
    if args.reuse_runtime and not runtime_root.is_dir():
        raise FileNotFoundError(f"requested reusable runtime does not exist: {runtime_root}")
    runtime_root.parent.mkdir(parents=True, exist_ok=True)
    args.output_root = args.output_dir
    tools = capture_helper.build_tools(args)
    capture_helper.ensure_no_existing_emulator(tools.adb)
    process = None
    log_watcher = None
    stop_log_watcher = None
    log_state = {"bytesRead": 0, "truncated": False}
    serial = None
    env = None
    session = None
    primary_error: str | None = None
    cleanup_error: str | None = None
    manifest: dict[str, object] = {
        "runId": args.run_id,
        "startedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apk": str(apk),
        "fixtureHost": f"127.0.0.1:{args.fixture_port}",
        "mediaHostInAvd": f"10.0.2.2:{args.fixture_port}",
        "headless": True,
        "noAudio": True,
        "stages": [],
    }
    try:
        manifest["apkMetadata"] = capture_helper.apk_metadata(tools.sdk, tools.apk)
        process, port, env, log_watcher, stop_log_watcher, log_state = start_emulator_bounded(
            tools, run_id=args.run_id, stage=stage, runtime_root=runtime_root,
            reuse_runtime=args.reuse_runtime,
        )
        serial = f"emulator-{port}"
        manifest["serial"] = serial
        manifest["isolatedAdbServerPort"] = int(env["ANDROID_ADB_SERVER_PORT"])
        capture_helper.wait_for_device(tools.adb, serial, process, env)
        session = capture_helper.UiSession(tools.adb, serial, env)
        capture_helper.verify_display(session)
        capture_helper.stabilize_isolated_ui_automation(session)
        manifest["display"] = {"width": session.screen_width, "height": session.screen_height, "densityDpi": session.screen_density}
        manifest["installOutput"] = install_and_start(session, apk, stage, f"10.0.2.2:{args.fixture_port}")
        nodes = session.wait_for(
            lambda current: capture_helper.matching_node(current, resource_id_suffix="tileSongName", clickable=True) is not None,
            description="Home dashboard with the Song Name tile",
            timeout=session.ui_wait_timeout,
        )
        (stage / "baseline-dashboard-nodes.json").write_text(
            json.dumps(summarize_nodes(session), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        manifest["stages"].append({"stage": "dashboard", "focused": [n.get("resource-id") for n in nodes if n.get("focused") == "true"]})
        print(json.dumps({"ready": True, "serial": serial, "adbServerPort": manifest["isolatedAdbServerPort"], "dashboard": True, "outputDir": str(stage)}, ensure_ascii=False), flush=True)
        save_capture(session, stage, "baseline-dashboard")
        if args.interactive:
            interactive_loop(session, stage)
        else:
            print(json.dumps(summarize_nodes(session), ensure_ascii=False), flush=True)
    except Exception as exc:
        primary_error = f"{type(exc).__name__}: {exc}"
        manifest["failure"] = primary_error
        if session is not None:
            try:
                manifest["failureEvidence"] = capture_helper.capture_failure_snapshot(session, stage)
            except Exception as capture_exc:
                manifest["failureCaptureError"] = str(capture_exc)
        print(primary_error, file=sys.stderr, flush=True)
    finally:
        if process is not None:
            cleanup_error = capture_helper.stop_owned_emulator(process, adb=tools.adb, serial=serial, env=env)
            adb_error = capture_helper.stop_isolated_adb_server(tools.adb, env)
            cleanup_error = cleanup_error or adb_error
        if stop_log_watcher is not None:
            stop_log_watcher.set()
        if log_watcher is not None:
            log_watcher.join(timeout=2)
            log_state["bytes"] = (stage / "emulator.log").stat().st_size if (stage / "emulator.log").exists() else 0
        manifest["cleanup"] = {"emuKillAndOwnedExit": "passed" if cleanup_error is None and process is not None else cleanup_error or "not-started", "runtimeRoot": str(runtime_root)}
        manifest["emulatorLog"] = {**log_state, "maxBytes": MAX_EMULATOR_LOG_BYTES}
        manifest["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        (stage / "run-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        runtime_parent = Path(args.runtime_parent).resolve()
        owner_file = runtime_root / ".ktv-home-ui-audit-owner"
        runtime_resolved = runtime_root.resolve() if runtime_root.exists() else None
        may_remove_runtime = (
            process is not None
            and process.poll() is not None
            and cleanup_error is None
            and runtime_resolved is not None
            and runtime_resolved.parent == runtime_parent
            and owner_file.is_file()
            and owner_file.read_text(encoding="utf-8") == args.run_id
        )
        if may_remove_runtime:
            try:
                manifest["cleanup"]["runtimeDirectory"] = remove_owned_runtime(
                    runtime_root, runtime_parent, args.run_id
                )
            except Exception as exc:
                manifest["cleanup"]["runtimeDirectory"] = f"failed: {type(exc).__name__}: {exc}"
                cleanup_error = cleanup_error or f"runtime cleanup failed: {exc}"
            (stage / "run-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if primary_error or cleanup_error else 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk-root", default=r"K:\Android")
    parser.add_argument("--avd-template", default="KTV_API34_TV_UI_AUDIT_20261103")
    parser.add_argument("--apk", default=str(ROOT / "android-tv" / "app" / "build" / "outputs" / "apk" / "debug" / "app-x86-debug.apk"))
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--runtime-parent", default=str(ROOT / ".tmp" / "iktv-avd"))
    parser.add_argument("--fixture-port", type=int, required=True)
    parser.add_argument("--run-id", default=f"iktv-mv-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}-{uuid.uuid4().hex[:6]}")
    parser.add_argument("--reuse-runtime", action="store_true")
    parser.add_argument("--interactive", action="store_true")
    parser.add_argument("--software-cpu-emulation", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
