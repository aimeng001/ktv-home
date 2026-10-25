import contextlib
import io
import json
import os
import runpy
import subprocess
import zipfile
import unittest
from tempfile import TemporaryDirectory
from pathlib import Path
from unittest.mock import patch

import scripts.create_archive as archive
from scripts.create_archive import ROOT, prepare_dist_apks, should_include_file


class ArchiveExcludesTest(unittest.TestCase):
    def test_should_exclude_m2_local_and_workbuddy(self):
        self.assertFalse(
            should_include_file(ROOT / "backend" / ".m2-local" / "repo" / "pkg.jar")
        )
        self.assertFalse(
            should_include_file(ROOT / ".workbuddy" / "audit" / "some_report.md")
        )
        self.assertFalse(
            should_include_file(ROOT / "android-tv" / ".gradle-local" / "daemon.log")
        )

    def test_should_exclude_named_control_files_build_trees_and_log_extensions(self):
        self.assertFalse(should_include_file(ROOT / "backend" / "src" / "PLAN.md"))
        self.assertFalse(should_include_file(ROOT / "android-tv" / "app" / "build" / "outputs" / "release.json"))
        self.assertFalse(should_include_file(ROOT / "backend" / "logs" / "test.log"))
        self.assertFalse(should_include_file(ROOT / "unapproved-root-file.txt"))

    def test_prepare_dist_apks_copies_release_only_with_requested_version(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / "release"
            debug = root / "debug"
            dist = root / "dist"
            release.mkdir()
            debug.mkdir()
            (release / "app-arm64-v8a-release.apk").write_bytes(b"release")
            (debug / "app-arm64-v8a-debug.apk").write_bytes(b"debug")

            with patch("scripts.create_archive.validate_release_apk") as validate:
                prepare_dist_apks(release_apk_dir=release, dist_tv=dist, version_name="2.3.4")
                validate.assert_called_once_with(
                    release / "app-arm64-v8a-release.apk", "2.3.4", "arm64-v8a"
                )

            self.assertEqual(
                b"release",
                (dist / "home-ktv-tv-2.3.4-arm64-v8a.apk").read_bytes(),
            )
            self.assertFalse((dist / "home-ktv-tv-2.3.4-debug.apk").exists())

    def test_archive_excludes_debug_apks_from_distribution_directory(self):
        self.assertFalse(
            should_include_file(ROOT / "dist" / "tv-apk" / "home-ktv-tv-0.1.0-arm64-v8a-debug.apk")
        )

    def test_archive_does_not_include_unapproved_release_named_apks(self):
        self.assertFalse(
            should_include_file(ROOT / "dist" / "tv-apk" / "home-ktv-tv-0.1.0-arm64-v8a.apk")
        )

    def test_archive_excludes_scratch_tree(self):
        self.assertFalse(should_include_file(ROOT / "scratch" / "patch_h5.py"))

    def test_apk_manifest_identity_must_be_the_release_application(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app-arm64-v8a-release.apk"
            apk.write_bytes(b"fixture")
            completed = __import__("subprocess").CompletedProcess(
                ["aapt"], 0, "package: name='com.homektv.tv.debug' versionCode='1' versionName='2.3.4'\n", ""
            )
            with patch("scripts.create_archive._aapt_command", return_value=["aapt"]), \
                    patch("scripts.create_archive.subprocess.run", return_value=completed):
                with self.assertRaisesRegex(ValueError, "application id mismatch"):
                    archive.validate_release_apk(apk, "2.3.4", "arm64-v8a")

    def test_prepare_dist_apks_uses_release_metadata_and_stages_each_supported_abi(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / "release"
            release.mkdir()
            (release / "output-metadata.json").write_text(
                json.dumps({"elements": [{"versionName": " 2.4.1 "}]}),
                encoding="utf-8",
            )
            arm64 = release / "app-arm64-v8a-release.apk"
            armv7 = release / "app-armeabi-v7a-release.apk"
            arm64.write_bytes(b"arm64")
            armv7.write_bytes(b"armv7")
            destination = root / "staged"

            with patch.dict(os.environ, {"KTV_TV_VERSION_NAME": ""}), patch(
                "scripts.create_archive.validate_release_apk"
            ) as validate:
                approved = prepare_dist_apks(release_apk_dir=release, dist_tv=destination)

        self.assertEqual(
            {
                (destination / "home-ktv-tv-2.4.1-arm64-v8a.apk").resolve(),
                (destination / "home-ktv-tv-2.4.1-armeabi-v7a.apk").resolve(),
            },
            approved,
        )
        self.assertEqual(2, validate.call_count)
        self.assertEqual("2.4.1", validate.call_args_list[0].args[1])

    def test_prepare_dist_apks_skips_missing_or_invalid_version_metadata(self):
        for metadata in (None, "{not json"):
            with self.subTest(metadata=metadata), TemporaryDirectory() as temporary:
                root = Path(temporary)
                release = root / "release"
                release.mkdir()
                if metadata is not None:
                    (release / "output-metadata.json").write_text(metadata, encoding="utf-8")
                with patch.dict(os.environ, {"KTV_TV_VERSION_NAME": ""}), patch(
                    "scripts.create_archive.validate_release_apk"
                ) as validate, contextlib.redirect_stdout(io.StringIO()) as stdout:
                    approved = prepare_dist_apks(release_apk_dir=release, dist_tv=root / "staged")

                self.assertEqual(set(), approved)
                self.assertIn("no release version metadata", stdout.getvalue())
                validate.assert_not_called()

    def test_prepare_dist_apks_skips_empty_metadata_and_uses_first_nonempty_version(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / "release"
            release.mkdir()
            (release / "app-arm64-v8a-release.apk").write_bytes(b"fixture")
            metadata = release / "output-metadata.json"
            with patch.dict(os.environ, {"KTV_TV_VERSION_NAME": ""}), patch(
                "scripts.create_archive.validate_release_apk"
            ) as validate, contextlib.redirect_stdout(io.StringIO()) as stdout:
                metadata.write_text(json.dumps({"elements": []}), encoding="utf-8")
                skipped = prepare_dist_apks(release_apk_dir=release, dist_tv=root / "empty")
                metadata.write_text(
                    json.dumps({
                        "elements": [
                            {},
                            {"versionName": None},
                            {"versionName": "  "},
                            {"versionName": " 2.5.0 "},
                            {"versionName": "2.6.0"},
                        ]
                    }),
                    encoding="utf-8",
                )
                staged = prepare_dist_apks(release_apk_dir=release, dist_tv=root / "nonempty")

        self.assertEqual(set(), skipped)
        self.assertIn("no release version metadata", stdout.getvalue())
        self.assertEqual(1, validate.call_count)
        self.assertEqual("2.5.0", validate.call_args.args[1])
        self.assertEqual({(root / "nonempty" / "home-ktv-tv-2.5.0-arm64-v8a.apk").resolve()}, staged)

    def test_prepare_dist_apks_returns_empty_when_no_release_apks_match(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            release = root / "release"
            release.mkdir()
            with patch("scripts.create_archive.validate_release_apk") as validate:
                approved = prepare_dist_apks(
                    release_apk_dir=release,
                    dist_tv=root / "staged",
                    version_name="2.4.1",
                )

        self.assertEqual(set(), approved)
        validate.assert_not_called()

    def test_apk_badging_requires_identity_and_allows_missing_version(self):
        self.assertEqual(
            ("com.homektv.tv", ""),
            archive.parse_apk_badging("package: name='com.homektv.tv' versionCode='12'\n"),
        )
        with self.assertRaisesRegex(ValueError, "did not contain an application identity"):
            archive.parse_apk_badging("not package metadata")

    def test_release_apk_rejects_debug_unsigned_and_wrong_abi_names_before_tool_lookup(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, expected in (
                ("home-debug-arm64-v8a.apk", "debug or unsigned"),
                ("home-unsigned-arm64-v8a.apk", "debug or unsigned"),
                ("home-release-x86.apk", "ABI does not match"),
            ):
                with self.subTest(name=name):
                    apk = root / name
                    apk.write_bytes(b"fixture")
                    with patch("scripts.create_archive._aapt_command") as find_tool:
                        with self.assertRaisesRegex(ValueError, expected):
                            archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")
                    find_tool.assert_not_called()

    def test_release_apk_requires_android_build_tools(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            with patch("scripts.create_archive._aapt_command", return_value=None):
                with self.assertRaisesRegex(RuntimeError, "build-tools.*not found"):
                    archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

    def test_release_apk_finds_sdk_build_tools_when_path_lookup_fails(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            tool_dir = sdk / "build-tools" / "35.0.0"
            tool_dir.mkdir(parents=True)
            tool = tool_dir / "aapt2.exe"
            tool.write_bytes(b"fixture")
            apk = root / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            result = subprocess.CompletedProcess(
                [], 0, "package: name='com.homektv.tv' versionName='2.4.1'\n", ""
            )

            with patch("scripts.create_archive.shutil.which", return_value=None), patch.dict(
                os.environ, {"ANDROID_SDK_ROOT": str(sdk), "ANDROID_HOME": ""}
            ), patch("scripts.create_archive.subprocess.run", return_value=result) as invoke:
                archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

        self.assertEqual(str(tool), invoke.call_args.args[0][0])
        self.assertEqual(30, invoke.call_args.kwargs["timeout"])

    def test_release_apk_uses_aapt_from_path_before_searching_sdk_folders(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            result = subprocess.CompletedProcess(
                [], 0, "package: name='com.homektv.tv' versionName='2.4.1'\n", ""
            )
            with patch("scripts.create_archive.shutil.which", return_value="C:/tools/aapt.exe") as which, patch(
                "scripts.create_archive.subprocess.run", return_value=result
            ) as invoke:
                archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

        which.assert_called_once_with("aapt")
        self.assertEqual("C:/tools/aapt.exe", invoke.call_args.args[0][0])

    def test_release_apk_stops_when_no_sdk_tool_exists(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            with patch("scripts.create_archive.shutil.which", return_value=None), patch.dict(
                os.environ, {"ANDROID_SDK_ROOT": "", "ANDROID_HOME": ""}
            ):
                with self.assertRaisesRegex(RuntimeError, "build-tools.*not found"):
                    archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            build_tools = sdk / "build-tools"
            build_tools.mkdir(parents=True)
            (build_tools / "not-a-directory.txt").write_text("skip")
            (build_tools / "preview").mkdir()
            (build_tools / "preview" / "notice.txt").write_text("no tool")
            apk = root / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            with patch("scripts.create_archive.shutil.which", return_value=None), patch.dict(
                os.environ,
                {"ANDROID_SDK_ROOT": str(root / "missing"), "ANDROID_HOME": str(sdk)},
            ):
                with self.assertRaisesRegex(RuntimeError, "build-tools.*not found"):
                    archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

    def test_release_apk_reports_tool_start_timeout_and_nonzero_failures(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            with patch("scripts.create_archive._aapt_command", return_value=["aapt"]):
                with patch("scripts.create_archive.subprocess.run", side_effect=OSError("missing")):
                    with self.assertRaisesRegex(RuntimeError, "cannot validate APK manifest: missing"):
                        archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")
                with patch(
                    "scripts.create_archive.subprocess.run",
                    side_effect=subprocess.TimeoutExpired("aapt", 30),
                ):
                    with self.assertRaisesRegex(RuntimeError, "cannot validate APK manifest"):
                        archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")
                with patch(
                    "scripts.create_archive.subprocess.run",
                    return_value=subprocess.CompletedProcess([], 1, "", "bad archive"),
                ):
                    with self.assertRaisesRegex(ValueError, "aapt failed.*bad archive"):
                        archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

    def test_release_apk_requires_matching_declared_version(self):
        with TemporaryDirectory() as temporary:
            apk = Path(temporary) / "home-release-arm64-v8a.apk"
            apk.write_bytes(b"fixture")
            result = subprocess.CompletedProcess(
                [], 0, "package: name='com.homektv.tv' versionName='2.4.0'\n", ""
            )
            with patch("scripts.create_archive._aapt_command", return_value=["aapt"]), patch(
                "scripts.create_archive.subprocess.run", return_value=result
            ):
                with self.assertRaisesRegex(ValueError, "APK version mismatch"):
                    archive.validate_release_apk(apk, "2.4.1", "arm64-v8a")

    def test_create_archive_writes_only_approved_project_files_to_the_zip(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "README.md").write_text("project", encoding="utf-8")
            source = root / "backend" / "src"
            source.mkdir(parents=True)
            (source / "Service.java").write_text("source", encoding="utf-8")
            target = root / "backend" / "target"
            target.mkdir()
            (target / "cache.jar").write_bytes(b"cache")
            music = root / "music"
            music.mkdir()
            (music / "song.mp3").write_bytes(b"media")
            dist = root / "dist"
            dist.mkdir()
            (dist / "unapproved.apk").write_bytes(b"apk")

            with patch("scripts.create_archive.ROOT", root), patch(
                "scripts.create_archive.prepare_dist_apks", return_value=set()
            ), contextlib.redirect_stdout(io.StringIO()):
                archive.create_archive("sample.zip")

            with zipfile.ZipFile(root / "sample.zip") as bundle:
                names = set(bundle.namelist())

        self.assertEqual(
            {"home-ktv/README.md", "home-ktv/backend/src/Service.java"},
            names,
        )

    def test_script_entrypoint_creates_archive_in_an_isolated_repository_root(self):
        script = Path(archive.__file__).resolve()
        original_resolve = Path.resolve
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "README.md").write_text("isolated", encoding="utf-8")

            def redirect_script_root(path, strict=False):
                if path == script:
                    return root / "scripts" / "create_archive.py"
                return original_resolve(path, strict=strict)

            with patch.object(Path, "resolve", new=redirect_script_root), contextlib.redirect_stdout(
                io.StringIO()
            ):
                runpy.run_path(str(script), run_name="__main__")

            archive_path = root / "home-ktv.zip"
            self.assertTrue(archive_path.is_file())
            with zipfile.ZipFile(archive_path) as bundle:
                self.assertIn("home-ktv/README.md", bundle.namelist())


if __name__ == "__main__":
    unittest.main()
