"""Contract tests for the T09 GitHub Release workflow.

These tests intentionally inspect the workflow as configuration.  They do not
contact GitHub or publish artifacts; they protect the release safety gates that
can be checked locally.
"""

from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).parents[1] / ".github" / "workflows" / "release.yml"
SAFE_VERSION = re.compile(
    r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$"
)


def job_block(workflow: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"workflow job {name!r} is missing")
    return match.group(0)


class ReleaseWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_release_source_gate_protects_every_publish_path(self) -> None:
        source_gate = job_block(self.workflow, "release-source")
        release_meta = job_block(self.workflow, "release-meta")
        image = job_block(self.workflow, "image")
        github_release = job_block(self.workflow, "github-release")

        self.assertIn("GITHUB_EVENT_NAME", source_gate)
        self.assertIn("GITHUB_REF_NAME", source_gate)
        self.assertIn("git merge-base --is-ancestor", source_gate)
        self.assertIn("needs: release-source", release_meta)
        self.assertIn("windows-player", image)
        self.assertIn("image", github_release)

    def test_release_source_gate_rejects_non_master_dispatches(self) -> None:
        source_gate = job_block(self.workflow, "release-source")

        self.assertRegex(source_gate, r'GITHUB_EVENT_NAME.*workflow_dispatch')
        self.assertRegex(source_gate, r'GITHUB_REF_NAME.*master')
        self.assertRegex(source_gate, r'GITHUB_REF_TYPE.*tag')

    def test_release_metadata_has_a_safe_version_contract(self) -> None:
        release_meta = job_block(self.workflow, "release-meta")

        self.assertRegex(release_meta, r'\[\[\s*"\$version"\s*=~')
        self.assertIn("Invalid release version", release_meta)
        self.assertIsNotNone(SAFE_VERSION.fullmatch("1.0.0"))
        self.assertIsNotNone(SAFE_VERSION.fullmatch("1.0.0-unstable.3"))
        self.assertIsNone(SAFE_VERSION.fullmatch("1.0.0/rc1"))
        self.assertIsNone(SAFE_VERSION.fullmatch("1.0.0:rc1"))

    def test_windows_package_explains_the_mpv_prerequisite(self) -> None:
        windows_player = job_block(self.workflow, "windows-player")

        self.assertIn("mpv.exe", windows_player)
        self.assertIn("Copy-Item windows-player/README.md", windows_player)
        self.assertIn("README-WINDOWS-PLAYER.md", windows_player)


if __name__ == "__main__":
    unittest.main()
