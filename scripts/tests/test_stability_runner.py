import contextlib
import io
import json
import runpy
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from scripts.stability_runner import build_run_config, main, new_client_token, result_document, run


class FakeServer(ThreadingHTTPServer):
    def __init__(self, health_status="UP", health_http_status=200):
        self.health_status = health_status
        self.health_http_status = health_http_status
        self.requests = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append((self.command, self.path, None))
                self.respond(owner.health_http_status, {"status": owner.health_status})

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length))
                owner.requests.append((self.command, self.path, body))
                self.respond(200, {"accepted": True})

            def respond(self, status, document):
                payload = json.dumps(document).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *_args):
                pass

        super().__init__(("127.0.0.1", 0), Handler)

    def __enter__(self):
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.shutdown()
        self.server_close()
        self.thread.join(timeout=2)

    @property
    def base_url(self):
        return f"http://127.0.0.1:{self.server_port}"


class StabilityRunnerTest(unittest.TestCase):
    def test_rejects_non_loopback_server_even_when_confirmed(self):
        with self.assertRaisesRegex(ValueError, "loopback"):
            build_run_config("http://192.168.1.10:8080", True)

    def test_requires_explicit_isolated_server_confirmation(self):
        with self.assertRaisesRegex(ValueError, "isolated"):
            build_run_config("http://127.0.0.1:8080", False)

    def test_each_run_uses_unique_token_and_cannot_claim_device_acceptance(self):
        self.assertNotEqual(new_client_token(), new_client_token())
        result = result_document("test-token", {"switches": 500})
        self.assertEqual("server_protocol_only", result["scope"])
        self.assertFalse(result["real_device_acceptance"])

    def test_run_sends_vocal_and_next_commands_and_reports_counts(self):
        with FakeServer() as server:
            result = run(build_run_config(server.base_url, True), 3, 2, 0)

        posts = [request for request in server.requests if request[0] == "POST"]
        self.assertEqual(
            [
                {"action": "set_vocal", "params": {"mode": "original"}},
                {"action": "set_vocal", "params": {"mode": "accompaniment"}},
                {"action": "set_vocal", "params": {"mode": "original"}},
                {"action": "next", "params": {}},
                {"action": "next", "params": {}},
            ],
            [{key: value for key, value in request[2].items() if key != "client_token"} for request in posts],
        )
        tokens = {request[2]["client_token"] for request in posts}
        self.assertEqual(1, len(tokens))
        self.assertEqual(
            {"vocal_switch_commands": 3, "next_commands": 2, "heartbeat_seconds": 0},
            result["counts"],
        )
        self.assertEqual("server_protocol_only", result["scope"])
        self.assertFalse(result["real_device_acceptance"])

    def test_run_treats_nonpositive_counts_as_no_commands(self):
        with FakeServer() as server:
            result = run(build_run_config(server.base_url, True), -1, 0, -2)

        self.assertEqual([], [request for request in server.requests if request[0] == "POST"])
        self.assertEqual(
            {"vocal_switch_commands": 0, "next_commands": 0, "heartbeat_seconds": 0},
            result["counts"],
        )

    def test_run_fails_closed_when_health_is_not_up(self):
        with FakeServer(health_status="DOWN") as server:
            with self.assertRaisesRegex(RuntimeError, "health check is not UP"):
                run(build_run_config(server.base_url, True), 1, 1, 0)
        self.assertEqual(1, len(server.requests))

    def test_run_counts_successful_heartbeat_polls(self):
        clock = SimpleNamespace(
            monotonic=Mock(side_effect=[100.0, 100.0, 102.0]),
            sleep=Mock(),
        )
        with FakeServer() as server, patch("scripts.stability_runner.time", clock):
            result = run(build_run_config(server.base_url, True), 0, 0, 1)

        self.assertEqual(1, result["counts"]["heartbeat_seconds"])
        self.assertEqual(2, sum(request[1] == "/api/health" for request in server.requests))
        clock.sleep.assert_called_once_with(1)

    def test_main_prints_a_json_result_for_an_isolated_local_server(self):
        with FakeServer() as server, patch(
            "sys.argv",
            [
                "stability_runner.py",
                "--server", server.base_url,
                "--confirm-isolated-test-server",
                "--vocal-switches", "2",
                "--next-commands", "1",
                "--duration-seconds", "0",
            ],
        ), contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, main())

        output = json.loads(stdout.getvalue())
        self.assertEqual(2, output["counts"]["vocal_switch_commands"])
        self.assertEqual(1, output["counts"]["next_commands"])

    def test_main_returns_json_error_for_server_failure(self):
        with FakeServer(health_http_status=503) as server, patch(
            "sys.argv",
            ["stability_runner.py", "--server", server.base_url, "--confirm-isolated-test-server"],
        ), contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(2, main())

        output = json.loads(stdout.getvalue())
        self.assertIn("error", output)
        self.assertEqual("server_protocol_only", output["scope"])

    def test_cli_help_exits_without_contacting_a_server(self):
        script = Path(__file__).resolve().parents[1] / "stability_runner.py"
        with patch("sys.argv", [str(script), "--help"]), contextlib.redirect_stdout(io.StringIO()) as stdout:
            with self.assertRaises(SystemExit) as exit_result:
                runpy.run_path(str(script), run_name="__main__")

        self.assertEqual(0, exit_result.exception.code)
        self.assertIn("--confirm-isolated-test-server", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
