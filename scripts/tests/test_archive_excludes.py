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


if __name__ == "__main__":
    unittest.main()
