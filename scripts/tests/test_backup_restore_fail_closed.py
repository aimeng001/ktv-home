import re
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).parents[2]
BACKUP = REPOSITORY / "scripts" / "backup.sh"
RESTORE = REPOSITORY / "scripts" / "restore.sh"


class BackupRestoreFailClosedTests(unittest.TestCase):
    def test_backup_uses_atomic_temp_output_and_does_not_publish_failed_dump(self):
        script = BACKUP.read_text(encoding="utf-8")

        self.assertIn("mktemp", script)
        self.assertRegex(script, r"\bmv\b")
        self.assertRegex(script, r"trap")
        self.assertNotIn('> "${output}"', script)

    def test_restore_does_not_start_ktv_after_restore_failure(self):
        script = RESTORE.read_text(encoding="utf-8")

        self.assertRegex(script, r"if\s+docker compose exec")
        self.assertNotRegex(script, r"trap\s+['\"]docker compose start ktv")
        self.assertNotEqual(-1, script.find("restore".lower()))


if __name__ == "__main__":
    unittest.main()
