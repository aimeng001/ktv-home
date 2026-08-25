"""Merge-gate contracts for repository boundaries and Compose validation."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
LOCAL_ONLY_DOCUMENTS = {
    "AGENTS.md",
    "ARCHITECTURE.md",
    "PLAN.md",
    "REQUIREMENTS.md",
    "TASK_STATE.md",
    "KTV_AGENT_PROMPTS.txt",
}


def head_paths() -> set[str]:
    result = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", "HEAD"],
        cwd=REPOSITORY,
        check=True,
        capture_output=True,
        text=True,
    )
    return {line for line in result.stdout.splitlines() if line}


class MergeGateContractTests(unittest.TestCase):
    def test_local_development_documents_are_not_in_release_tree(self) -> None:
        leaked = sorted(LOCAL_ONLY_DOCUMENTS & head_paths())
        self.assertEqual([], leaked, f"local-only files are tracked in HEAD: {leaked}")

    def test_ci_validates_every_compose_definition(self) -> None:
        workflow = (REPOSITORY / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("compose-config:", workflow)
        self.assertIn("files=(", workflow)
        for filename in (
            "docker-compose.yml",
            "docker-compose.dev.yml",
            "docker-compose.nas.yml",
            "docker-compose.prebuilt.yml",
            "docker-compose.hardware.yml",
            "docker-compose.rockchip.yml",
        ):
            self.assertIn(filename, workflow)
        self.assertIn('docker compose -f "$file" config --quiet', workflow)

    def test_ci_combines_compose_hardware_overlays_with_the_base(self) -> None:
        workflow = (REPOSITORY / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('case "$file" in', workflow)
        self.assertIn(
            "docker-compose.hardware.yml|docker-compose.rockchip.yml)",
            workflow,
        )
        self.assertIn(
            'docker compose -f docker-compose.yml -f "$file" config --quiet',
            workflow,
        )


if __name__ == "__main__":
    unittest.main()
