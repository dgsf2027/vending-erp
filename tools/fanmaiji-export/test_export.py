#!/usr/bin/env python3
"""Dependency-free smoke tests for date and locking behavior."""
from __future__ import annotations

import datetime as dt
import importlib.util
import io
import json
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch


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
        start.press.assert_called_once_with("Tab")
        end.fill.assert_called_once_with("2026-09-21 23:59:59")
        end.press.assert_called_once_with("Tab")

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


class QuerySynchronizationTests(unittest.TestCase):
    def test_response_predicate_requires_exact_dates_and_get(self) -> None:
        start, end = "2026-09-22 00:00:00", "2026-09-22 23:59:59"
        url = "https://fanmaiji.top/delivery-log/page?" + export.urlencode({"startTime": start, "endTime": end, "pageSize": 20})
        response = Mock(url=url, request=Mock(method="GET"))
        self.assertTrue(export._query_response_matches(response, "/delivery-log/page", start, end))
        self.assertFalse(export._query_response_matches(response, "/delivery-log/page", "2026-09-23 00:00:00", end))
        response.request.method = "POST"
        self.assertFalse(export._query_response_matches(response, "/delivery-log/page", start, end))

    def test_response_rejects_boolean_negative_or_noninteger_totals(self) -> None:
        for total in (True, -1, "338", None):
            response = Mock(ok=True)
            response.json.return_value = {"code": 200, "status": True, "data": {"total": total}}
            with self.subTest(total=total), self.assertRaises(export.ExportError):
                export._query_response_total(response)
        response.json.return_value = {"code": 200, "status": True, "data": {"total": 338}}
        self.assertEqual(export._query_response_total(response), 338)
        response.json.return_value = {"code": 401, "status": False}
        with self.assertRaises(export.ExportError):
            export._query_response_total(response)

    def test_query_wait_is_registered_before_click_and_dom_waits_for_complete_response(self) -> None:
        events = []
        start = Mock(input_value=Mock(return_value="2026-09-22 00:00:00"))
        end = Mock(input_value=Mock(return_value="2026-09-22 23:59:59"))
        response = Mock(ok=True)
        response.json.side_effect = lambda: events.append("json") or {"code": 200, "status": True, "data": {"total": 338}}
        pending = MagicMock()
        pending.__enter__.side_effect = lambda: events.append("listen") or Mock(value=response)
        page = Mock()
        page.expect_response.return_value = pending
        spec = {"date_filters": {"verified": True, "start_selectors": ["#start"], "end_selectors": ["#end"], "apply_selectors": ["#query"], "response_path": "/delivery-log/page"}}
        with patch.object(export, "_first_locator", side_effect=[start, end]), patch.object(export, "_click_first", side_effect=lambda *args: events.append("click")), patch.object(export, "_wait_pagination_total", side_effect=lambda *args: events.append("render")) as render, patch.object(export, "_wait_network_idle") as idle:
            count = export._apply_date_range(page, spec, export.resolve_date_range(date_value="2026-09-22"))
        self.assertEqual(count, 338)
        self.assertEqual(events, ["listen", "click", "json", "render"])
        render.assert_called_once_with(page, 338)
        idle.assert_not_called()

    def test_current_ready_route_never_reclicks_menu(self) -> None:
        page = Mock(url="https://fanmaiji.top/runspace_pc/salesManager/deliveryList")
        spec = {"url_path": "/runspace_pc/salesManager/deliveryList", "date_filters": {"start_selectors": ["#start"]}}
        with patch.object(export, "_wait_first_locator", return_value=Mock()), patch.object(export, "_click_first") as click:
            export._open_export(page, spec)
        click.assert_not_called()

    def test_stale_pagination_rejected_even_after_response(self) -> None:
        pagination = Mock(inner_text=Mock(return_value="共269条"))
        with patch.object(export, "_first_locator", return_value=pagination):
            with self.assertRaisesRegex(export.ExportError, "偏离本次查询响应"):
                export._report_validator(Mock(), {"validate_sales_report": True}, export.resolve_date_range(date_value="2026-09-22"), None, expected_rows=338)


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

    def test_task_filename_hint_and_validator_before_publish(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            body = export.XLS_MAGIC + b"test content"
            def validate(path: Path) -> None:
                self.assertEqual(path.read_bytes(), body)
                self.assertEqual([item.name for item in root.iterdir()], [path.name])
                self.assertEqual(path.suffix, ".part")
            target = export._save_download(self.download(body, "260923155100091"), root, "sales", filename_hint="出货明细_20260923155101.xls", validator=validate)
            self.assertEqual(target.suffix, ".xls")
            self.assertEqual(target.read_bytes(), body)

    def test_bad_xls_or_data_validation_failure_never_publishes(self) -> None:
        for body, validator in [(b"fake xls", None), (export.XLS_MAGIC, Mock(side_effect=ValueError("row mismatch"))), (export.XLS_MAGIC, Mock(return_value=False))]:
            with self.subTest(body=body), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(export.ExportError):
                    export._save_download(self.download(body, "task"), Path(directory), "sales", filename_hint="report.xls", validator=validator)
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_matching_verified_cache_skips_submit_and_download_after_query(self) -> None:
        dates = export.resolve_date_range(date_value="2026-09-22")
        spec = {"verified": True, "date_range_param": "day", "async_export": True}
        validator = Mock()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            existing = root / "sales-2026-09-22.xls"
            existing.write_bytes(export.XLS_MAGIC)
            with patch.object(export, "_validate_date_filters"), patch.object(export, "_open_export"), patch.object(export, "_apply_date_range", return_value=338) as query, patch.object(export, "_report_validator", return_value=validator), patch.object(export, "_download_async_export") as submit, patch.object(export, "_save_download") as save:
                result = export._download_one(Mock(), spec, "sales", root, dates)
            self.assertEqual(result, existing)
            query.assert_called_once()
            validator.assert_called_once_with(existing)
            submit.assert_not_called()
            save.assert_not_called()

    def test_cache_requires_validator_and_exact_query_date(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sales-2026-09-22.xls").write_bytes(export.XLS_MAGIC)
            self.assertIsNone(export._reuse_validated_download(root, "sales-2026-09-22", None))
            validator = Mock()
            self.assertIsNone(export._reuse_validated_download(root, "sales-2026-09-23", validator))
            validator.assert_not_called()

    def test_newest_valid_same_date_archive_is_reused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "sales-2026-09-22.xls"
            archive = root / "sales-2026-09-22-run-20260923T162100000000.xls"
            newest = root / "sales-2026-09-22-run-20260923T162200000000.xls"
            unrelated = root / "sales-2026-09-23-run-20260924T073000000000.xls"
            for index, path in enumerate((original, archive, newest, unrelated)):
                path.write_bytes(b"good" if path == archive else b"invalid")
                export.os.utime(path, (index + 1, index + 1))
            checked = []
            def validator(path: Path) -> bool:
                checked.append(path)
                return path.read_bytes() == b"good"
            result = export._reuse_validated_download(root, "sales-2026-09-22", validator)
            self.assertEqual(result, archive)
            self.assertEqual(checked, [newest, archive])
            self.assertEqual(original.read_bytes(), b"invalid")

    def test_failed_cached_validator_preserves_original_and_downloads_normally(self) -> None:
        dates = export.resolve_date_range(date_value="2026-09-22")
        spec = {"verified": True, "date_range_param": "day", "async_export": True}
        validator = Mock(side_effect=ValueError("count changed"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            existing = root / "sales-2026-09-22.xls"
            existing.write_bytes(b"old original")
            with patch.object(export, "_validate_date_filters"), patch.object(export, "_open_export"), patch.object(export, "_apply_date_range", return_value=338), patch.object(export, "_report_validator", return_value=validator), patch.object(export, "_download_async_export", return_value=(Mock(), "report.xls")) as submit, patch.object(export, "_save_download", return_value=root / "sales-new.xls"):
                export._download_one(Mock(), spec, "sales", root, dates)
            submit.assert_called_once()
            self.assertEqual(existing.read_bytes(), b"old original")

    def test_known_confirmation_only(self) -> None:
        spec = {"export_confirm_text": "确定要导出数据?", "export_confirm_selectors": [".el-message-box:visible button:has-text('确定')"]}
        dialog = Mock()
        dialog.inner_text.return_value = "温馨提示\n确定要导出数据?\n取消 确定"
        with patch.object(export, "_wait_first_locator", return_value=dialog), patch.object(export, "_click_first") as click:
            export._confirm_export(Mock(), spec)
            click.assert_called_once()
        dialog.inner_text.return_value = "确定要删除所有数据?"
        with patch.object(export, "_wait_first_locator", return_value=dialog), patch.object(export, "_click_first") as click:
            with self.assertRaisesRegex(export.ExportError, "拒绝确认未知弹窗"):
                export._confirm_export(Mock(), spec)
            click.assert_not_called()

    def test_confirmation_occurs_inside_download_wait(self) -> None:
        events = []
        page = Mock()
        download_wait = MagicMock()
        download_wait.__enter__.side_effect = lambda: events.append("begin") or Mock(value=Mock())
        download_wait.__exit__.side_effect = lambda *args: events.append("end")
        page.expect_download.return_value = download_wait
        spec = {"verified": True, "date_range_param": "none", "export_button_selectors": ["#export"]}
        with patch.object(export, "_open_export"), patch.object(export, "_click_first", side_effect=lambda *args: events.append("click")), patch.object(export, "_confirm_export", side_effect=lambda *args: events.append("confirm")), patch.object(export, "_save_download", return_value=Path("products.csv")):
            export._download_one(page, spec, "products", Path("."), None)
        self.assertEqual(events, ["begin", "click", "confirm", "end"])


class AsyncExportTests(unittest.TestCase):
    task_id = "260923155100091"

    def test_submit_success_and_exact_existing_task_reuse(self) -> None:
        for payload in ({"taskId": self.task_id, "status": True}, {"taskId": self.task_id, "status": False, "desc": "当前任务已存在,请到导出记录下载"}):
            self.assertEqual(export._task_id_from_response(payload), self.task_id)
        for payload in ({"status": True}, {"taskId": "wrong/id", "status": True}, {"taskId": self.task_id, "status": False, "desc": "权限不足"}):
            with self.assertRaises(export.ExportError):
                export._task_id_from_response(payload)

    def test_dialog_waits_for_expected_text_and_never_confirms_unknown(self) -> None:
        page = Mock()
        dialog = Mock(inner_text=Mock(return_value="温馨提示 任务提交成功，是否到导出记录下载？ 取消 确定"))
        with patch.object(export, "_first_locator", return_value=dialog), patch.object(export, "_click_first") as click:
            export._confirm_known_dialog(page, ["任务提交成功，是否到导出记录下载？"])
        page.locator.return_value.filter.return_value.first.wait_for.assert_called_once_with(state="visible", timeout=export.DEFAULT_TIMEOUT_MS)
        pattern = page.locator.return_value.filter.call_args.kwargs["has_text"]
        self.assertIsNotNone(pattern.search("任务提交成功，是否到导出记录下载？"))
        self.assertIsNone(pattern.search("确定要导出数据?"))
        click.assert_called_once()
        page.locator.return_value.filter.return_value.first.wait_for.side_effect = TimeoutError()
        with patch.object(export, "_click_first") as click:
            with self.assertRaisesRegex(export.ExportError, "拒绝确认未知弹窗"):
                export._confirm_known_dialog(page, ["确定要下载么?"])
        click.assert_not_called()

    def task_page(self, *, task_id: str | None = None, rows: int = 1, status: str = "处理成功", function: str = "出货明细") -> tuple[Mock, Mock]:
        page = Mock()
        page.locator.return_value.count.return_value = rows
        cells = page.locator.return_value.nth.return_value.locator.return_value
        cells.count.return_value = 8
        values = ["1", task_id or self.task_id, "出货明细_20260923155101.xls", function, "operator", status, "2026-09-23", "导出 删除"]
        cells.nth.side_effect = lambda index: Mock(inner_text=Mock(return_value=values[index]))
        field = Mock(input_value=Mock(return_value=self.task_id))
        return page, field

    def test_unique_matching_successful_task(self) -> None:
        page, field = self.task_page()
        with patch.object(export, "_first_locator", return_value=field):
            record = export._read_task_row(page, self.task_id, "出货明细")
        self.assertEqual(record["filename"], "出货明细_20260923155101.xls")
        self.assertEqual(page.mock_calls[0], unittest.mock.call.locator(export.TASK_ROWS_SELECTOR))

    def test_wrong_duplicate_failed_or_other_function_rejected(self) -> None:
        for options in ({"task_id": "999"}, {"rows": 2}, {"status": "处理失败"}, {"function": "其他功能"}):
            page, field = self.task_page(**options)
            with self.subTest(options=options), patch.object(export, "_first_locator", return_value=field):
                with self.assertRaises(export.ExportError):
                    export._read_task_row(page, self.task_id, "出货明细")

    def test_pending_task_poll_and_timeout(self) -> None:
        page = Mock()
        record = {"filename": "report.xls"}
        with patch.object(export, "_read_task_row", side_effect=[None, record]), patch.object(export, "_first_locator", return_value=Mock()):
            self.assertEqual(export._wait_task_record(page, self.task_id, "出货明细"), record)
        page.wait_for_timeout.assert_called_once()
        with patch.object(export, "_read_task_row", return_value=None), patch.object(export.time, "monotonic", side_effect=[0, 120.1]):
            with self.assertRaisesRegex(export.ExportError, "超过 120 秒"):
                export._wait_task_record(Mock(), self.task_id, "出货明细")

    def test_async_flow_binds_response_task_and_download_filename(self) -> None:
        page = Mock()
        response = Mock(ok=True, url="https://fanmaiji.top" + export.TASK_SUBMIT_PATH)
        response.json.return_value = {"taskId": self.task_id, "status": False, "desc": "当前任务已存在,请到导出记录下载"}
        submitted = MagicMock()
        submitted.__enter__.return_value = Mock(value=response)
        page.expect_response.return_value = submitted
        downloaded = Mock()
        download_wait = MagicMock()
        download_wait.__enter__.return_value = Mock(value=downloaded)
        page.expect_download.return_value = download_wait
        record = {"filename": "出货明细_20260923155101.xls"}
        with patch.object(export, "_click_first") as click, patch.object(export, "_confirm_known_dialog") as confirm, patch.object(export, "_wait_first_locator", return_value=Mock()), patch.object(export, "_wait_task_record", return_value=record) as wait:
            actual, hint = export._download_async_export(page, {"expected_function": "出货明细", "export_button_selectors": ["#export"]})
        self.assertIs(actual, downloaded)
        self.assertEqual(hint, record["filename"])
        self.assertEqual(confirm.call_count, 3)
        self.assertIn("确定要下载么?", confirm.call_args.args[1])
        self.assertEqual(click.call_args.args[1], ['button:has-text("导出"):visible'])
        wait.assert_called_once_with(page, self.task_id, "出货明细")
        self.assertIn("?taskId=" + self.task_id, page.goto.call_args.args[0])
        predicate = page.expect_response.call_args.args[0]
        self.assertTrue(predicate(Mock(url=response.url, request=Mock(method="POST"))))
        self.assertFalse(predicate(Mock(url=response.url, request=Mock(method="GET"))))

    def test_sales_validator_uses_query_count_and_keeps_external_validator(self) -> None:
        pagination = Mock(inner_text=Mock(return_value="共 338 条 前往 1 页"))
        verifier_module = types.ModuleType("verify_sales")
        verifier_module.verify_sales_file = Mock(return_value={"rows": 338})
        verifier_module.SalesValidationError = ValueError
        extra = Mock()
        dates = export.resolve_date_range(date_value="2026-09-22")
        with patch.object(export, "_first_locator", return_value=pagination), patch.dict(sys.modules, {"verify_sales": verifier_module}):
            validator = export._report_validator(Mock(), {"validate_sales_report": True}, dates, extra)
            validator(Path("temporary.part"))
        verifier_module.verify_sales_file.assert_called_once_with(Path("temporary.part"), dates.start_at, dates.end_at, 338)
        extra.assert_called_once_with(Path("temporary.part"))


class CDPTests(unittest.TestCase):
    def setUp(self) -> None:
        self.options = {
            "mode": "cdp", "cdp_url": "http://127.0.0.1:9222", "start_url": "/index",
            "session_success_selectors": ["#session-ready"],
        }
        self.pw = Mock()
        self.browser = self.pw.chromium.connect_over_cdp.return_value
        self.context = Mock()
        self.original_pages = [Mock(url="https://fanmaiji.top/index")] + [Mock(url="https://other.example/") for _ in range(6)]
        self.context.pages = self.original_pages
        self.browser.contexts = [self.context]
        self.owned_page = self.context.new_page.return_value

    def test_endpoint_allows_loopback_http_only(self) -> None:
        for address in ("http://127.0.0.1:9222", "http://localhost:9222", "http://[::1]:9222"):
            self.assertEqual(export.validate_cdp_url(address), address)
        for address in ("http://192.168.1.2:9222", "http://example.com:9222", "ws://127.0.0.1:9222",
                        "https://127.0.0.1:9222", "http://127.0.0.1:0", "http://127.0.0.1:99999",
                        "http://127.0.0.1", "http://user:pass@127.0.0.1:9222", "http://127.0.0.1:9222/path"):
            with self.subTest(address=address), self.assertRaises(export.ExportError):
                export.validate_cdp_url(address)

    def assert_existing_browser_untouched(self) -> None:
        self.browser.close.assert_not_called()
        self.browser.new_context.assert_not_called()
        self.context.close.assert_not_called()
        self.pw.chromium.launch.assert_not_called()
        for existing in self.original_pages:
            self.assertEqual(existing.mock_calls, [])

    def test_success_closes_only_new_page(self) -> None:
        with patch.object(export, "_wait_first_locator", return_value=Mock()):
            with export._browser_page(self.pw, self.options) as page:
                self.assertIs(page, self.owned_page)
        self.owned_page.goto.assert_called_once_with("https://fanmaiji.top/index", wait_until="domcontentloaded", timeout=export.DEFAULT_TIMEOUT_MS)
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_only_owned_popup_tree_is_closed_and_listeners_removed(self) -> None:
        popup, descendant, user_page = Mock(), Mock(), Mock()
        with patch.object(export, "_wait_first_locator", return_value=Mock()):
            with export._browser_page(self.pw, self.options):
                popup_handler = next(call.args[1] for call in self.owned_page.on.call_args_list if call.args[0] == "popup")
                popup_handler(popup)
                child_handler = next(call.args[1] for call in popup.on.call_args_list if call.args[0] == "popup")
                child_handler(descendant)
                # Another user tab can be created concurrently. It emits no
                # popup from our dedicated page tree and must remain untouched.
                self.context.pages.append(user_page)
        popup.close.assert_called_once_with()
        descendant.close.assert_called_once_with()
        self.owned_page.close.assert_called_once_with()
        self.owned_page.remove_listener.assert_any_call("popup", popup_handler)
        self.assertEqual(user_page.mock_calls, [])
        self.assert_existing_browser_untouched()

    def test_popup_cleanup_also_runs_on_export_timeout(self) -> None:
        popup = Mock()
        with patch.object(export, "_wait_first_locator", return_value=Mock()):
            with self.assertRaises(TimeoutError):
                with export._browser_page(self.pw, self.options):
                    handler = next(call.args[1] for call in self.owned_page.on.call_args_list if call.args[0] == "popup")
                    handler(popup)
                    raise TimeoutError("download")
        popup.close.assert_called_once_with()
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_known_rate_limit_only_reads_owned_download_popup(self) -> None:
        self.owned_page.url = "https://fanmaiji.top/runspace_pc/recordCenter/exporterUserTask"
        popup = Mock(url="https://fanmaiji.top/standalone/export/task/download/260923155100091")
        popup.locator.return_value.inner_text.return_value = json.dumps({"code": 999999, "message": "请勿短时间内重复下载，请稍后再试", "status": False, "data": None})
        tracked = export._OwnedPages(self.owned_page)
        tracked.track(popup)
        self.assertIn("厂家下载限频", tracked.known_download_error())
        for existing in self.original_pages:
            self.assertEqual(existing.mock_calls, [])
        popup.url = "https://unrelated.example/private"
        popup.locator.reset_mock()
        self.assertIsNone(tracked.known_download_error())
        popup.locator.assert_not_called()
        tracked.close()

    def test_initial_query_completes_and_renders_before_session_is_yielded(self) -> None:
        self.options["initial_response_path"] = "/delivery-log/page"
        events = []
        response = Mock(ok=True)
        response.json.side_effect = lambda: events.append("json") or {"code": 200, "status": True, "data": {"total": 269}}
        pending = MagicMock()
        pending.__enter__.side_effect = lambda: events.append("listen") or Mock(value=response)
        self.owned_page.expect_response.return_value = pending
        self.owned_page.goto.side_effect = lambda *args, **kwargs: events.append("goto")
        with patch.object(export, "_wait_first_locator", return_value=Mock()), patch.object(export, "_wait_pagination_total", side_effect=lambda *args: events.append("render")):
            with export._browser_page(self.pw, self.options):
                events.append("ready")
        self.assertEqual(events, ["listen", "goto", "json", "render", "ready"])
        self.assert_existing_browser_untouched()

    def test_failed_initial_query_is_controlled_and_closes_only_owned_page(self) -> None:
        self.options["initial_response_path"] = "/delivery-log/page"
        response = Mock(ok=True)
        response.json.return_value = {"code": 401, "status": False}
        pending = MagicMock()
        pending.__enter__.return_value = Mock(value=response)
        self.owned_page.expect_response.return_value = pending
        with self.assertRaisesRegex(export.ExportError, "会话可能已过期"):
            with export._browser_page(self.pw, self.options):
                self.fail("Failed bootstrap must not reach the export")
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_expired_session_closes_only_new_page_and_does_not_login(self) -> None:
        with patch.object(export, "_wait_first_locator", return_value=None), patch.object(export, "_login") as login:
            with self.assertRaisesRegex(export.ExportError, "会话已过期"):
                with export._browser_page(self.pw, self.options):
                    self.fail("Expired session must not reach the export")
        login.assert_not_called()
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_export_exception_closes_only_new_page(self) -> None:
        with patch.object(export, "_wait_first_locator", return_value=Mock()):
            with self.assertRaisesRegex(RuntimeError, "test export failure"):
                with export._browser_page(self.pw, self.options):
                    raise RuntimeError("test export failure")
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_cdp_config_does_not_require_fresh_login_fields(self) -> None:
        config = {"browser": self.options, "login": {"verified": False}, "exports": {"products": {"verified": True, "date_range_param": "none"}}}
        export.validate_config(config, ["products"])
        config["browser"] = {"mode": "fresh"}
        with self.assertRaisesRegex(export.ExportError, "登录配置尚未联真"):
            export.validate_config(config, ["products"])

    def test_cdp_run_never_loads_credentials_and_disconnects_client(self) -> None:
        config = {"browser": self.options, "exports": {"products": {"verified": True, "date_range_param": "none"}}}
        manager = MagicMock()
        manager.__enter__.return_value = self.pw
        api = types.ModuleType("playwright.sync_api")
        api.sync_playwright = Mock(return_value=manager)
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(sys.modules, {"playwright.sync_api": api}), patch.dict(export.os.environ, {
                "FANMAIJI_DOWNLOAD_DIR": directory, "FANMAIJI_LOCK_FILE": str(Path(directory) / "lock"),
                "FANMAIJI_ENV_FILE": str(Path(directory) / "must-not-be-read.env"),
            }), patch.object(export, "_load_config", return_value=config), patch.object(export, "load_dotenv") as dotenv, patch.object(export, "env_or_die") as credentials, patch.object(export, "_login") as login, patch.object(export, "configure_logging"), patch.object(export, "_wait_first_locator", return_value=Mock()), patch.object(export, "_download_one", return_value=Path(directory) / "products.csv"):
                export.run(["products"])
        dotenv.assert_not_called()
        credentials.assert_not_called()
        login.assert_not_called()
        manager.__exit__.assert_called_once()
        self.owned_page.close.assert_called_once_with()
        self.assert_existing_browser_untouched()

    def test_fresh_mode_closes_its_own_browser(self) -> None:
        fresh = self.pw.chromium.launch.return_value
        with export._browser_page(self.pw, {"mode": "fresh"}):
            pass
        fresh.close.assert_called_once_with()
        fresh.new_context.assert_called_once_with(accept_downloads=True, timezone_id="Asia/Shanghai")
        self.pw.chromium.connect_over_cdp.assert_not_called()


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
