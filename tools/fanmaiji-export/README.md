# fanmaiji.top 后台数据下载

当前状态：**本地代码和离线验证已完成，真实登录、日期查询和导出尚未通过验证，定时任务未启用**。本次联真因浏览器工具的 URL 访问限制未执行；没有改用其他网络通道绕过限制。`config.yaml` 的登录、导出及日期筛选均保留 `verified: false`，普通执行会在读取凭据、启动浏览器前拒绝运行。

脚本默认只下载昨日销售原始报表。补货记录和商品列表按需运行；文件不会自动上传 ERP、确认入账或修改厂家后台业务数据。

## 离线检查

Python 3.9 及以上可直接检查日期计划，无需安装依赖或提供凭据，也不会访问网站：

```bash
python3 tools/fanmaiji-export/export.py --plan
python3 tools/fanmaiji-export/export.py --type sales --date 2026-09-21 --plan
python3 tools/fanmaiji-export/test_export.py
python3 tools/fanmaiji-export/test_daily.py
```

默认日期为 **Asia/Shanghai 时区昨天的 00:00:00–23:59:59**。计划仅展示查询参数，不表示网站功能已验证。

```bash
python3 export.py --type sales --date 2026-09-21
python3 export.py --type sales --start-date 2026-09-01 --end-date 2026-09-21
python3 export.py --type sales --month 2026-08
python3 export.py --type replenish --date 2026-09-21
python3 export.py --type products
python3 export.py --all --date 2026-09-21
```

以上真实导出命令都需要先完成联真。`--date`、`--month`、`--start-date` 互斥；日期范围包含首尾日期。只传 `--start-date` 时结束日相同，不能单独传 `--end-date`。

## 独立运行目录

A 机拟使用 `/Users/yh-1/fanmaiji-export-run` 作为独立运行目录，代码位于其 `code/tools/fanmaiji-export` 子目录，凭据位于运行目录的 `.env`。这些都是拟定路径，本仓库中的模板不代表服务器已部署。

安装运行依赖后，将 `.env.example` 复制为独立运行目录的 `.env`，填写真实地址和凭据，并设为仅当前用户可读写。不要把凭据提交到代码仓库。

```bash
python3 -m venv /Users/yh-1/fanmaiji-export-run/.venv
/Users/yh-1/fanmaiji-export-run/.venv/bin/python -m pip install -r /Users/yh-1/fanmaiji-export-run/code/tools/fanmaiji-export/requirements.txt
/Users/yh-1/fanmaiji-export-run/.venv/bin/python -m playwright install chromium
```

直接执行 `export.py` 时，环境变量 `FANMAIJI_ENV_FILE` 指定独立 `.env` 路径；未设置时兼容脚本目录下的 `.env`。现有环境变量优先于 `.env`。

运行入口统一使用：

```bash
/Users/yh-1/fanmaiji-export-run/.venv/bin/python \
  /Users/yh-1/fanmaiji-export-run/code/tools/fanmaiji-export/run_daily.py \
  --runtime-dir /Users/yh-1/fanmaiji-export-run --plan
```

联真通过后去掉 `--plan` 才会执行昨日销售导出。`run_daily.py` 默认限定单次运行最多 15 分钟，超时结束整个子进程组，并记录逐次日志和 `run/last-status.json`。运行目录的 `downloads`、`logs`、`run` 与现有财务爬虫分开；双层互斥锁防止同一任务重叠运行。

## 联真与定时模板

登录输入框及按钮已根据查看过的页面记录到配置，密码加密由网站登录页自己的 JavaScript 完成。仍需核实登录成功标记、销售页面的唯一控件、日期查询实际效果和下载内容，再逐项设置 `verified: true`。销售和补货日期控件目前为空，脚本不会猜测或默认查询当天。

有头探查入口为 `export.py --probe`；只有访问限制解除且允许联真时才使用。验证码出现时需要人工完成，不自动绕过验证码。日期填入后，脚本会再次读取查询后的控件值；值不一致或无法读取就拒绝导出。

`launchd.plist.template` 为每天 **07:30** 的任务模板，需将 `__RUNTIME__` 替换为实际运行目录。模板仅暂存，尚未安装或加载到 A 机。先取得真实昨日文件并核对内容，再启用模板。

下载先保存到临时文件，拒绝空文件、HTML 错误页和无效 XLSX，再原子发布为 `downloads/<运行日期>/sales-<查询日期>.xlsx` 等文件。重复下载保留原文件，追加运行时间生成新文件。日志不回显密码、原始页面错误正文或可能含输入值的 Playwright 异常。
