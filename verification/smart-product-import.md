# 商品列表自动建档与编号

导入中心通道③与商品档案的导入按钮共用新预览。后台商品编号、瓶身条码和 ERP 编码分别处理；新商品的 ERP 编码留空，在确认时分配 SP + 至少六位数字。手工新建也可留空编码。

- 已有编号/条码绑定优先；唯一名称和规格可匹配，模糊名称只提供候选。证据冲突、多个同名商品不会自动合并。
- 新品需要基本单位和每箱数量，可批量补齐空白。已有商品默认只绑定来源，明确勾选后才更新非空资料、价格、状态。
- 数据库序列行锁串行化建档与匹配；逐行事务包含商品、别名及待绑定处理，失败行可在原预览中重试。已有商品重复上传沿用原码。
- 原始文件保存在导入归档目录，批次记录数量和错误；智能批次不走旧版失败行重导管线。关闭预览后可重新上传原表，已成功商品会再次匹配。
- 上传凭据保留四小时，服务重启后需重新上传。新迁移 V1.0.103 只增加序列表，不修改历史商品编码。

## 验证

在 backend 中执行（Java 17，编译目标 Java 8）：

```text
mvn -s settings.xml -Dtest=ProductCatalogMatcherTest,ProductCodeServiceTest,SmartProductImportServiceTest,AliasMatcherAmbiguityTest,ProductImportServiceTest,PurchasePhotoPreviewServiceTest,ImportArchiveServiceTest package
```

共 63 个测试通过。H2 用于序列锁并发及行事务回滚验证；保留原商品导入、照片匹配和原文件归档回归。不依赖或写入生产数据库；未运行需本地 MySQL 的完整 Spring 集成套件。

前端 `pnpm typecheck`、`pnpm build` 通过。启动本地前端 5176 后：

```text
node verification/scripts/smart_product_import.cjs
```

脚本支持 PLAYWRIGHT_MODULE、PLAYWRIGHT_CHANNEL、SMART_PRODUCT_BASE_URL。所有 API 均为模拟；覆盖通道③路由、候选确认、批量补齐、修改后重检、部分失败仅重试失败行、编码下载、销售导入保留旧流程、手工新建自动编码。截图输出到前端 .vite/smart-product-qa（不入库）。
