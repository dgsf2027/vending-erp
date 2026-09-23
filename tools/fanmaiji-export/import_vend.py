"""Conservative, restart-safe import of a verified original sales XLS into vend."""
from __future__ import annotations

import datetime as dt
import fcntl
import hashlib
import http.client
import json
import math
import os
import re
import secrets
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from verify_sales import SalesValidationError, verify_sales_file

FILE_TYPE = "出货明细"
BATCHES_PATH = "/api/v1/imports/batches"
UNCERTAIN_STATES = {"confirming", "uncertain", "imported", "reused", "needs_attention"}
KNOWN_STATES = UNCERTAIN_STATES | {"validated", "uploading", "preview_rejected", "no_data"}


class ImportFailure(RuntimeError):
    """A controlled error whose message never contains server response contents."""


class ImportUncertain(ImportFailure):
    """Confirmation may have committed; never automatically confirm again."""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class VendClient:
    """Minimal stdlib HTTP client. Only the verified vend origins are permitted."""

    def __init__(self, base_url: str, token: str | None = None, timeout: float = 30):
        try:
            parsed = urllib.parse.urlsplit(base_url)
            valid = (
                isinstance(base_url, str)
                and not any(c.isspace() for c in base_url)
                and parsed.username is None and parsed.password is None
                and not parsed.query and not parsed.fragment and parsed.path in ("", "/")
                and (
                    (parsed.scheme == "https" and parsed.hostname == "vend.vvaix.com"
                     and parsed.port in (None, 443))
                    or (parsed.scheme == "http" and parsed.hostname == "127.0.0.1"
                        and parsed.port == 8089)
                )
            )
        except (ValueError, TypeError, AttributeError):
            valid = False
        if not valid:
            raise ImportFailure("vend 地址不在已核实的服务范围内")
        if token is not None and (not isinstance(token, str) or not token or any(c.isspace() for c in token)):
            raise ImportFailure("vend 登录凭据格式无效")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise ImportFailure("vend 请求超时参数无效")
        self.base_url = base_url.rstrip("/")
        self._token = token
        self.timeout = timeout
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())

    def _request(self, method: str, path: str, data: bytes | None = None,
                 content_type: str | None = None, params: dict | None = None) -> Any:
        if not isinstance(path, str) or not (
            re.fullmatch(r"/api/v1/[A-Za-z0-9/_-]+", path)
            or path in {"/api/auth/login", "/api/auth/me"}
        ):
            raise ImportFailure("vend API 路径无效")
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        headers = {"Accept": "application/json"}
        if self._token is not None:
            headers["Authorization"] = "Bearer " + self._token
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                if response.status != 200:
                    raise ImportFailure("vend HTTP 返回状态异常")
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise ImportFailure("vend 响应超过校验上限")
            envelope = json.loads(raw.decode("utf-8"), parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        except urllib.error.HTTPError as exc:
            # Do not follow redirects or expose response bodies, URLs, or headers.
            raise ImportFailure(f"vend HTTP 请求失败（状态 {exc.code}）") from None
        except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
            raise ImportFailure("vend 网络请求失败或超时") from None
        except (ValueError, UnicodeError):
            raise ImportFailure("vend 响应不是有效 JSON") from None
        if not isinstance(envelope, dict) or type(envelope.get("code")) is not int or envelope["code"] != 200 or "data" not in envelope:
            raise ImportFailure("vend 业务响应未通过成功校验")
        return envelope["data"]

    def get_json(self, path: str, params: dict | None = None) -> Any:
        return self._request("GET", path, params=params)

    def post_json(self, path: str, payload: dict) -> Any:
        return self._request("POST", path, json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"), "application/json")

    def upload_sales(self, file_path: Path, file_name: str, expected_digest: str) -> Any:
        content = Path(file_path).read_bytes()
        if hashlib.sha256(content).hexdigest() != expected_digest:
            raise ImportFailure("销售文件在核验后发生变化，已停止上传")
        if not re.fullmatch(r"fanmaiji-sales-\d{4}-\d{2}-\d{2}-[0-9a-f]{16}\.xls", file_name):
            raise ImportFailure("销售归档文件名无效")
        boundary = "vend-" + secrets.token_hex(24)
        prefix = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"fileType\"\r\n\r\n{FILE_TYPE}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            "Content-Type: application/vnd.ms-excel\r\n\r\n"
        ).encode("utf-8")
        body = prefix + content + f"\r\n--{boundary}--\r\n".encode("ascii")
        return self._request("POST", "/api/v1/imports/upload", body, f"multipart/form-data; boundary={boundary}")


def _integer(value: Any, label: str) -> int:
    if type(value) is not int or value < 0:
        raise ImportFailure(f"vend {label}结构无效")
    return value


def _batch_id(value: Any) -> str:
    # MyBatis/Jackson deployments may serialize 64-bit identifiers as text.
    if isinstance(value, bool) or not isinstance(value, (str, int)) or not re.fullmatch(r"[1-9]\d*", str(value)):
        raise ImportFailure("vend 批次编号结构无效")
    return str(value)


def _find_batch(client: VendClient, file_name: str) -> dict | None:
    current, size, total, seen, matches = 1, 100, None, set(), []
    while True:
        page = client.get_json(BATCHES_PATH, {"current": current, "size": size, "fileType": FILE_TYPE})
        if not isinstance(page, dict) or not isinstance(page.get("records"), list):
            raise ImportFailure("vend 批次分页结构无效")
        returned_total = _integer(page.get("total"), "批次总数")
        pages = _integer(page.get("pages"), "批次页数")
        if (_integer(page.get("current"), "当前页") != current
                or _integer(page.get("size"), "分页大小") != size
                or pages != (returned_total + size - 1) // size or pages > 1000
                or (total is not None and returned_total != total)):
            raise ImportFailure("vend 批次分页不完整或读取期间发生变化")
        total = returned_total
        if len(page["records"]) != min(size, max(0, total - (current - 1) * size)):
            raise ImportFailure("vend 批次分页记录不完整")
        for record in page["records"]:
            if not isinstance(record, dict) or record.get("fileType") != FILE_TYPE or not isinstance(record.get("fileName"), str):
                raise ImportFailure("vend 批次记录结构无效")
            ident = _batch_id(record.get("id"))
            if ident in seen:
                raise ImportFailure("vend 批次分页存在重复记录")
            seen.add(ident)
            if record["fileName"] == file_name:
                matches.append(record)
        if current >= max(1, pages):
            break
        current += 1
    if len(seen) != total or len(matches) > 1:
        raise ImportFailure("vend 批次唯一性核验失败")
    return matches[0] if matches else None


def _save_state(path: Path, state: dict) -> None:
    state["updatedAt"] = dt.datetime.now(ZoneInfo("Asia/Shanghai")).isoformat()
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, prefix=".vend-state-", delete=False) as stream:
            temporary = Path(stream.name)
            os.fchmod(stream.fileno(), 0o600)
            json.dump(state, stream, ensure_ascii=False, allow_nan=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _load_state(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ImportFailure("vend 本地导入状态无法读取，已停止自动导入") from None
    if (not isinstance(state, dict) or state.get("version") != 1
            or not isinstance(state.get("status"), str) or state["status"] not in KNOWN_STATES):
        raise ImportFailure("vend 本地导入状态结构无效")
    os.chmod(path, 0o600)
    return state


def _commit_evidence(response: Any, expected_rows: int) -> dict:
    if not isinstance(response, dict) or response.get("fileType") != FILE_TYPE:
        raise ImportFailure("vend 确认响应类型无效")
    result = {"batchId": _batch_id(response.get("batchId"))}
    for field in ("rowTotal", "rowOk", "rowFail", "rowDup", "pendingBind", "priceChangeCount"):
        result[field] = _integer(response.get(field), "确认统计")
    if (result["rowTotal"] != expected_rows or result["rowOk"] + result["rowFail"] + result["rowDup"] != expected_rows
            or result["pendingBind"] > expected_rows):
        raise ImportFailure("vend 确认响应行数不一致")
    return result


def _summary(batch: dict, expected_rows: int, evidence: dict | None, reused: bool) -> dict:
    ident = _batch_id(batch.get("id"))
    if batch.get("batchStatus") == "处理中":
        raise ImportUncertain("vend 批次仍在处理中，已停止重复确认")
    if batch.get("batchStatus") != "已导入":
        raise ImportFailure("vend 同文件批次不是已导入状态，已停止自动处理")
    result = {"batchId": ident, "batchStatus": "已导入", "reused": reused}
    for field in ("rowTotal", "rowOk", "rowFail", "rowDup"):
        result[field] = _integer(batch.get(field), "批次统计")
    if evidence is not None:
        evidence = _commit_evidence({**evidence, "fileType": FILE_TYPE}, expected_rows)
        if evidence["batchId"] != ident or any(result[key] != evidence[key] for key in ("rowTotal", "rowOk", "rowFail", "rowDup")):
            raise ImportUncertain("vend 已存批次与确认响应不一致，需核查")
    result["pendingBind"] = evidence["pendingBind"] if evidence is not None else None
    result["priceChangeCount"] = evidence["priceChangeCount"] if evidence is not None else None
    if result["rowFail"] != 0 or result["rowTotal"] != expected_rows or result["rowOk"] + result["rowDup"] != expected_rows:
        result.update(status="needs_attention", reason="partial_import")
    elif result["pendingBind"] is None:
        result.update(status="needs_attention", reason="pending_binding_count_unknown")
    elif result["pendingBind"] > 0:
        result.update(status="needs_attention", reason="pending_bindings")
    else:
        result["status"] = "reused" if reused else "imported"
    return result


def import_sales(client: VendClient, file_path: Path, query_date: str,
                 expected_rows: int, state_dir: Path) -> dict:
    """Validate, preview and confirm exactly once, recovering only from durable batches."""
    if not isinstance(query_date, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", query_date):
        raise ImportFailure("销售查询日期格式无效")
    try:
        day = dt.date.fromisoformat(query_date)
    except ValueError:
        raise ImportFailure("销售查询日期无效") from None
    expected_rows = _integer(expected_rows, "网页查询条数")
    path = Path(file_path)
    try:
        content_digest = hashlib.sha256(path.read_bytes()).hexdigest()
        timezone = ZoneInfo("Asia/Shanghai")
        verified = verify_sales_file(path, dt.datetime.combine(day, dt.time.min, timezone),
                                     dt.datetime.combine(day, dt.time(23, 59, 59), timezone), expected_rows)
        if hashlib.sha256(path.read_bytes()).hexdigest() != content_digest:
            raise ImportFailure("销售文件在核验期间发生变化")
    except (OSError, SalesValidationError):
        raise ImportFailure("销售原始文件未通过日期、格式及行数核验") from None
    filename = f"fanmaiji-sales-{query_date}-{content_digest[:16]}.xls"
    directory = Path(state_dir)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    state_path = directory / f"vend-sales-{query_date}.json"
    lock_path = directory / f"vend-sales-{query_date}.lock"
    with lock_path.open("a+") as lock:
        os.fchmod(lock.fileno(), 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ImportFailure("同日销售导入正在运行") from None
        state = _load_state(state_path)
        if state is not None and (state.get("sha256") != content_digest or state.get("queryDate") != query_date
                                  or state.get("fileName") != filename or state.get("expectedRows") != expected_rows):
            raise ImportFailure("同日文件摘要或行数已变化，已停止自动导入以免产生重复明细")
        if state is None:
            state = {"version": 1, "status": "validated", "queryDate": query_date,
                     "sha256": content_digest, "fileName": filename, "expectedRows": expected_rows,
                     "verified": verified}
            _save_state(state_path, state)
        base = {"queryDate": query_date, "fileName": filename, "sha256": content_digest, "verified": verified}
        if expected_rows == 0:
            summary = dict(base, status="no_data", batchId=None, rowTotal=0, rowOk=0, rowFail=0, rowDup=0, pendingBind=0, priceChangeCount=0)
            state.update(status="no_data", summary=summary)
            _save_state(state_path, state)
            return summary
        batch = _find_batch(client, filename)
        if batch is not None:
            try:
                summary = dict(base, **_summary(batch, expected_rows, state.get("commitEvidence"), reused=True))
            except ImportFailure:
                state["status"] = "uncertain"
                _save_state(state_path, state)
                raise
            state.update(status=summary["status"], summary=summary, batchId=summary["batchId"])
            _save_state(state_path, state)
            return summary
        if state["status"] in UNCERTAIN_STATES:
            state["status"] = "uncertain"
            _save_state(state_path, state)
            raise ImportUncertain("之前的确认可能已提交，但未找到对应批次；已停止重复上传或确认")
        state["status"] = "uploading"
        _save_state(state_path, state)
        preview = client.upload_sales(path, filename, expected_digest=content_digest)
        if (not isinstance(preview, dict) or preview.get("columnsOk") is not True
                or type(preview.get("rowTotal")) is not int or preview["rowTotal"] != expected_rows
                or preview.get("fileType") != FILE_TYPE or preview.get("fileName") != filename
                or not isinstance(preview.get("token"), str) or not preview["token"]):
            state["status"] = "preview_rejected"
            _save_state(state_path, state)
            raise ImportFailure("vend 上传预览的列、类型、文件名或行数不一致，未确认导入")
        # The upload token is never persisted. Commit intent must survive a crash before HTTP.
        state["status"] = "confirming"
        _save_state(state_path, state)
        try:
            response = client.post_json("/api/v1/imports/confirm", {"token": preview["token"]})
            state["commitEvidence"] = _commit_evidence(response, expected_rows)
            _save_state(state_path, state)
            batch = _find_batch(client, filename)
            if batch is None:
                raise ImportUncertain("确认后未查到已存批次")
            summary = dict(base, **_summary(batch, expected_rows, state["commitEvidence"], reused=False))
        except Exception:
            # Even application errors can arrive after a successful commit. Never auto-reconfirm.
            state["status"] = "uncertain"
            _save_state(state_path, state)
            raise ImportUncertain("vend 确认结果尚不确定；下次只核查对应批次，不重复确认") from None
        state.update(status=summary["status"], summary=summary, batchId=summary["batchId"])
        _save_state(state_path, state)
        return summary
