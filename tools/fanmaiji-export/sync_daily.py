#!/usr/bin/env python3
"""Download one complete sales day and import it through the ERP's normal API."""
from __future__ import annotations

import argparse
import datetime as dt
import importlib
import json
import os
from pathlib import Path
import stat
import sys
from zoneinfo import ZoneInfo

SHANGHAI = ZoneInfo("Asia/Shanghai")


def load_credentials(path: Path) -> dict[str, str]:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ValueError("vend 凭据必须是当前用户拥有的私有文件（权限 600）")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or any(not isinstance(data.get(k), str) or not data[k].strip()
                                         for k in ("base_url", "username", "password")):
        raise ValueError("vend 凭据文件缺少连接配置")
    return {key: data[key] for key in ("base_url", "username", "password")}


def report_row_count(path: Path) -> int:
    # export.run has already checked these rows against the exact vendor query.
    # Count the original workbook again to bind the ERP preview to that artifact.
    import xlrd
    book = xlrd.open_workbook(str(path), on_demand=True)
    try:
        return sum(1 for sheet in book.sheets() for index in range(1, sheet.nrows)
                   if any(value not in (None, "") for value in sheet.row_values(index)))
    finally:
        book.release_resources()


def save_result(path: Path, result: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="抓取昨日销售并导入 vend 销售记录")
    parser.add_argument("--runtime-dir", required=True, type=Path)
    parser.add_argument("--date", help="查询日期 YYYY-MM-DD，默认上海时区昨天")
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args(argv)
    exporter = importlib.import_module("export")
    date_range = exporter.resolve_date_range(date_value=args.date)
    root = args.runtime_dir.expanduser().resolve()
    if args.plan:
        print(json.dumps({"queryDate": date_range.label, "source": "A Chrome / fanmaiji.top",
                          "destination": "vend.vvaix.com/import", "fileType": "出货明细",
                          "actions": ["下载并校验原始报表", "核验批次防重", "销售导入"]}, ensure_ascii=False))
        return 0
    os.umask(0o077)
    (root / "run").mkdir(parents=True, exist_ok=True)
    result = {"queryDate": date_range.label, "runId": os.getenv("FANMAIJI_RUN_ID"),
              "startedAt": dt.datetime.now(SHANGHAI).isoformat(), "status": "starting"}
    result_path = root / "run" / "last-sync.json"
    save_result(result_path, result)
    stage = "vend 登录"
    try:
        from import_vend import VendClient, import_sales
        credentials = load_credentials(root / "vend-credentials.json")
        auth = VendClient(credentials["base_url"]).post_json("/api/auth/login", {
            "username": credentials["username"], "password": credentials["password"]})
        if not isinstance(auth, dict) or not isinstance(auth.get("token"), str) or not auth["token"]:
            raise ValueError("vend 登录没有返回有效令牌")
        client = VendClient(credentials["base_url"], auth["token"])
        client.get_json("/api/auth/me")
        stage = "销售抓取"
        result["status"] = "exporting"
        save_result(result_path, result)
        os.environ["FANMAIJI_DOWNLOAD_DIR"] = str(root / "downloads")
        os.environ["FANMAIJI_LOCK_FILE"] = str(root / "run" / "export.lock")
        files = exporter.run(["sales"], date_range=date_range)
        if len(files) != 1:
            raise ValueError("销售抓取没有返回唯一原始文件")
        result["file"] = str(files[0])
        stage = "vend 销售导入"
        result["status"] = "importing"
        save_result(result_path, result)
        imported = import_sales(client, files[0], date_range.label, report_row_count(files[0]), root / "run" / "imports")
        result["import"] = imported
        result["status"] = "success" if imported.get("status") in {"imported", "reused", "no_data"} else "needs_attention"
        code = 0 if result["status"] == "success" else 3
    except Exception as exc:
        # Vendor/API errors can contain uploaded rows or credentials. The client
        # writes safe receipt details; the outer boundary never prints raw errors.
        result.update(status="failed", stage=stage, errorType=type(exc).__name__)
        print(f"每日同步未完成：{stage}，错误类型 {type(exc).__name__}；请检查本次日志和导入回执", file=sys.stderr)
        code = 2
    result.update(finishedAt=dt.datetime.now(SHANGHAI).isoformat(), exitCode=code)
    save_result(result_path, result)
    print(json.dumps(result, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main())
