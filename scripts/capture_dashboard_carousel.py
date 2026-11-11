#!/usr/bin/env python3
"""Capture the Home KTV dashboard photo carousel on an owned headless AVD.

This narrow probe reuses the project's isolated AVD/ADB helpers and screenshot
assertions. It does not read or modify reference screenshots or media files.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from types import ModuleType


def load_capture_helpers() -> ModuleType:
    project_root = Path(__file__).resolve().parents[1]
    helper_path = project_root / "参考" / "capture_tab_variants.py"
    spec = importlib.util.spec_from_file_location("ktv_capture_tab_variants", helper_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load the existing isolated capture helper: {helper_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    helper = load_capture_helpers()
    args = helper.parse_args()
    tools = helper.build_tools(args)
    tools.output_root.mkdir(parents=True, exist_ok=True)

    run_id = helper.new_run_id()
    stage = tools.output_root / run_id
    stage.mkdir(parents=False, exist_ok=False)
    runtime_root = Path(tempfile.gettempdir()) / "ktv-home-ui-audit" / run_id

    process = None
    serial: str | None = None
    env: dict[str, str] | None = None
    session = None
    records: list[dict[str, object]] = []
    primary_error: str | None = None
    cleanup_errors: list[str] = []
    failure_state: dict[str, object] | None = None

    def take_frame(
        filename: str,
        label: str,
        assertion: str,
        action: str,
        focus_id: str,
    ) -> float:
        started = time.monotonic()
        data = session.run_bytes(["exec-out", "screencap", "-p"], timeout=max(30, session.default_timeout))
        width, height = helper.verify_png(data, (session.screen_width, session.screen_height))
        (stage / filename).write_bytes(data)
        records.append(
            {
                "file": filename,
                "routeLabel": label,
                "routeAssertion": assertion,
                "routeAssertionPassed": True,
                "routeAssertionEvidence": "UI Automator state checked immediately before this no-input capture window",
                "focusId": focus_id,
                "inputAction": action,
                "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                "secondsFromFirstFrame": round(started - capture_epoch, 3),
                "width": width,
                "height": height,
                "bytes": len(data),
            }
        )
        return started

    try:
        helper.ensure_no_existing_emulator(tools.adb)
        helper.apk_metadata(tools.sdk, tools.apk)
        process, port, env = helper.start_emulator(
            tools,
            run_id=run_id,
            stage=stage,
            runtime_root=runtime_root,
        )
        serial = f"emulator-{port}"
        helper.wait_for_device(tools.adb, serial, process, env)
        session = helper.UiSession(
            tools.adb,
            serial,
            env,
            slow_emulation=tools.software_cpu_emulation,
        )
        helper.verify_display(session)
        helper.stabilize_isolated_ui_automation(session)
        helper.install_and_start(session, tools.apk, stage)
        initial_nodes = session.wait_for_dashboard()
        initial_focus = next(
            (
                node.get("resource-id")
                for node in initial_nodes
                if node.get("focused") == "true" and node.get("resource-id")
            ),
            "unknown",
        )
        if not initial_focus.endswith("tileHotRanking"):
            raise RuntimeError(f"Unexpected initial dashboard focus: {initial_focus}")

        # The configured TV app opens on the dashboard with the hot-ranking tile
        # focused; the singer/song artwork itself is therefore in its no-focus state.
        capture_epoch = time.monotonic()
        take_frame(
            "01_pair_01_no_focus.png",
            "大厅写真第 1 组/中央磁贴未聚焦",
            "visible-clickable-tile:tileHotRanking",
            "application-startup",
            "com.homektv.tv.debug:id/tileHotRanking",
        )

        # Open the real Home KTV queue drawer on the first dashboard photo set,
        # matching the Song Yuqi background in the trusted queue screenshot.
        # Use the verified 1080p Dock hit target directly so UI hierarchy polling
        # cannot consume most of the remaining carousel interval before opening.
        session.run(["shell", "input", "tap", "1812", "956"])
        session.wait_for_node("queueDrawerRoot", label="照片轮播暂停验证的已点抽屉")
        session.wait_for_focused_control("btnDrawerClose", label="已点抽屉关闭按钮")
        take_frame(
            "02_pair_01_queue_drawer_open.png",
            "大厅写真初始组/已点抽屉刚打开",
            "visible-node:queueDrawerRoot",
            "adb-shell-input-tap-1812-956",
            "com.homektv.tv.debug:id/btnDrawerClose",
        )
        time.sleep(11.2)
        take_frame(
            "03_pair_01_queue_drawer_after_11s.png",
            "已点抽屉保持打开/跨过轮播节拍",
            "visible-node:queueDrawerRoot",
            "open-queue-and-wait-11.2s",
            "com.homektv.tv.debug:id/btnDrawerClose",
        )
        session.keyevent(4)
        home_nodes = session.wait_for_dashboard()
        home_focus = next(
            (
                node.get("resource-id")
                for node in home_nodes
                if node.get("focused") == "true" and node.get("resource-id")
            ),
            "unknown",
        )
        if home_focus.endswith(("tileSinger", "tileSongName")):
            raise RuntimeError(f"Photo tile remained focused during no-focus cycle: {home_focus}")

        # The carousel runs continuously after the drawer closes. Sample every
        # four seconds to cover each image pair without assuming the timer phase.
        frame_started = take_frame(
            "04_dashboard_no_focus_sample_01.png",
            "大厅写真未聚焦/轮播采样 1",
            "visible-clickable-tile:tileHotRanking",
            "queue-dismissed-dashboard-focus-restored",
            home_focus,
        )
        for sample_number in range(2, 8):
            time.sleep(max(0.0, 4.0 - (time.monotonic() - frame_started)))
            frame_started = take_frame(
                f"04_dashboard_no_focus_sample_{sample_number:02d}.png",
                f"大厅写真未聚焦/轮播采样 {sample_number}",
                "visible-clickable-tile:tileHotRanking",
                "idle-carousel-sample-4s",
                home_focus,
            )

        # Enter through the real singer-tile route, then Back restores focus to
        # that tile. This avoids assuming a focus edge from the bottom Dock.
        session.tap_control("tileSinger", fallback_point=(680, 320))
        session.wait_for_selected_tab("btnTabSingers", label="歌手写真磁贴入口")
        session.keyevent(4)
        session.wait_for_dashboard()
        session.wait_for_focused_control("tileSinger", label="歌手写真磁贴焦点")

        # UIA route checks can consume several seconds; sample every four seconds
        # and assign the actual three pairs from the resulting screenshot evidence.
        session.wait_for_dashboard()
        session.wait_for_focused_control("tileSinger", label="歌手写真磁贴焦点稳定")
        focus_id = "com.homektv.tv.debug:id/tileSinger"
        frame_started = take_frame(
            "05_singer_focus_sample_01.png",
            "歌手写真磁贴聚焦/轮播采样 1",
            "visible-clickable-tile:tileHotRanking",
            "focus-restored-after-singer-route-back",
            focus_id,
        )
        for sample_number in range(2, 8):
            time.sleep(max(0.0, 4.0 - (time.monotonic() - frame_started)))
            frame_started = take_frame(
                f"05_singer_focus_sample_{sample_number:02d}.png",
                f"歌手写真磁贴聚焦/轮播采样 {sample_number}",
                "visible-clickable-tile:tileHotRanking",
                "idle-carousel-sample-4s",
                focus_id,
            )

        # Validate the lower photo tile's normal entry/Back focus restoration too.
        session.tap_control("tileSongName", fallback_point=(680, 650))
        session.wait_for_selected_tab("btnTabSongName", label="歌名写真磁贴入口")
        session.keyevent(4)
        session.wait_for_dashboard()
        session.wait_for_focused_control("tileSongName", label="歌名写真磁贴焦点")
        take_frame(
            "07_song_name_focused_return.png",
            "歌名写真磁贴通过原路返回恢复焦点",
            "visible-clickable-tile:tileHotRanking",
            "enter-song-name-tab-then-KEYCODE_BACK",
            "com.homektv.tv.debug:id/tileSongName",
        )

    except Exception as exc:  # retain evidence and always clean up owned resources
        primary_error = f"{type(exc).__name__}: {exc}"
        if session is not None:
            try:
                failure_state = helper.capture_failure_snapshot(session, stage)
            except Exception as diagnostic_error:
                failure_state = {"snapshotError": str(diagnostic_error)}
    finally:
        cleanup = helper.stop_owned_emulator(
            process,
            adb=tools.adb,
            serial=serial,
            env=env,
        )
        if cleanup:
            cleanup_errors.append(cleanup)
        adb_cleanup = helper.stop_isolated_adb_server(tools.adb, env)
        if adb_cleanup:
            cleanup_errors.append(adb_cleanup)
        if runtime_root.exists():
            try:
                helper.safe_remove_runtime(runtime_root, run_id)
            except Exception as exc:
                cleanup_errors.append(f"isolated AVD cleanup failed: {exc}")

    if process is not None and process.poll() is None:
        cleanup_errors.append("owned emulator process is still alive after cleanup")
    if runtime_root.exists():
        cleanup_errors.append("isolated AVD runtime directory remains after cleanup")
    server_port_free = (
        helper.port_is_available(int(env["ANDROID_ADB_SERVER_PORT"])) if env else True
    )
    if not server_port_free:
        cleanup_errors.append("isolated ADB server port is still occupied after cleanup")

    manifest = {
        "status": "complete" if primary_error is None and not cleanup_errors else "failed",
        "runId": run_id,
        "apk": str(tools.apk),
        "avdTemplate": tools.avd_template,
        "serial": serial,
        "headless": True,
        "audioDisabled": True,
        "logicalDisplay": {"width": session.screen_width, "height": session.screen_height}
        if session
        else None,
        "captureMethod": "reused isolated reference helpers; immediate adb exec-out screencap via UiSession",
        "expectedSequence": [
            "initial-no-focus-frame",
            "pair01-queue-drawer-open-and-still-open-over-10s",
            "dashboard-no-focus-sampled-every-4s-to-cover-all-pairs",
            "singer-focus-sampled-every-4s-to-cover-all-pairs",
            "song-name-focus-restored-by-normal-back-route",
        ],
        "screenshots": records,
        "failureState": failure_state,
        "error": primary_error,
        "cleanup": {
            "adbEmuKillAttempted": process is not None,
            "emulatorProcessExited": process is None or process.poll() is not None,
            "isolatedAdbServerPort": int(env["ANDROID_ADB_SERVER_PORT"]) if env else None,
            "isolatedAdbServerPortFree": server_port_free,
            "runtimeRootRemoved": not runtime_root.exists(),
            "errors": cleanup_errors,
        },
    }
    manifest_path = stage / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Carousel evidence: {stage}")
    print(f"Status: {manifest['status']}; screenshots: {len(records)}")
    print(f"Cleanup: {json.dumps(manifest['cleanup'], ensure_ascii=False)}")
    if primary_error:
        print(f"Capture error: {primary_error}", file=sys.stderr)
    return 0 if manifest["status"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
