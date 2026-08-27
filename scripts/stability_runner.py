#!/usr/bin/env python3
"""Isolated Home KTV server/protocol exerciser; never a real-device acceptance test."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass


@dataclass(frozen=True)
class RunConfig:
    base_url: str


def build_run_config(base_url: str, isolated_confirmed: bool) -> RunConfig:
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("stability runner accepts a loopback server only")
    if not isolated_confirmed:
        raise ValueError("explicit isolated test server confirmation is required")
    return RunConfig(base_url.rstrip("/"))


def new_client_token() -> str:
    return f"stability-{uuid.uuid4()}"


def result_document(client_token: str, counts: dict[str, int]) -> dict[str, object]:
    return {
        "scope": "server_protocol_only",
        "real_device_acceptance": False,
        "client_token": client_token,
        "counts": counts,
    }


def request_json(url: str, method: str = "GET", body: dict | None = None) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url, data=payload, method=method,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def control(config: RunConfig, token: str, action: str, params: dict | None = None) -> None:
    request_json(
        f"{config.base_url}/api/control",
        "POST",
        {"action": action, "params": params or {}, "client_token": token},
    )


def run(config: RunConfig, vocal_switches: int, next_commands: int, duration_seconds: int) -> dict:
    token = new_client_token()
    health = request_json(f"{config.base_url}/api/health")
    if health.get("status") != "UP":
        raise RuntimeError("isolated server health check is not UP")

    counts = {"vocal_switch_commands": 0, "next_commands": 0, "heartbeat_seconds": 0}
    for index in range(max(0, vocal_switches)):
        control(config, token, "set_vocal", {"mode": "original" if index % 2 == 0 else "accompaniment"})
        counts["vocal_switch_commands"] += 1
    for _ in range(max(0, next_commands)):
        control(config, token, "next")
        counts["next_commands"] += 1
    deadline = time.monotonic() + max(0, duration_seconds)
    while time.monotonic() < deadline:
        request_json(f"{config.base_url}/api/health")
        counts["heartbeat_seconds"] += 1
        time.sleep(1)
    return result_document(token, counts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, help="loopback URL of an isolated test server")
    parser.add_argument("--confirm-isolated-test-server", action="store_true")
    parser.add_argument("--vocal-switches", type=int, default=500)
    parser.add_argument("--next-commands", type=int, default=100)
    parser.add_argument("--duration-seconds", type=int, default=0)
    args = parser.parse_args()
    try:
        config = build_run_config(args.server, args.confirm_isolated_test_server)
        print(json.dumps(run(config, args.vocal_switches, args.next_commands, args.duration_seconds),
                         ensure_ascii=False, indent=2))
        return 0
    except (ValueError, RuntimeError, urllib.error.URLError) as error:
        print(json.dumps({"error": str(error), "scope": "server_protocol_only"}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
