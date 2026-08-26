"""Merge-gate contracts for repository boundaries and Compose validation."""

from __future__ import annotations

import re
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

    def test_local_development_documents_are_gitignored(self) -> None:
        result = subprocess.run(
            ["git", "check-ignore", "--no-index", *sorted(LOCAL_ONLY_DOCUMENTS)],
            cwd=REPOSITORY,
            check=False,
            capture_output=True,
            text=True,
        )
        ignored = {line for line in result.stdout.splitlines() if line}
        self.assertEqual(
            sorted(LOCAL_ONLY_DOCUMENTS),
            sorted(ignored),
            "every local-only development document must be protected by .gitignore",
        )

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

    def test_standalone_nas_source_path_is_configurable_and_read_only(self) -> None:
        compose = (REPOSITORY / "docker-compose.nas.yml").read_text(encoding="utf-8")
        source_target = compose.index("        target: /source-music")
        mount_start = compose.rfind("      - type: bind", 0, source_target)
        mount_end = compose.find("      - type: bind", source_target)
        source_mount = compose[mount_start:mount_end if mount_end >= 0 else len(compose)]

        self.assertIn("${KTV_SOURCE_MUSIC_DIR:-./source-music}", source_mount)
        self.assertIn("target: /source-music", source_mount)
        self.assertIn("read_only: true", source_mount)
        self.assertIn("create_host_path: false", source_mount)

    def test_standalone_nas_deployment_does_not_require_a_gpu_device(self) -> None:
        compose = (REPOSITORY / "docker-compose.nas.yml").read_text(encoding="utf-8")
        ktv_block = re.search(r"(?ms)^  ktv:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", compose)

        self.assertIsNotNone(ktv_block)
        self.assertNotIn("\n    devices:", ktv_block.group(1))


if __name__ == "__main__":
    unittest.main()
