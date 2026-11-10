/* All API traffic is intercepted. These deletion tests never reach production. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const base = process.env.PURCHASE_DELETE_BASE_URL || 'http://127.0.0.1:5177';
const out = path.resolve(__dirname, '../../vending-erp/frontend/.vite/receipt-delete-qa');
fs.mkdirSync(out, { recursive: true });
const rows = [
  [51,'已确认'],[52,'草稿'],[53,'待确认'],[54,'已红冲'],[55,'已作废'],
  [56,'已结算'],[57,'已完成'],[58,'已确认'],[59,'已确认'],[60,'已确认'],
].map(([id,docStatus])=>({ id,docNo:`RK-TEST-${id}`,bizDate:'2026-10-30',docStatus,supplierId:1,supplierName:'测试供应商',totalQty:261,totalAmount:783,itemCount:5,diffCount:0 }));
let failDraft=true,failReverse=true,failPreview=true;
const writes=[],errors=[],unexpected=[],passed=[];
const ok=(route,data)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data})});
const fail=(route,message)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:500,message})});
const preview=id=>({originDocId:id,originDocNo:`RK-TEST-${id}`,docType:'采购入库',docStatus:rows.find(r=>r.id===id).docStatus,totalAmount:783,executable:id!==58,blockers:id===58?['已有成本调整单，请先核对关联记录']:[],bookPeriod:'2026-10',lockedPeriod:id===56,lockLine:'2026-10',stockChanges:[{productId:1,skuCode:'YQ000025',productName:'阿萨姆奶茶',warehouseDelta:-90,machineDelta:0,warehouseNow:180,warehouseAfter:90}],payableImpact:{enabled:true,note:'应付余额同步减少',wouldReverseAmount:-783},marginImpacts:[{period:'2026-10',locationType:'仓库',costAmountReversal:-783}],marginNote:'历史记录保留'});
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  const context=await browser.newContext({viewport:{width:1500,height:1000}});
  await context.addInitScript(()=>{localStorage.setItem('vend_token','receipt-delete-test');localStorage.setItem('vend_tour_seen','1');});
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/**',async route=>{
    const req=route.request(),p=new URL(req.url()).pathname;
    if(!p.startsWith('/api/'))return route.continue();
    let match;
    if(req.method()==='DELETE' && (match=p.match(/\/purchase\/receipts\/(\d+)$/))){
      const id=Number(match[1]);writes.push({kind:'draft',id});
      if(id===52 && failDraft){failDraft=false;return fail(route,'模拟删除失败，请重试');}
      assert.ok(['草稿','待确认'].includes(rows.find(r=>r.id===id).docStatus));
      rows.find(r=>r.id===id).docStatus='已作废';return ok(route,null);
    }
    if((match=p.match(/\/docs\/(\d+)\/red-flush\/preview$/))){
      const id=Number(match[1]);
      if(id===59 && failPreview){failPreview=false;return fail(route,'模拟读取失败');}
      if(id===60)await new Promise(r=>setTimeout(r,800));
      return ok(route,preview(id));
    }
    if(req.method()==='POST' && (match=p.match(/\/docs\/(\d+)\/red-flush$/))){
      const id=Number(match[1]),body=req.postDataJSON();writes.push({kind:'reverse',id,body});
      assert.equal(id,51);assert.equal(body.reason,'重复导入');assert.equal(body.bossOverride,false);
      if(failReverse){failReverse=false;return fail(route,'模拟撤销失败，请重试');}
      rows.find(r=>r.id===id).docStatus='已红冲';return ok(route,151);
    }
    if(req.method()!=='GET'){unexpected.push(`${req.method()} ${p}`);return route.abort();}
    if(p.endsWith('/purchase/receipts'))return ok(route,rows);
    if((match=p.match(/\/purchase\/receipts\/(\d+)$/)))return ok(route,{head:rows.find(r=>r.id===Number(match[1])),items:[]});
    if(p.endsWith('/purchase/in-transit'))return ok(route,[]);
    if(p.endsWith('/basedata/suppliers'))return ok(route,{records:[{id:1,supplierName:'测试供应商'}],total:1});
    if(p.endsWith('/report/stock'))return ok(route,{dataAsOf:'2026-11-06',records:[]});
    return ok(route,{records:[],total:0});
  });
  const table=page.getByTestId('purchase-receipts');
  const row=id=>table.locator('.el-table__body tr').filter({hasText:`RK-TEST-${id}`});
  const remove=id=>row(id).getByRole('button',{name:'删除',exact:true});
  const popup=()=>page.getByRole('dialog').filter({hasText:'删除采购入库单'}).last();
  try{
    await page.goto(`${base}/purchase`);await row(51).waitFor();
    assert.equal(await row(54).count(),0);assert.equal(await row(55).count(),0);assert.equal(await remove(57).count(),0);
    await page.screenshot({path:path.join(out,'01-list.png'),fullPage:true});
    passed.push('active list has delete buttons; archived rows hidden and completed rows protected');

    await remove(51).click();await popup().getByText('将扣回的库存',{exact:false}).waitFor();
    assert.equal(await popup().getByRole('button',{name:'确认删除并撤销入库'}).isDisabled(),true);
    assert.equal(await popup().getByText('应付链未启用',{exact:true}).count(),0);
    await popup().getByRole('button',{name:'取消',exact:true}).click();assert.equal(writes.length,0);
    await remove(52).click();await popup().getByRole('button',{name:'取消',exact:true}).click();assert.equal(writes.length,0);
    passed.push('opening/cancelling either removal path performs no write; confirmed receipt needs a reason');

    await remove(52).click();await popup().getByRole('button',{name:'确认删除',exact:true}).click();
    await page.getByText('模拟删除失败，请重试',{exact:true}).waitFor();assert.equal(await row(52).count(),1);
    await remove(52).click();await popup().getByRole('button',{name:'确认删除',exact:true}).click();await row(52).waitFor({state:'detached'});
    passed.push('draft deletion errors leave the row available, retry succeeds and archives it');

    await row(53).getByRole('button',{name:'详情',exact:true}).click();
    const detail=page.getByRole('dialog').filter({hasText:'RK-TEST-53'});
    await detail.getByRole('button',{name:'删除',exact:true}).click();await popup().getByRole('button',{name:'确认删除',exact:true}).click();await row(53).waitFor({state:'detached'});
    passed.push('pending receipt can also be removed from its detail view');

    await remove(51).click();await popup().getByRole('button',{name:'重复导入',exact:true}).click();
    await page.screenshot({path:path.join(out,'02-confirm-reversal.png'),fullPage:true});
    await popup().getByRole('button',{name:'确认删除并撤销入库'}).click();await page.getByText('模拟撤销失败，请重试',{exact:true}).waitFor();
    assert.equal(await popup().getByRole('textbox').inputValue(),'重复导入');
    await popup().getByRole('button',{name:'确认删除并撤销入库'}).click();await row(51).waitFor({state:'detached'});
    await page.locator('label.el-checkbox').filter({hasText:'显示已删除/撤销'}).click();await row(51).waitFor();
    assert.equal(await remove(51).count(),0);assert.equal(await remove(52).count(),0);
    await page.locator('label.el-checkbox').filter({hasText:'显示已删除/撤销'}).click();
    passed.push('confirmed receipt uses reversal API with reason, can retry, and stays inspectable without a second delete');

    await remove(58).click();await popup().getByText('已有成本调整单，请先核对关联记录',{exact:false}).waitFor();
    assert.equal(await popup().getByRole('button',{name:'确认删除并撤销入库'}).count(),0);await popup().getByRole('button',{name:'取消',exact:true}).click();
    await remove(59).click();await popup().getByRole('button',{name:'重新读取影响清单'}).click();
    await popup().getByText('将扣回的库存',{exact:false}).waitFor();await popup().getByRole('button',{name:'取消',exact:true}).click();
    await remove(56).click();await popup().getByRole('button',{name:'重复导入',exact:true}).click();
    assert.equal(await popup().getByRole('button',{name:'确认删除并撤销入库'}).isDisabled(),true);await popup().getByRole('button',{name:'取消',exact:true}).click();
    passed.push('downstream blockers, preview retry and locked-period guard remain effective');

    await remove(60).click();await popup().getByRole('button',{name:'取消',exact:true}).click();
    await remove(58).click();await popup().getByText('已有成本调整单，请先核对关联记录',{exact:false}).waitFor();
    await page.waitForTimeout(900);
    assert.equal(await popup().getByRole('button',{name:'确认删除并撤销入库'}).count(),0);
    assert.ok((await popup().innerText()).includes('RK-TEST-58'));
    await popup().getByRole('button',{name:'取消',exact:true}).click();
    passed.push('a slow preview for a closed receipt cannot overwrite the newly opened receipt');
    assert.equal(writes.length,5);assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
    console.log(JSON.stringify({passed,writes,apiWritesIsolated:true,screenshots:out},null,2));
  }catch(e){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});console.error({errors,body:(await page.locator('body').innerText()).slice(-2200)});throw e;}
  finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
