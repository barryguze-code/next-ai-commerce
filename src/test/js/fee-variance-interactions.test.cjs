const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
const fragment=fs.readFileSync('src/main/resources/templates/fragments/fee-variance.html','utf8');
const dialogs=fragment.slice(fragment.indexOf('<dialog'),fragment.lastIndexOf('</dialog>')+9);
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};

async function fixture(options={}){
 const browser=await chromium.launch({headless:true});
 const page=await browser.newPage({viewport:{width:1280,height:900}});
 const postSeen=deferred(),postRelease=deferred(),historySeen=deferred(),historyRelease=deferred();
 const requests=[];
 const saved=new Set();
 const row=(order='ORDER-A',index=0)=>({id:'comparison-'+order+'-'+index,order_item_id:'item-'+order+'-'+index,
  order_id:order,seller_sku:order==='ORDER-B'?'SKU-B':'SKU-A',fee_type:'FBA',currency:'USD',quantity:2,
  state:'HIGHER',difference:'2',expected_amount:'10',actual_amount:'12',unit_expected:'5',unit_actual:'6',
  unit_price:'30',shipping:'0',shipping_verified:true,current_revision:index===0,charge_id:'charge-'+order+'-'+index,
  boundary_at:'2026-10-20T10:00:00Z',charge_received_at:'2026-10-21T10:00:00Z'});
 await page.route('**/*',async route=>{
  const request=route.request(),url=new URL(request.url());
  if(url.pathname==='/app/fee-variance/visible')return route.fulfill({json:[]});
  if(url.pathname==='/app/fee-variance/approve'){
   const payload=request.postDataJSON();requests.push(payload);postSeen.resolve();
   if(options.delayApproval)await postRelease.promise;
   if(options.approvalNetworkError)return route.abort('failed');
   if(options.approvalMalformed)return route.fulfill({contentType:'text/html',body:'Sign in'});
   if(options.approvalError||options.approvalReject)return route.fulfill({status:options.approvalReject?409:500,body:'unavailable'});
   payload.items.forEach(i=>saved.add(i.comparison));
   return route.fulfill({json:{id:payload.items.map((_,i)=>'12345678-1234-4234-8234-'+String(i+1).padStart(12,'0'))}});
  }
  if(url.pathname==='/app/fee-variance'){
   historySeen.resolve();if(options.delayHistory)await historyRelease.promise;
   const order=url.searchParams.get('order')||'ORDER-A',pageNumber=Number(url.searchParams.get('page')||0);
   if(options.historyError||(options.olderError&&pageNumber))return route.fulfill({status:500,body:'unavailable'});
   let rows=options.pages?Array.from({length:pageNumber?1:100},(_,i)=>({...row(pageNumber?'ORDER-PRIOR-100':i?'ORDER-PRIOR-'+i:order,i+pageNumber*100),
    ...(i||pageNumber?{current_revision:false,currency:'EUR',quantity:3}: {})})): [row(order),{...row('ORDER-PRIOR-1',1),currency:'EUR',quantity:3}];
   if(options.emptyOlder&&pageNumber)rows=[];
   if(options.projectionPages)rows=pageNumber===0?[row(order),...Array.from({length:99},(_,i)=>({...row(order,i+1),current_revision:false,state:'AWAITING'}))]:pageNumber===1?Array.from({length:100},(_,i)=>({...row(order,i+100),current_revision:false,state:'AWAITING'})):[{...row('ORDER-PRIOR-100',201),seller_sku:'SKU-A',currency:'EUR',quantity:3},{...row('ORDER-PRIOR-101',202),seller_sku:'SKU-A',currency:'EUR',quantity:3}];
   if(options.mixedItems)rows=[row(order),{...row(order),id:'other-comparison',order_item_id:'other-item',fee_type:'REFERRAL'}];
   rows=rows.map(r=>({...r,already_approved:saved.has(r.id)}));
   return route.fulfill({json:{rows,canApprove:!options.sku,hasMore:options.projectionPages?pageNumber<2:!!options.pages&&!pageNumber,snapshot:101,
    scope:{display_name:'Synthetic store',marketplace_identifier:'TEST'}}});
  }
  const rows=['A','B'].map(x=>`<article data-fee-order="ORDER-${x}" data-fee-sku="SKU-${x}" id="row-${x}"><span class="order-actions"></span></article>`).join('');
  return route.fulfill({contentType:'text/html',body:`<html data-theme="light"><body>${rows}<input id="buy-shipping-csrf" value="synthetic-csrf" data-header="X-CSRF-TOKEN">${dialogs}</body></html>`});
 });
 await page.goto('http://localhost/app/'+(options.sku?'marketplace-skus':'orders'));
 await page.addStyleTag({path:'src/main/resources/static/css/fee-variance.css'});
 await page.addScriptTag({path:'src/main/resources/static/js/fee-variance.js'});
 const open=async letter=>{
  await page.locator('#row-'+letter).getByRole('button',{name:'Compare fees',exact:true}).click();
  await page.locator('#fee-comparison .fee-detail').first().waitFor();
 };
 const approve=async letter=>{
  await open(letter);await page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  await page.locator('#row-'+letter).getByRole('button',{name:'Use actual fees',exact:true}).click();
 };
 return {page,requests,postSeen,postRelease,historySeen,historyRelease,open,approve,
  close:async()=>{postRelease.resolve();historyRelease.resolve();await browser.close();}};
}

test('SKU earlier evidence identifies the exact order, currency and total quantity',async()=>{
 const f=await fixture({sku:true});try{
  await f.open('A');await f.page.getByText('Earlier fee differences',{exact:true}).click();
  assert.match(await f.page.locator('#fee-comparison details p').first().innerText(),/ORDER-PRIOR-1.*EUR.*total for 3 units/);
 }finally{await f.close();}
});

test('history pagination restores stable dialog focus after replacing its button',async()=>{
 const f=await fixture({pages:true});try{
  await f.open('A');await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).click();
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).waitFor();
  assert.equal(await f.page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'Close fee comparison');
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/Loaded/);
 }finally{await f.close();}
});

test('comparison loading and failure are announced in its existing live status',async()=>{
 const f=await fixture({delayHistory:true,historyError:true});try{
  await f.page.locator('#row-A').getByRole('button',{name:'Compare fees',exact:true}).click();await f.historySeen.promise;
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/Loading/);
  f.historyRelease.resolve();await f.page.locator('[data-fee-feedback]').getByText('Fee evidence could not be loaded. Try again.',{exact:true}).waitFor();
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/could not be loaded/);
 }finally{await f.close();}
});

test('new approval confirmation clears feedback from the preceding record',async()=>{
 const f=await fixture({approvalReject:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();
  await f.page.locator('#fee-approval .fee-feedback').getByText('Fee evidence changed or approval is unavailable. Compare again.',{exact:true}).waitFor();
  assert.match(await f.page.locator('#row-A [data-fee-approval-state]').innerText(),/Order ORDER-A.*Fee evidence changed or approval is unavailable/);
  await f.page.keyboard.press('Escape');await f.approve('B');
  assert.equal(await f.page.locator('#fee-approval .fee-feedback').innerText(),'');
 }finally{await f.close();}
});

test('network or malformed approval responses use controlled unconfirmed copy without claiming success',async()=>{
 for(const option of ['approvalNetworkError','approvalMalformed']){
  const f=await fixture({[option]:true});try{
   await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();
   await f.page.locator('#row-A [data-fee-approval-state]').getByText(/could not be confirmed/).waitFor();
   assert.equal(await f.page.locator('#fee-approval .fee-feedback').innerText(),'The approval result could not be confirmed. Compare fees before retrying.');
   assert.equal(await f.page.locator('[data-fee-approved]').count(),0);
  }finally{await f.close();}
 }
});

test('regenerated same-record controls cannot duplicate pending or completed approval',async()=>{
 const f=await fixture({delayApproval:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();await f.postSeen.promise;
  await f.page.keyboard.press('Escape');await f.open('A');await f.page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  assert.equal(await f.page.locator('#row-A').getByRole('button',{name:'Use actual fees',exact:true}).isDisabled(),true);
  f.postRelease.resolve();await f.page.locator('#row-A [data-fee-approved]').waitFor();
  await f.open('A');await f.page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  assert.equal(await f.page.locator('#row-A').getByRole('button',{name:'Use actual fees',exact:true}).count(),0);
  assert.equal(f.requests.length,1);
 }finally{await f.close();}
});

test('failed regenerated same-record control requires fresh comparison before a new confirmation',async()=>{
 const f=await fixture({delayApproval:true,approvalNetworkError:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();await f.postSeen.promise;
  await f.page.keyboard.press('Escape');await f.open('A');await f.page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  f.postRelease.resolve();await f.page.locator('#row-A [data-fee-approval-state]').getByText(/could not be confirmed/).waitFor();
  assert.equal(await f.page.locator('#row-A').getByRole('button',{name:'Use actual fees',exact:true}).count(),0);
  await f.open('A');await f.page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  assert.equal(await f.page.locator('#row-A').getByRole('button',{name:'Use actual fees',exact:true}).isEnabled(),true);
 }finally{await f.close();}
});

test('mixed order items never become a combined approval action',async()=>{
 const f=await fixture({mixedItems:true});try{
  await f.open('A');await f.page.getByRole('button',{name:'Close fee comparison',exact:true}).click();
  assert.equal(await f.page.locator('#row-A').getByRole('button',{name:'Use actual fees',exact:true}).count(),0);
 }finally{await f.close();}
});

test('delayed approval cannot mark, close or steal focus from another record',async()=>{
 const f=await fixture({delayApproval:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();await f.postSeen.promise;
  await f.page.keyboard.press('Escape');await f.approve('B');
  const focus=await f.page.evaluate(()=>document.activeElement?.outerHTML);
  f.postRelease.resolve();await f.page.locator('[data-fee-approved]').waitFor();
  assert.equal(await f.page.locator('#row-A [data-fee-approved]').count(),1);
  assert.equal(await f.page.locator('#row-B [data-fee-approved]').count(),0);
  assert.equal(await f.page.locator('#fee-approval').evaluate(e=>e.open),true);
  assert.match(await f.page.locator('#fee-approval [data-fee-approval-identity]').innerText(),/SKU-B/);
  assert.equal(await f.page.evaluate(()=>document.activeElement?.outerHTML),focus);
  assert.equal(f.requests.length,1);assert.equal(f.requests[0].items[0].comparison,'comparison-ORDER-A-0');
 }finally{await f.close();}
});

test('pending approval warns that closing is not cancellation and submits once',async()=>{
 const f=await fixture({delayApproval:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').dblclick();await f.postSeen.promise;
  assert.match(await f.page.locator('#fee-approval .fee-feedback').innerText(),/Closing this window does not cancel the request/);
  await f.page.locator('#fee-approval footer').getByRole('button',{name:'Close',exact:true}).click();
  const focus=await f.page.evaluate(()=>document.activeElement?.outerHTML);
  f.postRelease.resolve();await f.page.locator('#row-A [data-fee-approved]').waitFor();
  assert.equal(f.requests.length,1);assert.equal(await f.page.locator('#fee-approval').evaluate(e=>e.open),false);
  assert.equal(await f.page.evaluate(()=>document.activeElement?.outerHTML),focus);
 }finally{await f.close();}
});

test('late failed approval reports only its initiating row while another confirmation stays intact',async()=>{
 const f=await fixture({delayApproval:true,approvalError:true});try{
  await f.approve('A');await f.page.locator('#fee-approval [data-fee-approve]').click();await f.postSeen.promise;
  await f.page.keyboard.press('Escape');await f.approve('B');const focus=await f.page.evaluate(()=>document.activeElement?.outerHTML);
  f.postRelease.resolve();await f.page.locator('#row-A [data-fee-approval-state]').getByText(/The approval result could not be confirmed/).waitFor();
  assert.match(await f.page.locator('#row-A [data-fee-approval-state]').innerText(),/ORDER-A.*SKU-A/);
  assert.equal(await f.page.locator('#fee-approval .fee-feedback').innerText(),'');
  assert.equal(await f.page.locator('#fee-approval').evaluate(e=>e.open),true);
  assert.equal(await f.page.evaluate(()=>document.activeElement?.outerHTML),focus);
  assert.equal(await f.page.locator('[data-fee-approved]').count(),0);
 }finally{await f.close();}
});

test('failed older history keeps newest evidence reachable without closing comparison',async()=>{
 const f=await fixture({pages:true,olderError:true});try{
  await f.open('A');await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).click();
  await f.page.locator('[data-fee-feedback]').getByText('Fee evidence could not be loaded. Try again.',{exact:true}).waitFor();
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).waitFor({timeout:1000});
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).click();
  await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).waitFor();
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/Loaded 100/);
 }finally{await f.close();}
});

test('closed comparison response cannot replace a newer record context',async()=>{
 const f=await fixture({delayHistory:true});try{
  await f.page.locator('#row-A').getByRole('button',{name:'Compare fees',exact:true}).click();await f.historySeen.promise;
  await f.page.keyboard.press('Escape');await f.page.locator('#row-B').getByRole('button',{name:'Compare fees',exact:true}).click();
  f.historyRelease.resolve();await f.page.locator('#fee-comparison .fee-detail').first().waitFor();
  assert.equal(await f.page.locator('[data-fee-identity]').innerText(),'Order ORDER-B');
  assert.match(await f.page.locator('#fee-comparison .fee-detail').first().innerText(),/ORDER-B/);
 }finally{await f.close();}
});


test('raw projection pages disclose rendered counts and keep later distinct same-SKU orders reachable',async()=>{
 const f=await fixture({projectionPages:true});try{
  await f.open('A');
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/100 evidence records.*1 current comparison.*0 earlier differences/);
  await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).click();
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).waitFor();
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/100 evidence records.*0 current comparisons.*0 earlier differences/);
  assert.match(await f.page.locator('[data-fee-body]').innerText(),/No distinct comparisons or earlier differences are displayed on this page/);
  assert.equal(await f.page.locator('.fee-detail').count(),0);
  assert.equal(await f.page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'Close fee comparison');
  await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).click();
  await f.page.getByText('Earlier fee differences',{exact:true}).waitFor();
  await f.page.getByText('Earlier fee differences',{exact:true}).click();
  const entries=await f.page.locator('#fee-comparison details p').allTextContents();
  assert.equal(entries.length,2);assert.match(entries[0],/ORDER-PRIOR-100.*EUR.*total for 3 units/);assert.match(entries[1],/ORDER-PRIOR-101.*EUR.*total for 3 units/);
  assert.match(await f.page.locator('[data-fee-feedback]').innerText(),/2 evidence records.*0 current comparisons.*2 earlier differences/);
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).click();
  await f.page.locator('.fee-detail').waitFor();
 }finally{await f.close();}
});


test('zero-row older evidence page preserves newest recovery and dialog focus',async()=>{
 const f=await fixture({pages:true,emptyOlder:true});try{
  await f.open('A');await f.page.getByRole('button',{name:'Older fee evidence',exact:true}).click();
  await f.page.locator('[data-fee-feedback]').getByText(/Loaded 0/).waitFor();
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).waitFor({timeout:1000});
  assert.equal(await f.page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'Close fee comparison');
  await f.page.getByRole('button',{name:'Newest fee evidence',exact:true}).click();
  await f.page.locator('.fee-detail').waitFor();
 }finally{await f.close();}
});
