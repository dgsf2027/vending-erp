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
import json
import logging
import os
import re
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator, Sequence
from urllib.parse import urljoin
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
    _validate_login(config.get("login") or {})


def _apply_date_range(page: Any, spec: dict[str, Any], date_range: DateRange) -> None:
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
        end.fill(end_value)
        end.press("Tab")
    except Exception as exc:
        raise ExportError("日期控件填写失败，拒绝导出") from exc
    apply_selectors = filters.get("apply_selectors", filters.get("apply_selector"))
    _click_first(page, apply_selectors)
    _wait_network_idle(page)
    try:
        if start.input_value() != start_value or end.input_value() != end_value:
            raise ExportError("查询后的日期值与请求不一致，拒绝导出")
    except ExportError:
        raise
    except Exception as exc:
        raise ExportError("无法确认日期控件值，拒绝导出") from exc


def _open_export(page: Any, spec: dict[str, Any]) -> None:
    menu_selectors = spec.get("menu_selectors", spec.get("menu_text"))
    _click_first(page, menu_selectors)
    _wait_network_idle(page)


def _save_download(download: Any, target_dir: Path, stem: str) -> Path:
    """Validate a private temporary file and atomically publish without overwrite."""

    suffix = Path(download.suggested_filename or "").suffix.lower()
    if suffix not in {".xlsx", ".xls", ".csv"}:
        raise ExportError("下载文件类型异常，拒绝保存为销售报表")
    with tempfile.NamedTemporaryFile(prefix=".download-", suffix=".part", dir=target_dir, delete=False) as tmp:
        temporary = Path(tmp.name)
    try:
        download.save_as(temporary)
        if temporary.stat().st_size == 0:
            raise ExportError("下载文件为空，拒绝发布")
        with temporary.open("rb") as stream:
            header = stream.read(1024).replace(b"\x00", b"").lstrip(b"\xef\xbb\xbf\xff\xfe\x20\t\r\n").lower()
        if b"<html" in header or b"<!doctype html" in header or header.startswith(b"<script"):
            raise ExportError("下载内容是 HTML 页面，拒绝将登录页或错误页当作报表")
        if suffix == ".xlsx":
            try:
                with zipfile.ZipFile(temporary) as workbook:
                    if "xl/workbook.xml" not in workbook.namelist():
                        raise ExportError("下载文件不是有效的 XLSX 工作簿")
            except zipfile.BadZipFile as exc:
                raise ExportError("下载文件不是有效的 XLSX 工作簿") from exc
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


def _download_one(page: Any, spec: dict[str, Any], etype: str, target_dir: Path, date_range: DateRange | None) -> Path:
    if spec.get("verified") is not True:
        raise ExportError(f"{etype} 导出控件尚未联真核实，拒绝执行")
    mode = str(spec.get("date_range_param", "none")).lower()
    if mode not in {"none", ""}:
        if date_range is None:
            raise ExportError(f"{etype} 需要日期范围")
        _validate_date_filters(spec)
    _open_export(page, spec)
    if mode not in {"none", ""}:
        _apply_date_range(page, spec, date_range)
    export_selectors = spec.get("export_button_selectors", spec.get("export_button_text"))
    try:
        with page.expect_download(timeout=DEFAULT_TIMEOUT_MS) as download_info:
            _click_first(page, export_selectors)
        download = download_info.value
        label = f"-{date_range.label}" if date_range is not None and mode not in {"none", ""} else ""
        return _save_download(download, target_dir, f"{etype}{label}")
    except ExportError:
        raise
    except Exception as exc:
        raise ExportError(f"{etype} 导出失败或页面未返回下载文件") from exc


def run(
    export_types: Sequence[str],
    month_arg: str | None = None,
    probe: bool = False,
    date_range: DateRange | None = None,
    log_file: str | None = None,
    lock_file: str | None = None,
) -> list[Path]:
    """Run one or more read-only downloads and return saved paths."""

    cfg = _load_config()
    if not probe:
        validate_config(cfg, export_types)
    env_path = _resolve_path(os.getenv("FANMAIJI_ENV_FILE", ""), BASE / ".env")
    if load_dotenv is not None:
        load_dotenv(env_path)
    elif env_path.is_file():
        raise ExportError("缺少 python-dotenv，无法加载独立凭据文件")
    configure_logging(log_file or os.getenv("FANMAIJI_LOG_FILE"), os.getenv("FANMAIJI_LOG_LEVEL"))
    if date_range is None:
        date_range = resolve_date_range(month_value=month_arg)
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
            browser = None
            try:
                browser = pw.chromium.launch(headless=not probe)
                context = browser.new_context(accept_downloads=True, timezone_id="Asia/Shanghai")
                page = context.new_page()
                login = cfg.get("login") or {}
                if probe:
                    login_path = str(login.get("url_path", "/login"))
                    page.goto(urljoin(base_url, login_path.lstrip("/")), wait_until="domcontentloaded", timeout=DEFAULT_TIMEOUT_MS)
                    LOGGER.info("probe 模式：请人工登录并核对 config.yaml；按 Ctrl+C 结束")
                    page.wait_for_timeout(600_000)
                    return downloaded
                _login(page, base_url, login, user, password)
                exports = cfg.get("exports") or {}
                for etype in export_types:
                    spec = exports.get(etype)
                    if not isinstance(spec, dict):
                        raise ExportError(f"配置中不存在导出类型: {etype}")
                    target = _download_one(page, spec, etype, out_dir, date_range)
                    downloaded.append(target)
                    LOGGER.info("已下载: %s", target)
            finally:
                if browser is not None:
                    browser.close()
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
