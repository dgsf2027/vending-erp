import contextlib
import importlib
import io
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import sync_daily


class SyncDailyTests(unittest.TestCase):
    def make_credentials(self, root):
        path = root / "vend-credentials.json"
        path.write_text(json.dumps({"base_url": "http://127.0.0.1:8089", "username": "sync_user", "password": "private-test-password"}))
        path.chmod(0o600)
        return path

    def test_credentials_reject_world_readable_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = self.make_credentials(Path(temporary))
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                sync_daily.load_credentials(path)

    def exercise(self, import_status="imported", auth_failure=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.make_credentials(root)
            report = root / "sales-2026-09-22.xls"
            report.write_bytes(b"already-validated-export")
            auth_client, client = Mock(), Mock()
            auth_client.post_json.return_value = {"token": "private-test-token"}
            if auth_failure:
                auth_client.post_json.side_effect = RuntimeError("private-test-password private-test-token")
            importer = Mock(return_value={"status": import_status, "batchId": 12, "rowOk": 338, "pendingBind": 0})
            fake = types.SimpleNamespace(VendClient=Mock(side_effect=[auth_client, client]), import_sales=importer)
            output = io.StringIO()
            exporter = importlib.import_module("export")
            with patch.dict("sys.modules", {"import_vend": fake}), patch.dict(os.environ, {"FANMAIJI_RUN_ID": "this-run"}), patch.object(exporter, "run", return_value=[report]) as download, patch.object(sync_daily, "report_row_count", return_value=338), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                code = sync_daily.main(["--runtime-dir", str(root), "--date", "2026-09-22"])
            result = json.loads((root / "run/last-sync.json").read_text())
            self.assertEqual(result["runId"], "this-run")
            self.assertEqual(result["queryDate"], "2026-09-22")
            self.assertNotIn("private-test", output.getvalue())
            self.assertNotIn("private-test", json.dumps(result))
            if auth_failure:
                download.assert_not_called()
                importer.assert_not_called()
            else:
                self.assertEqual(download.call_args.kwargs["date_range"].label, "2026-09-22")
                importer.assert_called_once_with(client, report, "2026-09-22", 338, root.resolve() / "run/imports")
            return code, result

    def test_success_requires_import_success(self):
        code, result = self.exercise()
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "success")

    def test_unresolved_import_does_not_report_success(self):
        code, result = self.exercise("needs_attention")
        self.assertEqual(code, 3)
        self.assertEqual(result["status"], "needs_attention")

    def test_login_failure_stops_before_vendor_and_hides_credentials(self):
        code, result = self.exercise(auth_failure=True)
        self.assertEqual(code, 2)
        self.assertEqual(result["stage"], "vend 登录")

    def test_plan_has_no_login_export_or_state_write(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            root = Path(temporary)
            self.assertEqual(sync_daily.main(["--runtime-dir", str(root), "--date", "2026-09-22", "--plan"]), 0)
            self.assertEqual(list(root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
