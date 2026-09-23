#!/usr/bin/env python3
"""Dependency-free smoke tests for date and locking behavior."""
from __future__ import annotations

import datetime as dt
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch


MODULE_PATH = Path(__file__).with_name("export.py")
spec = importlib.util.spec_from_file_location("fanmaiji_export", MODULE_PATH)
assert spec and spec.loader
export = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = export
spec.loader.exec_module(export)


class DateRangeTests(unittest.TestCase):
    def test_defaults_to_yesterday(self) -> None:
        result = export.resolve_date_range(today=dt.date(2026, 9, 22))
        self.assertEqual(result.label, "2026-09-21")

    def test_explicit_range_and_month(self) -> None:
        result = export.resolve_date_range(start_value="2026-02-28", end_value="2026-03-01")
        self.assertEqual(result.label, "2026-02-28_2026-03-01")
        self.assertEqual(export.month_bounds("2024-02").label, "2024-02-01_2024-02-29")

    def test_conflicting_options_rejected(self) -> None:
        with self.assertRaises(ValueError):
            export.resolve_date_range(date_value="2026-09-21", month_value="2026-09")
        with self.assertRaises(ValueError):
            export.resolve_date_range(start_value="2026-09-22", end_value="2026-09-21")

    def test_full_day_and_year_boundary(self) -> None:
        result = export.resolve_date_range(today=dt.date(2026, 1, 1))
        self.assertEqual(result.start_at.isoformat(), "2025-12-31T00:00:00+08:00")
        self.assertEqual(result.end_at.isoformat(), "2025-12-31T23:59:59+08:00")

    def test_rejects_end_without_start_and_compact_date(self) -> None:
        with self.assertRaises(ValueError):
            export.resolve_date_range(end_value="2026-09-22")
        with self.assertRaises(ValueError):
            export.parse_date("20260922")


class PreflightTests(unittest.TestCase):
    def test_plan_does_not_load_config_or_credentials_or_run(self) -> None:
        output = io.StringIO()
        with patch.object(export, "_load_config") as config, patch.object(export, "run") as run:
            with redirect_stdout(output):
                result = export.main(["--type", "sales", "--date", "2026-09-21", "--plan"])
        config.assert_not_called()
        run.assert_not_called()
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(output.getvalue())["end"], "2026-09-21T23:59:59+08:00")

    def test_unverified_date_config_stops_before_credentials(self) -> None:
        config = {"exports": {"sales": {"verified": True, "date_range_param": "day", "date_filters": {"verified": False}}}}
        with patch.object(export, "_load_config", return_value=config), patch.object(export, "env_or_die") as env:
            with self.assertRaisesRegex(export.ExportError, "日期筛选控件尚未联真"):
                export.run(["sales"])
        env.assert_not_called()

    def test_unverified_date_filter_does_not_touch_page(self) -> None:
        page = Mock()
        with self.assertRaisesRegex(export.ExportError, "尚未联真"):
            export._apply_date_range(page, {}, export.resolve_date_range(date_value="2026-09-21"))
        self.assertEqual(page.mock_calls, [])

    def test_verified_filter_checks_complete_day_after_query(self) -> None:
        start = Mock()
        start.input_value.return_value = "2026-09-21 00:00:00"
        end = Mock()
        end.input_value.return_value = "2026-09-21 23:59:59"
        spec = {"date_filters": {"verified": True, "start_selectors": ["#start"], "end_selectors": ["#end"], "apply_selectors": ["#query"]}}
        with patch.object(export, "_first_locator", side_effect=[start, end]), patch.object(export, "_click_first"), patch.object(export, "_wait_network_idle"):
            export._apply_date_range(Mock(), spec, export.resolve_date_range(date_value="2026-09-21"))
        start.fill.assert_called_once_with("2026-09-21 00:00:00")
        end.fill.assert_called_once_with("2026-09-21 23:59:59")

    def test_date_reset_after_query_fails(self) -> None:
        field = Mock()
        field.input_value.return_value = "2026-09-22 00:00:00"
        spec = {"date_filters": {"verified": True, "start_selectors": ["#start"], "end_selectors": ["#end"], "apply_selectors": ["#query"]}}
        with patch.object(export, "_first_locator", return_value=field), patch.object(export, "_click_first"), patch.object(export, "_wait_network_idle"):
            with self.assertRaisesRegex(export.ExportError, "与请求不一致"):
                export._apply_date_range(Mock(), spec, export.resolve_date_range(date_value="2026-09-21"))

    def test_fill_failure_does_not_echo_secret(self) -> None:
        field = Mock()
        field.fill.side_effect = RuntimeError("fill(secret-test-value) failed")
        with patch.object(export, "_first_locator", return_value=field):
            with self.assertRaises(export.ExportError) as caught:
                export._fill_first(Mock(), ["#password"], "secret-test-value")
        self.assertNotIn("secret-test-value", str(caught.exception))


class DownloadTests(unittest.TestCase):
    def download(self, body: bytes, name: str = "sales.csv") -> Mock:
        download = Mock()
        download.suggested_filename = name
        download.save_as.side_effect = lambda path: Path(path).write_bytes(body)
        return download

    def test_rejects_empty_html_and_fake_xlsx(self) -> None:
        for body, name in [(b"", "sales.csv"), (b"<html>login</html>", "sales.xls"), (b"not a workbook", "sales.xlsx")]:
            with self.subTest(name=name, body=body), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(export.ExportError):
                    export._save_download(self.download(body, name), Path(directory), "sales-2026-09-21")
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_repeat_download_preserves_original_and_removes_temp(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = export._save_download(self.download(b"sku,qty\nA,1\n"), root, "sales-2026-09-21")
            rerun = export._save_download(self.download(b"sku,qty\nA,2\n"), root, "sales-2026-09-21")
            self.assertNotEqual(original, rerun)
            self.assertEqual(original.read_bytes(), b"sku,qty\nA,1\n")
            self.assertEqual(rerun.read_bytes(), b"sku,qty\nA,2\n")
            self.assertEqual(len(list(root.iterdir())), 2)


class LockTests(unittest.TestCase):
    def test_lock_blocks_second_owner(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "export.lock"
            with export.export_lock(path):
                with self.assertRaises(export.AlreadyRunningError):
                    with export.export_lock(path):
                        pass
            with export.export_lock(path):
                pass  # Release permits the next run despite the persistent file.


if __name__ == "__main__":
    unittest.main()
