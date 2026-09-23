# fanmaiji.top 后台销售下载

当前使用 **A 机已登录的 Google Chrome 会话**，经本机 CDP 端口 `127.0.0.1:9222` 接入。用户一直要求使用 A 机 Chrome；此前选用本机 Firefox 是执行环境选择错误，其访问拒绝不能作为 A 机导出受阻的依据。

截至 2026-09-23 16:32（Asia/Shanghai），**A 机完整 daily 入口已通过真实下载验收，每日 07:30 LaunchAgent 已安装并加载，随后经 launchd 手动触发验收成功**。9 月 22 日出货明细为 338 行，全部日期正确。9 月 24 日 07:30 的首次自然触发尚未发生，不能据此称长期稳定运行。

默认仅下载昨日销售原始报表，不自动上传 ERP 或确认入账。重新登录、商品列表及补货记录尚未验证，相关 `verified: false` 保持不变，不执行这些流程。

## A 机运行方式

- 独立运行目录：`/Users/yh-1/fanmaiji-export-run`。
- 代码：`/Users/yh-1/fanmaiji-export-run/code/tools/fanmaiji-export`。
- Python：`/Users/yh-1/fanmaiji-export-run/.venv/bin/python`。
- Chrome：A 机现有实例，CDP `http://127.0.0.1:9222`，当前用户目录为 `/tmp/yz-gui-debug`。
- 下载、日志、状态分别写入运行目录的 `downloads`、`logs`、`run`。

`config.yaml` 使用 `browser.mode: cdp`。脚本连接现有浏览器及 context，只新建、跟踪并关闭自己使用的标签页及其派生 popup，保留用户原有标签页和 Chrome 进程。验收后原 `/index?isFrom=login` 登录页仍保留。此模式不需要 `.env`、账号或密码，也不新启动 Chromium。A 机现有 Chrome 必须保持运行、9222 端口可连接且售货机登录有效；条件不满足时明确失败，不自动改登录或另开空白浏览器。

固定依赖在 `requirements.txt`，其中 `xlrd==2.0.2` 用于读取厂家实际导出的 XLS 文件。代码更新时按该文件更新独立环境依赖即可；当前 CDP 方案不需要安装或启动新的浏览器。

先只检查日期计划：

```bash
/Users/yh-1/fanmaiji-export-run/.venv/bin/python /Users/yh-1/fanmaiji-export-run/code/tools/fanmaiji-export/run_daily.py --runtime-dir /Users/yh-1/fanmaiji-export-run --plan
```

正式入口为同一命令去掉 `--plan`。LaunchAgent `com.aole.fanmaiji-export` 已安装于 `/Users/yh-1/Library/LaunchAgents/com.aole.fanmaiji-export.plist` 并加载，每天 **07:30（Asia/Shanghai）** 执行，沿用原计划，错开现有 07:00 财务抓取任务。`RunAtLoad=false`；16:29 的 launchd 手动触发验收后 `runs=2`、`last exit code=0`。正式配置已加载和自然定时触发已成功是不同验收项；后者待 9 月 24 日核验。

`run_daily.py` 在父进程开始时按 Asia/Shanghai 确定昨日日期，再通过 `--date` 固定传给导出子进程，避免跨午夜前后取到不同日期。两层互斥锁防止重叠，单次最多 15 分钟，结果写入逐次日志及 `run/last-status.json`。

## 查询、导出与文件校验

销售及销售日期控件已在 A 机现有登录会话中核实，`exports.sales.verified` 和 `exports.sales.date_filters.verified` 为 `true`。这只表示销售查询导出流程已核实，不代表 fresh 登录、商品或补货流程已验证。

销售流程为：

1. 先等页面初始查询完成并渲染，再查询指定日期的 00:00:00–23:59:59；等待请求日期完全匹配的响应、DOM 总条数同步，回读日期控件，防止误用当天默认查询结果。
2. 当前归档目录若已有同日期范围的原件，用本次查询条数重新验证 XLS、全部日期及字段，验证通过则复用，不再提交或下载；无有效原件才继续导出。
3. 提交异步导出，取得返回的 `taskId`。若网站明确返回同查询任务已存在，则复用该任务 ID；不随意选择其他历史导出。
4. 在导出记录中按 ID 定位，核对任务 ID、功能为「出货明细」及完成状态，再确认下载。
5. 下载到临时文件，用 `xlrd` 校验 XLS 必需表头、每条记录日期、数量及金额字段，并核对文件明细条数与网页查询条数一致，通过后才发布文件。

空文件、HTML 错误页、格式无效、日期越界或条数不符均失败，不发布为成功结果。需要重新下载时保留原文件并使用新文件名。厂家返回「请勿短时间内重复下载，请稍后再试」时明确失败，不自动重试；日志不回显凭据或原始页面敏感错误内容。

缓存复用证明原件仍符合请求日期、字段及当前条数，**不能证明厂家在条数不变时没有修改金额或状态**。日志中的「复用已校验的本地文件」不是本轮重新下载最新快照。

## 已验收文件与待对账差异

2026-09-22 出货明细：

- A 机正式归档：`/Users/yh-1/fanmaiji-export-run/downloads/2026-09-23/sales-2026-09-22.xls`，91,136 字节，由 16:19:40–16:19:43 完整 daily 入口实际下载并验证。
- 首次探查原件另保留为同目录 `sales-2026-09-22-initial.xls`。
- 本地：`/Users/aole/Documents/ChatGPT/vend/outputs/fanmaiji/sales-2026-09-22.xls`。
- 校验汇总：338 行，240 个订单，362 件，明细金额合计 **1,401.40 元**；全部 338 行日期正确。

16:29:41–16:29:44 的 launchd 验收重新查询 338 行并校验复用正式归档，退出码 0；这次没有重新下载。完整时间、日志和 SHA-256 见 [状态记录](STATUS-2026-09-23.md)。

现有首页对照值为 **1,388.50 元 / 239 个订单**，与明细相差 **+12.90 元 / +1 单**，原因尚未查明。保留原始导出，不自行删除订单或用差额调整文件；本次未导入 ERP。文件日期和条数校验通过，并不表示与首页统计口径已对平。

## 离线检查与补查日期

无需访问网站即可执行：

```bash
python3 tools/fanmaiji-export/export.py --plan
python3 tools/fanmaiji-export/export.py --type sales --date 2026-09-22 --plan
python3 tools/fanmaiji-export/test_export.py
python3 tools/fanmaiji-export/test_verify_sales.py
python3 tools/fanmaiji-export/test_daily.py
```

本地与 A 机均 **56 项离线检查通过：导出 46 项、数据校验 6 项、调度 4 项**；8 个运行、配置及测试文件的 SHA-256 一致。离线通过、完整 daily 下载成功和 launchd 触发成功分别记录，首次 07:30 自然触发仍待核验。

销售支持 `--date YYYY-MM-DD`、`--month YYYY-MM` 或 `--start-date YYYY-MM-DD --end-date YYYY-MM-DD`；范围包含首尾日期，这三种起始参数互斥。只传 `--start-date` 时结束日相同，不能单独传 `--end-date`。补货、商品及 `--all` 因包含未验证流程仍拒绝执行。最新验收进展见 [状态记录](STATUS-2026-09-23.md)。
