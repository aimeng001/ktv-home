import unittest

from scripts.stability_runner import build_run_config, new_client_token, result_document


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


if __name__ == "__main__":
    unittest.main()
