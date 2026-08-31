"""Behavior tests for the release evidence manifest."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).parents[1]
SCRIPT = REPOSITORY / "scripts" / "release_manifest.py"


class ReleaseManifestTests(unittest.TestCase):
    def run_manifest(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            cwd=REPOSITORY,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_build_records_source_identity_migration_and_artifact_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migrations = root / "backend" / "src" / "main" / "resources" / "db" / "migration"
            migrations.mkdir(parents=True)
            (migrations / "V2__old.sql").write_text("select 1;", encoding="utf-8")
            (migrations / "V27__current.sql").write_text("select 27;", encoding="utf-8")
            artifacts = root / "release-assets"
            artifacts.mkdir()
            apk = artifacts / "home-ktv-tv-1.0.0-arm64-v8a.apk"
            apk.write_bytes(b"apk fixture")
            windows = artifacts / "home-ktv-windows-player-1.0.0-win-x64.zip"
            windows.write_bytes(b"windows fixture")
            manifest = artifacts / "release-manifest.json"

            result = self.run_manifest(
                "build",
                "--repository",
                str(root),
                "--output",
                str(manifest),
                "--release-tag",
                "v1.0.0",
                "--version",
                "1.0.0",
                "--channel",
                "stable",
                "--source-sha",
                "abc123",
                "--build-time",
                "2026-08-30T00:00:00Z",
                "--image-ref",
                "ghcr.io/aimeng001/ktv-home@sha256:deadbeef",
                "--artifact-dir",
                str(artifacts),
            )

            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(1, data["schemaVersion"])
            self.assertEqual("v1.0.0", data["releaseTag"])
            self.assertEqual("1.0.0", data["version"])
            self.assertEqual("abc123", data["source"]["gitSha"])
            self.assertEqual(27, data["source"]["latestMigration"])
            self.assertEqual(
                "ghcr.io/aimeng001/ktv-home@sha256:deadbeef",
                data["image"]["ref"],
            )
            by_path = {item["path"]: item for item in data["artifacts"]}
            self.assertEqual(
                hashlib.sha256(apk.read_bytes()).hexdigest(),
                by_path[apk.name]["sha256"],
            )
            self.assertEqual(windows.stat().st_size, by_path[windows.name]["size"])

            verify = self.run_manifest(
                "verify",
                "--repository",
                str(root),
                "--manifest",
                str(manifest),
            )
            self.assertEqual(0, verify.returncode, verify.stderr)

    def test_verify_fails_when_a_published_artifact_changes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            migrations = root / "backend" / "src" / "main" / "resources" / "db" / "migration"
            migrations.mkdir(parents=True)
            (migrations / "V1__initial.sql").write_text("select 1;", encoding="utf-8")
            artifacts = root / "release-assets"
            artifacts.mkdir()
            artifact = artifacts / "app.zip"
            artifact.write_bytes(b"before")
            manifest = artifacts / "release-manifest.json"

            built = self.run_manifest(
                "build",
                "--repository",
                str(root),
                "--output",
                str(manifest),
                "--release-tag",
                "v1.0.0",
                "--version",
                "1.0.0",
                "--channel",
                "stable",
                "--source-sha",
                "abc123",
                "--build-time",
                "2026-08-30T00:00:00Z",
                "--artifact-dir",
                str(artifacts),
                "--include",
                artifact.name,
            )
            self.assertEqual(0, built.returncode, built.stderr)

            artifact.write_bytes(b"after")
            verify = self.run_manifest(
                "verify",
                "--repository",
                str(root),
                "--manifest",
                str(manifest),
            )
            self.assertNotEqual(0, verify.returncode)
            self.assertIn("hash", verify.stderr.lower())


if __name__ == "__main__":
    unittest.main()
