# 导入中心独立通道页面

- /import：首页仅显示三个入口，期初向导保留；全部历史记录点击后展开。
- /import/sales：出货明细上传、预览、确认与销售导入记录。
- /import/replenishment：补货记录上传、预览、确认与补货导入记录。
- /import/products：商品匹配、编号、模板下载、手工录入及商品导入记录；可直接进入已有商品档案。

三个通道通过各自路由独立挂载，预览和结果不跨通道复用。历史使用现有服务端 fileType 参数过滤，分页总数与过滤一致。切换页面前提示未提交的预览或商品行；处理中的请求阻止切换。刷新、浏览器硬导航不会保存本地未提交内容。

无后端协议或数据库变更。前端 typecheck/build 通过；本地 Playwright API 均使用模拟数据：

```text
node verification/scripts/import_channel_pages.cjs
node verification/scripts/smart_product_import.cjs
node verification/scripts/import_file_preview.cjs
node verification/scripts/import_batch_actions.cjs
```

支持 PLAYWRIGHT_MODULE、PLAYWRIGHT_CHANNEL 指定已有 Playwright 和浏览器。通道测试覆盖首页整洁度、独立页面/历史过滤、正确的销售与补货上传类型、入账后清除预览、商品编码入口、未提交内容保护、深链接刷新及手机布局。其余脚本覆盖商品功能、原始归档预览/下载和批次菜单。
