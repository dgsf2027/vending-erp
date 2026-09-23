# 浏览器执行环境更正及历史诊断

更新时点：2026-09-23 16:32，Asia/Shanghai。

## 当前结论：此前选错浏览器环境

用户一直要求所有爬取项目使用 **A 机 Chrome**。此前调用的是本机 Firefox，这是执行环境选择错误。下面保留的 Firefox Computer Use 拒绝证据，只解释那次本机工具调用；不能把它扩展为 A 机 Chrome、A 机爬虫或售货机域名不可用的结论，也不应因此要求用户重新配置已有的 A 机登录环境。

已核实 A 机现有 Chrome 主进程使用 `/tmp/yz-gui-debug`，监听 `127.0.0.1:9222`。在该实例中复用原有 context，新建任务专用页查询并导出；用户原登录页（标签 ID 前缀 `5814F…`，URL `/index?isFrom=login`）始终保留。仅追踪及清理本次专用页与其 popup 树，不关闭用户其他标签页或 Chrome，也不另开空白浏览器。16:31 仅关闭本次人工检查的 `exporterUserTask` 页后，Chrome 恢复 7 个页面，原登录页仍在。

完整 daily 入口已于 16:19:40.912965–16:19:43.985018 实际下载并校验 A 机正式文件 `/Users/yh-1/fanmaiji-export-run/downloads/2026-09-23/sales-2026-09-22.xls`，退出码 0；首次探查的 `sales-2026-09-22-initial.xls` 另行保留。本地副本位于 `/Users/aole/Documents/ChatGPT/vend/outputs/fanmaiji/sales-2026-09-22.xls`。全部 338 行日期正确，汇总为 240 个订单、362 件、1,401.40 元；首页对照为 239 个订单、1,388.50 元，差异 1 单 / 12.90 元仍待查明，不能自行删单或入 ERP。

代码现已支持接入现有 CDP 会话、按 `taskId` 核对异步导出记录（含网站明确返回的同查询缓存任务）、下载确认，以及 XLS 日期和网页条数校验后发布。当前路径不需要 `.env` 或密码，不自动改登录；Chrome 未运行、调试端口不可连接或会话失效时明确失败。fresh 登录、商品及补货仍未验证，不执行。

截至上述更新时点，每日 07:30 LaunchAgent 已安装、加载，16:29:41.841656–16:29:44.863307 经 launchd 手动触发成功，退出码 0；本轮重新查询 338 行后校验复用已有正式文件，并未再次下载。9 月 24 日 07:30 的首次自然触发尚未核验，不能称长期稳定运行。完整日志、校验值及调度证据见 [A 机每日销售导出状态](STATUS-2026-09-23.md)。

## A 机实际运行问题及修复

以下问题来自正确的 A 机 Chrome 执行链路，与本机 Firefox 的历史拒绝不同：

- **初始查询与指定日期查询的渲染时序**：已修正为先等待初始请求完成并渲染，再等待日期完全匹配的查询响应和 DOM 总条数同步，避免将当天默认 269 条误当成 9 月 22 日的 338 条。
- **厂家重复下载限频**：16:20:42 的重复下载测试实际失败，弹出页返回 `code=999999`、`status=false`、`message=请勿短时间内重复下载，请稍后再试`。脚本明确记录失败，未自动重试。
- **原件复用**：随后增加当前归档目录同日期范围文件的重新校验；只有 XLS、全部日期、字段和本次查询条数均通过才复用，避免重复提交和下载。16:29 的 launchd 验收成功使用此路径。
- **页面清理**：只跟踪任务专用页派生的 popup 树，失败及成功后均清理自己的页面，保留用户原登录页和其他标签页。

缓存证明已有快照仍符合日期、字段和条数，无法证明厂家在条数不变时没有修订金额或状态，不能表述为每次取得最新下载。本地与 A 机均通过 56 项测试（导出 46、数据校验 6、调度 4），并分别完成真实下载与 launchd 触发验收。

## 历史证据：本机 Firefox 调用被拒

此次失败发生在 Codex Computer Use 的网址策略检查环节。它不是售卖机网页返回的登录失败或导出错误。具体被拒绝的网址及触发规则仍未查明，不能据此认定 `fanmaiji.top` 被列入黑名单。

- 原始操作：读取 Firefox 当前应用状态（`getApp("Firefox")` 和 `getAXState()`）。调用始于 2026-09-22 18:28:16.149，约 2.08 秒后返回拒绝。
- 工具返回：`This session has been stopped because Computer Use is not allowed on the current browser URL.` 后续文字要求停止对该网址的 Computer Use 操作。
- 本机 `SkyComputerUseClient` 与 `SkyComputerUseService` 均包含完整拒绝文字。服务还包含网址策略检查及缓存组件名称、`blockedURL` 字段和日志模板 `Computer Use stopped due to encountering a disallowed URL: `。这说明错误由 Computer Use 组件定义；静态字符串不能证明本次具体判定路径。
- 本任务 browser 会话许可文件在 9 月 22 日 18:13:46 已记录 `https://fanmaiji.top` 为 allowed；没有明确 deny 字段。该文件是任务会话许可，不能等同于 native Computer Use 的全部许可。
- 已检查的用户配置没有明确的站点拒绝；标准系统/用户 requirements 文件不存在。没有据此排除服务端或其他策略层。
- HTTP 与 HTTPS 属于不同 origin；现有 HTTPS 许可不自动覆盖 HTTP。原始入口包含 HTTP，但原始拒绝没有返回 URL，因此协议差异仅是待核查线索。
- 9 月 23 日重新调用工具文档与浏览器连接清单成功，清单仅列出 Codex 内置浏览器。未重新访问受阻网页，也未修改权限。工具并非整体无响应。

## 日志核验及局限

核对了本任务的原始工具调用记录、桌面应用日志，以及原始调用前后的 macOS 系统日志。没有找到包含被拒绝 URL 或拒绝规则的可用事件。系统日志同时出现 AccessibilitySupport.UIElementError Code=2，但没有证据证明它导致此次策略拒绝。

不能确定拒绝来自明确的网站状态、缓存、网址识别问题，还是策略检查失败后的处理。以上均不得写成已确认根因。未读取浏览器 Cookie、登录凭据、网页内容或其他任务的浏览记录。

## 供产品支持定位的信息

- 桌面应用：26.915.31945（9922）。
- Computer Use：26.916.1001103（1001103）。
- 事件时间：2026-09-22 10:28:16–10:28:18 UTC。
- 任务 ID：01a0c891-5cfc-7252-9995-d7dade22c788。
- 待补充的关键诊断字段：实际被判定 URL/origin、拒绝规则/来源、网址状态检查是否成功、是否命中缓存。

官方权限入口因控制方式不同而不同：内置浏览器使用 Settings → Browser；浏览器扩展使用 Settings → Computer Use → 浏览器旁 Manage。原始错误发生在 native Firefox 路径，不能直接套用扩展的处理方式。

- [官方内置浏览器说明](https://learn.chatgpt.com/docs/browser)
- [官方浏览器扩展说明](https://learn.chatgpt.com/docs/chrome-extension)
- [官方配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)

## 历史诊断的适用范围

原先因本机 Firefox 拒绝而认为售卖机导出无法继续，是将不同执行环境混为一谈。上述历史工具诊断不构成 A 机访问失败的证据；当前 A 机真实文件结果见本文顶部。自动调度的完成状态必须另由 A 机完整脚本验收和 LaunchAgent 加载记录证明。

## A 机对照核验（2026-09-23 15:27–15:29）

通过 SSH `project-mac` 只读复核，主机为 `Mac`，SSH 正常。

- 现有 `com.aole.finance-scrapling` 每天 07:00 运行，已加载，上次退出码 0。
- 今日财务任务 07:00:05 开始，07:02:33 完成。天猫直通车、CPS、京东、有赞均第一次成功，无重试。已核对输出 CSV 行数与状态记录一致。
- 财务任务使用 A 机本地 Scrapling `StealthySession` 和 Chrome，复用各来源既有登录配置；数据通过页面及浏览器响应读取。它不调用本机 Codex 的 native Firefox 控制工具。
- 证据位于 A 机 `/Users/yh-1/finance-scrapling-starter/data/logs/2026-09-23.log`、`/tmp/finance-scrapling-launchd.out.log`、`/Users/yh-1/Library/LaunchAgents/com.aole.finance-scrapling.plist`。
- 在 **15:27–15:29 这个历史时点**，售卖机运行目录中 `.env` 不存在，`downloads` 和 `run` 为空，只有依赖安装与离线环境验证日志。`launchctl print gui/501/com.aole.fanmaiji-export` 返回未找到服务，正式 LaunchAgents 目录中也没有该 plist；07:30 配置仅在运行目录暂存。随后已取得本文顶部的真实 XLS；当前 CDP 路径不以 `.env` 是否存在判断就绪。

这次 15:27–15:29 对照核验仅读取既有状态和日志，没有通过 A 机发起新的站点访问，因此当时不能描述为 A 机已经尝试并失败。现有财务爬虫与本机 Firefox 分属不同执行链路；后续在正确的 A 机 Chrome 中已成功查询和导出销售文件，更新了当时「售卖机尚未验证」的状态。
