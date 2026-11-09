/* Local browser regression with mocked APIs. No production data is written. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/machine-import-qa');
fs.mkdirSync(out, { recursive: true });
const base = process.env.MACHINE_IMPORT_BASE_URL || 'http://127.0.0.1:5177';
const passed = [], errors = [], unexpected = [], commits = [];
let saved = [], downloads = 0, listReads = 0;
const source = Array.from({ length: 10 }, (_, i) => ({ rowNo: i + 7, deviceId: `QA-00${i + 1}`, machineName: `测试园区${i + 1}号机`,
  location: `测试园区${i + 1}号机`, machineCode: '', sourceRowCount: 1, action: '待新增', message: '确认时自动分配内部编号' }));
const review = rows => {
  const checked = rows.map(r => {
    const existing = saved.find(m => m.deviceId === r.deviceId);
    return { ...r, action: existing ? '已存在' : !r.machineName.trim() ? '待检查' : '待新增',
      message: existing ? '保留现有档案，本次跳过' : !r.machineName.trim() ? '请填写机器名称' : '确认时自动分配内部编号',
      machineCode: existing?.machineCode || r.machineCode };
  });
  return { rows: checked, headerRowNo: 6, sourceRows: 10, createCount: checked.filter(r => r.action === '待新增').length,
    errorCount: checked.filter(r => r.action === '待检查').length, existingCount: checked.filter(r => r.action === '已存在').length };
};
const ok = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) });
const upload = async (dialog, name = '机器清单.xlsx') => dialog.locator('input[type=file]').setInputFiles({ name,
  mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('local-mock-only') });

(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 }, acceptDownloads: true });
  await context.addInitScript(() => { localStorage.setItem('vend_token', 'machine-qa'); localStorage.setItem('vend_tour_seen', '1'); });
  const page = await context.newPage();
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), endpoint = url.pathname;
    if (!endpoint.startsWith('/api/')) return route.continue();
    if (endpoint.endsWith('/machines/import/template')) {
      assert.equal(request.headers().authorization, 'Bearer machine-qa'); downloads++;
      return route.fulfill({ status: 200, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', body: Buffer.from('PK-local-template-download') });
    }
    if (endpoint.endsWith('/machines/import/parse')) {
      if (request.postData().includes('bad.xlsx')) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 400, message: '未找到机器列表表头，请上传正确清单' }) });
      return ok(route, review(source));
    }
    if (endpoint.endsWith('/machines/import/review')) return ok(route, review(request.postDataJSON().rows));
    if (endpoint.endsWith('/machines/import/commit')) {
      assert.equal(request.headers().authorization, 'Bearer machine-qa');
      const rows = request.postDataJSON().rows; commits.push(rows);
      const data = review(rows); assert.equal(data.errorCount, 0);
      const resultRows = data.rows.map(r => {
        if (r.action === '已存在') return r;
        const row = { ...r, machineCode: `M${String(saved.length + 1).padStart(6, '0')}`, action: '已新增' };
        saved.push({ ...row, id: saved.length + 1, machineStatus: '在线' }); return row;
      });
      return ok(route, { created: data.createCount, skipped: data.existingCount, rows: resultRows });
    }
    if (endpoint.endsWith('/basedata/machines') && request.method() === 'GET') {
      listReads++; return ok(route, { records: saved, total: saved.length, current: 1, size: 20 });
    }
    if (endpoint.endsWith('/slots') && request.method() === 'GET') return ok(route, []);
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) { unexpected.push(endpoint); return route.abort(); }
    if (endpoint.endsWith('/report/stock')) return ok(route, { dataAsOf: '2026-11-09', records: [] });
    return ok(route, { records: [], total: 0, current: 1, size: 20 });
  });
  try {
    await page.goto(base + '/settings');
    await page.getByRole('button', { name: '导入机器列表', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '导入机器列表', exact: true });
    await dialog.getByText('两列就能导入机器', { exact: true }).waitFor();
    assert.equal(await dialog.getByRole('button', { name: '确认导入（0 台）', exact: true }).isEnabled(), false);
    const downloaded = page.waitForEvent('download');
    await dialog.getByRole('button', { name: '下载模板', exact: true }).click();
    const file = await downloaded;
    assert.equal(file.suggestedFilename(), '机器列表导入模板.xlsx'); assert.equal(await file.failure(), null); assert.equal(downloads, 1);
    passed.push('entry, empty preview, disabled confirmation and authenticated template download');

    await upload(dialog);
    await dialog.getByText('机器清单.xlsx · 表头在第 6 行 · 10 条记录，合并为 10 台机器', { exact: true }).waitFor();
    assert.equal(await dialog.getByRole('checkbox').count(), 10);
    assert.equal(commits.length, 0);
    await page.screenshot({ path: path.join(out, 'desktop-preview.png'), animations: 'disabled', fullPage: true });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' }); assert.equal(commits.length, 0);
    passed.push('ten row preview and cancel never write machines');

    await page.getByRole('button', { name: '导入机器列表', exact: true }).click();
    await upload(dialog, 'bad.xlsx');
    await dialog.getByText('未找到机器列表表头，请上传正确清单', { exact: true }).waitFor();
    assert.equal(await dialog.getByRole('checkbox').count(), 0);
    await upload(dialog);
    await dialog.locator('label.el-checkbox').first().waitFor();
    await dialog.getByRole('textbox', { name: '机器名称', exact: true }).first().fill('');
    await dialog.getByRole('button', { name: '确认导入（10 台）', exact: true }).click();
    await dialog.getByText('请填写机器名称', { exact: true }).waitFor(); assert.equal(commits.length, 0);
    await dialog.getByRole('textbox', { name: '机器名称', exact: true }).first().fill('修正后的机器名称');
    await dialog.getByRole('textbox', { name: '点位', exact: true }).first().fill('修正后的点位');
    passed.push('upload failure recovers; invalid editable row blocks confirmation');

    await dialog.locator('label.el-checkbox').last().click();
    await dialog.getByRole('button', { name: '确认导入（9 台）', exact: true }).click();
    await dialog.getByText('导入完成：新增 9 台，跳过已有 0 台', { exact: true }).waitFor();
    assert.equal(commits.length, 1); assert.equal(commits[0].length, 9); assert.equal(saved[0].machineName, '修正后的机器名称');
    assert.equal(saved[0].location, '修正后的点位'); assert.equal(saved[0].machineCode, 'M000001');
    assert.ok(listReads >= 2);
    await dialog.getByRole('button', { name: '完成', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
    await page.locator('span.num').filter({ hasText: /^M000001$/ }).waitFor();
    passed.push('only selected rows are committed; generated codes shown and machine list refreshed');

    await page.getByRole('button', { name: '导入机器列表', exact: true }).click();
    await upload(dialog);
    await dialog.getByText('已存在 9', { exact: true }).waitFor();
    assert.equal(await dialog.getByRole('checkbox').first().isEnabled(), false);
    assert.equal(await dialog.getByRole('checkbox').first().isChecked(), false);
    assert.equal(await dialog.getByRole('checkbox').last().isChecked(), true);
    await dialog.getByRole('button', { name: '取消', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
    assert.equal(commits.length, 1); passed.push('repeated uploads skip and disable existing machines');

    await page.getByRole('button', { name: '＋ 新增机器', exact: true }).click();
    const manual = page.getByRole('dialog', { name: /新增机器\(上线向导/ });
    await manual.waitFor(); await manual.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '货道配置', exact: true }).first().click();
    await page.getByText('还没有货道,先批量初始化', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    passed.push('manual creation and slot configuration remain accessible');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '导入机器列表', exact: true }).click(); await upload(dialog);
    await dialog.getByText('已存在 9', { exact: true }).waitFor();
    const bounds = await dialog.boundingBox();
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 391);
    const confirm = dialog.getByRole('button', { name: '确认导入（1 台）', exact: true });
    const button = await confirm.boundingBox(); assert.ok(button.y >= 0 && button.y + button.height <= 844);
    await page.screenshot({ path: path.join(out, 'mobile-preview.png'), animations: 'disabled', fullPage: false });
    assert.equal(await dialog.evaluate(el => el.scrollWidth > el.clientWidth + 1), false);
    passed.push('mobile dialog fits viewport and confirmation remains visible');
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
    console.log(JSON.stringify({ passed, browserErrors: errors, unexpectedWrites: unexpected }, null, 2));
  } catch (e) {
    await page.screenshot({ path: path.join(out, 'failure.png'), animations: 'disabled', fullPage: true });
    console.error(e); console.error({ errors, unexpected }); process.exitCode = 1;
  } finally { await browser.close(); }
})();
