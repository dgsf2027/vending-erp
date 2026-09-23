#!/usr/bin/env python3
"""Bound the daily sales download/import job and record its outcome."""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
from zoneinfo import ZoneInfo

BASE = Path(__file__).resolve().parent


def main() -> int:
    parser = argparse.ArgumentParser(description="Run yesterday's sales export with a hard timeout")
    parser.add_argument("--runtime-dir", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--plan", action="store_true", help="Print the export plan without accessing the site")
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    root = args.runtime_dir.expanduser().resolve()
    os.umask(0o077)
    for name in ("logs", "run", "downloads"):
        (root / name).mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update({
        "TZ": "Asia/Shanghai",
        "PYTHONUNBUFFERED": "1",
        "FANMAIJI_ENV_FILE": str(root / ".env"),
        "FANMAIJI_DOWNLOAD_DIR": str(root / "downloads"),
        "FANMAIJI_LOCK_FILE": str(root / "run" / "export.lock"),
    })
    command = [sys.executable, str(BASE / "sync_daily.py"), "--runtime-dir", str(root)]
    if args.plan:
        return subprocess.call(command + ["--plan"], env=env, cwd=BASE)
    with (root / "run" / "daily.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("An export is already running; skipping this trigger.")
            return 75
        started = dt.datetime.now(ZoneInfo("Asia/Shanghai"))
        env["FANMAIJI_RUN_ID"] = started.isoformat()
        log_path = root / "logs" / (started.strftime("%Y-%m-%dT%H%M%S.%f") + ".log")
        status = {
            "startedAt": started.isoformat(),
            "queryDate": (started.date() - dt.timedelta(days=1)).isoformat(),
            "status": "running",
            "log": str(log_path),
        }
        command += ["--date", status["queryDate"]]

        def save_status() -> None:
            temporary = root / "run" / "last-status.json.tmp"
            temporary.write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            temporary.replace(root / "run" / "last-status.json")

        save_status()
        try:
            with log_path.open("x") as output:
                process = subprocess.Popen(command, env=env, cwd=BASE, stdout=output,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    code = process.wait(timeout=args.timeout)
                    status["status"] = "success" if code == 0 else "failed"
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                    code = 124
                    status["status"] = "timeout"
        except OSError:
            code = 2
            status["status"] = "failed_to_start"
        sync_path = root / "run" / "last-sync.json"
        if sync_path.is_file():
            try:
                sync_status = json.loads(sync_path.read_text(encoding="utf-8"))
                if sync_status.get("runId") == env["FANMAIJI_RUN_ID"]:
                    status["sync"] = sync_status
            except (ValueError, OSError):
                pass
        status.update(exitCode=code, finishedAt=dt.datetime.now(ZoneInfo("Asia/Shanghai")).isoformat())
        save_status()
        print(json.dumps(status, ensure_ascii=False))
        return code


if __name__ == "__main__":
    sys.exit(main())
