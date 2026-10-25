"""Local contract checks for the CI workflow; no GitHub or release actions run."""

from pathlib import Path
import re
import unittest


REPOSITORY = Path(__file__).resolve().parents[1]
WORKFLOW = REPOSITORY / ".github" / "workflows" / "ci.yml"


def job_block(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"workflow job {name!r} is missing")
    return match.group(0)


class CiWorkflowContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_ci_is_limited_to_reviewed_master_pushes_and_pull_requests(self) -> None:
        self.assertRegex(self.workflow, r"(?m)^on:\s*$")
        self.assertIn("  push:\n    branches: [master]", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^  pull_request:\s*$")
        self.assertIn("permissions:\n  contents: read", self.workflow)

    def test_backend_and_large_library_checks_keep_the_10000_row_limit(self) -> None:
        backend = job_block(self.workflow, "backend")
        large_scan = job_block(self.workflow, "large-library-scan")

        self.assertIn("./mvnw --batch-mode -T 1C test", backend)
        self.assertIn("-Dtest=LargeExternalLibraryScanTest", large_scan)
        self.assertIn("-DscanRows=10000", large_scan)
        self.assertIn("-Xmx512m", large_scan)
        self.assertNotIn("scanRows=100000", large_scan)

    def test_h5_android_and_windows_jobs_run_tests_and_build_checks(self) -> None:
        h5 = job_block(self.workflow, "h5")
        android = job_block(self.workflow, "android-tv")
        windows = job_block(self.workflow, "windows-player")

        self.assertIn("npm ci", h5)
        self.assertIn("npm test", h5)
        self.assertIn("npm run build", h5)
        self.assertIn("testReleaseUnitTest lintRelease", android)
        self.assertIn("sha256sum -c -", android)
        self.assertIn("dotnet build windows-player/HomeKtv.Windows.sln -c Release", windows)
        self.assertIn("dotnet test windows-player/HomeKtv.Windows.sln -c Release", windows)

    def test_compose_job_validates_each_local_configuration(self) -> None:
        compose = job_block(self.workflow, "compose-config")
        compose_contracts = (REPOSITORY / "scripts" / "compose_config_test.py").read_text(encoding="utf-8")

        for filename in (
            "docker-compose.yml",
            "docker-compose.dev.yml",
            "docker-compose.nas.yml",
            "docker-compose.prebuilt.yml",
            "docker-compose.ci.yml",
            "docker-compose.hardware.yml",
            "docker-compose.rockchip.yml",
        ):
            self.assertIn(filename, compose_contracts)
        self.assertIn("python scripts/compose_config_test.py", compose)
        self.assertIn("test_nas_source_mount_is_read_only_and_never_auto_created", compose_contracts)
        self.assertIn("test_password_required_profiles_fail_closed_when_password_is_blank", compose_contracts)

    def test_workflow_contract_job_runs_local_static_checks_without_publishing(self) -> None:
        contract = job_block(self.workflow, "release-config")

        self.assertIn("python scripts/ci_workflow_test.py", contract)
        self.assertIn("python scripts/release_workflow_test.py", contract)
        self.assertIn("python scripts/merge_gate_contract_test.py", contract)
        self.assertNotIn("actions/upload-artifact", self.workflow)
        self.assertNotIn("docker push", self.workflow)
        self.assertNotIn("softprops/action-gh-release", self.workflow)


if __name__ == "__main__":
    unittest.main()
