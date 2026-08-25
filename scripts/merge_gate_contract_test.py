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
        self.assertIn("docker-compose*.yml", workflow)
        self.assertIn('docker compose -f "$file" config --quiet', workflow)


if __name__ == "__main__":
    unittest.main()
