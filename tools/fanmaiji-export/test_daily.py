"""Exercise the scheduler wrapper against local child processes, never a website."""
import contextlib
import fcntl
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("daily", Path(__file__).with_name("run_daily.py"))
daily = importlib.util.module_from_spec(spec)
spec.loader.exec_module(daily)


class DailyTests(unittest.TestCase):
    def run_child(self, source, expected_code, expected_status):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "export.py").write_text(source, encoding="utf-8")
            with patch.object(daily, "BASE", root), patch("sys.argv", [
                "run_daily.py", "--runtime-dir", str(root), "--timeout", "1"
            ]), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(daily.main(), expected_code)
            status = json.loads((root / "run" / "last-status.json").read_text())
            self.assertEqual(status["status"], expected_status)
            self.assertEqual(status["exitCode"], expected_code)
            self.assertTrue(Path(status["log"]).exists())

    def test_success_records_status(self):
        self.run_child("print('completed')", 0, "success")

    def test_failure_is_not_reported_as_success(self):
        self.run_child("raise SystemExit(2)", 2, "failed")

    def test_hung_child_is_terminated(self):
        self.run_child("import time\ntime.sleep(60)", 124, "timeout")

    def test_overlap_leaves_existing_status_untouched(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "run").mkdir()
            status = root / "run" / "last-status.json"
            status.write_text('{"status":"running"}')
            with (root / "run" / "daily.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with patch("sys.argv", ["run_daily.py", "--runtime-dir", str(root)]), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(daily.main(), 75)
            self.assertEqual(json.loads(status.read_text()), {"status": "running"})


if __name__ == "__main__":
    unittest.main()
