/* Purchase photo regression: synthetic images/data; every /api request is mocked.
 * Start the frontend locally, then run:
 *   node verification/scripts/purchase_photo_recognition.cjs
 * Optional: PURCHASE_PHOTO_BASE_URL, PLAYWRIGHT_MODULE, PLAYWRIGHT_CHANNEL,
 * PLAYWRIGHT_EXECUTABLE_PATH, PURCHASE_PHOTO_QA_DIR.
 * The slow-response case intentionally takes >15 seconds to reproduce the old bug.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const output = process.env.PURCHASE_PHOTO_QA_DIR || path.join(root, 'vending-erp/frontend/.vite/purchase-photo-qa');
const baseURL = process.env.PURCHASE_PHOTO_BASE_URL || 'http://127.0.0.1:5174';
const token = 'synthetic-purchase-photo-token';
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aT1kAAAAASUVORK5CYII=', 'base64');
const product = { id: 7, skuCode: 'TEST007', productName: '测试绿茶1L', unit: '瓶', boxSpec: 12, productStatus: '在售' };
const otherProduct = { id: 8, skuCode: 'TEST008', productName: '测试袋装咖啡', unit: '袋', boxSpec: 24, productStatus: '在售' };
const supplier = { id: 31, supplierCode: 'TEST-SUPPLIER', supplierName: '测试送货商', coopStatus: '合作中' };
const passed = [];
const errors = [];
const requests = [];
const photoRequests = [];
const draftRequests = [];
const unexpectedWrites = [];

function preview(overrides = {}) {
  return {
    documentNo: 'TEST-PHOTO-001', bizDate: '2026-10-27', supplierName: supplier.supplierName,
    supplierId: supplier.id, declaredTotal: 180, calculatedTotal: 180, warnings: [],
    rows: [
      { rowNo: 1, sourceName: '测试绿茶', sourceSpec: '1L', sourceUnit: '箱', unitMode: 'box',
        productId: product.id, productName: product.productName, skuCode: product.skuCode,
        boxSpec: product.boxSpec, unit: product.unit, qty: 5, unitPrice: 36,
        sourceAmount: 180, calculatedAmount: 180, crossedOut: false, uncertain: false, warnings: [] },
      { rowNo: 2, sourceName: '划掉商品（测试）', sourceSpec: null, sourceUnit: null, unitMode: null,
        productId: null, productName: null, skuCode: null, boxSpec: null, unit: null,
        qty: 3, unitPrice: 30, sourceAmount: 90, calculatedAmount: null,
        crossedOut: true, uncertain: true, warnings: ['票面已划掉，默认不带入入库单'] },
    ],
    ...overrides,
  };
}

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}

async function until(check, message, timeout = 10000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  assert.fail(message);
}

async function ok(route, data) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
}

function dialog(page) {
  return page.getByRole('dialog', { name: /拍照识别/ });
}

async function openPhoto(page) {
  await page.getByRole('button', { name: '📷 拍照识别', exact: true }).click();
  await dialog(page).waitFor({ state: 'visible' });
  return dialog(page);
}

async function closePhoto(page) {
  await dialog(page).getByRole('button', { name: '取消', exact: true }).click();
  await dialog(page).waitFor({ state: 'hidden' });
}

async function pick(page, name = 'synthetic-delivery-note.png') {
  await dialog(page).locator('input[type="file"]').setInputFiles({ name, mimeType: 'image/png', buffer: png });
}

async function waitForDocument(page, number = 'TEST-PHOTO-001') {
  await dialog(page).locator('.photo-meta').getByText(number, { exact: true }).waitFor({ timeout: 25000 });
}

async function setup(browser, viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport });
  await context.addInitScript(({ token }) => {
    localStorage.setItem('vend_token', token);
    localStorage.setItem('vend_user_name', '采购照片回归测试');
    localStorage.setItem('vend_user_role', '测试用户');
    localStorage.setItem('vend_tour_seen', '1');
  }, { token });
  const page = await context.newPage();
  const state = { photoHandler: route => ok(route, preview()) };
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    // Vite module URLs such as /src/api/purchase.ts are source files, not API calls.
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const info = { path: url.pathname, method: request.method(), auth: request.headers().authorization };
    requests.push(info);
    if (url.pathname.endsWith('/purchase/import/receipt/photo-preview')) {
      photoRequests.push({ ...info, contentType: request.headers()['content-type'], startedAt: Date.now() });
      return state.photoHandler(route);
    }
    if (url.pathname.endsWith('/purchase/receipts') && request.method() === 'POST') {
      const payload = request.postDataJSON();
      draftRequests.push(payload);
      return ok(route, {
        head: { id: 901, docNo: 'TEST-DRAFT-901', docStatus: '草稿', supplierId: payload.supplierId, bizDate: payload.bizDate },
        items: payload.items.map((item, i) => ({ id: 1001 + i, ...item, amount: item.qty * item.unitPrice })),
      });
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      unexpectedWrites.push(info);
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '测试拒绝未预期的写操作' }) });
    }
    if (url.pathname.endsWith('/basedata/suppliers')) return ok(route, { records: [supplier], total: 1, current: 1, size: 200 });
    if (url.pathname.endsWith('/basedata/products/7')) return ok(route, product);
    if (url.pathname.endsWith('/basedata/products/8')) return ok(route, otherProduct);
    if (url.pathname.endsWith('/basedata/products')) return ok(route, { records: [product, otherProduct], total: 2, current: 1, size: 20 });
    if (url.pathname.endsWith('/purchase/receipts') || url.pathname.endsWith('/purchase/in-transit')) return ok(route, []);
    if (url.pathname.endsWith('/purchase/price-check')) return ok(route, { lastPrice: 3, compareScope: '同供应商', diffPct: 0, warn: false, history: [] });
    if (url.pathname.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-10-27', records: [] });
    return ok(route, { records: [], total: 0, current: 1, size: 10 });
  });
  await page.goto(`${baseURL}/purchase`);
  await page.getByRole('button', { name: '📷 拍照识别', exact: true }).waitFor({ timeout: 10000 }).catch(async error => {
    await page.screenshot({ path: path.join(output, 'photo-recognition-setup-failure.png'), fullPage: true, animations: 'disabled' });
    fs.writeFileSync(path.join(output, 'photo-recognition-setup-failure.json'), JSON.stringify({ errors, requests, body: await page.locator('body').innerText() }, null, 2));
    throw error;
  });
  return { page, context, state };
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
    ...(process.env.PLAYWRIGHT_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH } : {}),
  });
  let activePage;
  try {
    const { page, context, state } = await setup(browser);
    activePage = page;

    // The old Axios instance aborted at 15 s. Hold a successful response beyond that limit.
    const slow = deferred();
    state.photoHandler = async route => { await slow.promise; return ok(route, preview()); };
    await openPhoto(page);
    await pick(page);
    await until(() => photoRequests.length === 1, 'Photo selection must automatically request recognition');
    await dialog(page).locator('.photo-progress').waitFor();
    await page.waitForTimeout(16200);
    assert.equal(await dialog(page).locator('.photo-error').count(), 0, 'A request lasting >15 s must not fail early');
    assert.equal(await dialog(page).locator('.photo-progress').isVisible(), true, 'Recognition remains in progress after the former deadline');
    assert.equal(draftRequests.length, 0, 'Recognition must not create a receipt');
    slow.resolve();
    await waitForDocument(page);
    const rows = dialog(page).locator('.el-table__body tbody tr');
    assert.equal(await rows.count(), 2, 'Both active and crossed-out source rows stay visible');
    const first = rows.nth(0);
    await until(async () => (await first.innerText()).includes(product.productName), 'Recognized SKU must populate the product selector');
    assert.match(await first.locator('.photo-source').innerText(), /票面：测试绿茶/);
    assert.equal(Number(await first.locator('td').nth(3).getByRole('spinbutton').inputValue()), 5);
    assert.equal(Number(await first.locator('td').nth(4).getByRole('spinbutton').inputValue()), 36);
    assert.match(await first.locator('.photo-unit').innerText(), /整箱/);
    assert.match(await first.innerText(), /票面单位：箱/);
    assert.match(await first.innerText(), /60\s*瓶/);
    assert.match(await first.innerText(), /3\.0000/);
    assert.equal(await rows.nth(1).getByRole('checkbox').isChecked(), false, 'Crossed-out row must be excluded by default');
    assert.match(await dialog(page).locator('.photo-totals').innerText(), /180\.00/);
    assert.match(await dialog(page).locator('.photo-fields').innerText(), /测试送货商/);
    assert.equal(await dialog(page).getByPlaceholder('入库日期（必选）').inputValue(), '2026-10-27');
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    assert.equal(await dialog(page).getByRole('button', { name: '重新识别', exact: true }).count(), 0, 'Successful editable results must not offer a destructive retry');
    assert.equal(photoRequests[0].auth, `Bearer ${token}`);
    assert.match(photoRequests[0].contentType, /^multipart\/form-data; boundary=/);
    assert.equal(draftRequests.length, 0);
    assert.deepEqual(unexpectedWrites, []);
    await page.screenshot({ path: path.join(output, 'photo-recognition-desktop.png'), fullPage: true, animations: 'disabled' });
    passed.push('response after 16.2 seconds automatically populates SKU, supplier, date, quantity, price and explicit box unit');
    passed.push('box conversion is 60 bottles at 3 yuan and crossed-out row is excluded');

    await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).click();
    await until(() => draftRequests.length === 1, 'Draft creation should occur only after the user clicks its button');
    assert.deepEqual(draftRequests[0].items, [{ productId: 7, qty: 60, expectQty: null, unitPrice: 3, poItemId: null }]);
    assert.equal(draftRequests[0].supplierId, 31);
    assert.equal(draftRequests[0].bizDate, '2026-10-27');
    assert.deepEqual(unexpectedWrites, [], 'Creating a draft must not call confirm or stock APIs');
    const receive = page.getByRole('dialog').filter({ hasText: '收货录入' });
    await receive.getByText('TEST-DRAFT-901', { exact: false }).waitFor();
    await receive.getByRole('button', { name: '关闭', exact: true }).click();
    await receive.waitFor({ state: 'hidden' });
    passed.push('only explicit draft submission writes a correctly converted receipt; no inventory confirmation');

    // No quantity unit evidence must stay unresolved, even when every other field is valid.
    const unknown = preview();
    unknown.documentNo = 'TEST-UNKNOWN-UNIT';
    unknown.rows = [{ ...unknown.rows[0], sourceUnit: null, unitMode: null }];
    state.photoHandler = route => ok(route, unknown);
    await openPhoto(page);
    await pick(page, 'unknown-unit.png');
    await waitForDocument(page, unknown.documentNo);
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true);
    await dialog(page).locator('.photo-unit .el-select').click();
    await page.locator('.el-select-dropdown:visible').getByRole('option', { name: '整箱', exact: true }).click();
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    await closePhoto(page);
    passed.push('unknown unit blocks submission until explicitly reviewed');

    // Quantity interpretation from the old SKU must not survive choosing a different product.
    const rematch = preview({ documentNo: 'TEST-REMATCH' });
    rematch.rows = [{ ...rematch.rows[0], sourceUnit: '瓶', unitMode: 'base' }];
    state.photoHandler = route => ok(route, rematch);
    await openPhoto(page);
    await pick(page, 'rematch-product.png');
    await waitForDocument(page, rematch.documentNo);
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    await dialog(page).locator('.el-table__body tbody tr').first().locator('.el-select').first().click();
    await page.locator('.el-select-dropdown:visible').getByRole('option').filter({ hasText: otherProduct.skuCode }).click();
    await until(async () => (await dialog(page).locator('.el-table__body tbody tr').first().innerText()).includes(otherProduct.productName), 'Selected replacement SKU must be displayed');
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true, 'Changing SKU must clear the old quantity interpretation');
    await dialog(page).locator('.photo-unit .el-select').click();
    await page.locator('.el-select-dropdown:visible').getByRole('option', { name: '基本单位（袋）', exact: true }).click();
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    await closePhoto(page);
    passed.push('changing product clears the old unit and requires explicit reconfirmation');

    // One unit choice covers all included rows; every SKU keeps its own pack size.
    const batch = preview({ documentNo: 'TEST-BATCH-REVIEW', declaredTotal: 324 });
    const candidate = p => ({ productId: p.id, productName: p.productName, skuCode: p.skuCode, boxSpec: p.boxSpec, unit: p.unit, score: 0.94 });
    batch.rows = [
      { ...batch.rows[0], sourceUnit: null, unitMode: null, matchKind: 'suggested', candidates: [candidate(product)] },
      { ...batch.rows[0], rowNo: 2, sourceName: '测试咖啡简称', sourceSpec: null, sourceUnit: null, unitMode: null,
        productId: null, productName: null, skuCode: null, boxSpec: null, unit: null, matchKind: 'none',
        qty: 3, unitPrice: 48, sourceAmount: 144, candidates: [candidate(otherProduct)],
        warnings: ['商品未能唯一匹配，请手动选择档案', '票面单位未能确定，请核对数量和单价按箱还是基本单位填写'] },
      { ...batch.rows[1], rowNo: 3 },
    ];
    state.photoHandler = route => ok(route, batch);
    await openPhoto(page);
    await pick(page, 'batch-review.png');
    await waitForDocument(page, batch.documentNo);
    assert.match(await dialog(page).locator('.photo-summary').innerText(), /含 1 行推荐/);
    assert.match(await dialog(page).locator('.el-table__body tbody tr').first().innerText(), /推荐 · 请核对/);
    await dialog(page).locator('.photo-batch').getByText('全部按箱', { exact: true }).click();
    await dialog(page).locator('.photo-candidates').getByRole('button', { name: /测试袋装咖啡/ }).click();
    const batchRows = dialog(page).locator('.el-table__body tbody tr');
    assert.match(await batchRows.nth(0).innerText(), /60\s*瓶/);
    assert.match(await batchRows.nth(1).innerText(), /72\s*袋/);
    assert.match(await batchRows.nth(1).innerText(), /2\.0000/);
    assert.doesNotMatch(await batchRows.nth(1).innerText(), /商品未能唯一匹配|票面单位未能确定|请在上方统一选择/);
    assert.equal(await batchRows.nth(2).locator('.photo-unit').count(), 0, 'Excluded rows stay excluded during a batch operation');
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true, 'Suggested products require one whole-document acknowledgement');
    await dialog(page).locator('.photo-summary .el-checkbox').click();
    assert.equal(await dialog(page).locator('.el-table__body tbody tr').count(), 1, 'Only suggested row still needs review');
    await dialog(page).locator('.photo-summary .el-checkbox').click();
    await dialog(page).locator('.photo-batch').getByText('全部按基本单位', { exact: true }).click();
    assert.match(await batchRows.nth(0).innerText(), /入库 5\s*瓶/);
    assert.match(await batchRows.nth(1).innerText(), /入库 3\s*袋/);
    await dialog(page).locator('.photo-batch').getByText('按识别结果', { exact: true }).click();
    assert.match(await batchRows.nth(0).innerText(), /请在上方统一选择数量单位/);
    await dialog(page).locator('.photo-batch').getByText('全部按箱', { exact: true }).click();
    await batchRows.nth(1).getByRole('spinbutton', { name: '每箱数量', exact: true }).fill('');
    await batchRows.nth(1).getByRole('spinbutton', { name: '每箱数量', exact: true }).blur();
    assert.match(await batchRows.nth(1).innerText(), /请填写每箱数量/);
    await dialog(page).locator('.photo-acknowledged').click();
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true, 'Batch choice must not invent missing pack sizes');
    await batchRows.nth(1).getByRole('spinbutton', { name: '每箱数量', exact: true }).fill('24');
    await batchRows.nth(1).getByRole('spinbutton', { name: '每箱数量', exact: true }).blur();
    assert.equal(await dialog(page).locator('.photo-acknowledged').getByRole('checkbox').isChecked(), false, 'Edits invalidate the previous acknowledgement');
    await dialog(page).locator('.photo-acknowledged').click();
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    await page.screenshot({ path: path.join(output, 'photo-batch-review-desktop.png'), fullPage: true, animations: 'disabled' });
    await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).click();
    await until(() => draftRequests.length === 2, 'Explicit batch draft submission must complete');
    assert.deepEqual(draftRequests[1].items, [
      { productId: 7, qty: 60, expectQty: null, unitPrice: 3, poItemId: null },
      { productId: 8, qty: 72, expectQty: null, unitPrice: 2, poItemId: null },
    ]);
    await receive.getByText('TEST-DRAFT-901', { exact: false }).waitFor();
    await receive.getByRole('button', { name: '关闭', exact: true }).click();
    await receive.waitFor({ state: 'hidden' });
    passed.push('suggested SKU is prefilled, candidates resolve with one click, batch units use individual pack sizes and a single acknowledgement creates a correct draft');
    passed.push('pending filter, reverting unit mode, missing pack size block and acknowledgement reset work');

    // A long receipt must bring a newly added row into view; removal only changes this preview.
    const longReceipt = preview({ documentNo: 'TEST-ROW-ACTIONS', declaredTotal: 2160 });
    longReceipt.rows = Array.from({ length: 12 }, (_, i) => ({ ...longReceipt.rows[0], rowNo: i + 1 }));
    state.photoHandler = route => ok(route, longReceipt);
    await openPhoto(page);
    await pick(page, 'row-actions.png');
    await waitForDocument(page, longReceipt.documentNo);
    await dialog(page).locator('.photo-batch').getByText('全部按箱', { exact: true }).click();
    const addRow = dialog(page).getByRole('button', { name: '＋ 手工添加一行', exact: true });
    const visibleRows = dialog(page).locator('.el-table__body tbody tr');
    await addRow.click();
    assert.equal(await visibleRows.count(), 13);
    const newRow = visibleRows.filter({ has: page.getByRole('button', { name: '删除第13行', exact: true }) });
    assert.match(await newRow.innerText(), /新增行/);
    assert.doesNotMatch(await newRow.innerText(), /字迹不清/);
    assert.match(await newRow.locator('.photo-unit').innerText(), /整箱/);
    await until(() => newRow.evaluate(row => row.contains(document.activeElement)), 'Adding a row must focus its product search');
    const newRowVisible = await newRow.evaluate(row => {
      const bounds = row.getBoundingClientRect();
      const viewport = row.closest('.el-scrollbar__wrap').getBoundingClientRect();
      return bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1;
    });
    assert.equal(newRowVisible, true, 'The new row must be fully visible inside the scrolled table');
    await newRow.locator('.photo-source input').fill('保留我的手工备注');
    await addRow.click();
    await addRow.click();
    assert.equal(await visibleRows.count(), 15);
    await dialog(page).getByRole('button', { name: '清理空白行（2）', exact: true }).click();
    assert.equal(await visibleRows.count(), 13, 'Only untouched manual rows are cleaned up');
    assert.equal(await newRow.locator('.photo-source input').inputValue(), '保留我的手工备注');
    await dialog(page).getByRole('button', { name: '撤销删除', exact: true }).click();
    assert.equal(await visibleRows.count(), 15, 'Bulk cleanup can be undone');
    await dialog(page).getByRole('button', { name: '删除第14行', exact: true }).click();
    await addRow.click();
    assert.equal(await visibleRows.count(), 15);
    assert.equal(await dialog(page).getByRole('button', { name: '删除第16行', exact: true }).count(), 1, 'Deleting a middle row must not reuse an existing row key');
    await dialog(page).getByRole('button', { name: '撤销删除', exact: true }).click();
    assert.equal(await visibleRows.count(), 16);
    assert.equal(await dialog(page).getByRole('button', { name: '删除第14行', exact: true }).count(), 1);
    await dialog(page).getByRole('button', { name: '删除第1行', exact: true }).click();
    assert.match(await dialog(page).locator('.photo-totals').innerText(), /1980\.00/);
    await dialog(page).getByRole('button', { name: '撤销删除', exact: true }).click();
    assert.match(await dialog(page).locator('.photo-totals').innerText(), /2160\.00/);
    assert.equal(await visibleRows.first().getByRole('button', { name: '删除第1行', exact: true }).count(), 1, 'Undo restores original order');
    await dialog(page).locator('.photo-summary .el-checkbox').click();
    await addRow.click();
    assert.equal(await visibleRows.count(), 5, 'Adding with pending-only filter still displays the new row');
    await until(() => visibleRows.last().evaluate(row => row.contains(document.activeElement)), 'Filtered addition must focus the new row too');
    await page.screenshot({ path: path.join(output, 'photo-row-actions-desktop.png'), fullPage: true, animations: 'disabled' });
    const editingRow = visibleRows.filter({ has: page.getByRole('button', { name: '删除第17行', exact: true }) });
    await editingRow.locator('.el-select').first().click();
    await page.locator('.el-select-dropdown:visible').getByRole('option').filter({ hasText: product.skuCode }).click();
    await editingRow.getByRole('spinbutton', { name: '票面数量', exact: true }).fill('2');
    await editingRow.getByRole('spinbutton', { name: '票面单价', exact: true }).fill('36');
    assert.equal(await editingRow.count(), 1, 'A completed new row must stay visible while editing under the pending filter');
    await editingRow.getByRole('spinbutton', { name: '票面数量', exact: true }).fill('24');
    assert.match(await editingRow.innerText(), /入库 288/);
    await editingRow.getByRole('button', { name: '删除第17行', exact: true }).click();
    await dialog(page).getByRole('button', { name: '清理空白行（3）', exact: true }).click();
    await dialog(page).getByRole('button', { name: '删除第13行', exact: true }).click();
    await dialog(page).locator('.photo-summary .el-checkbox').click();
    assert.equal(await visibleRows.count(), 12);
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isEnabled(), true);
    assert.equal(draftRequests.length, 2, 'Add, delete, cleanup and undo must never submit a receipt');
    assert.deepEqual(unexpectedWrites, []);
    await closePhoto(page);
    passed.push('long-table addition scrolls and focuses the new row; pending filter and inherited units work');
    passed.push('row deletion, selective blank cleanup and undo preserve edits, totals, order and unique row keys without server writes');

    // A gateway failure is not a successful empty recognition. Retry reuses the same image.
    const beforeRetry = photoRequests.length;
    state.photoHandler = route => route.fulfill({ status: 504, contentType: 'application/json', body: JSON.stringify({ message: 'Gateway Timeout' }) });
    await openPhoto(page);
    await pick(page, 'retry-same-photo.png');
    await dialog(page).locator('.photo-error').waitFor();
    assert.doesNotMatch(await dialog(page).locator('.photo-error').innerText(), /Gateway Timeout|Network Error|timeout of|Request failed/);
    assert.equal(await dialog(page).locator('.el-table__body tbody tr').count(), 0, 'Failure must not fabricate empty product rows');
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true);
    assert.equal(await dialog(page).locator('.photo-original img').isVisible(), true);
    await page.screenshot({ path: path.join(output, 'photo-recognition-retry.png'), fullPage: true, animations: 'disabled' });
    state.photoHandler = route => ok(route, preview({ documentNo: 'TEST-RETRIED' }));
    await dialog(page).getByRole('button', { name: '重新识别', exact: true }).click();
    await waitForDocument(page, 'TEST-RETRIED');
    assert.equal(photoRequests.length, beforeRetry + 2, 'Retry must make one new recognition request with the retained photo');
    await closePhoto(page);
    passed.push('localized gateway failure, no fake result, original image retained and same-photo retry succeeds');

    // Closing aborts the old operation, and a late response cannot overwrite a reopened dialog.
    const stale = deferred();
    const staleFinished = deferred();
    const beforeStale = photoRequests.length;
    state.photoHandler = async route => {
      await stale.promise;
      try { await ok(route, preview({ documentNo: 'TEST-STALE-NEVER-SHOW' })); }
      catch (error) {
        // Browser cancellation may reject fulfilling the deliberately late intercepted request.
        if (!/closed|cancel|abort|Invalid InterceptionId/i.test(String(error))) throw error;
      } finally { staleFinished.resolve(); }
    };
    await openPhoto(page);
    await pick(page, 'stale-request.png');
    await until(() => photoRequests.length === beforeStale + 1, 'Pending request must start before closing');
    await closePhoto(page);
    state.photoHandler = route => ok(route, preview({ documentNo: 'TEST-CURRENT-REOPENED' }));
    await openPhoto(page);
    await pick(page, 'current-request.png');
    await waitForDocument(page, 'TEST-CURRENT-REOPENED');
    stale.resolve();
    await staleFinished.promise;
    await page.waitForTimeout(150);
    assert.equal(await dialog(page).getByText('TEST-STALE-NEVER-SHOW', { exact: true }).count(), 0);
    assert.match(await dialog(page).locator('.photo-meta').innerText(), /TEST-CURRENT-REOPENED/);
    assert.doesNotMatch((await page.locator('.el-message__content').allTextContents()).join(' '), /canceled|cancelled|ERR_CANCELED/i);
    await closePhoto(page);
    passed.push('close/reopen ignores stale response and cancellation does not show an error toast');

    // An empty service response must not be accepted as a successful import.
    state.photoHandler = route => ok(route, preview({ documentNo: 'TEST-EMPTY', rows: [], declaredTotal: null, calculatedTotal: 0 }));
    await openPhoto(page);
    await pick(page, 'empty-response.png');
    await dialog(page).locator('.photo-error').waitFor();
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true);
    assert.equal(await dialog(page).locator('.el-table__body tbody tr').count(), 0);
    await dialog(page).getByRole('button', { name: '对照原图手工录入', exact: true }).click();
    await dialog(page).locator('.el-table__body tbody tr').waitFor();
    assert.equal(await dialog(page).locator('.el-table__body tbody tr').count(), 1);
    assert.equal(await dialog(page).locator('.photo-source input').inputValue(), '');
    await dialog(page).getByRole('button', { name: '删除第1行', exact: true }).click();
    assert.equal(await dialog(page).locator('.el-table__body tbody tr').count(), 0);
    await dialog(page).getByRole('button', { name: '＋ 手工添加一行', exact: true }).click();
    assert.equal(await dialog(page).getByRole('button', { name: '删除第2行', exact: true }).count(), 1, 'Manual mode recovers after deleting its last row');
    assert.equal(await dialog(page).getByRole('button', { name: '生成入库草稿', exact: true }).isDisabled(), true);
    assert.equal(draftRequests.length, 2);
    assert.deepEqual(unexpectedWrites, []);
    await closePhoto(page);
    passed.push('empty response is an error; manual mode requires an explicit action and does not submit');
    await context.close();

    const mobile = await setup(browser, { width: 390, height: 844 });
    activePage = mobile.page;
    const baselinePageWidth = await mobile.page.evaluate(() => document.documentElement.scrollWidth);
    await openPhoto(mobile.page);
    await pick(mobile.page, 'mobile-note.png');
    await waitForDocument(mobile.page);
    // Fast mocked recognition can finish during Element Plus's entrance transition.
    await mobile.page.waitForTimeout(350);
    const mobileBounds = await dialog(mobile.page).evaluate(element => {
      const box = element.getBoundingClientRect();
      return {
        left: box.left, right: box.right, width: box.width,
        clientWidth: element.clientWidth, scrollWidth: element.scrollWidth,
        viewportWidth: innerWidth, pageScrollWidth: document.documentElement.scrollWidth,
      };
    });
    assert.ok(mobileBounds.left >= -1 && mobileBounds.right <= mobileBounds.viewportWidth + 1, 'Mobile recognition dialog fits its viewport');
    assert.ok(mobileBounds.scrollWidth <= mobileBounds.clientWidth + 1, `Mobile dialog content must not overflow: ${JSON.stringify(mobileBounds)}`);
    assert.ok(mobileBounds.pageScrollWidth <= Math.max(baselinePageWidth, mobileBounds.viewportWidth) + 1, 'Recognition dialog must not increase horizontal page overflow');
    await mobile.page.screenshot({ path: path.join(output, 'photo-recognition-mobile.png'), fullPage: true, animations: 'disabled' });
    await mobile.context.close();
    passed.push('mobile dialog fits viewport and auto-populates recognition');

    assert.deepEqual(errors, [], 'No browser runtime errors');
    assert.deepEqual(unexpectedWrites, [], 'No confirm, inventory or other unexpected writes');
    const report = { passed, mobileBounds, photoRequestCount: photoRequests.length, draftRequests, unexpectedWrites, errors };
    fs.writeFileSync(path.join(output, 'photo-recognition-qa.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } catch (error) {
    if (activePage && !activePage.isClosed()) {
      await activePage.screenshot({ path: path.join(output, 'photo-recognition-failure.png'), fullPage: true, animations: 'disabled' }).catch(() => {});
      fs.writeFileSync(path.join(output, 'photo-recognition-failure.json'), JSON.stringify({ passed, errors, requests, body: await activePage.locator('body').innerText().catch(() => '') }, null, 2));
    }
    throw error;
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
