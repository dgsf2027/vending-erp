/* Isolated purchase UI regression. All API requests are mocked; no production writes. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const base = process.env.PURCHASE_WORKBOOK_BASE_URL || 'http://127.0.0.1:5177';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/purchase-workbook-qa'); fs.mkdirSync(out, { recursive: true });
const catalog = [
  [15, '元气森林气泡水维C橙味480ml'], [16, '元气森林气泡水葡萄味480ml'],
  [7, '外星人电解质水青柠600ml'], [22, '外星人电解质水荔枝600ml'], [23, '外星人电解质水PRO专业版600ml'],
].map(([id, productName]) => ({ id, productId: id, productName, skuCode: `YQ${String(id).padStart(6, '0')}`, unit: '瓶', boxSpec: 15, productStatus: '在售',
  salesQty: 123, firstSale: '2026-10-20 09:00:00', lastSale: '2026-11-06 09:00:00', salesNames: [productName], reason: '销售口味候选' }));
const source = [
  { rowNo: 5, bizDate: '2026-10-19', sourceName: '元气森林气泡水', skuCode: '', boxCount: 16, qty: 240, boxPrice: 50, totalPrice: 800, allocations: [], candidates: catalog.slice(0, 2), needsConfirmation: true },
  { rowNo: 19, bizDate: '2026-10-23', sourceName: '外星人电解质水(荔枝)', skuCode: 'YQ000007', boxCount: 5, qty: 75, boxPrice: 60, totalPrice: 300, allocations: [{ productId: 22, qty: 75 }], suggestedProductId: 22, candidates: catalog.slice(2), needsConfirmation: true },
  { rowNo: 29, bizDate: '2026-10-23', sourceName: '外星人电解质水(专业版)', skuCode: 'YQ000007', boxCount: 4, qty: 60, boxPrice: 60, totalPrice: 240, allocations: [{ productId: 23, qty: 60 }], suggestedProductId: 23, candidates: catalog.slice(2), needsConfirmation: true },
  { rowNo: 34, bizDate: '2026-10-26', sourceName: '元气森林气泡橙水', skuCode: 'YQ000015', boxCount: 5, qty: 75, boxPrice: 50, totalPrice: 250, allocations: [{ productId: 15, qty: 75 }], candidates: catalog.slice(0, 2), needsConfirmation: false },
  { rowNo: 35, bizDate: '2026-10-26', sourceName: '气泡橙水', skuCode: 'YQ000015', boxCount: 2, qty: 30, boxPrice: 50, totalPrice: 100, allocations: [{ productId: 15, qty: 30 }], candidates: catalog.slice(0, 2), needsConfirmation: false },
].map(r => ({ ...r, boxSpec: 15, unitPrice: Number((r.totalPrice / r.qty).toFixed(4)), hint: '', confirmed: false,
  matchMessage: r.needsConfirmation ? '表中编码与销售口味需核对：请确认本次采购归属' : '按表中商品编码匹配', errors: [] }));
const evaluate = input => {
  const rows = structuredClone(input), days = [];
  for (const row of rows) {
    row.errors = [];
    if (!row.allocations.length) row.errors.push('请选择对应的销售商品');
    if (row.allocations.some(a => !a.productId || !(a.qty > 0))) row.errors.push('各口味必须选择商品并填写大于0的数量');
    if (row.needsConfirmation && !row.confirmed) row.errors.push('请确认这行的口味与销售商品关联');
    if (row.allocations.reduce((sum, a) => sum + (a.qty || 0), 0) !== row.qty) row.errors.push('各口味数量之和必须等于原行采购数量');
    let day = days.find(d => d.bizDate === row.bizDate);
    if (!day) { day = { bizDate: row.bizDate, totalQty: 0, totalAmount: 0, sourceCount: 0, lines: [], errors: [] }; days.push(day); }
    day.sourceCount++; day.totalQty += row.qty; day.totalAmount += row.totalPrice; day.errors.push(...row.errors);
    if (!row.errors.length) for (const a of row.allocations) {
      const p = catalog.find(p => p.id === a.productId); if (!p) continue;
      let group = day.lines.find(g => g.productId === a.productId);
      if (!group) { group = { productId: p.id, skuCode: p.skuCode, productName: p.productName, unit: '瓶', qty: 0, totalPrice: 0, sourceRows: [], sourceNames: [] }; day.lines.push(group); }
      group.qty += a.qty; group.totalPrice += Number((row.totalPrice * a.qty / row.qty).toFixed(2)); group.unitPrice = Number((group.totalPrice / group.qty).toFixed(4)); group.sourceRows.push(row.rowNo); group.sourceNames.push(row.sourceName);
    }
  }
  return { rows, days, skippedSubtotals: 3, errors: [], warnings: [] };
};
const passed = [], errors = [], writes = [], creates = [], updates = [], confirms = [];
let nextId = 1, failConfirmation = true;
const documents = new Map();
const ok = (route, data) => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
const until = async fn => { const end = Date.now() + 10000; while (!await fn()) { if (Date.now() > end) throw Error('Condition timed out'); await new Promise(r => setTimeout(r, 30)); } };
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 }, acceptDownloads: true });
  await context.addInitScript(() => { localStorage.setItem('vend_token', 'purchase-workbook-qa'); localStorage.setItem('vend_tour_seen', '1'); });
  const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname;
    if (!p.startsWith('/api/')) return route.continue();
    if (p.endsWith('/receipt/workbook/preview')) return ok(route, evaluate(source));
    if (p.endsWith('/receipt/workbook/review')) return ok(route, evaluate(request.postDataJSON().rows));
    if (p.endsWith('/receipt/workbook/template')) { assert.equal(request.headers().authorization, 'Bearer purchase-workbook-qa'); return route.fulfill({ contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('PK-mocked-template') }); }
    if (p.endsWith('/purchase/receipts') && request.method() === 'POST') {
      const body = request.postDataJSON(); creates.push(body);
      const id = nextId++, doc = { head: { ...body, id, docNo: `QA-${id}`, docStatus: '草稿', totalAmount: body.items.reduce((s, r) => s + (r.amount ?? r.qty * r.unitPrice), 0) }, items: body.items };
      documents.set(id, doc); return ok(route, doc);
    }
    if (/\/purchase\/receipts\/\d+$/.test(p) && request.method() === 'PUT') { updates.push(request.postDataJSON()); return ok(route, documents.get(Number(p.split('/').at(-1)))); }
    if (/\/purchase\/receipts\/\d+\/confirm$/.test(p)) {
      confirms.push(p); if (failConfirmation) { failConfirmation = false; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 500, message: '模拟确认失败，可重试原草稿' }) }); }
      const doc = documents.get(Number(p.split('/').at(-2))); doc.head.docStatus = '已确认'; return ok(route, doc);
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) { writes.push(p); return route.abort(); }
    if (p.endsWith('/purchase/receipts')) return ok(route, [...documents.values()].map(d => d.head));
    if (p.endsWith('/purchase/in-transit')) return ok(route, []);
    if (p.endsWith('/basedata/suppliers')) return ok(route, { records: [{ id: 1, supplierName: '测试供应商' }], total: 1 });
    if (p.endsWith('/basedata/products')) return ok(route, { records: catalog, total: catalog.length });
    if (/\/basedata\/products\/\d+$/.test(p)) return ok(route, catalog.find(v => v.id === Number(p.split('/').at(-1))));
    if (p.endsWith('/purchase/price-check')) return ok(route, { lastPrice: null, history: [], warn: false });
    if (p.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-11-06', records: [] });
    return ok(route, { records: [], total: 0 });
  });
  try {
    await page.goto(base + '/purchase'); await page.getByRole('button', { name: '按表格导入', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '采购拿货表 · 汇总与销售关联', exact: true });
    await dialog.waitFor(); const download = page.waitForEvent('download'); await dialog.getByRole('button', { name: '下载同格式模板' }).click();
    assert.equal((await download).suggestedFilename(), '采购拿货表导入模板.xlsx');
    await dialog.locator('input[type=file]').setInputFiles({ name: '拿货原表.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('mocked') });
    await dialog.getByText('5 行明细 · 3 次采购', { exact: true }).waitFor(); assert.equal(creates.length, 0);
    await dialog.getByRole('button', { name: '带入 2026-10-19 入库单', exact: true }).click();
    await dialog.getByText('这次采购还有待核对的口味、数量或日期，请先处理下方提示。', { exact: true }).waitFor(); assert.equal(creates.length, 0);
    passed.push('authenticated template, multi-date preview and unresolved flavors blocked without writing');

    const card = dialog.locator('.source-card').first();
    await card.locator('.candidate').filter({ hasText: 'YQ000015' }).click();
    await card.getByRole('button', { name: '＋ 混装，拆分口味' }).click();
    await card.locator('.allocation').first().getByRole('spinbutton').fill('6'); await card.locator('.allocation').first().getByRole('spinbutton').press('Tab');
    const second = card.locator('.allocation').nth(1); await second.locator('.el-select').click();
    await page.getByRole('option', { name: /YQ000016/ }).last().click(); await second.getByRole('spinbutton').fill('9'); await second.getByRole('spinbutton').press('Tab');
    await card.getByRole('button', { name: '确认本行关联', exact: true }).click(); await dialog.getByRole('button', { name: '重新核对', exact: true }).click();
    await card.getByText(/各口味数量之和必须等于/).waitFor(); assert.equal(creates.length, 0);
    await second.getByRole('spinbutton').fill('10'); await second.getByRole('spinbutton').press('Tab'); await card.getByRole('button', { name: '确认本行关联', exact: true }).click();
    await dialog.getByRole('button', { name: '带入 2026-10-19 入库单', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    const receipt = page.getByRole('dialog').filter({ hasText: '收货录入 · 直接录入' }); await receipt.waitFor();
    assert.ok((await receipt.textContent()).includes('800.00'));
    // Merely focusing/blurring a number field must retain the original box amount.
    await receipt.getByRole('spinbutton').nth(1).focus(); await receipt.getByRole('spinbutton').nth(1).press('Tab');
    await receipt.locator('.el-select').first().click(); await page.getByRole('option', { name: '测试供应商', exact: true }).click();
    await receipt.getByRole('button', { name: '仅存草稿', exact: true }).click(); await receipt.waitFor({ state: 'hidden' });
    assert.equal(creates.length, 1); assert.equal(creates[0].bizDate, '2026-10-19'); assert.deepEqual(creates[0].items.map(i => [i.productId, i.qty, i.amount]), [[15, 90, 300], [16, 150, 500]]);
    passed.push('mixed flavors entered by boxes; quantity mismatch blocks; exact money and date reach saved draft');

    await page.getByRole('button', { name: '按表格导入', exact: true }).click(); await dialog.waitFor();
    assert.equal(await dialog.getByRole('button', { name: '带入 2026-10-19 入库单', exact: true }).isEnabled(), false);
    await dialog.locator('.date-cards button').filter({ hasText: '2026-10-23' }).click();
    await page.screenshot({ path: path.join(out, 'desktop-flavor-review.png'), animations: 'disabled', fullPage: true });
    const flavorCards = dialog.locator('.source-card');
    await flavorCards.nth(0).getByRole('button', { name: '确认本行关联', exact: true }).click();
    await flavorCards.nth(1).getByRole('button', { name: '确认本行关联', exact: true }).click();
    await dialog.getByRole('button', { name: '带入 2026-10-23 入库单', exact: true }).click(); await receipt.waitFor();
    await receipt.locator('.el-select').first().click(); await page.getByRole('option', { name: '测试供应商', exact: true }).click();
    await receipt.getByRole('button', { name: '✓ 确认入库', exact: true }).click(); await until(() => confirms.length === 1);
    await receipt.getByRole('button', { name: '✓ 确认入库', exact: true }).click(); await receipt.waitFor({ state: 'hidden' });
    assert.equal(creates.length, 2); assert.equal(updates.length, 1); assert.equal(confirms.length, 2);
    assert.deepEqual(creates[1].items.map(i => i.productId), [22, 23]);
    passed.push('flavor recommendations retain separate codes; confirmation failure retries same draft without duplicate creation');

    await page.getByRole('button', { name: '按表格导入', exact: true }).click();
    await dialog.locator('.date-cards button').filter({ hasText: '2026-10-26' }).click();
    assert.equal(await dialog.locator('.summary-table .el-table__body tr').count(), 1);
    assert.match(await dialog.locator('.summary-table .el-table__body tr').first().innerText(), /第\s*34、\s*35\s*行/);
    passed.push('same-day same-code lines merge and show original row references; other dates remain separate');
    await page.setViewportSize({ width: 390, height: 844 });
    await dialog.locator('.date-cards button').filter({ hasText: '2026-10-19' }).click();
    await dialog.locator('label.el-checkbox').click();
    await page.screenshot({ path: path.join(out, 'mobile-workbook.png'), animations: 'disabled', fullPage: false });
    const box = await dialog.boundingBox(), button = await dialog.getByRole('button', { name: '带入 2026-10-19 入库单', exact: true }).boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 391 && button.y + button.height <= 844);
    assert.equal(await dialog.evaluate(el => el.scrollWidth > el.clientWidth + 1), false);
    assert.deepEqual(errors, []); assert.deepEqual(writes, []);
    passed.push('mobile preview and footer fit viewport; no browser errors or unexpected writes');
    console.log(JSON.stringify({ passed }, null, 2));
  } catch (e) { await page.screenshot({ path: path.join(out, 'failure.png'), animations: 'disabled', fullPage: true }); console.error(e); console.error({ errors, writes }); process.exitCode = 1; }
  finally { await browser.close(); }
})();
