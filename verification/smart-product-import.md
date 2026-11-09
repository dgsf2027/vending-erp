# 商品列表自动建档与编号

## 商品价格表直接导入（2026-11-09）

商品导入支持前 50 行内定位真实表头，跳过标题、说明和空行，错误行号仍对应原 Excel。销售、补货等其他导入继续按原来的第一非空行解析。

- 可直接上传六列价格表：商品编码、商品名称、常见销售单价（元）、拿货价（元/瓶或罐）、每箱瓶/罐数、整箱拿货价（元）。下载模板使用同样的前六列，另附可选基本单位列；旧模板继续兼容。
- 保留 YQ 等用户编码。公式读取缓存结果；单件价空白时按整箱价除以箱数计算，显式零值不覆盖，单件价与整箱价矛盾时要求核对。
- 瓶/罐格式按名称提示单位：罐装为罐，容量明确的瓶装饮料为瓶；盒、袋、桶、杯或未知包装仍需填写。识别结果显示在可编辑的预览中。
- 价格在主列表展示，单件拿货价显示四位小数。修改整箱价或箱规会重算单件价，修改单件价会更新整箱价。可批量选择更新已有商品资料和价格，默认仍保持已有资料。

验证：7 个后端测试类共 61 个测试通过（包含实际附件的 25 行解析、YQ 编码、公式价格和隔离 H2 入档，未写线上业务数据）。前端类型检查、生产构建和 `verification/scripts/smart_product_import.cjs` 八组浏览器回归通过。UI 回归使用模拟 API，HTTP 路由和 XLSX 模板由控制器测试覆盖。

实际附件验证通过可选参数 `-Dvend.test.productPriceFile=<本地文件路径>` 启用；附件本身不进入仓库。

导入中心通道③进入独立的 /import/products 页面，表格、批量包装和下载模板无需上传即可看到。商品档案的导入按钮共用该工作台（弹窗模式）。后台商品编号、瓶身条码和 ERP 编码分别处理；新商品的 ERP 编码留空，在确认时分配 SP + 至少六位数字。

- 已有编号/条码绑定优先；唯一名称和规格可匹配，模糊名称只提供候选。证据冲突、多个同名商品不会自动合并。
- 新品需要基本单位和每箱数量，可批量补齐空白。已有商品默认只绑定来源，明确勾选后才更新非空资料、价格、状态。
- 数据库序列行锁串行化建档与匹配；逐行事务包含商品、别名及待绑定处理，失败行可在原预览中重试。已有商品重复上传沿用原码。
- 支持不上传表格直接添加、移除和确认商品。手工录入使用同一匹配和编号管线，并在提交时生成 Excel 归档快照。
- 原始文件保存在导入归档目录，批次记录数量和错误；智能批次不走旧版失败行重导管线。弹窗关闭再打开保留当前页面的未入档内容；刷新或离开页面后需重新录入/上传。
- 上传凭据保留四小时，服务重启后需重新上传。新迁移 V1.0.103 只增加序列表，不修改历史商品编码。
- HTTP 接口前缀为 /v1/basedata/products/smart-import，由应用 context-path=/api 拼成前端使用的 /api/v1/...。修复旧版控制器重复声明 /api 导致上传、复核、保存及模板下载路由不匹配的问题。

## 验证

在 backend 中执行（Java 17，编译目标 Java 8）：

```text
mvn -s settings.xml -Dtest=SmartProductImportControllerTest,ProductCatalogMatcherTest,ProductCodeServiceTest,SmartProductImportServiceTest,AliasMatcherAmbiguityTest,ProductImportServiceTest,PurchasePhotoPreviewServiceTest,ImportArchiveServiceTest package
```

共 69 个测试通过。新增 HTTP 控制器测试使用生产 context-path 与前端 URL，覆盖四个接口的路由和登录校验、经手人身份、模板实际 Excel 内容及手工录入模式。H2 用于序列锁并发及行事务回滚验证；保留原商品导入、照片匹配和原文件归档回归。不依赖或写入生产数据库；未运行需本地 MySQL 的完整 Spring 集成套件。

前端 `pnpm typecheck`、`pnpm build` 通过。启动本地前端 5176 后：

```text
node verification/scripts/smart_product_import.cjs
```

脚本支持 PLAYWRIGHT_MODULE、PLAYWRIGHT_CHANNEL、SMART_PRODUCT_BASE_URL。浏览器 API 为模拟，验证上传前常驻界面、模板下载带认证、上传错误保留与重试、候选确认、批量补齐、部分失败仅重试失败行、编码下载、直接手工录入和销售导入旧流程。真实 HTTP 映射与 XLSX 文件内容由控制器测试验证，上线另对 /api/v2/api-docs 核对实际路由。截图输出到前端 .vite/smart-product-qa（不入库）。
