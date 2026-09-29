/* Isolated batch-menu QA. All /api/ traffic is mocked; no real data is read or written.
 * node verification/scripts/import_batch_actions.cjs
 * Use an installed playwright package or set PLAYWRIGHT_MODULE to its module path.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

const output = path.resolve(__dirname, '../../.impeccable/review');
const baseURL = process.env.IMPORT_ACTIONS_BASE_URL || 'http://127.0.0.1:5176';
const batches = [
  { id: 201, fileType: '出货明细', rowFail: 2, batchStatus: '已导入' },
  { id: 202, fileType: '商品列表', rowFail: 0, batchStatus: '已导入' },
  { id: 203, fileType: '出货明细', rowFail: 0, batchStatus: '已回滚' },
  { id: 204, fileType: '出货明细', rowFail: 0, batchStatus: '处理中' },
  { id: 205, fileType: '系统补货记录', rowFail: 0, batchStatus: '已导入' },
].map(row => ({ batchNo: `IMP-SYNTHETIC-${row.id}`, fileName: `测试数据-${row.fileType}-${row.id}.xlsx`,
  periodRange: '2026-09', rowTotal: 100, rowOk: 98, rowDup: 0, createTime: '2026-09-29 12:00:00', ...row }));
const requests = [];
const runtimeErrors = [];
const labels = ['错误明细', '修改失败行并重导', '改价清单', '重处理待绑定', '回滚导入数据', '删除批次历史'];
const menu = page => page.locator('.batch-actions-menu:visible');
const more = (page, id) => page.getByRole('button', { name: `更多操作，批次 IMP-SYNTHETIC-${id}`, exact: true });
const direct = (page, id) => page.getByRole('button', { name: `查看表格，批次 IMP-SYNTHETIC-${id}`, exact: true });

async function setup(browser, viewport) {
  const context = await browser.newContext({ viewport });
  await context.addInitScript(() => {
    localStorage.setItem('vend_token', 'synthetic-actions-test-token');
    localStorage.setItem('vend_user_name', '界面测试');
    localStorage.setItem('vend_user_role', '测试用户');
    localStorage.setItem('vend_tour_seen', '1');
  });
  const page = await context.newPage();
  page.setDefaultTimeout(7000);
  page.on('pageerror', error => runtimeErrors.push(error.message));
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const req = route.request();
    const url = new URL(req.url());
    requests.push({ method: req.method(), path: url.pathname, query: url.search });
    const ok = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    const id = Number(url.pathname.match(/\/batches\/(\d+)/)?.[1]);
    if (url.pathname.endsWith('/imports/batches')) return ok({ records: batches, total: batches.length, current: 1, size: 10 });
    if (url.pathname.endsWith('/errors')) return ok({ records: [{ id: 1, batchId: id, rowNo: 8, errorType: '测试错误', errorMsg: `错误来自批次 ${id}`, rawContent: '合成数据' }], total: 1 });
    if (url.pathname.endsWith('/failed-rows')) return ok({ fileType: '出货明细', columnSpec: [['设备ID', '1']], rows: [{ errorId: 1, rowNo: 8, cells: { 设备ID: `测试机器-${id}` }, errorType: '测试错误', errorMsg: '合成数据' }] });
    if (url.pathname.endsWith('/price-changes')) return ok([{ productId: 1, skuCode: 'TEST', productName: `改价来自批次 ${id}`, refPrice: 3, newPrice: 4, rowCount: 1 }]);
    if (url.pathname.endsWith('/reprocess')) return ok({ scanned: 1, rebound: 1, stillPending: 0 });
    if (url.pathname.endsWith('/file-preview')) return ok({ fileName: batches.find(row => row.id === id).fileName, sheets: [{ index: 0, name: '测试工作表' }], sheetIndex: 0, columns: ['A'], rows: [{ rowNo: 1, cells: [`原表来自批次 ${id}`] }], total: 1, current: 1, size: 50, warnings: [] });
    if (url.pathname.endsWith('/report/stock')) return ok({ dataAsOf: '2026-09-29', records: [] });
    return ok({ records: [], total: 0, size: 10, current: 1 });
  });
  await page.goto(`${baseURL}/import`);
  await more(page, 201).waitFor();
  return { context, page };
}

async function openMenu(page, id) {
  await more(page, id).click();
  await menu(page).waitFor();
  await page.waitForFunction(() => [...document.querySelectorAll('.batch-actions-menu')].some(el => {
    const rect = el.getBoundingClientRect();
    return rect.width && rect.left >= 0 && rect.top >= 0 && rect.right <= innerWidth + 1 && rect.bottom <= innerHeight + 1;
  }));
  await page.waitForFunction(() => ![...document.querySelectorAll('.batch-actions-menu')].some(el => /(?:enter|leave)-active/.test(el.className)));
}

async function closeMenu(page) {
  await page.locator('.ledger-title').click();
  await menu(page).waitFor({ state: 'hidden' });
}

async function choose(page, id, label) {
  await openMenu(page, id);
  await menu(page).getByRole('menuitem', { name: label, exact: true }).click();
  await menu(page).waitFor({ state: 'hidden' });
}

async function closeDialog(page) {
  await page.locator('.el-dialog:visible .el-dialog__headerbtn').click();
  await page.locator('.el-dialog:visible').waitFor({ state: 'hidden' });
}

async function menuGeometry(page, id) {
  return page.evaluate(({ id }) => {
    const rect = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }; };
    const trigger = document.querySelector(`[aria-label="更多操作，批次 IMP-SYNTHETIC-${id}"]`);
    const popper = [...document.querySelectorAll('.batch-actions-menu')].find(el => el.getBoundingClientRect().width);
    return { viewport: { width: innerWidth, height: innerHeight }, trigger: rect(trigger), popper: rect(popper),
      items: [...popper.querySelectorAll('.el-dropdown-menu__item')].map(el => ({ text: el.textContent.trim(), ...rect(el), color: getComputedStyle(el).color, divided: el.previousElementSibling?.getAttribute('role') === 'separator' })),
      fixedRight: trigger.closest('td').className, pageScrollWidth: document.documentElement.scrollWidth };
  }, { id });
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    const desktop = await setup(browser, { width: 1440, height: 1000 });
    const { page } = desktop;
    await openMenu(page, 201);
    assert.deepEqual(await menu(page).getByRole('menuitem').allTextContents().then(items => items.map(t => t.trim())), labels);
    const desktopLayout = await menuGeometry(page, 201);
    assert.equal(desktopLayout.items[4].divided, true);
    assert.equal(desktopLayout.items[5].divided, false);
    assert.equal(desktopLayout.items[4].color, 'rgb(192, 57, 43)');
    assert.equal(desktopLayout.items[5].color, 'rgb(192, 57, 43)');
    await page.screenshot({ path: path.join(output, 'import-actions-desktop.png'), animations: 'disabled' });
    await closeMenu(page);
    for (const [id, expected] of [[202, [labels[0], labels[2], labels[5]]], [203, [labels[0], labels[2], labels[5]]], [205, [labels[0], labels[2], labels[4], labels[5]]]]) {
      await openMenu(page, id);
      assert.deepEqual(await menu(page).getByRole('menuitem').allTextContents().then(items => items.map(t => t.trim())), expected);
      await closeMenu(page);
    }
    assert.equal(await more(page, 204).isDisabled(), true);
    assert.equal(await more(page, 204).innerText(), '处理中');
    assert.equal(await direct(page, 204).isDisabled(), true);

    await choose(page, 201, '错误明细');
    await page.getByText('错误来自批次 201', { exact: true }).waitFor();
    await closeDialog(page);
    await choose(page, 201, '修改失败行并重导');
    await page.locator('.el-dialog:visible').getByRole('textbox').first().waitFor();
    assert.match(await page.locator('.el-dialog:visible').innerText(), /修改失败行重导 · IMP-SYNTHETIC-201/);
    await closeDialog(page);
    await choose(page, 205, '改价清单');
    await page.getByText('改价来自批次 205', { exact: true }).waitFor();
    await closeDialog(page);
    await choose(page, 201, '重处理待绑定');
    await page.getByText('回补完成:扫描 1 行,回补 1 行,仍待绑定 0 行', { exact: true }).waitFor();

    const writesBeforeCancel = requests.filter(req => req.method !== 'GET').length;
    await choose(page, 205, '回滚导入数据');
    assert.match(await page.locator('.el-message-box').innerText(), /IMP-SYNTHETIC-205/);
    await page.getByRole('button', { name: '再想想', exact: true }).click();
    await page.locator('.el-message-box').waitFor({ state: 'hidden' });
    await choose(page, 202, '删除批次历史');
    assert.match(await page.locator('.el-message-box').innerText(), /IMP-SYNTHETIC-202/);
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await page.locator('.el-message-box').waitFor({ state: 'hidden' });
    assert.equal(requests.filter(req => req.method !== 'GET').length, writesBeforeCancel);

    await direct(page, 203).click();
    await page.getByText('原表来自批次 203', { exact: true }).waitFor();
    await page.locator('.import-file-drawer .el-drawer__close-btn').click();
    await page.locator('.import-file-drawer').waitFor({ state: 'hidden' });

    await more(page, 201).focus();
    await page.keyboard.press('Enter');
    await menu(page).waitFor();
    await page.waitForFunction(() => document.activeElement?.getAttribute('role') === 'menuitem', null, { timeout: 3000 });
    await page.keyboard.press('ArrowDown');
    const keyboardFocus = await page.evaluate(() => ({ role: document.activeElement.getAttribute('role'), text: document.activeElement.textContent.trim() }));
    assert.equal(keyboardFocus.role, 'menuitem');
    assert.ok(labels.includes(keyboardFocus.text));
    await page.keyboard.press('Escape');
    await menu(page).waitFor({ state: 'hidden' });
    await desktop.context.close();

    const mobile = await setup(browser, { width: 390, height: 844 });
    await openMenu(mobile.page, 201);
    assert.deepEqual(await menu(mobile.page).getByRole('menuitem').allTextContents().then(items => items.map(t => t.trim())), labels);
    const mobileLayout = await menuGeometry(mobile.page, 201);
    assert.ok(mobileLayout.trigger.height >= 40);
    assert.ok(mobileLayout.items.every(item => item.height >= 44));
    assert.match(mobileLayout.fixedRight, /el-table-fixed-column--right/);
    assert.ok(mobileLayout.popper.x >= 0 && mobileLayout.popper.right <= 391 && mobileLayout.popper.bottom <= 845);
    await mobile.page.screenshot({ path: path.join(output, 'import-actions-mobile.png'), animations: 'disabled' });
    await mobile.context.close();

    assert.deepEqual(requests.filter(req => req.method !== 'GET'), [{ method: 'POST', path: '/api/v1/imports/batches/201/reprocess', query: '' }]);
    assert.equal(runtimeErrors.length, 0, `Browser runtime errors: ${runtimeErrors.join('; ')}`);
    const report = { passed: ['sale/product/rolled-back/refill conditions', 'processing buttons disabled', 'destructive actions grouped and red', 'correct row dispatch for errors/failed rows/prices/reprocess', 'rollback/delete cancel performs no writes', 'direct file view', 'Enter opens menu; ArrowDown reaches item; Escape closes', '390px mobile fixed right actions and unclipped 44px menu'], desktopLayout, mobileLayout, keyboardFocus, requests, runtimeErrors };
    fs.writeFileSync(path.join(output, 'import-actions-qa.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
