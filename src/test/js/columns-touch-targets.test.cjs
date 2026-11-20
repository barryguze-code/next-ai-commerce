const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const resource=(kind,name)=>path.resolve('src/main/resources/static',kind,name);
const css=['app.css','brand.css','table-widget.css','mobile-navigation.css'];
const js=['table-preferences.js','table-widget.js','mobile-navigation.js'];
const longTitle='Field 8 with an unusually long marketplace inventory availability heading';
const headings=Array.from({length:25},(_,i)=>i===8?longTitle:'Field '+i);
const markup=`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body class="app-page"><aside class="sidebar"><a class="brand" href="#">Synthetic preview</a><nav class="product-nav"><a class="active" href="#">Marketplace SKUs</a><a href="#">Orders</a></nav><div class="sidebar-user"><button>Theme</button></div></aside><main class="workspace"><header style="min-height:80px">Isolated Columns fixture — synthetic fields only</header><section class="data-card"><div class="table-wrap"><table data-table-widget="columns-touch-test"><thead><tr>${headings.map(t=>`<th>${t}</th>`).join('')}</tr></thead><tbody><tr>${headings.map((t,i)=>`<td>${i}</td>`).join('')}</tr></tbody></table></div></section></main></body></html>`;
let ordinary,hybrid;
const measurements=[];
before(async()=>{
 const options={headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})};
 ordinary=await chromium.launch(options);
 hybrid=await chromium.launch({...options,args:['--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=6']});
});
after(async()=>{
 const evidence=process.env.COLUMNS_C1_EVIDENCE_DIR;
 if(evidence){
  fs.mkdirSync(evidence,{recursive:true});fs.writeFileSync(path.join(evidence,'measurements.json'),JSON.stringify(measurements,null,2));
  const styles=css.map(name=>fs.readFileSync(resource('css',name),'utf8').replace('@media(any-pointer:coarse)','@media all')).join('\n');
  const scripts=js.map(name=>'<script>'+fs.readFileSync(resource('js',name),'utf8').replaceAll('</script','<\\/script')+'</script>').join('');
  const preview=markup.replace('<head>','<head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data:"><style>'+styles+'</style>')
   .replace('Isolated Columns fixture — synthetic fields only','Offline touch-geometry preview — coarse CSS condition forced for review only; not UAT.')
   .replace('</body>',scripts+'<script>document.addEventListener("DOMContentLoaded",()=>{document.querySelector(".table-column-control").open=true})</script></body>');
  fs.writeFileSync(path.join(evidence,'columns-c1-touch-preview.html'),preview);
 }
 await ordinary?.close();await hybrid?.close();
});
async function fixture(input,theme,width,height,scale=1){
 const context=await (input==='hybrid'?hybrid:ordinary).newContext({viewport:{width,height},hasTouch:input==='coarse'});
 const page=await context.newPage();page.setDefaultTimeout(5000);page.setDefaultNavigationTimeout(5000);
 await page.route('**/*',r=>r.request().url()==='http://columns.test/'?r.fulfill({contentType:'text/html',body:markup}):r.abort());
 await page.goto('http://columns.test/');await page.evaluate(theme=>document.documentElement.dataset.theme=theme,theme);
 for(const name of css)await page.addStyleTag({path:resource('css',name)});
 for(const name of js)await page.addScriptTag({path:resource('js',name)});
 if(scale!==1){const session=await context.newCDPSession(page);await session.send('Emulation.setPageScaleFactor',{pageScaleFactor:scale});}
 await page.locator('.table-column-control>summary').click();
 await page.waitForFunction(()=>{const p=document.querySelector('.table-column-panel');return p.style.position==='fixed'&&p.style.top!==''&&p.style.width!=='';});
 return {page,context};
}
const scenarios=[[320,640],[390,844],[420,640],[430,640],[760,640],[760,360],[320,360],[390,240],[760,640,2],[1280,800]];
for(const input of ['fine','coarse','hybrid'])for(const theme of ['light','dark'])test(`Columns targets and bounds: ${input}, ${theme}`,async()=>{
 for(const [width,height,scale=1] of scenarios){
  console.log("Columns case",input,theme,width,height,scale);
  const {page,context}=await fixture(input,theme,width,height,scale);
  try{
   const result=await page.evaluate(()=>{
    const panel=document.querySelector('.table-column-panel'),r=panel.getBoundingClientRect(),v=visualViewport;
    const box=e=>{const a=e.getBoundingClientRect();return{x:a.x,y:a.y,w:a.width,h:a.height,right:a.right,bottom:a.bottom};};
    const rows=Array.from(document.querySelectorAll('.table-column-option'));
    return {fine:matchMedia('(pointer:fine)').matches,coarse:matchMedia('(pointer:coarse)').matches,anyCoarse:matchMedia('(any-pointer:coarse)').matches,
     panel:box(panel),viewport:{left:v.offsetLeft,top:v.offsetTop,w:v.width,h:v.height,scale:v.scale},
     reset:box(panel.querySelector('header button')),search:box(panel.querySelector('.table-column-search')),footer:box(panel.querySelector('.table-column-footnote')),
     controls:rows.flatMap(row=>Array.from(row.querySelectorAll('button')).map(box)),rows:rows.map(box),
     list:box(panel.querySelector('.table-column-list')),icon:box(panel.querySelector('.column-eye svg')),
     overlap:rows.some(row=>{const [a,b]=row.querySelectorAll('button');return a.getBoundingClientRect().right>b.getBoundingClientRect().left;}),
     labelWrap:getComputedStyle(rows[9].querySelector('span')).whiteSpace,
     requiredDisabled:Array.from(panel.querySelectorAll('.table-column-option.required .column-eye')).every(e=>e.disabled)};
   });
   const note=JSON.stringify({input,theme,width,height,scale,result:{...result,controls:result.controls.slice(0,4),rows:result.rows.slice(0,2)}});
   assert.equal(result.fine,input!=='coarse',note);assert.equal(result.coarse,input==='coarse',note);assert.equal(result.anyCoarse,input!=='fine',note);
   assert.ok(result.panel.x>=result.viewport.left+11&&result.panel.right<=result.viewport.left+result.viewport.w-11,note);
   assert.ok(result.panel.y>=result.viewport.top+11&&result.panel.bottom<=result.viewport.top+result.viewport.h-11,note);
   assert.ok(result.reset.y>=result.panel.y&&result.reset.bottom<=result.panel.bottom,note);
   assert.ok(result.search.bottom<=result.panel.bottom,note);
   if(height>=360)assert.ok(result.footer.bottom<=result.panel.bottom,note);
   else{await page.locator('.table-column-footnote').scrollIntoViewIfNeeded();const footer=await page.locator('.table-column-footnote').boundingBox(),panel=await page.locator('.table-column-panel').boundingBox();assert.ok(footer.y+footer.height<=panel.y+panel.height+1,note);}
   assert.equal(result.overlap,false,note);assert.equal(result.requiredDisabled,true,note);
   assert.equal(result.icon.w,17,note);assert.equal(result.icon.h,17,note);
   if(input==='fine'){assert.ok(result.controls.every(r=>r.w===26&&r.h===26),note);assert.ok(result.reset.h<44,note);assert.equal(result.labelWrap,'nowrap',note);}
   else{assert.ok(result.controls.every(r=>r.w>=44&&r.h>=44),note);assert.ok(result.reset.w>=44&&result.reset.h>=44,note);assert.equal(result.labelWrap,'normal',note);}
   if(height>=360)assert.ok(result.list.h>=result.rows[0].h,note);
   // Final controls remain reachable using the independent scroll list.
   const last=page.locator('.column-drag-handle').last();await last.scrollIntoViewIfNeeded();
   const visible=await last.boundingBox(),list=await page.locator('.table-column-list').boundingBox();
   assert.ok(visible.y>=list.y-1&&visible.y+visible.height<=list.y+list.height+1,note);
   const hit=element=>{const r=element.getBoundingClientRect();return [[r.x+r.width/2,r.y+r.height/2],[r.x+1,r.y+r.height/2],[r.right-1,r.y+r.height/2],[r.x+r.width/2,r.y+1],[r.x+r.width/2,r.bottom-1]].every(([x,y])=>element.contains(document.elementFromPoint(x,y)));};
   assert.equal(await last.evaluate(hit),true,note);
   await page.locator('.table-column-panel').evaluate(e=>e.scrollTop=0);
   const reset=page.getByRole('button',{name:'Reset default',exact:true});assert.equal(await reset.evaluate(hit),true,note);
   measurements.push({input,theme,width,height,scale,...result});
   const evidence=process.env.COLUMNS_C1_EVIDENCE_DIR;
   if(evidence&&input==='coarse'&&width===390&&height===844){
    fs.mkdirSync(evidence,{recursive:true});await page.locator('.table-column-list').evaluate(e=>e.scrollTop=0);
    await page.screenshot({path:path.join(evidence,`columns-c1-${theme}-390.png`)});
    await page.locator('.table-column-search input').fill(longTitle);await page.screenshot({path:path.join(evidence,`columns-c1-${theme}-long-label-390.png`)});
   }
   if(evidence&&input==='coarse'&&width===760&&height===360)await page.screenshot({path:path.join(evidence,`columns-c1-${theme}-landscape.png`)});
  }finally{await context.close();}
 }
});
test('touch controls retain keyboard order, required state, Reset and browser-local persistence',async()=>{
 const {page,context}=await fixture('hybrid','light',390,640);
 try{
  const summary=page.locator('.table-column-control>summary'),eye=page.getByRole('button',{name:'Toggle '+longTitle,exact:true});
  await page.locator('.table-column-search input').fill(longTitle);await eye.click();assert.equal(await eye.getAttribute('aria-pressed'),'false');
  const stored=await page.evaluate(()=>localStorage.getItem('nextai.table.preferences.columns-touch-test'));assert.ok(stored);
  await page.locator('.table-column-search input').fill('');
  const handle=page.getByRole('button',{name:'Drag to reorder '+longTitle,exact:true});await handle.focus();await handle.press('ArrowUp');
  assert.equal(await handle.evaluate(e=>e===document.activeElement),true);
  const order=await page.evaluate(()=>JSON.parse(localStorage.getItem('nextai.table.preferences.columns-touch-test')).order);
  assert.ok(order.indexOf('field-8-with-an-unusually-long-marketplace-inventory-availability-heading')<order.indexOf('field-7'));
  await summary.press('Escape');assert.equal(await summary.evaluate(e=>e===document.activeElement),true);
  await page.reload();for(const name of css)await page.addStyleTag({path:resource('css',name)});for(const name of js)await page.addScriptTag({path:resource('js',name)});await summary.click();
  assert.equal(await page.getByRole('button',{name:'Toggle '+longTitle,exact:true}).getAttribute('aria-pressed'),'false');
  await page.getByRole('button',{name:'Reset default',exact:true}).click();assert.equal(await page.getByRole('button',{name:'Toggle '+longTitle,exact:true}).getAttribute('aria-pressed'),'true');
  assert.equal(await page.getByRole('button',{name:'Toggle Record',exact:true}).isEnabled(),false);
 }finally{await context.close();}
});
