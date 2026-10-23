const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const resource=(kind,name)=>path.resolve('src/main/resources/static',kind,name);
const markup=`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body class="app-page"><main class="workspace"><button id="outside">Outside</button>${['one','two'].map(key=>`<section class="data-card"><table data-table-widget="activity-${key}"><thead><tr>${Array.from({length:25},(_,i)=>`<th>${i===8?'Long inventory availability and fulfillment description':'Field '+i}</th>`).join('')}</tr></thead><tbody><tr>${Array.from({length:25},(_,i)=>`<td>${i}</td>`).join('')}</tr></tbody></table></section>`).join('')}</main></body></html>`;
let ordinary,hybrid;const measurements=[];
before(async()=>{const options={headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})};ordinary=await chromium.launch(options);hybrid=await chromium.launch({...options,args:['--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=6']});});
after(async()=>{if(process.env.COLUMNS_C2_EVIDENCE_DIR){fs.mkdirSync(process.env.COLUMNS_C2_EVIDENCE_DIR,{recursive:true});fs.writeFileSync(path.join(process.env.COLUMNS_C2_EVIDENCE_DIR,'measurements.json'),JSON.stringify(measurements,null,2));}await ordinary?.close();await hybrid?.close();});
async function fixture({input='fine',theme='light',width=390,height=640,server,scale=1}={}){
 const context=await (input==='hybrid'?hybrid:ordinary).newContext({viewport:{width,height},hasTouch:input==='coarse'}),page=await context.newPage();page.setDefaultTimeout(5000);
 const errors=[],requests=[];page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async r=>{const url=r.request().url();requests.push(url);if(url==='http://activity.test/')return r.fulfill({contentType:'text/html',body:markup});if(server&&url.includes('/progress'))return r.fulfill({contentType:'application/json',status:server.status||200,body:JSON.stringify(server.data||{})});return r.abort();});
 await page.goto('http://activity.test/');await page.evaluate(({theme,server})=>{document.documentElement.dataset.theme=theme;if(server)localStorage.setItem('nextai.catalogImportJob','synthetic-job');},{theme,server});
 for(const name of ['app.css','brand.css','table-widget.css','background-jobs.css'])await page.addStyleTag({path:resource('css',name)});
 for(const name of ['table-preferences.js','table-widget.js','background-jobs.js'])await page.addScriptTag({path:resource('js',name)});
 // Synthetic geometry only; no connection to a live browser or application.
 if(scale!==1){const cdp=await context.newCDPSession(page);await cdp.send('Emulation.setPageScaleFactor',{pageScaleFactor:scale});}
 await page.waitForFunction(()=>document.querySelectorAll('.table-column-control').length===2);
 return{page,context,errors,requests};
}
async function open(page,index=0){await page.locator('.table-column-control>summary').nth(index).click();await page.waitForFunction(()=>{const p=document.querySelector('.table-column-control[open] .table-column-panel');return p?.style.position==='fixed'&&p.style.top;});}
async function local(page){await page.evaluate(()=>window.syntheticTask=NextAiBackgroundJobs.start('Synthetic task','Preparing test data'));}
const dims=[[320,640],[390,844],[420,640],[430,640],[760,640],[760,360],[320,360],[390,240],[760,640,2],[761,640],[1280,800]];
for(const input of ['fine','coarse','hybrid'])for(const theme of ['light','dark'])test(`activity strip bounds and targets: ${input}, ${theme}`,async()=>{
 for(const [width,height,scale=1] of dims){const {page,context,errors}=await fixture({input,theme,width,height,scale});try{
  await open(page);await local(page);
  await page.waitForFunction(()=>!document.querySelector('.table-column-control[open] .table-column-activity')?.hidden);
  const m=await page.evaluate(()=>{const panel=document.querySelector('.table-column-control[open] .table-column-panel'),strip=panel.querySelector('.table-column-activity'),list=panel.querySelector('.table-column-list'),b=strip.querySelector('button');const box=e=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,w:r.width,h:r.height,right:r.right,bottom:r.bottom}};return{panel:box(panel),strip:box(strip),list:box(list),button:box(b),viewport:{w:visualViewport.width,h:visualViewport.height,x:visualViewport.offsetLeft,y:visualViewport.offsetTop},trayHidden:document.querySelector('.background-job-stack').hidden,anyCoarse:matchMedia('(any-pointer:coarse)').matches,fine:matchMedia('(pointer:fine)').matches,coarse:matchMedia('(pointer:coarse)').matches,eyes:[...panel.querySelectorAll('.column-eye')].map(box)};});
  measurements.push({input,theme,width,height,scale,...m});assert.ok(m.trayHidden);assert.ok(m.button.h>=44&&m.button.w>=44);assert.ok(m.panel.x>=m.viewport.x+11&&m.panel.right<=m.viewport.x+m.viewport.w-11);assert.ok(m.panel.y>=m.viewport.y+11&&m.panel.bottom<=m.viewport.y+m.viewport.h-11);assert.ok(m.list.h>0);
  if(input!=='fine')assert.ok(m.eyes.every(b=>b.w>=44&&b.h>=44));if(input==='hybrid')assert.ok(m.fine&&m.anyCoarse&&!m.coarse);
  // Review and final controls remain reachable even below normal landscape height.
  const review=page.getByRole('button',{name:'Review background activity'});await review.scrollIntoViewIfNeeded();const hit=await review.evaluate(b=>{const r=b.getBoundingClientRect();return [[r.x+r.width/2,r.y+r.height/2],[r.x+2,r.y+r.height/2],[r.right-2,r.y+r.height/2],[r.x+r.width/2,r.y+2],[r.x+r.width/2,r.bottom-2]].every(([x,y])=>document.elementFromPoint(x,y)===b);});assert.ok(hit);
  await page.evaluate(()=>syntheticTask.fail('Synthetic failure requires review'));
  assert.equal(await page.locator('.table-column-control[open] .table-column-activity>span').textContent(),'Background activity needs attention');
  if(process.env.COLUMNS_C2_EVIDENCE_DIR&&input==='coarse'&&((width===390&&height===844)||(width===320&&height===360))){fs.mkdirSync(process.env.COLUMNS_C2_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.COLUMNS_C2_EVIDENCE_DIR,`activity-${theme}-${width}x${height}.png`)});}
  await review.click();assert.equal(await page.locator('.table-column-control[open]').count(),0);assert.equal(await page.locator('.background-job-stack').isVisible(),true);assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Synthetic task');assert.deepEqual(errors,[]);
 }finally{await context.close();}}
});
test('arrival, transitions, deduplicated announcements, priority and focus; no preference writes',async()=>{
 const{page,context,errors}=await fixture();try{
  await open(page);const original=await page.evaluate(()=>JSON.stringify(localStorage));await local(page);
  await page.locator('.table-column-control[open] input').focus();
  await page.evaluate(()=>{syntheticTask.update('Processing first batch');syntheticTask.update('Processing first batch');});
  assert.equal(await page.locator('.background-job-announcer>div').count(),2);
  await page.evaluate(()=>{window.otherTask=NextAiBackgroundJobs.start('Second task','Preparing');syntheticTask.fail('Failed: details retained');otherTask.complete('Finished second task');});
  assert.equal(await page.locator('.table-column-control[open] .table-column-activity>span').textContent(),'Background activity needs attention');assert.equal(await page.evaluate(()=>document.activeElement.matches('input')),true);
  const n=await page.locator('.background-job-announcer>div').count();await page.keyboard.press('Escape');assert.equal(await page.evaluate(()=>document.activeElement.matches('.table-column-control>summary')),true);assert.equal(await page.locator('.background-job-card').count(),2);
  await open(page);await page.keyboard.press('Escape');assert.equal(await page.locator('.background-job-announcer>div').count(),n);assert.equal(await page.evaluate(()=>JSON.stringify(localStorage)),original);
  await page.locator('.background-job-close').first().click();await open(page);assert.equal(await page.locator('.table-column-control[open] .table-column-activity>span').textContent(),'Background activity completed');
  await page.getByRole('button',{name:'Review background activity'}).press('Enter');assert.equal(await page.evaluate(()=>document.activeElement.textContent),'Second task');assert.equal(await page.locator('.background-job-card').count(),1);assert.deepEqual(errors,[]);
 }finally{await context.close();}
});
test('all close paths, replacement/removal, no invisible focus and no tray flash on switch',async()=>{
 const{page,context}=await fixture();try{
  await local(page);await page.locator('.background-job-close').focus();await page.evaluate(()=>document.querySelector('.table-column-control').open=true);
  await page.waitForFunction(()=>document.querySelector('.background-job-stack').hidden);assert.equal(await page.evaluate(()=>document.activeElement.matches('summary')),true);assert.equal(await page.locator('.background-job-stack').getAttribute('inert'),'');
  await page.evaluate(()=>{window.flashes=[];new MutationObserver(()=>flashes.push(document.querySelector('.background-job-stack').hidden)).observe(document.querySelector('.background-job-stack'),{attributes:true,attributeFilter:['hidden']});document.querySelectorAll('.table-column-control')[1].open=true;});
  await page.waitForFunction(()=>document.querySelectorAll('.table-column-control[open]').length===1);assert.deepEqual(await page.evaluate(()=>flashes),[]);
  await page.evaluate(()=>document.querySelector('.table-column-control[open]').remove());await page.waitForFunction(()=>!document.querySelector('.background-job-stack').hidden);
  await open(page);await page.locator('.table-column-control>summary').click();await page.waitForFunction(()=>!document.querySelector('.background-job-stack').hidden);
  await open(page);await page.locator('#outside').click();await page.waitForFunction(()=>!document.querySelector('.background-job-stack').hidden);
 }finally{await context.close();}
});
for(const state of ['COMPLETED_WITH_WARNINGS','FAILED','COMPLETED'])test(`server ${state}: preserved storage and review destination`,async()=>{
 const{page,context,errors,requests}=await fixture({server:{data:{state,phase:'Synthetic result',error:'Synthetic failure',totalRows:4}}});try{
  await page.waitForFunction(()=>document.querySelector('.background-job-card')?.dataset.jobState);
  await open(page);assert.equal(await page.locator('.table-column-control[open] .table-column-activity>span').textContent(),state==='COMPLETED'?'Background activity completed':'Background activity needs attention');
  assert.equal(await page.evaluate(()=>localStorage.getItem('nextai.catalogImportJob')),'synthetic-job');await page.getByRole('button',{name:'Review background activity'}).click();
  assert.equal(await page.evaluate(()=>document.activeElement.matches('[data-job-review]')),state==='FAILED');if(state==='COMPLETED_WITH_WARNINGS')assert.match(await page.locator('.background-job-title strong').textContent(),/Needs attention/);assert.equal(await page.locator('.background-job-announcer[aria-live=polite]').count(),1);assert.equal(await page.locator('.background-job-card[role=status]').count(),0);assert.ok(requests.every(u=>u.startsWith('http://activity.test/')));assert.deepEqual(errors,[]);
 }finally{await context.close();}
});
for(const status of [409,503])test(`existing ${status===409?'context mismatch':'reconnecting'} remains visible and announced`,async()=>{
 const{page,context,errors}=await fixture({server:{status}});try{await page.waitForFunction(()=>/Return to the account|Reconnecting/.test(document.querySelector('.background-job-phase')?.textContent));await open(page);assert.equal(await page.locator('.table-column-control[open] .table-column-activity>span').textContent(),'Background activity in progress');assert.match(await page.locator('.background-job-announcer').textContent(),status===409?/Return to the account/:/Reconnecting/);assert.deepEqual(errors,[]);}finally{await context.close();}
});
for(const state of ['COMPLETED','COMPLETED_WITH_WARNINGS','FAILED'])test(`polling ${state} while open retains latest state without focus theft`,async()=>{
 const server={data:{state:'RUNNING',phase:'Processing synthetic rows',percent:20}};
 const{page,context}=await fixture({server});try{
  await page.waitForFunction(()=>document.querySelector('[data-job-percent]')?.textContent==='20%');await open(page);await page.locator('.table-column-control[open] input').focus();
  const n=await page.locator('.background-job-announcer>div').count();
  await page.waitForTimeout(1300);assert.equal(await page.locator('.background-job-announcer>div').count(),n);
  server.data={state,phase:'Latest synthetic terminal result',percent:100};
  await page.waitForFunction(state=>document.querySelector('.background-job-card').dataset.jobState===state,state);
  assert.equal(await page.evaluate(()=>document.activeElement.matches('input')),true);assert.equal(await page.locator('.background-job-stack').isVisible(),false);
  assert.equal(await page.locator('.background-job-announcer>div').count(),n+1);
  await page.getByRole('button',{name:'Review background activity'}).click();assert.match(await page.locator('.background-job-phase').textContent(),state==='FAILED'?/Review the staged file/:/Latest synthetic terminal result/);
  assert.equal(await page.evaluate(()=>localStorage.getItem('nextai.catalogImportJob')),'synthetic-job');
 }finally{await context.close();}
});
test('no feedback produces no strip; non-Columns UI does not suppress or discard tasks',async()=>{
 const{page,context}=await fixture();try{
  await open(page);assert.equal(await page.locator('.table-column-activity:not([hidden])').count(),0);await page.keyboard.press('Escape');await local(page);
  assert.equal(await page.locator('.background-job-stack').isVisible(),true);await page.evaluate(()=>{const d=document.createElement('details');d.innerHTML='<summary>Unrelated</summary><p>Menu</p>';document.body.append(d);d.open=true;});assert.equal(await page.locator('.background-job-stack').isVisible(),true);
  await page.locator('.background-job-close').click();await open(page);assert.equal(await page.locator('.table-column-activity:not([hidden])').count(),0);await page.keyboard.press('Escape');assert.equal(await page.locator('.background-job-card').count(),0);
 }finally{await context.close();}
});
test('immediate-applied choices survive activity review and reload in disposable storage',async()=>{
 const{page,context}=await fixture();try{
  await open(page);const eye=page.locator('.table-column-control[open] .column-eye:not(:disabled)').first();await eye.click();const stored=await page.evaluate(()=>JSON.stringify(localStorage));await local(page);
  await page.getByRole('button',{name:'Review background activity'}).click();assert.equal(await page.evaluate(()=>JSON.stringify(localStorage)),stored);
  await page.reload();for(const name of ['app.css','brand.css','table-widget.css','background-jobs.css'])await page.addStyleTag({path:resource('css',name)});for(const name of ['table-preferences.js','table-widget.js','background-jobs.js'])await page.addScriptTag({path:resource('js',name)});
  await open(page);assert.equal(await page.locator('.table-column-control[open] .column-eye[aria-pressed=false]').count(),1);assert.equal(await page.evaluate(()=>JSON.stringify(localStorage)),stored);
 }finally{await context.close();}
});
