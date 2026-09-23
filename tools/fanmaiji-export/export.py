#!/usr/bin/env python3
"""Download read-only reports from the fanmaiji.top management console.

The exporter deliberately stops at downloading files. It never uploads a file
to the ERP and it never changes data in the vendor console. Credentials are
read from ``.env`` at runtime and are not included in logs, filenames, or
configuration files.

The site selectors are kept in ``config.yaml`` because the vendor can change
the page markup. Each selector may be a string or a list; lists are tried in
order, which makes a small selector change less likely to break a scheduled
run.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import fcntl
import ipaddress
import json
import logging
import os
import re
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterator, Sequence
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from zoneinfo import ZoneInfo

try:  # Keep date/config helpers importable for tests without optional packages.
    import yaml
except ImportError:  # pragma: no cover - exercised by the dependency check
    yaml = None  # type: ignore[assignment]

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover - exercised by the dependency check
    load_dotenv = None  # type: ignore[assignment]


BASE = Path(__file__).resolve().parent
LOGGER = logging.getLogger("fanmaiji-export")
DEFAULT_TIMEOUT_MS = 30_000
REPORT_TIMEZONE = ZoneInfo("Asia/Shanghai")
TASK_SUBMIT_PATH = "/standalone/export/task/submit"
TASK_RECORDS_PATH = "/runspace_pc/recordCenter/exporterUserTask"
TASK_FILTER_SELECTOR = "input[placeholder='导出记录编码']"
TASK_ROWS_SELECTOR = ".el-table__body-wrapper tr"
TASK_POLL_SECONDS = 120
XLS_MAGIC = bytes.fromhex("d0cf11e0a1b11ae1")


class ExportError(RuntimeError):
    """A user-actionable export failure."""


class AlreadyRunningError(ExportError):
    """Another exporter process owns the lock."""


@dataclass(frozen=True)
class DateRange:
    """An inclusive calendar date range used by a report query."""

    start: dt.date
    end: dt.date

    def __post_init__(self) -> None:
        if self.end < self.start:
            raise ValueError("结束日期不能早于开始日期")

    @property
    def label(self) -> str:
        if self.start == self.end:
            return self.start.isoformat()
        return f"{self.start.isoformat()}_{self.end.isoformat()}"

    @property
    def start_at(self) -> dt.datetime:
        return dt.datetime.combine(self.start, dt.time.min, REPORT_TIMEZONE)

    @property
    def end_at(self) -> dt.datetime:
        return dt.datetime.combine(self.end, dt.time(23, 59, 59), REPORT_TIMEZONE)


def parse_date(value: str) -> dt.date:
    """Parse an ISO calendar date and produce a useful CLI error."""

    try:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise ValueError("invalid date format")
        return dt.date.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"日期必须是 YYYY-MM-DD: {value!r}") from exc


def month_bounds(value: str) -> DateRange:
    """Return the first and last day for a ``YYYY-MM`` month."""

    try:
        first = dt.date.fromisoformat(f"{value}-01")
    except (TypeError, ValueError) as exc:
        raise ValueError(f"月份必须是 YYYY-MM: {value!r}") from exc
    if first.strftime("%Y-%m") != value:
        raise ValueError(f"月份必须是 YYYY-MM: {value!r}")
    next_month = (first.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
    return DateRange(first, next_month - dt.timedelta(days=1))


def resolve_date_range(
    *,
    date_value: str | None = None,
    start_value: str | None = None,
    end_value: str | None = None,
    month_value: str | None = None,
    today: dt.date | None = None,
) -> DateRange:
    """Resolve CLI date options, defaulting to yesterday.

    ``--month`` is retained for older operators and is useful for a missed
    backfill. ``--date`` is the normal scheduled mode, and ``--start-date`` /
    ``--end-date`` allow an explicit inclusive range.
    """

    supplied = sum(value is not None for value in (date_value, month_value))
    if supplied > 1:
        raise ValueError("--date 与 --month 不能同时使用")
    if date_value is not None and (start_value is not None or end_value is not None):
        raise ValueError("--date 不能与 --start-date/--end-date 同时使用")
    if month_value is not None and (start_value is not None or end_value is not None):
        raise ValueError("--month 不能与 --start-date/--end-date 同时使用")
    if date_value is not None:
        day = parse_date(date_value)
        return DateRange(day, day)
    if month_value is not None:
        return month_bounds(month_value)
    if end_value is not None and start_value is None:
        raise ValueError("--end-date 必须与 --start-date 一起使用")
    if start_value is not None:
        start = parse_date(start_value)
        end = parse_date(end_value) if end_value else start
        return DateRange(start, end)
    yesterday = (today or dt.datetime.now(REPORT_TIMEZONE).date()) - dt.timedelta(days=1)
    return DateRange(yesterday, yesterday)


def build_plan(export_types: Sequence[str], date_range: DateRange) -> dict[str, Any]:
    """Describe the date window without loading credentials or contacting a site."""

    return {
        "mode": "plan",
        "timezone": "Asia/Shanghai",
        "start": date_range.start_at.isoformat(),
        "end": date_range.end_at.isoformat(),
        "types": list(export_types),
        "filename_label": date_range.label,
        "site_validation": "not_performed",
        "note": "仅展示日期计划；没有验证登录、日期筛选或导出能力。",
    }


def _as_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        return [str(item) for item in value if item is not None and str(item)]
    return [str(value)]


def _load_config() -> dict[str, Any]:
    if yaml is None:
        raise ExportError(
            "缺少依赖 PyYAML，请执行: pip3 install playwright pyyaml python-dotenv"
        )
    path = BASE / "config.yaml"
    try:
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise ExportError(f"无法读取有效配置文件: {path}") from exc
    if not isinstance(config, dict):
        raise ExportError(f"配置文件格式无效: {path}")
    return config


def env_or_die(key: str) -> str:
    val = os.getenv(key, "").strip()
    if not val or "待老板填" in val:
        raise ExportError(f"{key} 未配置: 请复制 .env.example 为 .env 并填入真实值(见 README)")
    return val


def _resolve_path(value: str, default: Path) -> Path:
    path = Path(value).expanduser() if value else default
    return path if path.is_absolute() else BASE / path


def configure_logging(log_file: str | None = None, level: str | None = None) -> None:
    """Configure console logging and an optional operator-selected log file."""

    LOGGER.setLevel(getattr(logging, (level or "INFO").upper(), logging.INFO))
    for handler in LOGGER.handlers[:]:
        handler.close()
        LOGGER.removeHandler(handler)
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%Y-%m-%d %H:%M:%S")
    stream = logging.StreamHandler()
    stream.setFormatter(formatter)
    LOGGER.addHandler(stream)
    if log_file:
        path = _resolve_path(log_file, BASE / "export.log")
        path.parent.mkdir(parents=True, exist_ok=True)
        handler = logging.FileHandler(path, encoding="utf-8")
        handler.setFormatter(formatter)
        LOGGER.addHandler(handler)


@contextlib.contextmanager
def export_lock(path: Path) -> Iterator[None]:
    """Hold an advisory process lock for the whole browser run.

    ``flock`` releases the lock automatically if a process is killed, so a
    stale lock file never blocks the next scheduled run. The file itself is
    mode 0600 and contains no credentials or other data.
    """

    path = path.expanduser()
    if not path.is_absolute():
        path = BASE / path
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise AlreadyRunningError(f"已有另一个导出任务运行中，锁文件: {path}") from exc
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def _locator(page: Any, selector: str) -> Any:
    """Build a Playwright locator from CSS or an exact text selector."""

    if selector.startswith("text="):
        return page.get_by_text(selector[5:], exact=True)
    return page.locator(selector)


def _first_locator(page: Any, selectors: Any, *, required: bool = True) -> Any | None:
    """Return the first visible matching selector, trying configured fallbacks."""

    for selector in _as_list(selectors):
        try:
            candidate = _locator(page, selector)
            count = candidate.count()
            visible = []
            for index in range(count):
                item = candidate.nth(index)
                if item.is_visible():
                    visible.append(item)
            if len(visible) > 1:
                raise ExportError(f"选择器匹配多个可见控件，拒绝猜测: {selector}")
            if visible:
                return visible[0]
        except ExportError:
            raise
        except Exception:  # Do not log Playwright exception text: it may contain input values.
            LOGGER.debug("选择器不可用 %r", selector)
    if required:
        tried = ", ".join(_as_list(selectors)) or "(未配置)"
        raise ExportError(f"页面上找不到可见控件，已尝试: {tried}")
    return None


def _fill_first(page: Any, selectors: Any, value: str) -> None:
    locator = _first_locator(page, selectors)
    assert locator is not None
    try:
        locator.fill(value)
    except Exception as exc:
        raise ExportError("填写页面控件失败，请检查已核实的选择器") from exc


def _wait_first_locator(page: Any, selectors: Any) -> Any | None:
    """Wait for an asynchronous page's explicit marker, without guessing a body state."""

    choices = _as_list(selectors)
    for selector in choices:
        try:
            _locator(page, selector).first.wait_for(
                state="visible", timeout=max(1, DEFAULT_TIMEOUT_MS // len(choices))
            )
        except Exception:
            continue
        result = _first_locator(page, [selector], required=False)
        if result is not None:
            return result
    return None


def _click_first(page: Any, selectors: Any) -> Any:
    locator = _first_locator(page, selectors)
    assert locator is not None
    try:
        locator.click()
    except Exception as exc:
        raise ExportError("点击页面控件失败，请检查已核实的选择器") from exc
    return locator


def _wait_network_idle(page: Any, timeout: int = DEFAULT_TIMEOUT_MS) -> None:
    try:
        page.wait_for_load_state("networkidle", timeout=timeout)
    except Exception:
        # Some SPAs keep a websocket open forever. DOM content is still usable;
        # timeout here is therefore not itself a failed export.
        LOGGER.debug("页面未在超时内进入 networkidle，继续处理")


def _login(page: Any, base_url: str, login: dict[str, Any], user: str, password: str) -> None:
    _validate_login(login)
    login_path = str(login.get("url_path", "/login"))
    page.goto(urljoin(base_url.rstrip("/") + "/", login_path.lstrip("/")), wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
    _fill_first(page, login.get("user_selectors", login.get("user_selector")), user)
    _fill_first(page, login.get("pass_selectors", login.get("pass_selector")), password)
    _click_first(page, login.get("submit_selectors", login.get("submit_selector")))
    _wait_network_idle(page)
    page.wait_for_timeout(300)

    error = _first_locator(page, login.get("error_selectors", [
        ".ant-message-error", ".el-message--error", ".layui-layer-content", "[role='alert']"
    ]), required=False)
    if error is not None:
        raise ExportError("后台显示登录错误，请人工检查；原始错误内容不写入日志")
    captcha = _first_locator(page, login.get("captcha_selectors", [
        "input[name*='captcha' i]", "input[placeholder*='验证码']", ".captcha"
    ]), required=False)
    if captcha is not None:
        raise ExportError("后台要求验证码，无法进行无人值守登录；请先完成联真或配置可用的登录方式")
    # Absence of the login form alone does not prove successful authentication.
    success = _first_locator(page, login.get("success_selectors", []), required=False)
    if success is None:
        raise ExportError("未找到已核实的登录成功标记，拒绝继续导出")
    LOGGER.info("后台登录成功")


def _format_date(value: dt.datetime, spec: dict[str, Any]) -> str:
    date_format = str(spec.get("date_format", "%Y-%m-%d %H:%M:%S"))
    try:
        return value.strftime(date_format)
    except (TypeError, ValueError) as exc:
        raise ExportError(f"配置中的日期格式无效: {date_format}") from exc


def _validate_login(login: dict[str, Any]) -> None:
    if login.get("verified") is not True:
        raise ExportError("登录配置尚未联真核实，拒绝执行；请先完成登录与成功标记验证")
    for key in ("user_selectors", "pass_selectors", "submit_selectors", "success_selectors"):
        if not _as_list(login.get(key)):
            raise ExportError(f"已核实登录配置缺少 {key}，拒绝执行")


def _browser_options(config: dict[str, Any]) -> dict[str, Any]:
    options = config.get("browser") or {}
    if not isinstance(options, dict) or options.get("mode", "fresh") not in {"fresh", "cdp"}:
        raise ExportError("browser.mode 必须是 fresh 或 cdp")
    return options


def validate_cdp_url(value: Any) -> str:
    """Allow only an existing loopback HTTP debugger endpoint, never remote CDP."""

    try:
        parsed = urlsplit(value if isinstance(value, str) else "")
        hostname = parsed.hostname or ""
        loopback = hostname == "localhost"
        if not loopback:
            try:
                loopback = ipaddress.ip_address(hostname).is_loopback
            except ValueError:
                loopback = False
        if (parsed.scheme != "http" or not loopback or parsed.username is not None
                or parsed.password is not None or parsed.path not in {"", "/"}
                or parsed.query or parsed.fragment or parsed.port is None or parsed.port < 1):
            raise ValueError("invalid endpoint")
    except (TypeError, ValueError) as exc:
        raise ExportError("CDP 地址必须为带端口的本机回环 HTTP 地址，例如 http://127.0.0.1:9222") from exc
    return value


def _cdp_start_url(options: dict[str, Any]) -> str:
    base = str(options.get("base_url", "https://fanmaiji.top"))
    start = str(options.get("start_url", "/index"))
    base_parts, start_parts = urlsplit(base), urlsplit(start)
    if (base_parts.scheme not in {"http", "https"} or not base_parts.hostname
            or base_parts.username is not None or base_parts.password is not None
            or start_parts.scheme or start_parts.netloc or not start.startswith("/")
            or start_parts.path.rstrip("/").lower() in {"/login", "/logout"}):
        raise ExportError("CDP 起始页必须是当前业务站点的会话页面，不能是登录或退出页")
    return urljoin(base.rstrip("/") + "/", start)


def _validate_browser(config: dict[str, Any]) -> None:
    options = _browser_options(config)
    if options.get("mode", "fresh") == "fresh":
        _validate_login(config.get("login") or {})
    else:
        validate_cdp_url(options.get("cdp_url"))
        _cdp_start_url(options)
        if not _as_list(options.get("session_success_selectors")):
            raise ExportError("CDP 模式必须配置已核实的 session_success_selectors")


def _query_response_matches(
    response: Any, path: str, start_value: str | None = None, end_value: str | None = None,
) -> bool:
    parts = urlsplit(response.url)
    if parts.path != path or response.request.method != "GET":
        return False
    if start_value is None and end_value is None:
        return True
    params = parse_qs(parts.query)
    return params.get("startTime") == [start_value] and params.get("endTime") == [end_value]


def _query_response_total(response: Any) -> int:
    try:
        payload = response.json()  # Wait for the complete body, not just response headers.
        if not response.ok or not isinstance(payload, dict) or payload.get("code") != 200 or payload.get("status") is not True:
            raise ValueError("unsuccessful query")
        data = payload.get("data")
        total = data.get("total") if isinstance(data, dict) else None
        if isinstance(total, bool) or not isinstance(total, int) or total < 0:
            raise ValueError("invalid total")
        return total
    except Exception as exc:
        raise ExportError("销售查询响应未成功或缺少有效总条数，拒绝导出") from exc


def _wait_pagination_total(page: Any, total: int) -> None:
    try:
        page.wait_for_function(
            r"""expected => {
                const pages = document.querySelectorAll('.el-pagination');
                if (pages.length !== 1) return false;
                const match = pages[0].innerText.match(/共\s*(\d+)\s*条/);
                return match !== null && Number(match[1]) === expected;
            }""",
            arg=total, timeout=DEFAULT_TIMEOUT_MS,
        )
    except Exception as exc:
        raise ExportError("页面分页条数未更新到本次查询响应，拒绝导出旧数据") from exc


class _OwnedPages:
    """Track only the dedicated page and popups emitted by that page tree."""

    def __init__(self, root_page: Any) -> None:
        self.root_page = root_page
        self.pages: list[Any] = []
        self.download_handlers: dict[int, Callable[[Any], None]] = {}
        root_page._fanmaiji_owned_pages = self
        self.track(root_page)

    def track(self, page: Any) -> None:
        if any(existing is page for existing in self.pages):
            return
        self.pages.append(page)
        page.on("popup", self.track)

        def log_download(_download: Any) -> None:
            kind = "专用页" if page is self.root_page else "派生弹出页"
            LOGGER.info("下载事件发生于本次%s", kind)

        self.download_handlers[id(page)] = log_download
        page.on("download", log_download)
        if page is not self.root_page:
            LOGGER.info("本次专用页派生弹出页已跟踪，派生页数量=%d", len(self.pages) - 1)

    def known_download_error(self) -> str | None:
        """Inspect only our own download popups for the observed rate-limit JSON."""

        for page in self.pages:
            if page is self.root_page:
                continue
            try:
                parts = urlsplit(page.url)
                if parts.netloc != urlsplit(self.root_page.url).netloc or not re.fullmatch(r"/standalone/export/task/download/[0-9]+", parts.path):
                    continue
                payload = json.loads(page.locator("body").inner_text(timeout=1000))
                if (isinstance(payload, dict) and payload.get("code") == 999999
                        and payload.get("status") is False
                        and payload.get("message") == "请勿短时间内重复下载，请稍后再试"):
                    return "厂家下载限频：请勿短时间内重复下载，请稍后再试；本次未自动重试"
            except Exception:
                continue
        return None

    def close(self) -> None:
        # Newest descendants close before the root. No context-wide page
        # snapshot/diff is used, so user-created or pre-existing tabs are never
        # mistaken for pages owned by this export.
        while self.pages:
            page = self.pages.pop()
            try:
                page.remove_listener("popup", self.track)
                page.remove_listener("download", self.download_handlers.pop(id(page)))
            except Exception:
                LOGGER.debug("本次页面事件监听清理未完成")
            try:
                page.close()
            except Exception:
                LOGGER.warning("本次专用页或派生弹出页无法关闭；不操作其他浏览器页面")
        self.root_page._fanmaiji_owned_pages = None


@contextlib.contextmanager
def _browser_page(pw: Any, options: dict[str, Any], *, probe: bool = False) -> Iterator[Any]:
    """Own only a new page when attached to the user's existing Chrome."""

    if options.get("mode", "fresh") == "cdp":
        endpoint = validate_cdp_url(options.get("cdp_url"))
        start_url = _cdp_start_url(options)
        browser = pw.chromium.connect_over_cdp(endpoint, timeout=DEFAULT_TIMEOUT_MS)
        contexts = browser.contexts
        origin = urlsplit(start_url).netloc
        matching = [context for context in contexts if any(
            urlsplit(existing.url).netloc == origin for existing in context.pages
        )]
        if len(matching) == 1:
            context = matching[0]
        elif len(contexts) == 1:
            context = contexts[0]
        else:
            raise ExportError("无法唯一识别现有 Chrome 的业务会话，请人工检查；不会创建新 context")
        owned_page = None
        owned_pages = None
        try:
            owned_page = context.new_page()
            owned_pages = _OwnedPages(owned_page)
            initial_path = options.get("initial_response_path")
            if initial_path:
                try:
                    with owned_page.expect_response(
                        lambda response: _query_response_matches(response, initial_path),
                        timeout=DEFAULT_TIMEOUT_MS,
                    ) as initial_query:
                        owned_page.goto(start_url, wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
                    initial_total = _query_response_total(initial_query.value)
                    _wait_pagination_total(owned_page, initial_total)
                except Exception as exc:
                    raise ExportError("A 机现有 Chrome 会话可能已过期或初始销售查询未成功，请检查现有登录会话") from exc
            else:
                owned_page.goto(start_url, wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
            if _wait_first_locator(owned_page, options.get("session_success_selectors")) is None:
                raise ExportError("A 机现有 Chrome 会话已过期或未登录；请在 A 机 Chrome 恢复登录后重试")
            LOGGER.info("复用现有 Chrome 已登录会话")
            yield owned_page
        finally:
            if owned_pages is not None:
                owned_pages.close()
            elif owned_page is not None:
                try:
                    owned_page.close()
                except Exception:
                    LOGGER.warning("本次专用标签页无法关闭；现有浏览器和其他标签页保持不动")
        # Exiting sync_playwright disconnects the client. Never close the CDP
        # browser or context: both belong to the user's running Chrome.
        return
    browser = pw.chromium.launch(headless=not probe)
    try:
        context = browser.new_context(accept_downloads=True, timezone_id="Asia/Shanghai")
        yield context.new_page()
    finally:
        browser.close()


def _validate_date_filters(spec: dict[str, Any]) -> None:
    filters = spec.get("date_filters")
    if not isinstance(filters, dict) or filters.get("verified") is not True:
        raise ExportError("日期筛选控件尚未联真核实，拒绝执行或导出默认日期的数据")
    for key in ("start_selectors", "end_selectors", "apply_selectors"):
        if not _as_list(filters.get(key)):
            raise ExportError(f"已核实日期配置缺少 {key}，拒绝执行")


def validate_config(config: dict[str, Any], export_types: Sequence[str]) -> None:
    """Fail before credentials/browser setup when any required page is unverified."""

    exports = config.get("exports") or {}
    for etype in export_types:
        spec = exports.get(etype)
        if not isinstance(spec, dict):
            raise ExportError(f"配置中不存在导出类型: {etype}")
        if etype in {"sales", "replenish"}:
            if spec.get("date_range_param") not in {"day", "range", "month"}:
                raise ExportError(f"{etype} 缺少日期筛选模式，拒绝执行")
            _validate_date_filters(spec)
        if spec.get("verified") is not True:
            raise ExportError(f"{etype} 的菜单和导出控件尚未联真核实，拒绝执行")
    _validate_browser(config)


def _apply_date_range(page: Any, spec: dict[str, Any], date_range: DateRange) -> int | None:
    _validate_date_filters(spec)
    filters = spec.get("date_filters") or {}
    if not isinstance(filters, dict):
        raise ExportError("date_filters 配置格式无效")
    start_selectors = filters.get("start_selectors", filters.get("start_selector"))
    end_selectors = filters.get("end_selectors", filters.get("end_selector"))
    start_value = _format_date(date_range.start_at, spec)
    end_value = _format_date(date_range.end_at, spec)
    start = _first_locator(page, start_selectors)
    end = _first_locator(page, end_selectors)
    try:
        start.fill(start_value)
        start.press("Tab")
        end.fill(end_value)
        end.press("Tab")
    except Exception as exc:
        raise ExportError("日期控件填写失败，拒绝导出") from exc
    apply_selectors = filters.get("apply_selectors", filters.get("apply_selector"))
    expected_rows = None
    response_path = filters.get("response_path")
    if response_path:
        try:
            with page.expect_response(
                lambda response: _query_response_matches(response, response_path, start_value, end_value),
                timeout=DEFAULT_TIMEOUT_MS,
            ) as query:
                _click_first(page, apply_selectors)
            expected_rows = _query_response_total(query.value)
            _wait_pagination_total(page, expected_rows)
        except ExportError:
            raise
        except Exception as exc:
            raise ExportError("未收到与请求日期完全匹配的销售查询响应，拒绝导出") from exc
    else:
        _click_first(page, apply_selectors)
        _wait_network_idle(page)
    try:
        if start.input_value() != start_value or end.input_value() != end_value:
            raise ExportError("查询后的日期值与请求不一致，拒绝导出")
    except ExportError:
        raise
    except Exception as exc:
        raise ExportError("无法确认日期控件值，拒绝导出") from exc
    return expected_rows


def _open_export(page: Any, spec: dict[str, Any]) -> None:
    filters = spec.get("date_filters") or {}
    ready_selectors = spec.get("ready_selectors") or filters.get("start_selectors")
    target_path = spec.get("url_path")
    if target_path and urlsplit(page.url).path == target_path:
        if ready_selectors and _wait_first_locator(page, ready_selectors) is not None:
            return
        raise ExportError("当前导出页面尚未显示已核实的控件，拒绝重复触发初始化查询")
    menu_selectors = spec.get("menu_selectors", spec.get("menu_text"))
    response_path = filters.get("response_path")
    if response_path:
        with page.expect_response(
            lambda response: _query_response_matches(response, response_path), timeout=DEFAULT_TIMEOUT_MS,
        ) as initial_query:
            _click_first(page, menu_selectors)
        _wait_pagination_total(page, _query_response_total(initial_query.value))
    else:
        _click_first(page, menu_selectors)
        _wait_network_idle(page)
    if ready_selectors and _wait_first_locator(page, ready_selectors) is None:
        raise ExportError("导出页面尚未显示已核实的控件，拒绝继续")


def _save_download(
    download: Any,
    target_dir: Path,
    stem: str,
    *,
    filename_hint: str | None = None,
    validator: Callable[[Path], Any] | None = None,
) -> Path:
    """Validate a private temporary file and atomically publish without overwrite."""

    suffix = Path(download.suggested_filename or "").suffix.lower()
    hint_suffix = Path(filename_hint).suffix.lower() if filename_hint else ""
    if not suffix:
        suffix = hint_suffix
    elif hint_suffix and suffix != hint_suffix:
        raise ExportError("下载文件类型与已核实的导出任务记录不一致")
    if suffix not in {".xlsx", ".xls", ".csv"}:
        raise ExportError("下载文件类型异常，拒绝保存为销售报表")
    with tempfile.NamedTemporaryFile(prefix=".download-", suffix=".part", dir=target_dir, delete=False) as tmp:
        temporary = Path(tmp.name)
    try:
        download.save_as(temporary)
        if temporary.stat().st_size == 0:
            raise ExportError("下载文件为空，拒绝发布")
        with temporary.open("rb") as stream:
            raw_header = stream.read(1024)
        header = raw_header.replace(b"\x00", b"").lstrip(b"\xef\xbb\xbf\xff\xfe\x20\t\r\n").lower()
        if b"<html" in header or b"<!doctype html" in header or header.startswith(b"<script"):
            raise ExportError("下载内容是 HTML 页面，拒绝将登录页或错误页当作报表")
        if suffix == ".xls" and not raw_header.startswith(XLS_MAGIC):
            raise ExportError("下载文件缺少 XLS 文件签名，拒绝发布")
        if suffix == ".xlsx":
            try:
                with zipfile.ZipFile(temporary) as workbook:
                    if "xl/workbook.xml" not in workbook.namelist():
                        raise ExportError("下载文件不是有效的 XLSX 工作簿")
            except zipfile.BadZipFile as exc:
                raise ExportError("下载文件不是有效的 XLSX 工作簿") from exc
        if validator is not None:
            try:
                if validator(temporary) is False:
                    raise ExportError("下载文件未通过数据校验，拒绝发布")
            except ExportError:
                raise
            except Exception as exc:
                raise ExportError("下载文件未通过数据校验，拒绝发布") from exc
        target = target_dir / f"{stem}{suffix}"
        while True:
            try:
                # Both files are on the same filesystem. link() exposes the
                # complete validated file atomically and refuses replacement;
                # unlike replace(), it preserves an existing original report.
                os.link(temporary, target)
                return target
            except FileExistsError:
                stamp = dt.datetime.now(REPORT_TIMEZONE).strftime("%Y%m%dT%H%M%S%f")
                target = target_dir / f"{stem}-run-{stamp}{suffix}"
    finally:
        temporary.unlink(missing_ok=True)


def _confirm_export(page: Any, spec: dict[str, Any]) -> None:
    """Confirm only the expected, explicitly configured export prompt."""

    expected = spec.get("export_confirm_text")
    selectors = spec.get("export_confirm_selectors")
    if expected is None and not selectors:
        return
    if not isinstance(expected, str) or not expected.strip() or not _as_list(selectors):
        raise ExportError("导出确认框配置不完整，拒绝自动确认")
    dialog = _wait_first_locator(page, [".el-message-box:visible"])
    if dialog is None:
        raise ExportError("未出现已核实的导出确认框，拒绝继续")
    try:
        text = "".join(dialog.inner_text().split())
    except Exception as exc:
        raise ExportError("无法核对导出确认框内容，拒绝确认") from exc
    if "".join(expected.split()) not in text:
        raise ExportError("导出确认框内容与已核实提示不符，拒绝确认未知弹窗")
    _click_first(page, selectors)


def _confirm_known_dialog(page: Any, expected_messages: Sequence[str]) -> None:
    # The request can finish before Vue replaces the previous message box.
    # Wait for the known next prompt, rather than inspecting the old prompt
    # immediately when expect_response resolves.
    expected_pattern = re.compile("|".join(re.escape(message) for message in expected_messages))
    matching = page.locator(".el-message-box:visible").filter(has_text=expected_pattern)
    try:
        matching.first.wait_for(state="visible", timeout=DEFAULT_TIMEOUT_MS)
    except Exception as exc:
        raise ExportError("预期文字的导出提示框未出现，拒绝确认未知弹窗") from exc
    dialog = _first_locator(page, [".el-message-box:visible"])
    body = "".join(dialog.inner_text().split())
    if not any("".join(message.split()) in body for message in expected_messages):
        raise ExportError("导出提示与已核实的内容不符，拒绝确认未知弹窗")
    _click_first(page, [".el-message-box:visible button:has-text('确定')"])


def _task_id_from_response(payload: Any) -> str:
    if not isinstance(payload, dict):
        raise ExportError("导出任务响应格式异常")
    task_id = str(payload.get("taskId", ""))
    if not re.fullmatch(r"\d+", task_id):
        raise ExportError("导出提交响应缺少有效 taskId，拒绝下载其他任务")
    existing = payload.get("status") is False and payload.get("desc") == "当前任务已存在,请到导出记录下载"
    if payload.get("status") is not True and not existing:
        raise ExportError("后台未确认导出任务成功或合法复用，拒绝继续")
    return task_id


def _read_task_row(page: Any, task_id: str, expected_function: str) -> dict[str, str] | None:
    task_filter = _first_locator(page, [TASK_FILTER_SELECTOR])
    if task_filter.input_value() != task_id:
        raise ExportError("导出记录筛选值不等于本次 taskId，拒绝下载")
    rows = page.locator(TASK_ROWS_SELECTOR)
    count = rows.count()
    if count == 0:
        return None
    if count != 1:
        raise ExportError("导出记录不是唯一任务，拒绝下载")
    cells = rows.nth(0).locator("td")
    if cells.count() < 8:
        raise ExportError("导出记录表结构变化，拒绝下载")
    actual_id = cells.nth(1).inner_text().strip()
    filename = cells.nth(2).inner_text().strip()
    function = cells.nth(3).inner_text().strip()
    status = cells.nth(5).inner_text().strip()
    if actual_id != task_id or function != expected_function:
        raise ExportError("导出记录的任务 ID 或所属功能与本次请求不一致，拒绝下载")
    if any(marker in status for marker in ("失败", "错误", "异常", "取消")):
        raise ExportError("后台导出任务失败，拒绝下载")
    if status != "处理成功":
        return None
    if Path(filename).suffix.lower() not in {".xls", ".xlsx", ".csv"}:
        raise ExportError("成功任务的文件名缺少有效报表后缀")
    return {"task_id": actual_id, "filename": filename, "function": function, "status": status}


def _wait_task_record(page: Any, task_id: str, expected_function: str) -> dict[str, str]:
    deadline = time.monotonic() + TASK_POLL_SECONDS
    while True:
        record = _read_task_row(page, task_id, expected_function)
        if record is not None:
            return record
        remaining_ms = int((deadline - time.monotonic()) * 1000)
        if remaining_ms <= 0:
            raise ExportError("等待导出任务超过 120 秒，任务尚未处理成功")
        page.wait_for_timeout(min(2000, remaining_ms))
        remaining_ms = int((deadline - time.monotonic()) * 1000)
        if remaining_ms <= 0:
            raise ExportError("等待导出任务超过 120 秒，任务尚未处理成功")
        query = _first_locator(page, ["button:has-text('查询'):visible"])
        query.click(timeout=min(DEFAULT_TIMEOUT_MS, remaining_ms))


def _download_async_export(page: Any, spec: dict[str, Any]) -> tuple[Any, str]:
    expected_function = spec.get("expected_function")
    if not isinstance(expected_function, str) or not expected_function.strip():
        raise ExportError("异步导出必须指定已核实的 expected_function")
    LOGGER.info("异步导出阶段: 开始等待任务提交响应")
    with page.expect_response(
        lambda response: urlsplit(response.url).path == TASK_SUBMIT_PATH and response.request.method == "POST",
        timeout=DEFAULT_TIMEOUT_MS,
    ) as submission:
        _click_first(page, spec.get("export_button_selectors", spec.get("export_button_text")))
        LOGGER.info("异步导出阶段: 已点击导出，等待首次确认提示")
        _confirm_known_dialog(page, ["确定要导出数据?"])
        LOGGER.info("异步导出阶段: 首次确认完成，等待任务提交响应")
    response = submission.value
    LOGGER.info("异步导出阶段: 已收到任务提交响应")
    if not response.ok:
        raise ExportError("导出任务提交请求失败")
    task_id = _task_id_from_response(response.json())
    LOGGER.info("异步导出阶段: 已确认 taskId=%s", task_id)
    _confirm_known_dialog(page, [
        "任务提交成功，是否到导出记录下载？",
        "本次导出已在导出记录存在，是否到导出记录下载？",
    ])
    LOGGER.info("异步导出阶段: 已确认进入导出记录")
    records_url = urljoin(response.url, TASK_RECORDS_PATH) + "?" + urlencode({"taskId": task_id})
    LOGGER.info("异步导出阶段: 开始打开任务记录页面")
    page.goto(records_url, wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
    LOGGER.info("异步导出阶段: 任务记录页面已打开，等待筛选值和表头")
    if _wait_first_locator(page, [TASK_FILTER_SELECTOR]) is None:
        raise ExportError("导出记录页面未出现已核实的任务筛选控件")
    page.wait_for_function(
        "args => document.querySelector(args.selector)?.value === args.taskId",
        arg={"selector": TASK_FILTER_SELECTOR, "taskId": task_id},
        timeout=DEFAULT_TIMEOUT_MS,
    )
    if _wait_first_locator(page, [".el-table__header-wrapper"]) is None:
        raise ExportError("导出记录表头尚未加载，拒绝继续")
    LOGGER.info("异步导出阶段: 任务筛选已核实，等待唯一成功记录")
    record = _wait_task_record(page, task_id, expected_function)
    LOGGER.info("异步导出阶段: 已匹配唯一成功任务 taskId=%s", task_id)
    # The table's fixed right-hand action column is outside the main row.
    # A unique, matching successful task is required before using its sole
    # visible export button. Never interact with the adjacent delete action.
    LOGGER.info("异步导出阶段: 开始等待真正下载事件")
    with page.expect_download(timeout=DEFAULT_TIMEOUT_MS) as pending_download:
        _click_first(page, ['button:has-text("导出"):visible'])
        LOGGER.info("异步导出阶段: 已点击记录导出按钮，等待下载确认")
        _confirm_known_dialog(page, ["确定要下载么?"])
        LOGGER.info("异步导出阶段: 已确认下载，等待下载事件")
    LOGGER.info("异步导出阶段: 已取得下载事件")
    LOGGER.info("导出记录核实成功: taskId=%s", task_id)
    return pending_download.value, record["filename"]


def _report_validator(
    page: Any,
    spec: dict[str, Any],
    date_range: DateRange | None,
    extra_validator: Callable[[Path], Any] | None,
    expected_rows: int | None = None,
) -> Callable[[Path], Any] | None:
    if not spec.get("validate_sales_report"):
        return extra_validator
    if date_range is None:
        raise ExportError("销售数据校验缺少日期范围")
    pagination = _first_locator(page, [".el-pagination"])
    count_match = re.search(r"共\s*(\d+)\s*条", pagination.inner_text())
    if count_match is None:
        raise ExportError("无法读取销售查询总条数，拒绝未经数量校验的导出")
    visible_rows = int(count_match.group(1))
    if expected_rows is not None and visible_rows != expected_rows:
        raise ExportError("页面总条数已偏离本次查询响应，拒绝导出")
    expected_rows = visible_rows if expected_rows is None else expected_rows
    LOGGER.info("查询页销售明细: %d 条", expected_rows)

    def validate(path: Path) -> None:
        from verify_sales import SalesValidationError, verify_sales_file

        try:
            stats = verify_sales_file(path, date_range.start_at, date_range.end_at, expected_rows)
        except SalesValidationError as exc:
            raise ExportError(f"销售文件校验失败: {exc}") from exc
        LOGGER.info("销售文件校验摘要: %s", json.dumps(stats, ensure_ascii=False, sort_keys=True))
        if extra_validator is not None and extra_validator(path) is False:
            raise ExportError("下载文件未通过附加数据校验，拒绝发布")

    return validate


def _reuse_validated_download(
    target_dir: Path, stem: str, validator: Callable[[Path], Any] | None,
) -> Path | None:
    if validator is None:
        return None
    filename_pattern = re.compile(re.escape(stem) + r"(?:-run-[0-9]{8}T[0-9]{12})?\.xls")
    candidates = [target_dir / f"{stem}.xls", *target_dir.glob(f"{stem}-run-*.xls")]
    candidates = [path for path in candidates if path.is_file() and filename_pattern.fullmatch(path.name)]
    candidates.sort(key=lambda path: (path.stat().st_mtime_ns, path.name), reverse=True)
    for target in candidates:
        try:
            if validator(target) is False:
                raise ExportError("缓存未通过校验")
        except Exception:
            LOGGER.warning("同范围本地旧文件未通过当前校验，保留原件并检查其他版本")
            continue
        LOGGER.info("复用已校验的本地文件，不重复提交或下载: %s", target)
        return target
    return None


def _download_one(
    page: Any, spec: dict[str, Any], etype: str, target_dir: Path,
    date_range: DateRange | None, validator: Callable[[Path], Any] | None = None,
) -> Path:
    if spec.get("verified") is not True:
        raise ExportError(f"{etype} 导出控件尚未联真核实，拒绝执行")
    mode = str(spec.get("date_range_param", "none")).lower()
    if mode not in {"none", ""}:
        if date_range is None:
            raise ExportError(f"{etype} 需要日期范围")
        _validate_date_filters(spec)
    _open_export(page, spec)
    expected_rows = None
    if mode not in {"none", ""}:
        expected_rows = _apply_date_range(page, spec, date_range)
    export_selectors = spec.get("export_button_selectors", spec.get("export_button_text"))
    try:
        checked_validator = _report_validator(page, spec, date_range, validator, expected_rows=expected_rows)
        label = f"-{date_range.label}" if date_range is not None and mode not in {"none", ""} else ""
        stem = f"{etype}{label}"
        cached = _reuse_validated_download(target_dir, stem, checked_validator)
        if cached is not None:
            return cached
        filename_hint = None
        if spec.get("async_export"):
            download, filename_hint = _download_async_export(page, spec)
        else:
            with page.expect_download(timeout=DEFAULT_TIMEOUT_MS) as download_info:
                _click_first(page, export_selectors)
                _confirm_export(page, spec)
            download = download_info.value
        target = _save_download(download, target_dir, stem, filename_hint=filename_hint, validator=checked_validator)
        LOGGER.info("新下载文件已校验并保存: %s", target)
        return target
    except ExportError:
        raise
    except Exception as exc:
        owned_pages = getattr(page, "_fanmaiji_owned_pages", None)
        known_error = owned_pages.known_download_error() if isinstance(owned_pages, _OwnedPages) else None
        if known_error is not None:
            raise ExportError(known_error) from exc
        LOGGER.error("导出流程异常类型: %s", type(exc).__name__)
        raise ExportError(f"{etype} 导出失败或页面未返回下载文件") from exc


def run(
    export_types: Sequence[str],
    month_arg: str | None = None,
    probe: bool = False,
    date_range: DateRange | None = None,
    log_file: str | None = None,
    lock_file: str | None = None,
    validator: Callable[[Path], Any] | None = None,
) -> list[Path]:
    """Run one or more read-only downloads and return saved paths."""

    cfg = _load_config()
    browser_options = _browser_options(cfg)
    cdp_mode = browser_options.get("mode", "fresh") == "cdp"
    if not probe:
        validate_config(cfg, export_types)
    elif cdp_mode:
        _validate_browser(cfg)
    if not cdp_mode:
        env_path = _resolve_path(os.getenv("FANMAIJI_ENV_FILE", ""), BASE / ".env")
        if load_dotenv is not None:
            load_dotenv(env_path)
        elif env_path.is_file():
            raise ExportError("缺少 python-dotenv，无法加载独立凭据文件")
    configure_logging(log_file or os.getenv("FANMAIJI_LOG_FILE"), os.getenv("FANMAIJI_LOG_LEVEL"))
    if date_range is None:
        date_range = resolve_date_range(month_value=month_arg)
    if not cdp_mode:
        base_url = env_or_die("FANMAIJI_BASE_URL").rstrip("/") + "/"
        user = env_or_die("FANMAIJI_USER")
        password = env_or_die("FANMAIJI_PASS")
    out_root = _resolve_path(os.getenv("FANMAIJI_DOWNLOAD_DIR", ""), BASE / "downloads")
    # Use the execution date for the directory, retaining the original layout.
    out_dir = out_root / dt.datetime.now(REPORT_TIMEZONE).date().isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    lock_path = _resolve_path(lock_file or os.getenv("FANMAIJI_LOCK_FILE", ""), Path("/tmp/fanmaiji-export.lock"))
    downloaded: list[Path] = []

    try:
        from playwright.sync_api import sync_playwright
    except ImportError as exc:
        raise ExportError(
            "缺少依赖 Playwright，请执行: pip3 install playwright pyyaml python-dotenv && python3 -m playwright install chromium"
        ) from exc

    with export_lock(lock_path):
        LOGGER.info("开始导出: types=%s date_range=%s", ",".join(export_types), date_range.label)
        with sync_playwright() as pw:
            with _browser_page(pw, browser_options, probe=probe) as page:
                login = cfg.get("login") or {}
                if probe:
                    if not cdp_mode:
                        login_path = str(login.get("url_path", "/login"))
                        page.goto(urljoin(base_url, login_path.lstrip("/")), wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
                    LOGGER.info("probe 模式：请在本次专用标签页核对 config.yaml；按 Ctrl+C 结束")
                    page.wait_for_timeout(600_000)
                    return downloaded
                if not cdp_mode:
                    _login(page, base_url, login, user, password)
                exports = cfg.get("exports") or {}
                for etype in export_types:
                    spec = exports.get(etype)
                    if not isinstance(spec, dict):
                        raise ExportError(f"配置中不存在导出类型: {etype}")
                    target = _download_one(page, spec, etype, out_dir, date_range, validator=validator)
                    downloaded.append(target)
                    LOGGER.info("原始文件就绪: %s", target)
        LOGGER.info("导出完成，共 %d 个文件", len(downloaded))
    return downloaded


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="fanmaiji.top 三类数据自动下载（只下载，不自动入账）")
    types_group = parser.add_mutually_exclusive_group()
    types_group.add_argument("--type", choices=["sales", "replenish", "products"], help="单独下载一类，缺省为 sales")
    types_group.add_argument("--all", action="store_true", help="三类全下")
    date_group = parser.add_mutually_exclusive_group()
    date_group.add_argument("--date", help="下载单日 YYYY-MM-DD；缺省为昨天")
    date_group.add_argument("--month", help="兼容旧用法：下载整月 YYYY-MM")
    date_group.add_argument("--start-date", help="日期范围开始 YYYY-MM-DD（可单独使用，结束日默认为同日）")
    parser.add_argument("--end-date", help="日期范围结束 YYYY-MM-DD")
    parser.add_argument("--probe", action="store_true", help="有头模式打开登录页，用于首次核对选择器")
    parser.add_argument("--plan", action="store_true", help="只输出日期计划，无需依赖或凭据，不访问网站")
    parser.add_argument("--log-file", help="可选日志文件；默认只输出到终端")
    parser.add_argument("--lock-file", help="可选锁文件路径；默认 /tmp/fanmaiji-export.lock")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if args.end_date and not args.start_date:
        parser.error("--end-date 必须与 --start-date 一起使用")
    if args.plan and args.probe:
        parser.error("--plan 与 --probe 不能同时使用")
    try:
        date_range = resolve_date_range(
            date_value=args.date,
            start_value=args.start_date,
            end_value=args.end_date,
            month_value=args.month,
        )
        types = ["sales", "replenish", "products"] if args.all else [args.type or "sales"]
        if args.plan:
            print(json.dumps(build_plan(types, date_range), ensure_ascii=False, indent=2))
            return 0
        run(types, probe=args.probe, date_range=date_range, log_file=args.log_file, lock_file=args.lock_file)
        return 0
    except (ExportError, ValueError) as exc:
        # Error text is controlled by this script and never contains password.
        LOGGER.error("任务失败: %s", exc)
        return 2
    except KeyboardInterrupt:
        LOGGER.warning("任务被中断")
        return 130
    except Exception as exc:
        LOGGER.error("任务失败 (%s)，未完成导出；原始异常未写入日志", type(exc).__name__)
        return 2


if __name__ == "__main__":
    sys.exit(main())
