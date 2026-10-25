import contextlib
import hashlib
import io
import json
import runpy
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from scripts.release_manifest import build_manifest, main, verify_manifest, write_manifest


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        temporary_parent = Path(__file__).resolve().parents[2] / "backend" / "target" / "scripts-tests"
        temporary_parent.mkdir(parents=True, exist_ok=True)
        self.temporary = TemporaryDirectory(dir=temporary_parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        migration_dir = (
            self.root
            / "repository"
            / "backend"
            / "src"
            / "main"
            / "resources"
            / "db"
            / "migration"
        )
        migration_dir.mkdir(parents=True)
        for name in ("V2__old.sql", "V10__current.sql", "V9__middle.sql", "Vbroken__ignored.sql"):
            (migration_dir / name).write_text("-- fixture\n", encoding="utf-8")

        self.repository = migration_dir.parents[5]
        self.artifacts = self.root / "dist"
        self.artifacts.mkdir()
        (self.artifacts / "app.bin").write_bytes(b"release-payload")
        (self.artifacts / "歌曲.txt").write_text("测试曲库\n", encoding="utf-8")
        self.output = self.artifacts / "release-manifest.json"

    def build(self, **overrides):
        arguments = {
            "repository": self.repository,
            "output": self.output,
            "release_tag": "v1.2.3",
            "version": "1.2.3-rc.1",
            "channel": "stable",
            "source_sha": "abc123",
            "build_time": "2026-10-19T01:00:00Z",
            "artifact_dir": self.artifacts,
        }
        arguments.update(overrides)
        return build_manifest(**arguments)

    def write_document(self, document):
        self.output.write_text(json.dumps(document), encoding="utf-8")

    def valid_document(self):
        document = self.build(image_ref="ghcr.io/example/ktv@sha256:abc")
        write_manifest(document, self.output)
        return document

    def test_build_manifest_records_numeric_migration_and_exact_artifact_digests(self):
        manifest = self.build(image_ref="ghcr.io/example/ktv@sha256:abc")

        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual("1.2.3-rc.1", manifest["version"])
        self.assertEqual(10, manifest["source"]["latestMigration"])
        self.assertEqual(
            ["app.bin", "歌曲.txt"],
            [item["path"] for item in manifest["artifacts"]],
        )
        app = manifest["artifacts"][0]
        self.assertEqual(len(b"release-payload"), app["size"])
        self.assertEqual(hashlib.sha256(b"release-payload").hexdigest(), app["sha256"])
        self.assertEqual("ghcr.io/example/ktv@sha256:abc", manifest["image"]["ref"])

    def test_build_manifest_honors_includes_and_omits_existing_output(self):
        self.output.write_text("stale output", encoding="utf-8")

        manifest = self.build(includes=["歌曲.txt", "app.bin", "release-manifest.json"])

        self.assertEqual(["app.bin", "歌曲.txt"], [item["path"] for item in manifest["artifacts"]])

    def test_build_manifest_rejects_invalid_release_identity(self):
        invalid_arguments = (
            ({"release_tag": "  "}, "release tag"),
            ({"version": "1.2"}, "release version"),
            ({"version": "v1.2.3"}, "release version"),
            ({"channel": "preview"}, "release channel"),
            ({"source_sha": " "}, "source SHA"),
        )
        for overrides, message in invalid_arguments:
            with self.subTest(overrides=overrides):
                with self.assertRaisesRegex(ValueError, message):
                    self.build(**overrides)

    def test_build_manifest_rejects_missing_or_escaping_included_files(self):
        for include in ("missing.zip", "../outside.zip", "."):
            with self.subTest(include=include):
                with self.assertRaisesRegex(ValueError, "artifact is not a file below"):
                    self.build(includes=[include])

    def test_write_manifest_creates_parent_and_preserves_unicode_json(self):
        output = self.root / "nested" / "deeper" / "manifest.json"
        document = {"artifact": "歌曲.txt"}

        write_manifest(document, output)

        contents = output.read_text(encoding="utf-8")
        self.assertIn("歌曲.txt", contents)
        self.assertEqual(document, json.loads(contents))

    def test_manifest_built_beside_artifacts_verifies_round_trip(self):
        manifest = self.build()

        write_manifest(manifest, self.output)

        self.assertEqual([], verify_manifest(self.repository, self.output))

    def test_verify_reports_all_expected_identity_mismatches(self):
        self.valid_document()

        errors = verify_manifest(
            self.repository,
            self.output,
            expected_release_tag="v9.9.9",
            expected_version="9.9.9",
            expected_channel="unstable",
            expected_source_sha="other-sha",
            expected_image_ref="ghcr.io/example/other@sha256:def",
        )

        self.assertEqual(5, len(errors))
        self.assertTrue(any("releaseTag mismatch" in error for error in errors))
        self.assertTrue(any("version mismatch" in error for error in errors))
        self.assertTrue(any("channel mismatch" in error for error in errors))
        self.assertTrue(any("source SHA mismatch" in error for error in errors))
        self.assertTrue(any("image ref mismatch" in error for error in errors))

    def test_verify_rejects_unreadable_and_non_object_manifests(self):
        self.assertTrue(verify_manifest(self.repository, self.root / "missing.json")[0].startswith("cannot read manifest:"))
        self.output.write_text("{not json", encoding="utf-8")
        self.assertTrue(verify_manifest(self.repository, self.output)[0].startswith("cannot read manifest:"))
        self.write_document([])
        self.assertEqual(["manifest root must be an object"], verify_manifest(self.repository, self.output))

    def test_verify_detects_invalid_metadata_and_source_image_types(self):
        document = {
            "schemaVersion": 7,
            "releaseTag": " ",
            "version": "latest",
            "channel": "preview",
            "source": "not-an-object",
            "image": ["not-an-object"],
            "artifacts": [{"path": "app.bin", "size": 15, "sha256": "unused"}],
        }
        self.write_document(document)

        errors = verify_manifest(self.repository, self.output, expected_image_ref="expected")

        expected_errors = (
            "unsupported manifest schemaVersion",
            "manifest releaseTag is missing or empty",
            "manifest version is missing or invalid",
            "manifest channel is missing or invalid",
            "manifest source must be an object",
            "manifest source.gitSha is missing or empty",
            "latest migration mismatch:",
            "manifest image must be an object",
            "image ref mismatch:",
            "artifact hash mismatch: app.bin",
        )
        for expected in expected_errors:
            with self.subTest(expected=expected):
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_verify_rejects_invalid_artifact_entries_and_missing_files(self):
        self.write_document(
            {
                "schemaVersion": 1,
                "releaseTag": "v1.2.3",
                "version": "1.2.3",
                "channel": "stable",
                "source": {"gitSha": "abc123", "latestMigration": 10},
                "artifacts": [None, {"path": 5}, {"path": "missing.zip"}, {"path": "../outside.zip"}],
            }
        )

        errors = verify_manifest(self.repository, self.output)

        self.assertTrue(any("invalid artifact entry" in error for error in errors))
        self.assertTrue(any("artifact is missing: missing.zip" in error for error in errors))
        self.assertTrue(any("artifact is missing: ../outside.zip" in error for error in errors))

    def test_verify_reports_artifact_size_and_hash_changes(self):
        document = self.valid_document()
        document["artifacts"][0]["size"] += 1
        document["artifacts"][0]["sha256"] = "wrong"
        self.write_document(document)

        errors = verify_manifest(self.repository, self.output)

        self.assertIn("artifact hash mismatch: app.bin", errors)
        self.assertIn("artifact size mismatch: app.bin", errors)

    def test_verify_requires_at_least_one_artifact(self):
        document = self.valid_document()
        document["artifacts"] = []
        self.write_document(document)

        errors = verify_manifest(self.repository, self.output)

        self.assertIn("manifest contains no artifacts", errors)

    def test_cli_build_and_verify_succeed_with_expected_release_fields(self):
        common = [
            "--repository", str(self.repository),
            "--release-tag", "v1.2.3",
            "--version", "1.2.3-rc.1",
            "--channel", "stable",
            "--source-sha", "abc123",
        ]
        build_args = [
            "build", *common,
            "--output", str(self.output),
            "--artifact-dir", str(self.artifacts),
            "--image-ref", "ghcr.io/example/ktv@sha256:abc",
            "--include", "app.bin",
        ]

        self.assertEqual(0, main(build_args))
        self.assertEqual(
            0,
            main([
                "verify", *common,
                "--manifest", str(self.output),
                "--image-ref", "ghcr.io/example/ktv@sha256:abc",
            ]),
        )

    def test_script_entrypoint_exits_with_the_build_result(self):
        arguments = [
            "release_manifest.py",
            "build",
            "--repository", str(self.repository),
            "--output", str(self.output),
            "--release-tag", "v1.2.3",
            "--version", "1.2.3",
            "--channel", "stable",
            "--source-sha", "abc123",
            "--artifact-dir", str(self.artifacts),
        ]

        with patch("sys.argv", arguments), self.assertRaises(SystemExit) as raised:
            runpy.run_path(
                str(Path(__file__).resolve().parents[2] / "scripts" / "release_manifest.py"),
                run_name="__main__",
            )

        self.assertEqual(0, raised.exception.code)

    def test_cli_returns_failure_codes_and_prints_verification_errors(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = main([
                "build",
                "--repository", str(self.repository),
                "--output", str(self.output),
                "--release-tag", "v1.2.3",
                "--version", "bad",
                "--channel", "stable",
                "--source-sha", "abc123",
                "--artifact-dir", str(self.artifacts),
            ])
        self.assertEqual(2, result)
        self.assertIn("invalid release version", stderr.getvalue())

        self.valid_document()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result = main([
                "verify",
                "--repository", str(self.repository),
                "--manifest", str(self.output),
                "--release-tag", "v9.9.9",
            ])
        self.assertEqual(1, result)
        self.assertIn("releaseTag mismatch", stderr.getvalue())

    def test_cli_returns_io_error_code_when_manifest_write_fails(self):
        stderr = io.StringIO()
        with patch(
            "scripts.release_manifest.write_manifest",
            side_effect=OSError("disk full"),
        ), contextlib.redirect_stderr(stderr):
            result = main([
                "build",
                "--repository", str(self.repository),
                "--output", str(self.output),
                "--release-tag", "v1.2.3",
                "--version", "1.2.3",
                "--channel", "stable",
                "--source-sha", "abc123",
                "--artifact-dir", str(self.artifacts),
            ])

        self.assertEqual(2, result)
        self.assertEqual("disk full\n", stderr.getvalue())

    def test_build_cli_fails_if_an_artifact_changes_before_self_verification(self):
        artifact = self.artifacts / "app.bin"
        original_writer = write_manifest

        def write_then_change_artifact(manifest, output):
            original_writer(manifest, output)
            artifact.write_bytes(b"changed during build")

        stderr = io.StringIO()
        with patch(
            "scripts.release_manifest.write_manifest",
            side_effect=write_then_change_artifact,
        ), contextlib.redirect_stderr(stderr):
            result = main([
                "build",
                "--repository", str(self.repository),
                "--output", str(self.output),
                "--release-tag", "v1.2.3",
                "--version", "1.2.3",
                "--channel", "stable",
                "--source-sha", "abc123",
                "--artifact-dir", str(self.artifacts),
            ])

        self.assertEqual(1, result)
        self.assertIn("artifact hash mismatch: app.bin", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
