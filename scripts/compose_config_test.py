"""Local, non-starting checks for the supported Docker Compose configurations."""

from __future__ import annotations

import json
import os
from pathlib import Path
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
        self.assertFalse(source_mount["bind"]["create_host_path"])
        expected_source = (ROOT / "backend" / "target" / f"compose-contract-{self.token}" / "source").resolve()
        self.assertEqual(str(expected_source), source_mount["source"])

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
        self.assertFalse(source_mounts[0]["bind"]["create_host_path"])

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
