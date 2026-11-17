/* 模拟全部业务接口，验证关联流程，不操作线上商品。 */
const {chromium}=require('playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.resolve(__dirname,'../../vending-erp/frontend/.vite/product-link-qa');fs.mkdirSync(out,{recursive:true});
const original={id:1,skuCode:'YQ000001',productName:'达利园青梅绿茶500ml',unit:'瓶',boxSpec:15,refPrice:2.8,refCost:2,productStatus:'在售'};
const source={id:26,skuCode:'YQ000026',productName:'500ml达利园青梅绿茶',unit:'件',boxSpec:0,productStatus:'在售'};
const blocked={...original,id:3,skuCode:'YQ000003',productName:'另一绿茶500ml'};
let linked=false,previewFail=false,commitFail=true,slow=false;
const writes=[],errors=[],unexpected=[],passed=[];
const ok=(route,data)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data})});
const fail=(route,message)=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:500,message})});
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});const context=await browser.newContext({viewport:{width:1480,height:1000}});
 await context.addInitScript(()=>{localStorage.setItem('vend_token','product-link-test');localStorage.setItem('vend_tour_seen','1');});
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),p=url.pathname;if(!p.startsWith('/api/'))return route.continue();
  if(p.endsWith('/26/link/candidates'))return ok(route,[original,blocked]);
  if(p.endsWith('/26/link/preview')){
   const id=Number(url.searchParams.get('targetId'));if(previewFail){previewFail=false;return fail(route,'读取失败，请重试');}
   if(slow && id===1)await new Promise(r=>setTimeout(r,600));
   return ok(route,{source,target:id===1?original:blocked,salesCount:3,salesQty:10,salesAmount:28,aliasCount:1,salesNames:[source.productName],blockers:id===3?['新增商品已有库存流水，不能直接归并']:[],executable:id===1,token:`token-${id}`});
  }
  if(p.endsWith('/26/link') && req.method()==='POST'){
   const body=req.postDataJSON();writes.push(body);assert.deepEqual(body,{targetId:1,token:'token-1'});
   if(commitFail){commitFail=false;return fail(route,'商品或销售记录已变化，请重新核对关联影响');}
   linked=true;return ok(route,null);
  }
  if(req.method()!=='GET'){unexpected.push(req.method()+' '+p);return route.abort();}
  if(p.endsWith('/basedata/products')){const records=linked?[original]:[original,source];return ok(route,{records,total:records.length});}
  if(p.endsWith('/basedata/products/1'))return ok(route,original);
  if(p.endsWith('/basedata/products/3'))return ok(route,blocked);
  if(p.endsWith('/report/stock'))return ok(route,{dataAsOf:'2026-11-17',records:[]});
  return ok(route,{records:[],total:0});
 });
 const sourceRow=()=>page.locator('.el-table__body tr').filter({hasText:'YQ000026'});
 const dialog=()=>page.getByRole('dialog',{name:'关联已有商品 · 别名绑定'});
 const open=async()=>{await sourceRow().getByRole('button',{name:'关联已有商品',exact:true}).click();await dialog().getByRole('button',{name:/YQ000001/}).waitFor();};
 const choose=async()=>{await dialog().getByRole('button',{name:/YQ000001/}).click();await dialog().getByText('统一使用 YQ000001',{exact:false}).waitFor();};
 try{
  await page.goto(process.env.PRODUCT_LINK_BASE_URL||'http://127.0.0.1:5177/products');await sourceRow().waitFor();
  await page.screenshot({path:path.join(out,'01-entry.png'),fullPage:true});
  await open();await choose();assert.equal(await dialog().getByRole('button',{name:'确认关联',exact:true}).isDisabled(),true);
  await dialog().getByRole('button',{name:'取消',exact:true}).click();assert.equal(writes.length,0);passed.push('中文入口、相似旧商品推荐、预览及取消均正常');
  previewFail=true;await open();await dialog().getByRole('button',{name:/YQ000001/}).click();await dialog().getByRole('button',{name:'重新核对'}).click();await dialog().getByText('统一使用 YQ000001',{exact:false}).waitFor();
  await dialog().locator('label.el-checkbox').click();await page.screenshot({path:path.join(out,'02-confirm.png'),fullPage:true});
  await dialog().getByRole('button',{name:'确认关联',exact:true}).click();await dialog().getByText('商品或销售记录已变化，请重新核对关联影响',{exact:true}).waitFor();
  assert.equal(await dialog().getByRole('button',{name:'确认关联',exact:true}).isDisabled(),true);
  await dialog().getByRole('button',{name:'重新核对'}).click();await dialog().getByText('统一使用 YQ000001',{exact:false}).waitFor();assert.equal(await dialog().getByRole('button',{name:'确认关联',exact:true}).isDisabled(),true);
  passed.push('读取失败可重试；提交失败后必须重新核对并确认');
  await dialog().getByRole('button',{name:/YQ000003/}).click();await dialog().getByText('暂时不能关联',{exact:true}).waitFor();assert.equal(await dialog().getByRole('button',{name:'确认关联',exact:true}).isDisabled(),true);
  passed.push('独立库存等拦截原因可见，无法越过确认');
  slow=true;await dialog().getByRole('button',{name:/YQ000001/}).click();await dialog().getByRole('button',{name:/YQ000003/}).click();await dialog().getByText('暂时不能关联',{exact:true}).waitFor();await page.waitForTimeout(700);assert.equal(await dialog().getByText('统一使用 YQ000001',{exact:false}).count(),0);slow=false;
  passed.push('切换目标后，较晚返回的旧预览不会覆盖新目标');
  await choose();await dialog().locator('label.el-checkbox').click();await dialog().getByRole('button',{name:'确认关联',exact:true}).click();await sourceRow().waitFor({state:'detached'});
  assert.equal(await page.locator('.el-table__body tr').filter({hasText:'YQ000001'}).count(),1);assert.equal(writes.length,2);assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
  passed.push('确认后重复商品收起，旧商品继续保留');
  console.log(JSON.stringify({passed,writes,未操作线上数据:true},null,2));
 }catch(e){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});console.error(errors);throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
