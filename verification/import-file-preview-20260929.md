# 批次历史原始表格查看

2026-09-29：本地实现及下述验证完成。用户随后授权将原表查看和操作整合一并上线；部署结果以发布验收为准。

入口为导入中心批次历史的文件名。抽屉保留 Excel 原始行号与列位置，支持多工作表、每页 50 行、完整原文件下载；预览最多 100 列并明确提示。接口读取归档文件，不写入业务数据，不重新计算公式；已删除历史、缺失归档和越界路径会返回明确错误。

## 验证

- `pnpm typecheck` 与 `pnpm build` 通过。构建保留既有依赖注释和大 bundle 提示。
- JDK 17：`mvn -Dtest=ImportArchiveServiceTest,ImportArchiveControllerTest,ExcelParserTest -Dmaven.build.directory=target/archive-preview-test test`，18 项通过，零失败、错误和跳过。包含原文件字节/文件名、登录鉴权、路径/符号链接、xls/xlsx、缓存公式、日期/金额显示、稀疏行和宽表。
- 本地 Vite 5176 下运行 `node verification/scripts/import_file_preview.cjs`，11 项通过。所有业务 API 使用标注为测试数据的隔离模拟响应，无生产读写。
- 浏览器脚本需已安装 `playwright`，或通过 `PLAYWRIGHT_MODULE` 指定运行环境提供的模块路径。
- 桌面 1440×1000、手机 390×844 截图确认抽屉稳定位置，表格滚动与分页均在视口内。覆盖打开、原始行列、翻页、切换工作表重置页号、认证下载、空表、缺失文件/网络失败重试、关闭重开时忽略旧响应。
- 独立界面审查结论：**Ship**，范围为本次新增入口与查看抽屉。文件名截断、中文网络错误和表头对比度均已修正；最终颜色修正后重新运行浏览器检查与生产构建均通过。
- `git diff --check` 通过。

截图与详细记录位于 `.impeccable/review/import-file-desktop.png`、`import-file-mobile.png` 和 `import-file-qa.json`。当前验证不代表生产环境归档联调或上线验证。

## 后续：整合批次操作

按用户追加截图，将原有多色操作链接收拢为「查看表格」与点击展开的「更多操作」。菜单保留批次类型、状态和失败行条件；回滚与删除在分隔线下以危险色显示，分别说明其作用于导入数据或批次历史，并保留原有确认弹窗。处理中批次禁用操作；请求期间避免重复触发；取消确认不产生未处理异常。

操作列由 350px 缩至桌面 210px，手机使用右侧固定的 112px 操作列，入口高度 40px、菜单项高度 44px。前端类型检查、生产构建及原表预览的 11 项浏览器回归通过；样式检测无新增问题。后端与部署状态不变。

独立菜单审查与 `verification/scripts/import_batch_actions.cjs` 的 8 组检查通过：类型/状态条件、处理中禁用、危险操作分组、操作指向正确批次、取消回滚/删除不发写入请求、直接查看原表、键盘开关/导航及手机固定操作列。测试仅使用隔离模拟 API，无浏览器运行错误。桌面及 390px 手机截图已打开核验，菜单未被裁切；证据为 `import-actions-desktop.png`、`import-actions-mobile.png` 和 `import-actions-qa.json`，均位于 `.impeccable/review/`。
