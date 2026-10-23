const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const root='src/main/resources',output='.local/release-preparation-20261023/notice';
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});fs.mkdirSync(output,{recursive:true});});
after(async()=>browser?.close());
async function fixture({width=1440,theme='light',version='preview-1',storageBlocked=false,expired=false}={}){
 const context=await browser.newContext({viewport:{width,height:900}}),page=await context.newPage();
 await page.clock.install({time:new Date('2026-10-23T18:00:00Z')});
 if(storageBlocked)await page.addInitScript(()=>{Object.defineProperty(window,'sessionStorage',{get(){throw new Error('Blocked');}});});
 const fragment=fs.readFileSync(root+'/templates/fragments/release-notice.html','utf8');
 const section=fragment.slice(fragment.indexOf('  <section'),fragment.indexOf('</section>')+10)
  .replace(/th:attr="[^"]*"/,`data-notice-version="${version}" data-valid-until="${expired?'2026-10-23T17:00:00Z':'2026-10-23T19:00:00Z'}"`);
 await page.route('**/*',route=>route.request().url()==='http://release-notice.test/'?route.fulfill({contentType:'text/html',body:`<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body class="app-page"><aside class="sidebar">${section}</aside><main class="workspace"><header class="workspace-header"><div><h1>Marketplace SKUs</h1><p>Review marketplace listings.</p></div><div class="header-actions"><button>Selected store</button></div></header><section class="data-card"><h2>Marketplace listings</h2><button id="sync" disabled>Syncing…</button></section></main></body></html>`}):route.abort());
 await page.goto('http://release-notice.test/');
 for(const file of ['app.css','brand.css','release-notice.css'])await page.addStyleTag({path:path.join(root,'static/css',file)});
 await page.addScriptTag({path:path.join(root,'static/js/release-notice.js')});
 return {page,context};
}
for(const theme of ['light','dark'])for(const width of [320,390,760,1440])test(`notice acknowledgement/reopen/containment ${theme} ${width}`,async()=>{
 const {page,context}=await fixture({theme,width});
 try{
  const notice=page.getByRole('region',{name:'An update is being prepared.'}),ack=page.getByRole('button',{name:'Got it',exact:true});
  assert.equal(await notice.isVisible(),true);
  assert.equal(await page.getByText('We’re preparing improvements to NextAI Commerce. We’ll confirm the timing before the update begins.',{exact:true}).isVisible(),true);
  assert.equal(await page.getByText('To be confirmed.',{exact:true}).isVisible(),true);
  assert.ok((await ack.boundingBox()).height>=44);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  assert.equal(await page.locator('#sync').isDisabled(),true);
  await page.screenshot({path:`${output}/expanded-${theme}-${width}.png`});
  await ack.press('Enter');
  const reopen=page.getByRole('button',{name:'Update planned · Timing to be confirmed View details'});
  assert.equal(await notice.isVisible(),false);assert.equal(await reopen.isVisible(),true);
  assert.equal(await reopen.evaluate(e=>e===document.activeElement),true);
  assert.ok((await reopen.boundingBox()).height>=44);
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  assert.equal(await page.evaluate(()=>sessionStorage.getItem('nextai.release-notice.preview-1')),'acknowledged');
  await page.screenshot({path:`${output}/collapsed-${theme}-${width}.png`});
  await reopen.press('Enter');assert.equal(await notice.isVisible(),true);
  assert.equal(await page.locator('#release-notice-title').evaluate(e=>e===document.activeElement),true);
  // Idempotence: loading the shared enhancer again cannot duplicate controls.
  await page.addScriptTag({path:path.join(root,'static/js/release-notice.js')});
  assert.equal(await page.locator('.release-notice-summary').count(),1);
 }finally{await context.close();}
});
test('session acknowledgement survives navigation; material version changes reopen',async()=>{
 const {page,context}=await fixture();try{
  await page.getByRole('button',{name:'Got it',exact:true}).click();
  await page.reload();await page.addScriptTag({path:path.join(root,'static/js/release-notice.js')});
  assert.equal(await page.locator('[data-release-notice]').isVisible(),false);
  assert.equal(await page.locator('.release-notice-summary').isVisible(),true);
  await page.evaluate(()=>{document.querySelector('[data-release-notice]').dataset.noticeVersion='preview-2';delete document.querySelector('[data-release-notice]').dataset.initialized;document.querySelector('.release-notice-summary').remove();});
  await page.addScriptTag({path:path.join(root,'static/js/release-notice.js')});
  assert.equal(await page.locator('[data-release-notice]').isVisible(),true);
 }finally{await context.close();}
});
test('blocked storage keeps acknowledgement/reopen usable without network',async()=>{
 const {page,context}=await fixture({storageBlocked:true});try{
  await page.getByRole('button',{name:'Got it',exact:true}).click();
  await page.locator('.release-notice-summary').click();
  assert.equal(await page.locator('[data-release-notice]').isVisible(),true);
 }finally{await context.close();}
});
test('expired notice is withdrawn without asserting maintenance or completion',async()=>{
 const {page,context}=await fixture({expired:true});try{assert.equal(await page.locator('[data-release-notice],.release-notice-summary').count(),0);}finally{await context.close();}
});
test('open pages withdraw at deadline without an operational request',async()=>{
 const {page,context}=await fixture();try{
  const requests=[];page.on('request',r=>requests.push(r.url()));
  await page.clock.fastForward(3600001);
  assert.equal(await page.locator('[data-release-notice],.release-notice-summary').count(),0);
  assert.deepEqual(requests,[]);
 }finally{await context.close();}
});
