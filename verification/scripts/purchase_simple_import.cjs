/* All HTTP API requests mocked. Real-file preview fixture comes from the isolated Java test. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const base = process.env.PURCHASE_WORKBOOK_BASE_URL || 'http://127.0.0.1:5177';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/simple-purchase-qa');
const fixture = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../vending-erp/frontend/.vite/simple-purchase-fixture.json'), 'utf8'));
fs.mkdirSync(out, { recursive: true });
const passed = [], errors = [], submissions = [], blocked = [];
let fail = true, imported = false;
const ok = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
  await context.addInitScript(() => { localStorage.setItem('vend_token', 'simple-purchase-qa'); localStorage.setItem('vend_tour_seen', '1'); });
  const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request(), p = new URL(req.url()).pathname;
    if (!p.startsWith('/api/')) return route.continue();
    if (p.endsWith('/receipt/workbook/simple-preview')) return ok(route, { ...fixture, receiptIds: imported ? [51, 52, 53] : [] });
    if (p.endsWith('/receipt/workbook/template')) { assert.equal(req.headers().authorization, 'Bearer simple-purchase-qa'); return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('PK-test-template') }); }
    if (p.endsWith('/receipt/workbook/commit')) {
      submissions.push(req.postDataJSON());
      if (fail) { fail = false; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 500, message: '模拟网络失败，请重试' }) }); }
      imported = true; return ok(route, { receiptIds: [51, 52, 53], alreadyImported: false });
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method())) { blocked.push(p); return route.abort(); }
    if (p.endsWith('/purchase/receipts') || p.endsWith('/purchase/in-transit')) return ok(route, []);
    if (p.endsWith('/basedata/suppliers')) return ok(route, { records: [{ id: 1, supplierName: '测试供应商' }], total: 1 });
    if (p.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-11-06', records: [] });
    return ok(route, { records: [], total: 0 });
  });
  try {
    await page.goto(base + '/purchase');
    await page.getByRole('button', { name: '按表格导入', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: '采购入库 · 确认口味即可', exact: true });
    const download = page.waitForEvent('download'); await dialog.getByRole('button', { name: '下载模板', exact: true }).click();
    assert.equal((await download).suggestedFilename(), '采购拿货表导入模板.xlsx');
    const upload = () => dialog.locator('input[type=file]').setInputFiles({ name: '元气森林拿货.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('mocked') });
    await upload(); await dialog.getByText('✓ 29 行商品已自动匹配').waitFor();
    assert.equal(await dialog.locator('.stock-group').count(), 3); assert.equal(await dialog.getByRole('spinbutton').count(), 0);
    assert.equal(await dialog.locator('.details').count(), 0); assert.equal(submissions.length, 0);
    await page.screenshot({ path: path.join(out, '01-preview.png'), fullPage: true });
    passed.push('37 original rows become 29 automatic rows and three family confirmations; no flavor quantities or receipt writes');
    const submit = dialog.getByRole('button', { name: /确认并全部入库/ }); assert.equal(await submit.isDisabled(), true);
    const first = dialog.locator('.stock-group').first();
    const lastFlavor = first.getByRole('checkbox').last(); await first.locator('.el-checkbox').last().click();
    assert.equal(await lastFlavor.isChecked(), false); await first.locator('.el-checkbox').last().click();
    for (let i = 0; i < 3; i++) await dialog.getByRole('button', { name: '确认共用库存', exact: true }).first().click();
    assert.equal(await dialog.locator('.stock-group.confirmed').count(), 3); assert.equal(await submit.isDisabled(), true);
    await dialog.locator('.supplier .el-select').click(); await page.getByRole('option', { name: '测试供应商' }).click();
    assert.equal(await submit.isEnabled(), true);
    await dialog.getByRole('button', { name: '已确认 · 修改' }).first().click(); assert.equal(await submit.isDisabled(), true);
    await dialog.getByRole('button', { name: '确认共用库存', exact: true }).click();
    passed.push('supplier and explicit group confirmations required; changing a group invalidates its confirmation');
    await dialog.getByRole('button', { name: '查看全部明细与日期汇总' }).click();
    await dialog.getByText('2026-10-19 · 15 行 · ¥8489.00').waitFor();
    await dialog.getByText('2026-10-23 · 13 行 · ¥3489.00').waitFor();
    await dialog.getByText('2026-10-26 · 9 行 · ¥1998.00').waitFor();
    await dialog.getByRole('button', { name: '收起全部明细与日期汇总' }).click();
    await page.screenshot({ path: path.join(out, '02-ready.png'), fullPage: true });
    await submit.click(); await dialog.getByText('模拟网络失败，请重试', { exact: true }).waitFor();
    assert.equal(submissions.length, 1); await submit.click(); await dialog.getByText('这张拿货表已完成入库', { exact: true }).waitFor();
    assert.deepEqual(submissions[0], submissions[1]); assert.equal(submissions[0].groups.length, 3);
    assert.deepEqual(submissions[0].rows, []); assert.equal('qty' in submissions[0], false);
    assert.equal(await page.getByRole('dialog', { name: /收货录入/ }).count(), 0);
    passed.push('original three dates/totals visible on demand; one commit with safe retry, no second receipt form');
    await upload(); await dialog.getByText('这张拿货表已完成入库', { exact: true }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: /确认并全部入库/ }).count(), 0);
    passed.push('already imported workbook cannot be submitted again');
    imported = false; await upload(); await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(out, '03-mobile.png'), fullPage: true });
    assert.equal(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth + 2), true);
    const footer = await dialog.locator('.el-dialog__footer').boundingBox(); assert.ok(footer.y + footer.height <= 844);
    passed.push('mobile layout keeps actions visible without horizontal overflow');
    await page.setViewportSize({ width: 1440, height: 1050 });
    await dialog.getByRole('button', { name: '尚未收到货？改用订货单导入' }).click();
    await page.getByRole('dialog', { name: '采购拿货表 · 汇总与销售关联', exact: true }).waitFor();
    passed.push('explicit order workflow remains accessible');
    assert.deepEqual(errors, []); assert.deepEqual(blocked, []);
    console.log(JSON.stringify({ passed, submissions: submissions.length, apiWritesIsolated: true, screenshots: out }, null, 2));
  } catch (e) { await page.screenshot({ path: path.join(out, 'failure.png'), fullPage: true }); console.error({ url: page.url(), errors, body: (await page.locator('body').innerText()).slice(0, 1800) }); throw e; }
  finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
