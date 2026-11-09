/* Local UI regression only. Synthetic API responses; never confirms production imports. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.IMPORT_CHANNEL_BASE_URL || 'http://127.0.0.1:5177';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/yuanqi-sales-qa');
fs.mkdirSync(out, { recursive: true });
const headers = ['点位名称','柜子编码','订单时间','订单编号','商品名称','支付时间','支付金额','优惠券抵扣金额','销售数量','支付渠道（微信/支付宝）'];
const row = Object.fromEntries(headers.map((h, i) => [h, ['二楼点位','YQLH-DEMO','2026-10-20 10:00:00','ORDER-1','示例电解质水600ml','2026-10-20 10:01:00','11.6','0','2','微信'][i]]));
const product = { sourceName:row.商品名称, barcode:null, skuCode:'YQ000007', productName:'示例电解质水600ml', status:'已匹配', reason:'按已有别名识别', rowCount:139, quantity:150, amount:870 };
const result = () => ({ token:'sales-preview',fileName:'销售10.20-11.06.xlsx',fileType:'出货明细',rowTotal:3403,columnsOk:true,
  headers:['订单号','商品名称','出货数量','商品金额(元)','设备ID','订单类型','出货时间'],
  previewRows:[{订单号:'ORDER-1',商品名称:row.商品名称,出货数量:'2','商品金额(元)':'11.6'}],sourceHeaders:headers,sourcePreviewRows:[row],
  warnings:['已识别元气森林销售表，无需修改表头。'],columnChecks:[{expected:'订单号',found:true,required:true}],
  sales:{format:'元气森林销售表',orderCount:2903,machineCount:10,productCount:25,quantity:3816,amount:16896.49,discount:0,
    dateFrom:'2026-10-20 14:54:08',dateTo:'2026-11-06 09:50:05',invalidRows:0,issues:[],missingMachines:[],products:[product,
      {...product,sourceName:'新上市茶500ml',skuCode:null,productName:null,status:'新增商品',reason:'确认后生成编号'}]} });
const ok = (route,data) => route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,data})});
(async () => {
 const browser=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL || 'msedge'});
 const page=await browser.newPage({viewport:{width:1500,height:1000}});
 let scenario='valid', confirmations=0, refreshes=0; const errors=[];
 page.on('pageerror', e=>errors.push(e.message));
 await page.addInitScript(()=>{localStorage.setItem('vend_token','local-qa');localStorage.setItem('vend_tour_seen','1');});
 await page.route('**/api/**',route=>{
   const p=new URL(route.request().url()).pathname;
   if(!p.startsWith('/api/'))return route.continue();
   if(p.endsWith('/imports/upload') || p.endsWith('/imports/fix/apply')) {
     const preview=result();
     if(p.endsWith('/fix/apply'))refreshes++;
     if(scenario==='machine')preview.sales.missingMachines=['YQLH-MISSING'];
     if(scenario==='invalid'){preview.sales.invalidRows=1;preview.sales.issues=['第3行：销售数量为0，请核对'];}
     return ok(route,preview);
   }
   if(p.endsWith('/imports/confirm')) {confirmations++;return ok(route,{batchId:1,batchNo:'QA-SALES',fileType:'出货明细',rowTotal:3403,rowOk:3403,rowDup:0,rowFail:0,pendingBind:0,priceChangeCount:0,negativeStock:[],autoCreatedProducts:[]});}
   if(!['GET','HEAD','OPTIONS'].includes(route.request().method()))throw Error('Unexpected write '+p);
   if(p.endsWith('/report/stock'))return ok(route,{dataAsOf:'2026-11-06',records:[]});
   return ok(route,{records:[],total:0,current:1,size:10});
 });
 const upload=async()=>page.locator('.channel-upload input[type=file]').setInputFiles({name:'销售.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('local fixture')});
 const dialog=page.getByRole('dialog',{name:/预览核对/});
 const confirm=()=>dialog.getByRole('button',{name:'确认导入 → 入账',exact:true});
 try {
   await page.goto(base+'/import/sales');
   await page.getByRole('heading',{name:'销售表录入区',exact:true}).waitFor();
   for(const h of headers)assert.equal(await page.getByRole('columnheader',{name:h,exact:true}).count(),1);
   await page.screenshot({path:path.join(out,'sales-ready.png'),fullPage:true,animations:'disabled'});
   await upload();await dialog.waitFor();
   await dialog.getByText('¥16,896.49',{exact:true}).waitFor();
   await page.waitForFunction(()=>Array.from(document.querySelectorAll('.el-dialog')).some(el=>getComputedStyle(el).opacity==='1'));
   assert.equal(await confirm().isEnabled(),true);assert.equal(confirmations,0);
   assert.equal(await dialog.getByRole('columnheader',{name:'订单编号',exact:true}).count(),1);
   await page.screenshot({path:path.join(out,'sales-preview.png'),fullPage:true,animations:'disabled'});
   await dialog.getByRole('tab',{name:'商品匹配（25）'}).click();
   await dialog.getByText('YQ000007',{exact:true}).waitFor();
   await dialog.getByText('确认后自动分配YQ编码',{exact:true}).waitFor();
   await page.screenshot({path:path.join(out,'sales-products.png'),fullPage:true,animations:'disabled'});
   await confirm().click();await dialog.waitFor({state:'hidden'});assert.equal(confirmations,1);
   scenario='machine';await upload();await dialog.waitFor();assert.equal(await confirm().isDisabled(),true);
   await dialog.getByText('YQLH-MISSING',{exact:true}).waitFor();
   scenario='valid';await dialog.getByRole('button',{name:'重新核对档案',exact:true}).click();
   await page.waitForFunction(()=>!Array.from(document.querySelectorAll('.el-dialog button')).find(b=>b.textContent.includes('确认导入 → 入账'))?.disabled);
   assert.equal(refreshes,1);assert.equal(await confirm().isEnabled(),true);assert.equal(confirmations,1);
   await dialog.getByRole('button',{name:'取消(不入账)',exact:true}).click();
   await page.reload();scenario='invalid';await upload();await dialog.waitFor();assert.equal(await confirm().isDisabled(),true);
   await dialog.getByText('第3行：销售数量为0，请核对',{exact:true}).waitFor();
   await page.setViewportSize({width:390,height:844});
   await page.waitForFunction(()=>{const b=Array.from(document.querySelectorAll('.el-dialog button')).find(el=>el.textContent.includes('确认导入 → 入账'));return b && b.getBoundingClientRect().bottom<=window.innerHeight;});
   await page.screenshot({path:path.join(out,'sales-mobile.png'),fullPage:true,animations:'disabled'});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>window.innerWidth+2),false);
   assert.deepEqual(errors,[]);
   console.log(JSON.stringify({passed:7,checks:['10 source headers','whole-file totals','preview is read-only','existing and new product codes','confirmation','missing machines and refresh','invalid rows and mobile'],confirmations,errors}));
 } catch(e) {
   await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});
   console.error(JSON.stringify({url:page.url(),errors,body:(await page.locator('body').innerText()).slice(0,1800)}));
   throw e;
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
