import hashlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import Mock, patch

import import_vend as module
from import_vend import ImportFailure, ImportUncertain, VendClient, import_sales


class FakeClient:
    def __init__(self):
        self.records = []
        self.uploads = 0
        self.confirms = 0
        self.preview_changes = {}
        self.confirm_changes = {}
        self.confirm_failure = None
        self.fail_before_commit = False
        self.query_failure_after_commit = False
        self.last_filename = None

    def get_json(self, path, params=None):
        if self.query_failure_after_commit and self.confirms:
            raise ImportFailure("network unavailable")
        assert path == module.BATCHES_PATH
        current, size = params["current"], params["size"]
        return {"records": self.records[(current - 1) * size:current * size],
                "current": current, "size": size, "total": len(self.records),
                "pages": (len(self.records) + size - 1) // size}

    def upload_sales(self, file_path, file_name, expected_digest):
        self.uploads += 1
        self.last_filename = file_name
        assert hashlib.sha256(Path(file_path).read_bytes()).hexdigest() == expected_digest
        return dict(token="preview-secret-never-save", fileName=file_name, fileType=module.FILE_TYPE,
                    rowTotal=2, columnsOk=True, **{}) | self.preview_changes

    def post_json(self, path, payload):
        assert path == "/api/v1/imports/confirm"
        assert payload == {"token": "preview-secret-never-save"}
        self.confirms += 1
        if self.fail_before_commit:
            raise ImportFailure("error includes token FAKE-SECRET")
        result = dict(batchId=15, fileType=module.FILE_TYPE, rowTotal=2, rowOk=2, rowDup=0,
                      rowFail=0, pendingBind=0, priceChangeCount=0) | self.confirm_changes
        self.records.append(dict(id=15, fileType=module.FILE_TYPE, fileName=self.last_filename,
                                 batchStatus="已导入", **{k: result[k] for k in ("rowTotal", "rowOk", "rowDup", "rowFail")}))
        if self.confirm_failure:
            raise self.confirm_failure
        return result


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.file = self.root / "vendor.xls"
        self.file.write_bytes(b"original vendor xls fixture")
        self.state_dir = self.root / "state"
        self.client = FakeClient()
        self.verifier = patch.object(module, "verify_sales_file", return_value={"rows": 2, "orders": 1, "quantity": 3, "lineAmountTotal": "10.00"})
        self.verify = self.verifier.start()
        self.addCleanup(self.verifier.stop)
        self.addCleanup(self.tmp.cleanup)

    def run_import(self, expected_rows=2):
        return import_sales(self.client, self.file, "2026-09-22", expected_rows, self.state_dir)

    def read_state(self):
        return json.loads((self.state_dir / "vend-sales-2026-09-22.json").read_text())

    def test_success_checks_durable_batch_and_reuses_without_writes(self):
        first = self.run_import()
        second = self.run_import()
        self.assertEqual(first["status"], "imported")
        self.assertEqual(second["status"], "reused")
        self.assertEqual(first["batchId"], "15")
        self.assertEqual(first["rowOk"], 2)
        self.assertEqual((self.client.uploads, self.client.confirms), (1, 1))
        args = self.verify.call_args.args
        self.assertEqual(args[1].isoformat(), "2026-09-22T00:00:00+08:00")
        self.assertEqual(args[2].isoformat(), "2026-09-22T23:59:59+08:00")
        self.assertEqual(args[3], 2)
        for path in self.state_dir.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("preview-secret", path.read_text())

    def test_unknown_confirmation_response_recovers_existing_batch_without_reconfirm(self):
        self.client.confirm_failure = TimeoutError("sensitive detail")
        with self.assertRaises(ImportUncertain) as caught:
            self.run_import()
        self.assertNotIn("sensitive", str(caught.exception))
        self.assertEqual(self.read_state()["status"], "uncertain")
        summary = self.run_import()
        self.assertEqual(summary["status"], "needs_attention")
        self.assertIsNone(summary["pendingBind"])
        self.assertEqual(summary["reason"], "pending_binding_count_unknown")
        self.assertEqual((self.client.uploads, self.client.confirms), (1, 1))

    def test_unknown_confirmation_without_batch_is_never_retried(self):
        self.client.fail_before_commit = True
        for _ in range(2):
            with self.assertRaises(ImportUncertain):
                self.run_import()
        self.assertEqual((self.client.uploads, self.client.confirms), (1, 1))
        self.assertNotIn("FAKE-SECRET", json.dumps(self.read_state()))

    def test_interrupt_keeps_confirming_intent_and_prevents_reconfirm(self):
        self.client.confirm_failure = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.run_import()
        self.assertEqual(self.read_state()["status"], "confirming")
        self.assertEqual(self.run_import()["status"], "needs_attention")
        self.assertEqual(self.client.confirms, 1)

    def test_confirm_evidence_survives_failed_readback(self):
        self.client.query_failure_after_commit = True
        with self.assertRaises(ImportUncertain):
            self.run_import()
        self.assertEqual(self.read_state()["commitEvidence"]["pendingBind"], 0)
        self.client.query_failure_after_commit = False
        self.assertEqual(self.run_import()["status"], "reused")
        self.assertEqual(self.client.confirms, 1)

    def test_changed_same_day_digest_rejected_before_api(self):
        self.run_import()
        self.file.write_bytes(b"changed order row sorting")
        self.client.get_json = Mock(side_effect=AssertionError("should not contact server"))
        with self.assertRaisesRegex(ImportFailure, "摘要"):
            self.run_import()
        self.assertEqual(self.client.confirms, 1)

    def test_preview_mismatch_never_confirms(self):
        for change in ({"rowTotal": 1}, {"rowTotal": True}, {"columnsOk": False},
                       {"fileType": "系统补货记录"}, {"fileName": "other.xls"}, {"token": ""}):
            with self.subTest(change=change):
                self.client.preview_changes = change
                with self.assertRaisesRegex(ImportFailure, "预览"):
                    self.run_import()
        self.assertEqual(self.client.confirms, 0)

    def test_pending_binding_is_attention_and_not_reimported(self):
        self.client.confirm_changes = {"pendingBind": 2}
        first, second = self.run_import(), self.run_import()
        self.assertEqual(first["status"], "needs_attention")
        self.assertEqual(second["pendingBind"], 2)
        self.assertEqual(first["reason"], "pending_bindings")
        self.assertEqual(self.client.confirms, 1)

    def test_partial_import_never_reconfirms_or_rolls_back(self):
        self.client.confirm_changes = {"rowOk": 1, "rowFail": 1}
        first, second = self.run_import(), self.run_import()
        self.assertEqual(first["status"], "needs_attention")
        self.assertEqual(second["reason"], "partial_import")
        self.assertEqual(self.client.confirms, 1)

    def test_duplicate_rows_count_as_success(self):
        self.client.confirm_changes = {"rowOk": 1, "rowDup": 1}
        self.assertEqual(self.run_import()["status"], "imported")

    def test_no_data_still_validates_and_never_calls_api(self):
        self.verify.return_value = {"rows": 0, "orders": 0, "quantity": 0, "lineAmountTotal": "0.00"}
        self.client.get_json = Mock(side_effect=AssertionError("must not contact server"))
        summary = self.run_import(expected_rows=0)
        self.verify.assert_called_once()
        self.assertEqual(summary["status"], "no_data")
        self.assertEqual(self.client.confirms, 0)
        self.assertEqual(self.client.uploads, 0)

    def test_invalid_original_file_never_calls_api(self):
        self.verify.side_effect = module.SalesValidationError("sensitive source row")
        self.client.get_json = Mock(side_effect=AssertionError("must not contact server"))
        with self.assertRaises(ImportFailure) as caught:
            self.run_import()
        self.assertNotIn("sensitive", str(caught.exception))

    def test_existing_batch_without_local_state_is_reused_with_attention(self):
        self.run_import()
        (self.state_dir / "vend-sales-2026-09-22.json").unlink()
        result = self.run_import()
        self.assertTrue(result["reused"])
        self.assertEqual(result["status"], "needs_attention")
        self.assertEqual(self.client.confirms, 1)

    def test_rolled_back_existing_batch_blocks_automatic_import(self):
        self.run_import()
        self.client.records[0]["batchStatus"] = "已回滚"
        with self.assertRaises(ImportFailure):
            self.run_import()
        self.assertEqual(self.client.confirms, 1)

    def test_incomplete_pagination_fails_before_upload(self):
        self.client.get_json = Mock(return_value={"records": [], "total": 2, "size": 100, "current": 1, "pages": 1})
        with self.assertRaisesRegex(ImportFailure, "不完整"):
            self.run_import()
        self.assertEqual(self.client.uploads, 0)

    def test_second_page_is_checked_for_existing_batch(self):
        self.run_import()
        existing = self.client.records[0]
        self.client.records = [dict(id=1000 + i, fileType=module.FILE_TYPE, fileName=f"old-{i}.xls") for i in range(100)] + [existing]
        self.assertEqual(self.run_import()["status"], "reused")
        self.assertEqual(self.client.confirms, 1)

    def test_multiple_matching_batches_are_rejected(self):
        self.run_import()
        self.client.records.append(dict(self.client.records[0], id=16))
        with self.assertRaisesRegex(ImportFailure, "唯一性"):
            self.run_import()
        self.assertEqual(self.client.confirms, 1)

    def test_same_day_lock_prevents_concurrent_import(self):
        self.state_dir.mkdir()
        with (self.state_dir / "vend-sales-2026-09-22.lock").open("w") as lock:
            module.fcntl.flock(lock, module.fcntl.LOCK_EX | module.fcntl.LOCK_NB)
            with self.assertRaisesRegex(ImportFailure, "正在运行"):
                self.run_import()
        self.assertEqual(self.client.uploads, 0)

    def test_state_is_confirming_before_confirmation_request(self):
        original = self.client.post_json
        def verify_intent(*args):
            self.assertEqual(self.read_state()["status"], "confirming")
            self.assertNotIn("token", json.dumps(self.read_state()))
            return original(*args)
        self.client.post_json = verify_intent
        self.run_import()

    def test_unknown_state_stops_before_api(self):
        self.run_import()
        state = self.read_state()
        state["status"] = "unrecognized"
        (self.state_dir / "vend-sales-2026-09-22.json").write_text(json.dumps(state))
        self.client.get_json = Mock(side_effect=AssertionError("must not contact server"))
        with self.assertRaisesRegex(ImportFailure, "结构无效"):
            self.run_import()


class HttpClientTests(unittest.TestCase):
    def client(self, response):
        client = VendClient("http://127.0.0.1:8089", "AUTH-SECRET")
        stream = io.BytesIO(json.dumps(response).encode())
        stream.status = 200
        client._opener = Mock()
        client._opener.open.return_value = stream
        return client

    def test_only_approved_origins_are_accepted(self):
        for url in ("https://vend.vvaix.com", "http://127.0.0.1:8089", "https://vend.vvaix.com/"):
            VendClient(url)
        for url in ("http://vend.vvaix.com", "https://evil.example", "https://vend.vvaix.com.evil.example",
                    "https://user@vend.vvaix.com", "https://vend.vvaix.com:8443", "https://vend.vvaix.com/api",
                    "http://127.0.0.1:8090", "https://vend.vvaix.com?x=1", "https://vend.vvaix.com#x",
                    "https://vend.vvaix.com\n"):
            with self.subTest(url=url), self.assertRaises(ImportFailure):
                VendClient(url)

    def test_business_failure_does_not_expose_message_or_data(self):
        client = self.client({"code": 401, "message": "AUTH-SECRET", "data": {"other": "sensitive"}})
        with self.assertRaises(ImportFailure) as caught:
            client.get_json("/api/auth/me")
        self.assertNotIn("AUTH-SECRET", str(caught.exception))
        self.assertNotIn("sensitive", str(caught.exception))

    def test_authorization_and_json_encoding(self):
        client = self.client({"code": 200, "data": {"ok": True}})
        self.assertEqual(client.post_json("/api/auth/login", {"username": "crawler"}), {"ok": True})
        request = client._opener.open.call_args.args[0]
        self.assertEqual(request.get_header("Authorization"), "Bearer AUTH-SECRET")
        self.assertEqual(json.loads(request.data), {"username": "crawler"})
        self.assertEqual(request.full_url, "http://127.0.0.1:8089/api/auth/login")

    def test_auth_allowlist_excludes_registration_and_admin_routes(self):
        client = VendClient("http://127.0.0.1:8089")
        for path in ("/api/auth/register", "/api/auth/refresh", "/api/auth/admin"):
            with self.assertRaises(ImportFailure):
                client.post_json(path, {})

    def test_redirect_is_not_followed_and_body_is_not_reported(self):
        self.assertIsNone(module._NoRedirect().redirect_request(None, None, 302, "x", {}, "https://evil.example"))
        client = VendClient("https://vend.vvaix.com", "AUTH-SECRET")
        client._opener = Mock()
        client._opener.open.side_effect = urllib.error.HTTPError("url", 302, "AUTH-SECRET", {}, None)
        with self.assertRaises(ImportFailure) as caught:
            client.get_json("/api/v1/imports/batches")
        self.assertNotIn("AUTH-SECRET", str(caught.exception))
        self.assertIn("302", str(caught.exception))

    def test_cross_origin_api_path_and_token_header_injection_are_rejected(self):
        client = VendClient("https://vend.vvaix.com", "AUTH-SECRET")
        for path in ("https://evil.example/", "//evil.example", "/api/v1/auth/me?x=1"):
            with self.assertRaises(ImportFailure):
                client.get_json(path)
        with self.assertRaises(ImportFailure):
            VendClient("https://vend.vvaix.com", "bad\r\nX-Secret: x")

    def test_multipart_preserves_original_bytes_and_stable_filename(self):
        client = self.client({"code": 200, "data": {"columnsOk": True}})
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "source.xls"
            content = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1\x00\x01vendor bytes"
            path.write_bytes(content)
            digest = hashlib.sha256(content).hexdigest()
            filename = f"fanmaiji-sales-2026-09-22-{digest[:16]}.xls"
            client.upload_sales(path, filename, expected_digest=digest)
            request = client._opener.open.call_args.args[0]
            self.assertIn(content, request.data)
            self.assertIn(filename.encode(), request.data)
            self.assertIn(module.FILE_TYPE.encode(), request.data)
            with self.assertRaisesRegex(ImportFailure, "发生变化"):
                client.upload_sales(path, filename, expected_digest="0" * 64)


if __name__ == "__main__":
    unittest.main()
