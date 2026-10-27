/* Local UI regression, synthetic files and mocked API only. No production writes.
 * Start frontend on 5176; set PLAYWRIGHT_MODULE if Playwright is not on Node's path. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.SMART_PRODUCT_BASE_URL || 'http://127.0.0.1:5176';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/smart-product-qa');
fs.mkdirSync(out, { recursive: true });
const tea = { id: 7, skuCode: 'SP000007', productName: '测试茉莉茶500ml', unit: '瓶', boxSpec: 12, productStatus: '在售' };
const fixtures = [
  { rowNo: 2, productName: tea.productName, sourceCode: '00100', barcode: '00123', action: '已匹配', existingProductId: 7, skuCode: tea.skuCode, matchedProductName: tea.productName, unit: '瓶', boxSpec: '12' },
  { rowNo: 3, productName: '测试面包80g', sourceCode: '00101', barcode: '00456', action: '资料缺失', errorMsg: '请填写基本单位', skuCode: '' },
  { rowNo: 4, productName: '测试茉莉绿茶500ml', sourceCode: '00102', barcode: '', action: '待确认', errorMsg: '发现相似商品', candidates: [tea], skuCode: '' },
  { rowNo: 5, productName: '测试巧克力20g', sourceCode: '00103', barcode: '00789', action: '新增商品', unit: '块', boxSpec: '10', skuCode: '' },
];
const clone = value => JSON.parse(JSON.stringify(value));
const errors = [], commits = [], manualCommits = [], unexpected = [], passed = [];
let genericUploads = 0, parseCalls = 0, templateCalls = 0;
let failParse = true;
const ok = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
const check = async (fn, message) => {
  const end = Date.now() + 12000;
  while (!await fn()) { if (Date.now() > end) throw Error(message); await new Promise(r => setTimeout(r, 50)); }
};
(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const context = await browser.newContext({ viewport: { width: 1540, height: 1100 } });
  await context.addInitScript(() => {
    localStorage.setItem('vend_token', 'smart-product-test'); localStorage.setItem('vend_user_name', '商品测试'); localStorage.setItem('vend_tour_seen', '1');
  });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), method = req.method();
    if (!url.pathname.startsWith('/api/')) return route.continue();
    if (url.pathname.endsWith('/smart-import/parse')) {
      parseCalls++; assert.match(req.headers()['content-type'], /multipart\/form-data; boundary=/);
      if (failParse) {
        failParse = false;
        return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ message: 'Not found' }) });
      }
      return ok(route, { token: 'test-upload', fileName: '测试商品.xlsx', rows: clone(fixtures) });
    }
    if (url.pathname.endsWith('/smart-import/template')) {
      templateCalls++; assert.equal(req.headers().authorization, 'Bearer smart-product-test');
      return route.fulfill({ status: 200, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('UI download fixture; actual XLSX format tested by controller tests') });
    }
    if (url.pathname.endsWith('/smart-import/review')) {
      const rows = req.postDataJSON().rows;
      for (const row of rows) {
        row.errorMsg = null;
        if (row.selectedProductId === 7 || row.existingProductId === 7) {
          Object.assign(row, { action: '已匹配', existingProductId: 7, skuCode: tea.skuCode, matchedProductName: tea.productName, unit: row.unit || '瓶', boxSpec: row.boxSpec || '12' });
        } else if (!row.unit || !row.boxSpec) { row.action = '资料缺失'; row.errorMsg = '请补齐包装'; }
        else row.action = '新增商品';
      }
      return ok(route, { rows, errorCount: rows.filter(r => r.action === '资料缺失').length });
    }
    if (url.pathname.endsWith('/smart-import/commit')) {
      const payload = req.postDataJSON();
      if (payload.manualEntry) {
        manualCommits.push(payload); assert.equal(payload.token, '');
        return ok(route, { created: payload.rows.length, matched: 0, updated: 0, failed: 0,
          savedRows: payload.rows.map(r => ({ ...r, skuCode: 'SP000900', action: '已新增' })), failedRows: [], errors: [] });
      }
      commits.push(payload);
      assert.equal(payload.token, 'test-upload');
      const fail = commits.length === 1 ? payload.rows.filter(r => r.rowNo === 5).map(r => ({ ...r, action: '资料缺失', errorMsg: '模拟保存失败，请重新检查' })) : [];
      const saved = payload.rows.filter(r => !fail.some(f => f.rowNo === r.rowNo)).map(r => ({
        ...r, skuCode: r.skuCode || 'SP000' + r.rowNo + '01', action: r.existingProductId ? '已匹配' : '已新增',
      }));
      return ok(route, { created: saved.filter(r => r.action === '已新增').length, matched: saved.filter(r => r.action === '已匹配').length, updated: 0, failed: fail.length, savedRows: saved, failedRows: fail, errors: [] });
    }
    if (url.pathname.endsWith('/imports/upload')) {
      genericUploads++; return ok(route, { token: 'generic', fileName: '销售.xlsx', fileType: '出货明细', rowTotal: 1, columnsOk: true, columnChecks: [], headers: [], previewRows: [], warnings: [] });
    }
    if (url.pathname.endsWith('/basedata/products') && method === 'POST') {
      assert.equal(req.postDataJSON().skuCode, '');
      passed.push('manual product supports blank SKU'); return ok(route, { ...req.postDataJSON(), id: 20, skuCode: 'SP000020' });
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) { unexpected.push({ path: url.pathname, method }); return route.abort(); }
    if (url.pathname.endsWith('/basedata/products/7')) return ok(route, tea);
    if (url.pathname.endsWith('/basedata/products')) return ok(route, { records: [tea], total: 1, current: 1, size: 20 });
    if (url.pathname.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-10-27', records: [] });
    return ok(route, { records: [], total: 0, current: 1, size: 10 });
  });
  try {
    await page.goto(base + '/import');
    const dialog = page.locator('#product-import-workspace');
    await dialog.getByText('商品导入工作台', { exact: true }).waitFor();
    await dialog.getByRole('columnheader', { name: 'ERP 编码 / 原始标识' }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: /确认入档/ }).isDisabled(), true);
    assert.equal(parseCalls, 0);
    await page.screenshot({ path: path.join(out, 'always-visible-workspace.png'), fullPage: true });
    const templateDownload = page.waitForEvent('download');
    await dialog.getByRole('button', { name: '下载模板', exact: true }).click();
    assert.equal((await templateDownload).suggestedFilename(), '商品自动编号模板.xlsx');
    assert.equal(templateCalls, 1);
    const fixture = { name: '测试商品.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('synthetic') };
    await dialog.locator('input[type=file]').setInputFiles(fixture);
    await dialog.getByText(/读取「测试商品.xlsx」失败：接口暂不可用/).waitFor();
    await dialog.locator('input[type=file]').setInputFiles(fixture);
    await dialog.getByText('测试商品.xlsx', { exact: true }).waitFor();
    assert.equal(parseCalls, 2); assert.equal(genericUploads, 0);
    passed.push('workspace controls visible before upload, authenticated template download and persistent upload error/retry');
    assert.equal(await dialog.getByRole('button', { name: /确认入档/ }).isDisabled(), true);
    assert.equal(await dialog.getByRole('checkbox', { name: '更新已有商品资料' }).isChecked(), false);
    await dialog.getByRole('textbox', { name: '批量基本单位', exact: true }).fill('袋');
    await dialog.getByRole('textbox', { name: '批量每箱数量', exact: true }).fill('24');
    await dialog.getByRole('button', { name: '填入勾选行', exact: true }).click();
    assert.equal(await dialog.getByRole('textbox', { name: '基本单位第2行', exact: true }).inputValue(), '瓶');
    assert.equal(await dialog.getByRole('textbox', { name: '每箱数量第3行', exact: true }).inputValue(), '24');
    await dialog.getByRole('button', { name: tea.skuCode + ' · ' + tea.productName, exact: true }).click();
    await dialog.getByRole('button', { name: '重新检查', exact: true }).click();
    await check(async () => await dialog.getByRole('button', { name: /确认入档/ }).isEnabled(), 'recheck did not enable commit');
    await page.screenshot({ path: path.join(out, 'smart-product-preview.png'), fullPage: true });
    passed.push('import center routing, candidates, batch fill, dirty recheck and preserve existing packaging');
    await dialog.getByRole('button', { name: /确认入档/ }).click();
    await dialog.getByText('已保存的商品编码（3）', { exact: true }).waitFor();
    assert.equal(commits[0].rows.length, 4);
    assert.equal(commits[0].rows[0].updateExisting || false, false);
    assert.equal(await dialog.getByRole('textbox', { name: '商品名称第5行', exact: true }).count(), 1);
    assert.equal(await dialog.getByRole('textbox', { name: '商品名称第3行', exact: true }).count(), 0);
    await dialog.getByRole('button', { name: '重新检查', exact: true }).click();
    await check(async () => await dialog.getByRole('button', { name: /确认入档/ }).isEnabled(), 'retry not ready');
    await dialog.getByRole('button', { name: /确认入档/ }).click();
    await dialog.getByText('已保存的商品编码（4）', { exact: true }).waitFor();
    assert.deepEqual(commits[1].rows.map(r => r.rowNo), [5]);
    const downloadPromise = page.waitForEvent('download');
    await dialog.getByRole('button', { name: '下载编码对照表', exact: true }).click();
    assert.equal((await downloadPromise).suggestedFilename(), '商品编码对照.csv');
    await page.screenshot({ path: path.join(out, 'smart-product-result.png'), fullPage: true });
    passed.push('partial failure retains only failed row, successful codes visible and downloadable');
    const sale = page.locator('.el-card').filter({ hasText: '通道① 出货明细' });
    await sale.locator('input[type=file]').setInputFiles({ name: '销售.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('synthetic') });
    await page.getByRole('dialog', { name: /预览核对/ }).waitFor(); assert.equal(genericUploads, 1);
    passed.push('sales import retains original flow');
    await page.goto(base + '/import');
    await dialog.getByRole('button', { name: '＋ 手工添加商品', exact: true }).click();
    await dialog.getByRole('textbox', { name: '商品名称第1行', exact: true }).fill('测试手工新商品');
    await dialog.getByRole('textbox', { name: '基本单位第1行', exact: true }).fill('瓶');
    await dialog.getByRole('textbox', { name: '每箱数量第1行', exact: true }).fill('24');
    await dialog.getByRole('button', { name: '＋ 手工添加商品', exact: true }).click();
    await dialog.getByRole('button', { name: '移除第2行', exact: true }).click();
    await dialog.getByRole('button', { name: '重新检查', exact: true }).click();
    await check(async () => await dialog.getByRole('button', { name: /确认入档/ }).isEnabled(), 'manual entry not ready');
    await dialog.getByRole('button', { name: /确认入档/ }).click();
    await dialog.getByText('SP000900', { exact: true }).waitFor();
    assert.equal(manualCommits.length, 1); assert.equal(manualCommits[0].rows.length, 1);
    passed.push('manual rows without uploading: add, remove, review, commit and generated code');
    await page.goto(base + '/products');
    await page.getByRole('button', { name: /新建商品/ }).click();
    const form = page.getByRole('dialog', { name: '新建商品', exact: true });
    await form.getByPlaceholder('采购商品名(主名称)').fill('测试自动编号商品');
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('商品已建档，编码：SP000020', { exact: true }).waitFor();
    await page.getByRole('button', { name: '⬆ 导入商品列表', exact: true }).click();
    const modal = page.getByRole('dialog', { name: '商品列表 · 自动建档与编号', exact: true });
    await modal.getByRole('button', { name: '下载模板', exact: true }).waitFor();
    await modal.getByRole('button', { name: '＋ 手工添加商品', exact: true }).click();
    await modal.getByRole('textbox', { name: '商品名称第1行', exact: true }).fill('关闭后保留的录入内容');
    await modal.getByRole('button', { name: '关闭', exact: true }).click();
    await page.getByRole('button', { name: '⬆ 导入商品列表', exact: true }).click();
    assert.equal(await modal.getByRole('textbox', { name: '商品名称第1行', exact: true }).inputValue(), '关闭后保留的录入内容');
    passed.push('product catalog dialog shows controls before upload and retains edits when reopened');
    assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ passed, commits: commits.length, manualCommits: manualCommits.length, errors }, null, 2));
    console.log(JSON.stringify({ passed, output: out }, null, 2));
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true });
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ message: String(error), errors, unexpected, body: await page.locator('body').innerText() }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
