"""Local, non-starting checks for the supported Docker Compose configurations."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[1]
COMPOSE_VARIANTS = (
    ("docker-compose.yml",),
    ("docker-compose.dev.yml",),
    ("docker-compose.nas.yml",),
    ("docker-compose.prebuilt.yml",),
    ("docker-compose.ci.yml",),
    ("docker-compose.yml", "docker-compose.hardware.yml"),
    ("docker-compose.yml", "docker-compose.rockchip.yml"),
)
PASSWORD_REQUIRED_VARIANTS = (
    ("docker-compose.yml",),
    ("docker-compose.dev.yml",),
    ("docker-compose.nas.yml",),
    ("docker-compose.prebuilt.yml",),
)


def _compose_environment() -> dict[str, str]:
    environment = os.environ.copy()
    for key in tuple(environment):
        if key.startswith(("KTV_", "COMPOSE_")) or key in {"TZ", "PUID", "PGID"}:
            environment.pop(key)
    return environment


def _fixture_env(token: str, *, include_password: bool) -> str:
    values = {
        "KTV_DB_USER": "ktv_compose_test",
        "KTV_DB_NAME": "ktv_compose_test",
        "KTV_PG_DIR": f"./backend/target/compose-contract-{token}/postgres",
        "KTV_SOURCE_MUSIC_DIR": f"./backend/target/compose-contract-{token}/source",
        "KTV_MUSIC_DIR": f"./backend/target/compose-contract-{token}/music",
        "KTV_DATA_DIR": f"./backend/target/compose-contract-{token}/data",
        "KTV_AVATAR_LIBRARY_DIR": f"./backend/target/compose-contract-{token}/avatars",
        "KTV_ADMIN_PASSWORD": "",
        "KTV_PLAYER_CREDENTIAL": "",
        "KTV_AI_API_KEY": "",
    }
    if include_password:
        values["KTV_DB_PASSWORD"] = "compose-contract-test-only"
    return "\n".join(f"{key}={value}" for key, value in values.items()) + "\n"


def _source_mount_explicitly_disables_host_path_creation(filename: str, target: str) -> bool:
    lines = (ROOT / filename).read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"^(?P<indent>\s*)-\s+type:\s*bind\s*$", line)
        if match is None:
            continue

        indent = len(match.group("indent"))
        end = index + 1
        while end < len(lines):
            sibling = re.match(r"^(?P<indent>\s*)-\s+", lines[end])
            if sibling is not None and len(sibling.group("indent")) == indent:
                break
            end += 1

        block = lines[index:end]
        has_target = any(
            re.fullmatch(rf"\s+target:\s*['\"]?{re.escape(target)}['\"]?\s*", item)
            for item in block
        )
        explicitly_disables_creation = False
        for bind_index, item in enumerate(block):
            bind_match = re.fullmatch(r"(?P<indent>\s*)bind:\s*", item)
            if bind_match is None:
                continue

            bind_indent = len(bind_match.group("indent"))
            for nested_item in block[bind_index + 1 :]:
                if nested_item.strip() and len(nested_item) - len(nested_item.lstrip()) <= bind_indent:
                    break
                if re.fullmatch(r"\s+create_host_path:\s*false\s*", nested_item, flags=re.IGNORECASE):
                    explicitly_disables_creation = True
                    break
            break
        if has_target:
            return explicitly_disables_creation

    return False


def _mount_disables_host_path_creation(mount: dict, filename: str, target: str) -> bool:
    bind = mount.get("bind", {})
    if not isinstance(bind, dict):
        return False
    if "create_host_path" in bind:
        return bind["create_host_path"] is False
    return _source_mount_explicitly_disables_host_path_creation(filename, target)


class ComposeConfigurationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.docker = shutil.which("docker")
        if cls.docker is None:
            raise RuntimeError("Docker CLI is required for local Compose configuration checks")
        cls.token = uuid.uuid4().hex[:12]
        cls.temp_root = tempfile.TemporaryDirectory(prefix="ktv-compose-contract-", dir=ROOT)
        cls.env_file = Path(cls.temp_root.name) / "compose.env"
        cls.env_file.write_text(_fixture_env(cls.token, include_password=True), encoding="utf-8")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp_root.cleanup()

    def _config(self, files: tuple[str, ...], *, include_password: bool = True) -> subprocess.CompletedProcess[str]:
        if include_password:
            env_file = self.env_file
        else:
            env_file = Path(self.temp_root.name) / "missing-password.env"
            env_file.write_text(_fixture_env(self.token, include_password=False), encoding="utf-8")

        command = [self.docker, "compose", "--env-file", str(env_file)]
        for filename in files:
            command.extend(("-f", str(ROOT / filename)))
        command.extend(("config", "--format", "json"))
        return subprocess.run(
            command,
            cwd=ROOT,
            env=_compose_environment(),
            capture_output=True,
            text=True,
            check=False,
            timeout=45,
        )

    def _nas_config(self) -> dict:
        result = self._config(("docker-compose.nas.yml",))
        self.assertEqual(0, result.returncode, "NAS Compose config should resolve using synthetic local paths")
        return json.loads(result.stdout)

    def test_every_supported_compose_variant_resolves_without_starting_services(self) -> None:
        for files in COMPOSE_VARIANTS:
            with self.subTest(files=files):
                result = self._config(files)
                self.assertEqual(0, result.returncode, f"Compose config failed for {', '.join(files)}")
                self.assertTrue(result.stdout.startswith("{"), f"Compose JSON missing for {', '.join(files)}")
                json.loads(result.stdout)

    def test_nas_source_mount_is_read_only_and_never_auto_created(self) -> None:
        compose = self._nas_config()
        app = compose["services"]["ktv"]
        source_mounts = [volume for volume in app["volumes"] if volume.get("target") == "/source-music"]

        self.assertEqual(1, len(source_mounts))
        source_mount = source_mounts[0]
        self.assertTrue(source_mount["read_only"])
        self.assertTrue(_mount_disables_host_path_creation(source_mount, "docker-compose.nas.yml", "/source-music"))
        expected_source = (ROOT / "backend" / "target" / f"compose-contract-{self.token}" / "source").resolve()
        self.assertEqual(str(expected_source), source_mount["source"])

    def test_omitted_false_mount_policy_uses_explicit_compose_declaration(self) -> None:
        normalized_mount = {"target": "/source-music", "bind": {}}
        self.assertTrue(_mount_disables_host_path_creation(normalized_mount, "docker-compose.ci.yml", "/source-music"))
        self.assertFalse(_mount_disables_host_path_creation(normalized_mount, "docker-compose.ci.yml", "/missing"))
        self.assertFalse(
            _mount_disables_host_path_creation(
                {"target": "/source-music", "bind": {"create_host_path": True}},
                "docker-compose.ci.yml",
                "/source-music",
            )
        )

    def test_nas_database_credentials_health_dependency_and_cache_boundary(self) -> None:
        compose = self._nas_config()
        services = compose["services"]
        app = services["ktv"]

        self.assertEqual("EXTERNAL_READ_ONLY", app["environment"]["KTV_LIBRARY_MODE"])
        self.assertEqual(
            services["db"]["environment"]["POSTGRES_PASSWORD"],
            app["environment"]["SPRING_DATASOURCE_PASSWORD"],
        )
        self.assertEqual("service_healthy", app["depends_on"]["db"]["condition"])
        self.assertIn("/api/ready", " ".join(app["healthcheck"]["test"]))
        media_cache_mounts = [
            volume
            for service in services.values()
            for volume in service.get("volumes", [])
            if any(token in f"{volume.get('source', '')} {volume.get('target', '')}".lower()
                   for token in ("transcode-cache", "transcoding-cache", "variant-cache"))
        ]
        self.assertEqual([], media_cache_mounts)

    def test_ci_compose_keeps_the_external_source_read_only(self) -> None:
        result = self._config(("docker-compose.ci.yml",))
        self.assertEqual(0, result.returncode, "CI Compose config should resolve without starting services")
        compose = json.loads(result.stdout)
        app = compose["services"]["ktv"]
        source_mounts = [volume for volume in app["volumes"] if volume.get("target") == "/source-music"]

        self.assertEqual("EXTERNAL_READ_ONLY", app["environment"]["KTV_LIBRARY_MODE"])
        self.assertEqual(1, len(source_mounts))
        self.assertTrue(source_mounts[0]["read_only"])
        self.assertTrue(_mount_disables_host_path_creation(source_mounts[0], "docker-compose.ci.yml", "/source-music"))

    def test_password_required_profiles_fail_closed_when_password_is_blank(self) -> None:
        for files in PASSWORD_REQUIRED_VARIANTS:
            with self.subTest(files=files):
                result = self._config(files, include_password=False)
                self.assertNotEqual(0, result.returncode, f"Compose unexpectedly accepted a blank DB password: {files}")
                self.assertIn("KTV_DB_PASSWORD", result.stderr)

    def test_config_does_not_create_the_synthetic_nas_source_directory(self) -> None:
        source = ROOT / "backend" / "target" / f"compose-contract-{self.token}" / "source"
        self.assertFalse(source.exists(), "synthetic source fixture must not pre-exist")
        self._nas_config()
        self.assertFalse(source.exists(), "config validation must not create or write the NAS source path")


if __name__ == "__main__":
    unittest.main(verbosity=2)
