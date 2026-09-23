# 每日销售抓取与 vend 导入

每天 **07:00（Asia/Shanghai）**，A 机通过现有已登录 Chrome 抓取昨天 00:00:00–23:59:59 的「出货明细」，校验原始 XLS，再通过 vend 正规销售导入接口上传、确认并记录批次回执。

用户提供的 `https://vend.vvaix.com/purchase` 是同一项目的采购入库页；销售数据进入 [导入中心](https://vend.vvaix.com/import) 的「出货明细 → 销售记录」，不生成采购单。导入会进入销售与库存推算、成本及报表；不会自动改商品参考价或生成资金结算。

## A 机配置

- 运行目录：`/Users/yh-1/fanmaiji-export-run`；代码在 `code/tools/fanmaiji-export`。
- Python：运行目录的 `.venv/bin/python`；依赖固定在 `requirements.txt`。
- 售货机浏览器：A 机现有 Chrome，CDP `http://127.0.0.1:9222`；仅新建和清理本次标签页及其派生页，保留用户其他页面。
- vend 接口：`http://127.0.0.1:8089`，已核实为 `vend.vvaix.com` 隧道对应的同一生产应用。
- vend 专用账号：`fanmaiji_daily_sync`，通过正规注册接口建立，默认「店员」角色。每次任务走正常登录，避免依赖过期的个人浏览器令牌；不伪造签名、不启用占位鉴权。
- 凭据文件：运行目录的 `vend-credentials.json`，包含 `base_url`、`username`、`password`，必须为当前用户所有且权限 600。密码不进 Git、命令参数或日志；不要把这个文件复制到仓库。
- LaunchAgent：`/Users/yh-1/Library/LaunchAgents/com.aole.fanmaiji-export.plist`；`StartCalendarInterval` 为 7:00，`RunAtLoad=false`。

A 机 Chrome 需保持运行、9222 可连接。若专用任务页确实跳转到同源 `/login` 且已核实表单可见，会用运行目录的 `fanmaiji-credentials.json` 正常登录一次，再重新校验销售查询；该文件只含 `username`、`password`，权限必须为 600 且属于当前用户，通过 `FANMAIJI_SOURCE_CREDENTIALS_FILE` 传路径。不会因普通网络失败循环登录；如出现验证码或登录错误则明确停止，不自动处理验证码。原有财务任务同在 07:00，售货机使用独立运行目录和专用任务页。

## 入口及状态

```bash
/Users/yh-1/fanmaiji-export-run/.venv/bin/python /Users/yh-1/fanmaiji-export-run/code/tools/fanmaiji-export/run_daily.py --runtime-dir /Users/yh-1/fanmaiji-export-run --plan
```

去掉 `--plan` 执行完整抓取与销售导入。`run_daily.py` 固定上海时区昨日日期，互斥防重并限制单次 15 分钟；`sync_daily.py` 串联正规登录、下载和导入。独立 `export.py` 仍只下载，供原始文件排查使用。

- `downloads/执行日期/`：保留原始 XLS，不去重行、不修改金额。
- `logs/`：逐次任务日志。
- `run/last-status.json`：调度退出状态及当前同步结果。
- `run/last-sync.json`：查询日期、文件与导入结果。
- `run/imports/vend-sales-YYYY-MM-DD.json`：当日原始文件 SHA-256、验证统计、确认回执及批次 ID；原子写入且权限 600。

`needs_attention`、`failed`、`uncertain` 都不是成功。应按日志和回执核查，不能删除状态文件后盲目重新确认。

## 校验与重复运行

1. 等待初始销售查询完成，再等待与指定日期完全一致的查询响应及页面总数，防止误用默认今日条数。
2. 检查 XLS 签名、必需列、每行日期/商品/设备/订单号/数量金额，并与源站查询总数一致。
3. 同日重复抓取先重新校验已有原表，合格则复用；厂家短时间重复下载会限频，不自动反复下载。缓存只代表已有原表，不能证明厂家没有在条数不变时修订金额。
4. 原始 XLS 不转换，vend 的 `ExcelParser` 按签名支持 XLS/XLSX。上传文件名包含日期及摘要，预览列、类型和行数全部一致才确认。
5. 导入前后分页核对唯一批次；成功需 `rowFail=0`、`rowOk+rowDup=rowTotal`、`pendingBind=0`。改价提示保留给人工处理。
6. 相同日期/摘要再次运行只查询已有成功批次。确认超时或响应丢失时记录不确定状态，下次只查批次，不再次确认。已有日文件摘要变化则停止自动导入，避免现有后端按同订单行序号去重造成误差。
7. 已验证零销售日记为 `no_data`，不提交空批次。

2026-09-22 原始报表为 338 行、240 单、362 件、1,401.40 元；源站首页为 239 单、1,388.50 元。原表与首页差异保留，不自行删单或改金额。用户后续已明确授权每天把原始销售明细导入 vend。

## 验证

```bash
python3 -m unittest discover -s tools/fanmaiji-export -p 'test_*.py'
```

本地及 A 机 101 项 Python 测试通过；独立临时 MySQL 下 40 项后端解析、导入及幂等回归通过。2026-09-23 已真实导入 9 月 22 日数据，批次 `IMP-20260923173830-C8C4`，338 行全部成功；重复运行复用批次 6，未增加销售记录或批次。17:44 已实测正常登录续期及重新查询成功。详情见 [2026-09-23 记录](STATUS-2026-09-23.md)；最新运行以 A 机状态、日志及 vend 批次为准。
