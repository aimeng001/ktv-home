"""Merge-gate contracts for repository boundaries and Compose validation."""

from __future__ import annotations

import json
import os
import re
import subprocess
import tempfile
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
    def _nas_compose_config(self, values: dict[str, str]) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory(prefix="ktv-compose-contract-") as temp_dir:
            env_file = Path(temp_dir) / ".env"
            env_file.write_text(
                "\n".join(f"{key}={value}" for key, value in values.items()) + "\n",
                encoding="utf-8",
            )
            environment = os.environ.copy()
            for key in {
                "KTV_DB_PASSWORD",
                "KTV_ADMIN_PASSWORD",
                "KTV_RELEASE_IMAGE",
                "KTV_SOURCE_MUSIC_DIR",
                "KTV_MUSIC_DIR",
                "KTV_DATA_DIR",
                "KTV_PG_DIR",
                "KTV_HTTP_PORT",
            }:
                environment.pop(key, None)
            return subprocess.run(
                [
                    "docker",
                    "compose",
                    "--env-file",
                    str(env_file),
                    "-f",
                    str(REPOSITORY / "docker-compose.nas.yml"),
                    "config",
                    "--format",
                    "json",
                ],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
                env=environment,
            )

    def test_readme_separates_existing_nas_and_managed_deployments(self) -> None:
        readme = (REPOSITORY / "README.md").read_text(encoding="utf-8")
        mode_heading = "### 选择曲库模式"
        mode_start = readme.index(mode_heading)
        next_heading = readme.find("\n### ", mode_start + len(mode_heading))
        mode_section = readme[mode_start:next_heading if next_heading >= 0 else len(readme)]

        self.assertIn("docker-compose.nas.yml", mode_section)
        self.assertIn("EXTERNAL_READ_ONLY", mode_section)
        self.assertTrue(
            "read-only" in mode_section.lower()
            or "只读" in mode_section
            or "read_only" in mode_section
        )
        self.assertIn("docker-compose.prebuilt.yml", mode_section)
        self.assertIn("MANAGED", mode_section)

    def test_readme_limits_source_cleanup_to_managed_workflow(self) -> None:
        readme = (REPOSITORY / "README.md").read_text(encoding="utf-8")
        import_section_start = readme.index("### 3. 导入歌曲")
        import_section_end = readme.index("### 4. 安装 Android TV 客户端", import_section_start)
        import_section = readme[import_section_start:import_section_end]

        self.assertIn("MANAGED", import_section)
        self.assertIn("自动清理", import_section)
        self.assertIn("仅", import_section)

    def test_readme_gives_an_explicit_external_library_safety_warning(self) -> None:
        readme = (REPOSITORY / "README.md").read_text(encoding="utf-8")
        mode_start = readme.index("### 选择曲库模式")
        mode_end = readme.index("### 2. 启动服务", mode_start)
        mode_section = readme[mode_start:mode_end]

        self.assertIn("不要复制", mode_section)
        self.assertIn("不要移动", mode_section)
        self.assertIn("不要重命名", mode_section)
        self.assertIn("不要删除", mode_section)

    def test_english_readme_documents_existing_nas_as_read_only(self) -> None:
        readme = (REPOSITORY / "README_EN.md").read_text(encoding="utf-8")
        lowered = readme.lower()

        self.assertIn("docker-compose.nas.yml", readme)
        self.assertIn("external_read_only", lowered)
        self.assertIn("read-only", lowered)
        self.assertIn("without copying", lowered)
        self.assertIn("without moving", lowered)
        self.assertIn("without renaming", lowered)
        self.assertIn("without deleting", lowered)

    def test_discovery_udp_port_is_fixed_across_server_clients_and_deployments(self) -> None:
        for filename in (
            ".env.example",
            "docker-compose.yml",
            "docker-compose.nas.yml",
            "docker-compose.prebuilt.yml",
            "README.md",
            "README_EN.md",
        ):
            text = (REPOSITORY / filename).read_text(encoding="utf-8")
            self.assertNotIn("KTV_DISCOVERY_UDP_PORT", text, filename)

        application = (
            REPOSITORY / "backend" / "src" / "main" / "resources" / "application.yml"
        ).read_text(encoding="utf-8")
        self.assertRegex(application, r"(?m)^\s+udp-port:\s*18888\s*$")

        for filename in (
            "docker-compose.yml",
            "docker-compose.nas.yml",
            "docker-compose.prebuilt.yml",
        ):
            compose = (REPOSITORY / filename).read_text(encoding="utf-8")
            self.assertRegex(compose, r'(?m)^\s*-\s*"18888:18888/udp"\s*$')

        android = (
            REPOSITORY
            / "android-tv"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "homektv"
            / "tv"
            / "net"
            / "DiscoveryProtocol.kt"
        ).read_text(encoding="utf-8")
        self.assertRegex(android, r"const val UDP_PORT\s*=\s*18_888")

        windows = (
            REPOSITORY
            / "windows-player"
            / "src"
            / "HomeKtv.Windows"
            / "ServerConnection"
            / "DiscoveryProtocol.cs"
        ).read_text(encoding="utf-8")
        self.assertRegex(windows, r"const int UdpPort\s*=\s*18_888")

    def test_readmes_match_current_release_notice(self) -> None:
        chinese = (REPOSITORY / "README.md").read_text(encoding="utf-8")
        self.assertNotIn("默认公告除了提示下载 TV APK，还会提醒", chinese)
        self.assertIn("默认公告会提示 Android TV APK 更新", chinese)
        self.assertIn("升级后请重新扫描曲库", chinese)
        self.assertIn("EXTERNAL_READ_ONLY", chinese)
        self.assertIn("Managed", chinese)

        english = (REPOSITORY / "README_EN.md").read_text(encoding="utf-8")
        self.assertNotIn("The default notice for `MANAGED` mode asks", english)
        self.assertIn("The default release notice announces the Android TV APK update", english)
        self.assertIn("rescan the library after upgrading", english)
        self.assertIn("`EXTERNAL_READ_ONLY` libraries must keep the source files unchanged.", english)
        self.assertIn("`MANAGED`", english)
        self.assertIn("maintenance instructions shown in the", english)

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

    def test_ci_compose_validation_supplies_a_non_secret_database_password(self) -> None:
        workflow = (REPOSITORY / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("KTV_DB_PASSWORD: ci-validation-only", workflow)

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

    def test_standalone_nas_applies_environment_contract_to_effective_config(self) -> None:
        result = self._nas_compose_config(
            {
                "KTV_DB_PASSWORD": "sentinel-db",
                "KTV_ADMIN_PASSWORD": "sentinel-admin",
                "KTV_RELEASE_IMAGE": "sentinel/image:v9",
                "KTV_SOURCE_MUSIC_DIR": "./sentinel-source",
                "KTV_MUSIC_DIR": "./sentinel-music",
                "KTV_DATA_DIR": "./sentinel-data",
                "KTV_PG_DIR": "./sentinel-postgres",
                "KTV_HTTP_PORT": "18080",
            }
        )
        self.assertEqual(0, result.returncode, result.stderr)
        config = json.loads(result.stdout)
        services = config["services"]

        self.assertEqual("sentinel-db", services["db"]["environment"]["POSTGRES_PASSWORD"])
        self.assertEqual(
            "sentinel-db",
            services["ktv"]["environment"]["SPRING_DATASOURCE_PASSWORD"],
        )
        self.assertEqual(
            "sentinel-admin",
            services["ktv"]["environment"]["KTV_ADMIN_PASSWORD"],
        )
        self.assertEqual("sentinel/image:v9", services["ktv"]["image"])
        self.assertEqual(
            "EXTERNAL_READ_ONLY",
            services["ktv"]["environment"]["KTV_LIBRARY_MODE"],
        )
        self.assertEqual("18080", services["ktv"]["environment"]["SERVER_PORT"])
        self.assertNotIn("KTV_DISCOVERY_UDP_PORT", services["ktv"]["environment"])
        self.assertTrue(
            any(
                isinstance(port, dict)
                and str(port.get("published")) == "18888"
                and port.get("target") == 18888
                and port.get("protocol") == "udp"
                for port in services["ktv"]["ports"]
            )
        )

        mounts = {mount["target"]: mount for mount in services["ktv"]["volumes"]}
        self.assertTrue(mounts["/source-music"]["read_only"])
        self.assertFalse(
            mounts["/source-music"].get("bind", {}).get("create_host_path", False)
        )
        self.assertTrue(mounts["/source-music"]["source"].endswith("sentinel-source"))
        self.assertTrue(mounts["/music"]["source"].endswith("sentinel-music"))
        self.assertTrue(mounts["/data"]["source"].endswith("sentinel-data"))
        self.assertTrue(
            {mount["target"]: mount for mount in services["db"]["volumes"]}["/var/lib/postgresql/data"][
                "source"
            ].endswith("sentinel-postgres")
        )

    def test_standalone_nas_requires_a_database_password(self) -> None:
        result = self._nas_compose_config({"KTV_ADMIN_PASSWORD": "sentinel-admin"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("KTV_DB_PASSWORD", result.stderr)


if __name__ == "__main__":
    unittest.main()
