/* Local navigation regression with synthetic API responses; never uses production data. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.IMPORT_CHANNEL_BASE_URL || 'http://127.0.0.1:5176';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/import-channel-qa');
fs.mkdirSync(out, { recursive: true });
const passed = [], errors = [], filters = [], uploads = [], confirmations = [], unexpected = [];
const types = ['出货明细', '系统补货记录', '商品列表'];
const batches = types.map((fileType, i) => ({ id: i + 1, batchNo: 'QA-' + (i + 1), fileType, fileName: fileType + '-历史.xlsx',
  rowTotal: 2, rowOk: 2, rowFail: 0, rowDup: 0, batchStatus: '已导入', createTime: '2026-10-27 12:00:00' }));
const ok = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
const until = async (fn, message) => {
  const end = Date.now() + 10000;
  while (!await fn()) { if (Date.now() > end) throw Error(message); await new Promise(r => setTimeout(r, 30)); }
};
(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
  await context.addInitScript(() => { localStorage.setItem('vend_token', 'channel-test'); localStorage.setItem('vend_tour_seen', '1'); });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    if (url.pathname.endsWith('/imports/batches')) {
      const type = url.searchParams.get('fileType'); filters.push(type);
      return ok(route, { records: type ? batches.filter(b => b.fileType === type) : batches, total: type ? 1 : 3, size: 10, current: 1 });
    }
    if (url.pathname.endsWith('/imports/upload')) {
      const body = req.postData();
      const type = body.includes('系统补货记录') ? '系统补货记录' : '出货明细';
      uploads.push(type);
      const headers = type === '出货明细' ? ['订单号', '商品名称', '出货数量'] : ['设备ID', '商品名称', '本次补货数'];
      return ok(route, { token: type, fileType: type, fileName: type + '.xlsx', rowTotal: 1, columnsOk: true,
        columnChecks: headers.map(expected => ({ expected, found: true, required: true })), headers, warnings: [],
        previewRows: [{ [headers[0]]: 'TEST-001', 商品名称: '测试茶', [headers[2]]: '2' }] });
    }
    if (url.pathname.endsWith('/imports/confirm')) {
      const token = req.postDataJSON().token; confirmations.push(token);
      return ok(route, { batchId: 99, batchNo: 'QA-COMMIT', fileType: token, rowTotal: 1, rowOk: 1, rowFail: 0, rowDup: 0,
        pendingBind: 0, priceChangeCount: 0, negativeStock: [], docsCreated: 1, matchedPrePending: 0, snapshots: 1 });
    }
    if (!['GET','HEAD','OPTIONS'].includes(req.method())) { unexpected.push(url.pathname); return route.abort(); }
    if (url.pathname.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-10-27', records: [] });
    return ok(route, { records: [], total: 0, size: 10, current: 1 });
  });
  try {
    await page.goto(base + '/import');
    await page.getByRole('link', { name: '进入商品导入', exact: true }).waitFor();
    assert.equal(await page.locator('.channel-entry').count(), 3);
    assert.equal(await page.locator('input[type=file]').count(), 0);
    assert.equal(await page.locator('#product-import-workspace').count(), 0);
    assert.equal(await page.locator('.channel-history').count(), 0);
    await page.screenshot({ path: path.join(out, 'import-home.png'), fullPage: true });
    passed.push('home contains only three channel entries and secondary links, no upload workbench');
    await page.getByRole('button', { name: '查看全部导入记录', exact: true }).click();
    await page.getByRole('button', { name: '查看表格：商品列表-历史.xlsx', exact: true }).waitFor();
    await page.getByRole('button', { name: '收起全部导入记录', exact: true }).click();
    await page.getByRole('link', { name: '进入销售导入', exact: true }).click();
    await page.getByRole('heading', { name: '通道① · 销售导入', exact: true }).waitFor();
    await until(() => filters.at(-1) === '出货明细', 'sales history filter missing');
    assert.equal(await page.locator('#product-import-workspace').count(), 0);
    await page.getByRole('columnheader', { name: '订单号', exact: true }).waitFor();
    await page.getByRole('button', { name: '查看表格：出货明细-历史.xlsx', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '查看表格：系统补货记录-历史.xlsx', exact: true }).count(), 0);
    await page.screenshot({ path: path.join(out, 'sales-page.png'), fullPage: true });
    await page.locator('.channel-upload input[type=file]').setInputFiles({ name: '销售.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('synthetic') });
    const preview = page.getByRole('dialog', { name: /预览核对/ });
    await preview.waitFor();
    assert.deepEqual(uploads, ['出货明细']);
    await preview.getByRole('button', { name: '取消(不入账)', exact: true }).click();
    await page.getByRole('button', { name: '返回导入中心', exact: true }).click();
    await page.getByRole('button', { name: '继续填写', exact: true }).click();
    assert.equal(new URL(page.url()).pathname, '/import/sales');
    await page.getByRole('button', { name: '继续核对并导入', exact: true }).waitFor();
    await page.getByRole('button', { name: '返回导入中心', exact: true }).click();
    await page.getByRole('button', { name: '离开页面', exact: true }).click();
    await page.getByRole('link', { name: '进入补货导入', exact: true }).click();
    await page.getByRole('heading', { name: '通道② · 补货导入', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '继续核对并导入', exact: true }).count(), 0);
    await until(() => filters.at(-1) === '系统补货记录', 'replenishment history filter missing');
    await page.getByRole('columnheader', { name: '本次补货数', exact: true }).waitFor();
    await page.screenshot({ path: path.join(out, 'replenishment-page.png'), fullPage: true });
    passed.push('sales and replenishment have separate workbenches, correct history filters, pending-preview leave guard and isolated state');
    await page.locator('.channel-upload input[type=file]').setInputFiles({ name: '补货.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('synthetic') });
    await preview.waitFor();
    await preview.getByRole('button', { name: '确认导入 → 入账', exact: true }).click();
    await until(() => confirmations.length === 1, 'replenishment confirm missing');
    await page.getByText('上次导入 · QA-COMMIT(系统补货记录)', { exact: true }).waitFor();
    assert.deepEqual(uploads, ['出货明细','系统补货记录']);
    assert.deepEqual(confirmations, ['系统补货记录']);
    assert.equal(await page.getByRole('button', { name: '继续核对并导入', exact: true }).count(), 0);
    await page.getByRole('button', { name: '返回导入中心', exact: true }).click();
    await page.getByRole('link', { name: '进入商品导入', exact: true }).click();
    await page.getByRole('heading', { name: '通道③ · 商品导入', exact: true }).waitFor();
    await page.getByRole('button', { name: '下载模板', exact: true }).waitFor();
    await until(() => filters.at(-1) === '商品列表', 'product history filter missing');
    await page.getByRole('button', { name: '查看已有商品编码', exact: true }).waitFor();
    assert.equal(await page.locator('.channel-upload').count(), 0);
    await page.screenshot({ path: path.join(out, 'products-page.png'), fullPage: true });
    await page.getByRole('button', { name: '＋ 手工添加商品', exact: true }).click();
    await page.getByRole('textbox', { name: '商品名称第1行', exact: true }).fill('保留的测试资料');
    await page.getByRole('button', { name: '查看已有商品编码', exact: true }).click();
    await page.getByRole('button', { name: '继续填写', exact: true }).click();
    assert.equal(await page.getByRole('textbox', { name: '商品名称第1行', exact: true }).inputValue(), '保留的测试资料');
    passed.push('replenishment uses its own upload/confirm payload; product page exposes code lookup and protects unsaved rows');
    await page.goto(base + '/import/replenishment');
    await page.reload();
    await page.getByRole('heading', { name: '通道② · 补货导入', exact: true }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + '/import');
    await page.getByRole('link', { name: '进入商品导入', exact: true }).waitFor();
    const bounds = await page.locator('.channel-entry').evaluateAll(nodes => nodes.map(n => ({ x: n.getBoundingClientRect().x, right: n.getBoundingClientRect().right })));
    assert.ok(bounds.every(b => b.x >= 0 && b.right <= 390), JSON.stringify(bounds));
    await page.screenshot({ path: path.join(out, 'import-home-mobile.png'), fullPage: true });
    passed.push('direct route refresh works and mobile entry cards stay within viewport');
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ passed, uploads, confirmations, errors }, null, 2));
    console.log(JSON.stringify({ passed, output: out }, null, 2));
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ message: String(error), errors, filters, uploads, body: await page.locator('body').innerText() }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
