const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const out='/tmp/variation-trial-operator-review',assets=path.resolve('src/main/resources/static');let browser;
before(async()=>{browser=await chromium.launch({headless:true,channel:'chrome'});fs.mkdirSync(out,{recursive:true});});
after(async()=>browser?.close());
const running={state:'RUNNING',get_reserved:3,oauth_reserved:1,deadline:'2026-10-22T06:30:00Z',request_in_flight:false};
async function fixture(width=390,theme='light',initial={state:'NOT_STARTED'},options={}){
 const page=await browser.newPage({viewport:{width,height:1000},colorScheme:theme});page.setDefaultTimeout(5000);
 await page.context().addCookies([{name:'nextai_theme',value:theme,url:'http://trial-fixture.test'}]);
 let status=initial;const posts=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{
  const request=route.request(),u=new URL(request.url());if(u.hostname!=='trial-fixture.test')return route.abort();
  const api='/app/marketplace-skus/variation-trial';
  if(u.pathname===api+'/operator')return route.fulfill({contentType:'text/html',body:fs.readFileSync(out+'/operator.html','utf8').replace('<html ','<html data-theme="'+theme+'" ')});
  if(u.pathname===api){if(options.denied||options.login)return route.fulfill({status:options.login?401:403,contentType:'application/json',body:'{}'});if(options.unreadable)return route.fulfill({contentType:'text/html',body:'sign-in'});if(options.malformed)return route.fulfill({contentType:'application/json',body:'{"private":"fixture-private-payload" BROKEN'});return route.fulfill({contentType:'application/json',body:JSON.stringify(status)});}
  if(u.pathname===api+'/start'||u.pathname===api+'/cancel'){
   posts.push({path:u.pathname,method:request.method(),body:Object.fromEntries(new URLSearchParams(request.postData())),headers:request.headers()});
   if(options.hold&&u.pathname.endsWith('/start'))await options.hold;
   if(options.cancelHold&&u.pathname.endsWith('/cancel'))await options.cancelHold;
   if(options.conflict)return route.fulfill({status:409,contentType:'application/json',body:'{}'});
   if(u.pathname.endsWith('/start')){status=running;return route.fulfill({contentType:'application/json',body:JSON.stringify({jobId:'11111111-1111-1111-1111-111111111111'})});}
   status={...running,state:'CANCELLED',request_in_flight:true};return route.fulfill({contentType:'application/json',body:JSON.stringify(status)});
  }
  if(/^\/js\//.test(u.pathname)&&!['/js/variation-trial-operator.js','/js/platform-ui.js','/js/mobile-navigation.js'].includes(u.pathname))return route.fulfill({body:''});
  if(/^\/(css|js|images)\//.test(u.pathname)){const file=path.join(assets,u.pathname);return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({status:404,body:''});}
  return route.fulfill({contentType:'application/json',body:'{}'});
 });
 await page.goto('http://trial-fixture.test/app/marketplace-skus/variation-trial/operator');
 return {page,posts,errors};
}
for(const width of [320,390,1440])for(const theme of ['light','dark'])test(`operator scope and native Start/Cancel ${width} ${theme}`,async()=>{
 const {page,posts,errors}=await fixture(width,theme);
 await page.getByRole('heading',{name:'Ready to start',exact:true}).waitFor();assert.equal(posts.length,0,'no automatic POST');
 const start=page.getByRole('button',{name:'Start approved KARACA trial'});assert.equal(await start.isEnabled(),true);
 for(const box of await page.locator('.trial-actions button').evaluateAll(nodes=>nodes.map(e=>e.getBoundingClientRect().toJSON())))assert.ok(box.height>=44&&box.width<=width,JSON.stringify(box));
 const back=page.getByRole('link',{name:'Return to Marketplace SKUs'});await back.focus();
 const contrast=await back.evaluate(e=>{
  const rgb=s=>(s.match(/[\d.]+/g)||[]).map(Number),fg=rgb(getComputedStyle(e).color);let bg=[255,255,255],chain=[];
  for(let p=e;p;p=p.parentElement)chain.push(rgb(getComputedStyle(p).backgroundColor));
  for(const c of chain.reverse()){const a=c.length>3?c[3]:1;if(c.length>=3)bg=bg.map((v,i)=>c[i]*a+v*(1-a));}
  const lum=c=>c.slice(0,3).map(v=>{v/=255;return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}).reduce((a,v,i)=>a+v*[.2126,.7152,.0722][i],0);
  const a=lum(fg),b=lum(bg);return {ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05),outline:getComputedStyle(e).outlineWidth};
 });assert.ok(contrast.ratio>=4.5,JSON.stringify(contrast));assert.equal(contrast.outline,'2px');
 assert.equal(await page.locator('[data-trial-reads]').innerText(),'—','missing telemetry is unknown, not zero');
 await page.screenshot({path:`${out}/${width}-${theme}-idle.png`,fullPage:true});
 await start.focus();await start.press('Enter');await page.getByRole('heading',{name:'Trial running',exact:true}).waitFor();
 assert.equal(posts.length,1);assert.equal(posts[0].method,'POST');assert.equal(posts[0].body._csrf,'fixture-csrf');
 assert.equal(posts[0].body.idempotencyKey,'12345678-1234-1234-1234-123456789abc');
 assert.ok(posts[0].headers['content-type'].startsWith('application/x-www-form-urlencoded'));
 assert.equal(await start.isDisabled(),true);assert.equal(await page.locator('[data-trial-state]').evaluate(e=>e===document.activeElement),true);
 assert.equal(await page.locator('[data-trial-reads]').innerText(),'3 of 10');
 await page.screenshot({path:`${out}/${width}-${theme}-running.png`,fullPage:true});
 await page.getByRole('button',{name:'Cancel trial',exact:true}).click();await page.getByRole('heading',{name:'Trial cancelled',exact:true}).waitFor();
 assert.equal(posts.length,2);assert.equal(posts[1].path.endsWith('/cancel'),true);assert.equal(posts[1].body._csrf,'fixture-csrf');
 assert.equal(await page.getByRole('button',{name:'Cancel trial',exact:true}).isDisabled(),true);
 assert.match(await page.locator('[data-trial-message]').innerText(),/in-flight request may still finish/);
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 assert.deepEqual(errors,[]);await page.screenshot({path:`${out}/${width}-${theme}-cancelled.png`,fullPage:true});await page.close();
});
for(const state of ['COMPLETED','AUTHORIZATION_REQUIRED','UNRESOLVED_EVIDENCE','DEADLINE','GET_LIMIT','OAUTH_LIMIT','RETRY_EXHAUSTED','RETRY_OUTSIDE_WINDOW','REQUEST_OUTCOME_UNKNOWN','JOB_CLAIM_LIMIT','FAMILY_LIMIT'])test('terminal outcome does not renew: '+state,async()=>{
 const {page,posts}=await fixture(390,'dark',{...running,state});await page.locator('[data-trial-state]').filter({hasText:/Checking/}).waitFor({state:'hidden'});
 assert.equal(await page.getByRole('button',{name:'Start approved KARACA trial'}).isDisabled(),true);
 assert.equal(await page.getByRole('button',{name:'Cancel trial',exact:true}).isDisabled(),true);assert.equal(posts.length,0);
 await page.screenshot({path:`${out}/390-dark-${state}.png`,fullPage:true});await page.close();
});
for(const mode of ['denied','login','unreadable','malformed'])test('status '+mode+' fails closed without POST',async()=>{
 const {page,posts}=await fixture(390,'light',{state:'NOT_STARTED'},{[mode]:true});await page.locator('[data-trial-error]').waitFor({state:'visible'});
 assert.equal(await page.getByRole('button',{name:'Start approved KARACA trial'}).isDisabled(),true);assert.equal(posts.length,0);
 assert.equal(await page.locator('[data-trial-error]').evaluate(e=>e===document.activeElement),true);
 assert.ok(!(await page.locator('body').innerText()).includes('fixture-private-payload'));
 await page.screenshot({path:`${out}/390-light-${mode}.png`,fullPage:true});await page.close();
});
test('pending Start locks duplicate submissions',async()=>{
 let release;const hold=new Promise(resolve=>{release=resolve});const {page,posts}=await fixture(390,'light',{state:'NOT_STARTED'},{hold});
 await page.getByRole('heading',{name:'Ready to start',exact:true}).waitFor();await page.getByRole('button',{name:'Start approved KARACA trial'}).click();
 await page.getByRole('heading',{name:'Starting trial…',exact:true}).waitFor();await page.locator('[data-trial-start]').dispatchEvent('submit');
 await page.screenshot({path:`${out}/390-light-starting.png`,fullPage:true});release();await page.getByRole('heading',{name:'Trial running',exact:true}).waitFor();assert.equal(posts.length,1);
 await page.close();
});
test('cancellation requested is distinct from confirmed cancellation',async()=>{
 let release;const cancelHold=new Promise(resolve=>{release=resolve});const {page,posts}=await fixture(390,'dark',running,{cancelHold});
 await page.getByRole('heading',{name:'Trial running',exact:true}).waitFor();await page.getByRole('button',{name:'Cancel trial',exact:true}).click();
 await page.getByRole('heading',{name:'Cancellation requested',exact:true}).waitFor();await page.locator('[data-trial-cancel]').dispatchEvent('submit');
 await page.screenshot({path:`${out}/390-dark-cancel-requested.png`,fullPage:true});release();await page.getByRole('heading',{name:'Trial cancelled',exact:true}).waitFor();assert.equal(posts.length,1);await page.close();
});
test('Start conflict never automatically retries or creates a new key',async()=>{
 const {page,posts}=await fixture(390,'light',{state:'NOT_STARTED'},{conflict:true});await page.getByRole('heading',{name:'Ready to start',exact:true}).waitFor();
 await page.getByRole('button',{name:'Start approved KARACA trial'}).click();await page.locator('[data-trial-error]').waitFor({state:'visible'});
 assert.equal(posts.length,1);assert.equal(await page.getByRole('button',{name:'Start approved KARACA trial'}).isDisabled(),true);
 assert.match(await page.locator('[data-trial-error]').innerText(),/already exists/);await page.close();
});
