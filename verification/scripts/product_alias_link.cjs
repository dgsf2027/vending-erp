/* 模拟全部业务接口，覆盖从旧商品和新增商品进入统一编码，不操作线上商品。 */
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.resolve(__dirname,'../../vending-erp/frontend/.vite/product-link-qa');fs.mkdirSync(out,{recursive:true});
const original={id:1,skuCode:'YQ000001',productName:'达利园青梅绿茶500ml',unit:'瓶',boxSpec:15,refPrice:2.8,refCost:2,productStatus:'在售'};
const source={id:26,skuCode:'YQ000026',productName:'500ml达利园青梅绿茶',unit:'件',boxSpec:0,productStatus:'在售'};
const blocked={...original,id:3,skuCode:'YQ000003',productName:'另一绿茶500ml'};
let linked=false,previewFail=false,commitFail=true,slow=false,single=true;
const writes=[],errors=[],unexpected=[],passed=[];
const ok=(route,data)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data})});
const fail=(route,message)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:500,message})});
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});const context=await browser.newContext({viewport:{width:1480,height:1000}});
 await context.addInitScript(()=>{localStorage.setItem('vend_token','product-link-test');localStorage.setItem('vend_tour_seen','1');});
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname;if(!p.startsWith('/api/'))return route.continue();
  const match=p.match(/\/products\/(\d+)\/link(?:\/(candidates|preview))?$/);
  if(match){
   const from=Number(match[1]);
   if(match[2]==='candidates'){const other=from===1?source:original;return ok(route,single?[other]:[other,blocked]);}
   if(match[2]==='preview'){
    const to=Number(url.searchParams.get('targetId'));if(previewFail){previewFail=false;return fail(route,'读取失败，请重试');}
    if(slow && to===1)await new Promise(r=>setTimeout(r,600));
    return ok(route,{source,target:to===3?blocked:original,requestedSourceId:from,requestedTargetId:to,directionReason:'自动保留原商品的完整资料，新销售名称同步为别名。',salesCount:3,salesQty:10,salesAmount:28,aliasCount:1,salesNames:[source.productName],blockers:to===3?['两份商品已有独立库存，不能直接归并']:[],executable:to!==3,token:`pair-${from}-${to}`});
   }
   if(req.method()==='POST'){
    const body=req.postDataJSON();writes.push({from,...body});assert.equal(body.token,`pair-${from}-${body.targetId}`);
    if(commitFail){commitFail=false;return fail(route,'商品或销售记录已变化，请重新核对关联影响');}
    linked=true;return ok(route,null);
   }
  }
  if(req.method()!=='GET'){unexpected.push(req.method()+' '+p);return route.abort();}
  if(p.endsWith('/basedata/products')){const records=linked?[original]:[original,source];return ok(route,{records,total:records.length});}
  for(const product of [original,source,blocked])if(p.endsWith('/basedata/products/'+product.id))return ok(route,product);
  if(p.endsWith('/report/stock'))return ok(route,{dataAsOf:'2026-11-17',records:[]});
  return ok(route,{records:[],total:0});
 });
 const row=code=>page.locator('.el-table__body tr').filter({hasText:code});
 const dialog=()=>page.getByRole('dialog',{name:'相同商品 · 统一编码'});
 const confirm=()=>dialog().getByRole('button',{name:'确认同一商品并统一编码',exact:true});
 const summary=()=>dialog().getByText('统一使用 YQ000001',{exact:false});
 const open=async(code='YQ000026')=>{await row(code).getByRole('button',{name:'关联已有商品',exact:true}).click();await dialog().getByText('可能是同一商品',{exact:true}).waitFor();};
 const cancel=async()=>dialog().getByRole('button',{name:'取消',exact:true}).click();
 try{
  await page.goto(process.env.PRODUCT_LINK_BASE_URL||'http://127.0.0.1:5177/products');await row('YQ000026').waitFor();
  await open('YQ000001');await summary().waitFor();assert.equal(await confirm().isEnabled(),true);
  assert.equal(await dialog().locator('label.el-checkbox').count(),0);
  await page.screenshot({path:path.join(out,'01-from-original.png'),fullPage:true,animations:'disabled'});await cancel();assert.equal(writes.length,0);
  passed.push('从有库存的旧商品进入，自动选中唯一重复商品并保留旧编码，取消不写入');
  await open();await summary().waitFor();assert.equal(await confirm().isEnabled(),true);await cancel();passed.push('从新增商品进入也保留相同旧编码，只需一次确认');
  previewFail=true;await open();await dialog().getByRole('button',{name:'重新核对'}).click();await summary().waitFor();
  await confirm().click();await dialog().getByText('商品或销售记录已变化，请重新核对关联影响',{exact:true}).waitFor();assert.equal(await confirm().isDisabled(),true);
  await dialog().getByRole('button',{name:'重新核对'}).click();await summary().waitFor();await cancel();
  passed.push('读取失败可重试；提交失败后必须重新核对才能再次确认');
  single=false;await open();assert.equal(await confirm().isDisabled(),true);
  await dialog().getByRole('button',{name:/YQ000003/}).click();await dialog().getByText('这两份档案仍需核对',{exact:true}).waitFor();assert.equal(await confirm().isDisabled(),true);
  slow=true;await dialog().getByRole('button',{name:/YQ000001/}).click();await dialog().getByRole('button',{name:/YQ000003/}).click();await page.waitForTimeout(700);assert.equal(await summary().count(),0);slow=false;await cancel();
  passed.push('多个相似商品不擅自选定，独立库存仍拦截，旧响应不覆盖新选择');
  single=true;await open('YQ000001');await summary().waitFor();await confirm().click();await row('YQ000026').waitFor({state:'detached'});
  assert.equal(await row('YQ000001').count(),1);assert.equal(writes.length,2);
  assert.deepEqual(writes[1],{from:1,targetId:26,token:'pair-1-26'});assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
  passed.push('反向入口提交原始商品对，确认后刷新列表并保留原编码');
  console.log(JSON.stringify({passed,writes,未操作线上数据:true},null,2));
 }catch(e){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});console.error(errors);throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
