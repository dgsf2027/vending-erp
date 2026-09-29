/* Isolated UI regression: synthetic data only, every /api request is mocked.
 * node verification/scripts/import_file_preview.cjs
 * Use an installed playwright package or set PLAYWRIGHT_MODULE to its module path.
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

const root = path.resolve(__dirname, '../..');
const output = path.join(root, '.impeccable/review');
const baseURL = process.env.IMPORT_PREVIEW_BASE_URL || 'http://127.0.0.1:5176';
const token = 'synthetic-preview-test-token';
const longName = '测试数据-出货明细-2026年09月-含多个工作表和长文件名称-原始Excel归档.xlsx';
const names = [longName, '测试数据-缺失归档.xlsx', '测试数据-慢请求.xlsx', '测试数据-最新批次.xlsx', '测试数据-网络断开.xlsx'];
const batches = names.map((fileName, i) => ({
  id: 101 + i, batchNo: `IMP-SYNTHETIC-${101 + i}`, fileName, fileType: '出货明细',
  periodRange: '2026-09', rowTotal: 120, rowOk: 120, rowFail: 0, rowDup: 0,
  batchStatus: '已导入', createTime: '2026-09-29 12:00:00',
}));
const sheets = [{ index: 0, name: '销售明细（测试数据）' }, { index: 1, name: '汇总（测试数据）' }, { index: 2, name: '空白工作表' }];
const requests = [];
const errors = [];
let missingFails = true;
let networkFails = true;
let releaseDelayed;
let delayedRequest;

function preview(id, sheetIndex, current, size) {
  const total = sheetIndex === 0 ? 120 : sheetIndex === 1 ? 2 : 0;
  const columns = total ? ['A', 'B', 'C', 'D', 'E', 'F', 'G'] : [];
  const rows = Array.from({ length: Math.max(0, Math.min(size, total - (current - 1) * size)) }, (_, offset) => {
    const rowNo = (current - 1) * size + offset + 1;
    let cells = rowNo === 1 ? ['商品', '编号', '', '数量', '金额', '商品', '备注'] :
      [`测试矿泉水 ${rowNo}`, `000${rowNo}`, '', String(rowNo), '3.50', '重复表头数据', '保留换行\n测试内容'];
    if (sheetIndex === 1) cells = rowNo === 1 ? ['汇总', '测试值'] : ['本月合计', '120'];
    if (id === 103) cells[0] = '过期响应不可出现';
    if (id === 104) cells[0] = '最新批次内容';
    return { rowNo, cells };
  });
  return { fileName: names[id - 101], sheets, sheetIndex, columns, rows, total, current, size, warnings: [] };
}

async function setup(browser, viewport) {
  const context = await browser.newContext({ viewport, acceptDownloads: true });
  await context.addInitScript(({ token }) => {
    localStorage.setItem('vend_token', token);
    localStorage.setItem('vend_user_name', '界面测试');
    localStorage.setItem('vend_user_role', '测试用户');
    localStorage.setItem('vend_tour_seen', '1');
  }, { token });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request();
    const url = new URL(req.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    requests.push({ path: url.pathname, query: url.search, auth: req.headers().authorization });
    const ok = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
    const match = url.pathname.match(/\/imports\/batches\/(\d+)\/file-preview$/);
    if (match) {
      const id = Number(match[1]);
      if (id === 105 && networkFails) return route.abort('failed');
      if (id === 103) {
        delayedRequest = new Promise(resolve => { releaseDelayed = resolve; });
        await delayedRequest;
      }
      if (id === 102 && missingFails) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '原始归档文件不存在（测试数据）' }) });
      return ok(preview(id, Number(url.searchParams.get('sheetIndex')), Number(url.searchParams.get('current')), Number(url.searchParams.get('size'))));
    }
    if (/\/imports\/batches\/\d+\/file$/.test(url.pathname)) return route.fulfill({ status: 200, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('SYNTHETIC-ORIGINAL-FILE') });
    if (url.pathname.endsWith('/imports/batches')) return ok({ records: batches, total: batches.length, current: 1, size: 10 });
    if (url.pathname.endsWith('/report/stock')) return ok({ dataAsOf: '2026-09-29', records: [] });
    return ok({ records: [], total: 0, size: 10, current: 1 });
  });
  await page.goto(`${baseURL}/import`);
  await page.getByRole('button', { name: `查看表格：${longName}`, exact: true }).waitFor({ timeout: 10000 }).catch(async error => {
    await page.screenshot({ path: path.join(output, 'import-file-setup-error.png') });
    console.error(JSON.stringify({ body: await page.locator('body').innerText(), requests, errors }));
    throw error;
  });
  return { page, context };
}

async function openFile(page, name) {
  await page.getByRole('button', { name: `查看表格：${name}`, exact: true }).click();
  await page.locator('.import-file-drawer').waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const r = document.querySelector('.import-file-drawer')?.getBoundingClientRect();
    return r && r.right <= innerWidth + 1;
  });
}

async function closeFile(page) {
  await page.locator('.import-file-drawer .el-drawer__close-btn').click();
  await page.locator('.import-file-drawer').waitFor({ state: 'hidden' });
}

async function switchSheet(page, name) {
  await page.locator('.file-sheet-select').click();
  await page.getByRole('option', { name, exact: true }).click();
}

async function layout(page) {
  return page.evaluate(() => {
    const rect = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom, right: r.right }; };
    const table = document.querySelector('.file-table .el-scrollbar__wrap');
    return { viewport: { width: innerWidth, height: innerHeight }, drawer: rect('.import-file-drawer'), pagination: rect('.file-pagination'), content: rect('.file-content'), table: rect('.file-table'), tableScrollHeight: table.scrollHeight, tableClientHeight: table.clientHeight, pageScrollWidth: document.documentElement.scrollWidth };
  });
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    const { page, context } = await setup(browser, { width: 1440, height: 1000 });
    const filenameLayout = await page.getByRole('button', { name: `查看表格：${longName}`, exact: true }).evaluate(el => ({
      html: el.outerHTML,
      spans: [...el.querySelectorAll('span')].map(s => ({ width: s.clientWidth, scrollWidth: s.scrollWidth, overflow: getComputedStyle(s).overflow, textOverflow: getComputedStyle(s).textOverflow, whiteSpace: getComputedStyle(s).whiteSpace, textDecoration: getComputedStyle(s).textDecorationLine })),
    }));
    await openFile(page, longName);
    await page.locator('.file-table').waitFor();
    assert.match(await page.locator('.file-table').innerText(), /商品[\s\S]*编号/);
    assert.equal(await page.locator('.file-table .el-table__header th').count(), 8);
    assert.match(await page.locator('.file-table .el-table__body tr').nth(1).innerText(), /2[\s\S]*0002[\s\S]*3\.50/);
    await page.screenshot({ path: path.join(output, 'import-file-desktop.png') });
    const desktopLayout = await layout(page);
    assert.ok(desktopLayout.pagination.bottom <= 1000 && desktopLayout.tableClientHeight < desktopLayout.tableScrollHeight, 'Desktop pagination and vertical table scrolling');
    await page.locator('.file-pagination .btn-next').click();
    await page.locator('.file-table .el-table__body tr').first().getByText('51', { exact: true }).first().waitFor();
    await switchSheet(page, '汇总（测试数据）');
    await page.locator('.file-table').getByText('本月合计', { exact: true }).waitFor();
    const lastSheetRequest = requests.filter(r => r.path.endsWith('/101/file-preview')).at(-1);
    assert.match(lastSheetRequest.query, /sheetIndex=1&current=1&size=50/);
    const downloadEvent = page.waitForEvent('download');
    await page.getByRole('button', { name: '下载原文件', exact: true }).click();
    const download = await downloadEvent;
    assert.equal(download.suggestedFilename(), longName);
    assert.equal(requests.find(r => r.path.endsWith('/101/file')).auth, `Bearer ${token}`);
    await switchSheet(page, '空白工作表');
    await page.getByText('此工作表没有内容', { exact: true }).waitFor();
    await closeFile(page);
    await openFile(page, names[1]);
    await page.locator('.file-error').getByText('原始归档文件不存在（测试数据）', { exact: true }).waitFor();
    missingFails = false;
    await page.getByRole('button', { name: '重新加载', exact: true }).click();
    await page.locator('.file-table').waitFor();
    await closeFile(page);
    await openFile(page, names[2]);
    await page.waitForFunction(() => document.querySelector('.file-content')?.getAttribute('aria-busy') === 'true');
    while (!releaseDelayed) await new Promise(r => setTimeout(r, 10));
    await closeFile(page);
    await openFile(page, names[3]);
    await page.locator('.file-table').getByText('最新批次内容', { exact: true }).first().waitFor();
    releaseDelayed();
    await page.waitForTimeout(250);
    assert.equal(await page.getByText('过期响应不可出现', { exact: true }).count(), 0);
    assert.equal(await page.locator('.file-identity h3').innerText(), names[3]);
    await closeFile(page);
    await openFile(page, names[4]);
    await page.locator('.file-error').waitFor();
    assert.match(await page.locator('.file-error').innerText(), /网络/);
    assert.doesNotMatch(await page.locator('.file-error').innerText(), /Network Error/);
    networkFails = false;
    await page.getByRole('button', { name: '重新加载', exact: true }).click();
    await page.locator('.file-table').waitFor();
    await context.close();

    const mobile = await setup(browser, { width: 390, height: 844 });
    await openFile(mobile.page, longName);
    await mobile.page.locator('.file-table').waitFor();
    await mobile.page.screenshot({ path: path.join(output, 'import-file-mobile.png') });
    const mobileLayout = await layout(mobile.page);
    assert.ok(mobileLayout.pagination.bottom <= 844, 'Mobile pagination stays inside viewport');
    assert.ok(mobileLayout.tableClientHeight < mobileLayout.tableScrollHeight, 'Mobile table scrolls vertically');
    assert.ok(mobileLayout.drawer.width <= 390 && mobileLayout.drawer.right <= 391, 'Mobile drawer fits viewport');
    assert.equal(errors.length, 0, `Unexpected browser runtime errors: ${errors.join('; ')}`);
    await mobile.context.close();
    const report = { passed: ['open archived file from history', 'original column positions and row numbers', 'next page', 'sheet switch resets page', 'authenticated download with original filename', 'empty worksheet', 'missing archive inline retry', 'late response ignored after close/reopen', 'localized network failure and retry', 'desktop/mobile pagination and table scrolling', 'no browser runtime errors'], filenameLayout, desktopLayout, mobileLayout, requests };
    fs.writeFileSync(path.join(output, 'import-file-qa.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } finally { await browser.close(); }
})().catch(err => { console.error(err); process.exitCode = 1; });
