/* Local isolated UI regression: all APIs are mocked; never deletes production products. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = 'http://127.0.0.1:5176';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/product-bulk-delete-qa');
fs.mkdirSync(out, { recursive: true });
const passed = [], errors = [], deletes = [], unexpected = [];
let networkFailure = false;
let products = Array.from({ length: 21 }, (_, i) => ({ id: i + 1, skuCode: 'SP' + String(i + 1).padStart(6, '0'),
  productName: `测试商品 ${i + 1}`, productStatus: '在售', refPrice: 5, refCost: 2 }));
const ok = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
const until = async (fn, message) => {
  const end = Date.now() + 10000;
  while (!await fn()) { if (Date.now() > end) throw Error(message); await new Promise(r => setTimeout(r, 40)); }
};
(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
  await context.addInitScript(() => { localStorage.setItem('vend_token', 'bulk-test'); localStorage.setItem('vend_tour_seen', '1'); });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/*', async route => {
    const req = route.request(), url = new URL(req.url());
    if (url.origin !== base) { unexpected.push(req.url()); return route.abort(); }
    if (!url.pathname.startsWith('/api/')) return route.continue();
    if (url.pathname === '/api/v1/basedata/products' && req.method() === 'GET') {
      const keyword = url.searchParams.get('keyword') || '';
      const filtered = products.filter(p => p.productName.includes(keyword));
      const current = Number(url.searchParams.get('current') || 1), size = Number(url.searchParams.get('size') || 20);
      return ok(route, { records: filtered.slice((current - 1) * size, current * size), total: filtered.length, current, size });
    }
    if (/\/basedata\/products\/\d+$/.test(url.pathname) && req.method() === 'DELETE') {
      const id = Number(url.pathname.split('/').at(-1)); deletes.push(id);
      assert.equal(req.headers().authorization, 'Bearer bulk-test');
      await new Promise(r => setTimeout(r, 120));
      if (networkFailure && id === 4) return route.abort('failed');
      if (id === 2) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '「测试商品 2」已有销售记录，不能删除，请使用停售。' }) });
      products = products.filter(p => p.id !== id);
      return ok(route, null);
    }
    if (req.method() !== 'GET') { unexpected.push(req.method() + ' ' + url.pathname); return route.abort(); }
    if (url.pathname.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-11-07', records: [] });
    return ok(route, { records: [], total: 0 });
  });
  const bulkButton = () => page.getByRole('button', { name: /^批量删除/ });
  const list = () => page.locator('.el-table').first();
  const checkRow = id => list().getByRole('row').filter({ hasText: `SP${String(id).padStart(6, '0')}` }).locator('.el-checkbox').click();
  const dialog = name => page.getByRole('dialog', { name, exact: true });
  try {
    await page.goto(base + '/products');
    await until(() => list().getByRole('row').filter({ hasText: 'SP000001' }).count(), 'products missing');
    assert.equal(await bulkButton().isDisabled(), true);
    await checkRow(1); await checkRow(2);
    await bulkButton().click();
    const confirm = dialog('确认批量删除');
    await confirm.getByText('SP000001', { exact: true }).waitFor();
    await confirm.getByText('SP000002', { exact: true }).waitFor();
    await confirm.getByRole('button', { name: '取消', exact: true }).click();
    assert.deepEqual(deletes, []);
    await page.getByRole('button', { name: '取消选择', exact: true }).click();
    assert.equal(await bulkButton().isDisabled(), true);
    passed.push('no selection disables deletion; confirmation lists exact selected products; cancel sends no delete');

    await list().locator('.el-table__header-wrapper .el-checkbox').click();
    await until(async () => /20/.test(await bulkButton().innerText()), 'current-page select-all missing');
    await bulkButton().click();
    assert.equal(await confirm.getByText('SP000021', { exact: true }).count(), 0);
    await confirm.getByRole('button', { name: '取消', exact: true }).click();
    await page.locator('.btn-next').click();
    await list().getByText('SP000021', { exact: true }).waitFor();
    assert.equal(await bulkButton().isDisabled(), true);
    await checkRow(21); await bulkButton().click();
    await confirm.getByRole('button', { name: '确认删除 1 个商品', exact: true }).click();
    const result = dialog('批量删除结果');
    await result.getByText('处理完成：已删除 1 个，未删除或待核对 0 个', { exact: true }).waitFor();
    await result.getByRole('button', { name: '完成', exact: true }).click();
    await list().getByText('SP000001', { exact: true }).waitFor();
    assert.equal(await page.locator('.el-pager .is-active').innerText(), '1');
    assert.deepEqual(deletes, [21]);
    passed.push('select-all only selects this page; pagination clears selection; deleting last page returns to a valid page');

    await checkRow(1); await checkRow(2); await checkRow(3); await bulkButton().click();
    await confirm.getByRole('button', { name: '确认删除 3 个商品', exact: true }).evaluate(el => { el.click(); el.click(); });
    await result.getByText('处理完成：已删除 2 个，未删除或待核对 1 个', { exact: true }).waitFor();
    await result.getByText('「测试商品 2」已有销售记录，不能删除，请使用停售。', { exact: true }).waitFor();
    assert.deepEqual(deletes, [21, 1, 2, 3]);
    assert.equal(await result.getByRole('button', { name: /^确认删除/ }).count(), 0);
    await page.screenshot({ path: path.join(out, 'partial-result.png'), fullPage: true, animations: 'disabled' });
    await result.getByRole('button', { name: '完成', exact: true }).click();
    assert.equal(await list().getByText('SP000001', { exact: true }).count(), 0);
    await list().getByText('SP000002', { exact: true }).waitFor();
    passed.push('only selected IDs are deleted once; used product stays with its reason; successful and failed rows are distinguished');

    networkFailure = true;
    await checkRow(4); await checkRow(5); await bulkButton().click();
    await confirm.getByRole('button', { name: '确认删除 2 个商品', exact: true }).click();
    await result.getByText('处理完成：已删除 0 个，未删除或待核对 2 个', { exact: true }).waitFor();
    await result.getByText('待核对', { exact: true }).waitFor();
    await result.getByText('未执行', { exact: true }).waitFor();
    assert.deepEqual(deletes, [21, 1, 2, 3, 4]);
    await result.getByRole('button', { name: '完成', exact: true }).click();
    passed.push('network failure reports uncertain outcome and stops remaining requests without automatic retry');

    await checkRow(2);
    const search = page.getByPlaceholder('搜索商品名 / 编码 / 条码…');
    await search.fill('测试商品 6'); await search.press('Enter');
    await list().getByText('SP000006', { exact: true }).waitFor();
    assert.equal(await bulkButton().isDisabled(), true);
    await search.fill(''); await search.press('Enter');
    await list().getByText('SP000002', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(out, 'product-list.png'), fullPage: true, animations: 'disabled' });
    passed.push('search clears hidden selection and refresh leaves batch deletion disabled until products are selected');
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ passed, deletes, errors }, null, 2));
    console.log(JSON.stringify({ passed, output: out }, null, 2));
  } catch (error) {
    await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true, animations: 'disabled' });
    fs.writeFileSync(path.join(out, 'failure.json'), JSON.stringify({ error: String(error), errors, deletes, body: await page.locator('body').innerText() }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
