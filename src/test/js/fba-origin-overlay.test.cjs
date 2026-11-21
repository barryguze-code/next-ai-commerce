const {test}=require('node:test'),assert=require('node:assert/strict'),{chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path');
test('verified origin state is visibly over the pin without legacy initials or badge sizing',async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:900,height:350}});
  await page.route('http://fba-origin.local/images/**',route=>{const file=path.resolve('src/main/resources/static','.'+new URL(route.request().url()).pathname);if(!file.startsWith(path.resolve('src/main/resources/static')+path.sep))return route.abort();return route.fulfill({contentType:file.endsWith('.svg')?'image/svg+xml':'image/png',body:fs.readFileSync(file)});});
  await page.setContent('<base href="http://fba-origin.local/"><body class="app-page fba-shipments-page"><main><section class="data-card"><table class="fba-shipment-table"><thead><tr><th>Shipment</th></tr></thead><tbody><tr data-ship-from-postal="93725" data-ship-from-city="Fresno" data-ship-from-state="CA"><td>Verified origin</td></tr><tr data-ship-from-postal="91911" data-ship-from-city="Not supplied"><td>State not supplied</td></tr></tbody></table></section></main></body>');
  for(const file of ['table-widget.css','fba.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+file});
  await page.addScriptTag({path:'src/main/resources/static/js/platform-icons.js'});
  await page.addScriptTag({path:'src/main/resources/static/js/table-widget.js'});
  const frame=page.locator('.fba-origin-frame').first(),label=frame.locator('.fba-origin-pin [class*="state"]');
  await page.waitForFunction(()=>{const img=document.querySelector('.fba-origin-frame .fba-parent-origin-art');return img?.complete&&img.naturalWidth>0});
  assert.equal(await label.textContent(),'CA');
  assert.equal(await label.isVisible(),true,'verified CA text must render, not merely exist in the DOM');
  const measure=await label.evaluate(n=>{const s=getComputedStyle(n),r=n.getBoundingClientRect(),p=n.parentElement.getBoundingClientRect(),f=n.closest('.fba-origin-frame').getBoundingClientRect();return{display:s.display,width:r.width,height:r.height,minWidth:s.minWidth,minHeight:s.minHeight,inside:r.x>=p.x&&r.right<=p.right&&r.y>=p.y&&r.bottom<=p.bottom,frame:[f.width,f.height]}});
  assert.notEqual(measure.display,'none');assert.ok(measure.width>0&&measure.width<=28);assert.ok(measure.height>0&&measure.height<=14);assert.equal(measure.inside,true);assert.deepEqual(measure.frame,[80,88]);
  assert.equal(await frame.locator('.fba-origin-city').textContent(),'Fresno');assert.equal(await frame.locator('[title],[data-tooltip]').count(),0);
  assert.equal(await page.locator('.fba-origin-frame').nth(1).locator('.fba-origin-pin [class*="state"]').count(),0,'missing state must never be inferred from ZIP');
  fs.mkdirSync('.local/fba-origin-overlay-diagnosis',{recursive:true});fs.writeFileSync('.local/fba-origin-overlay-diagnosis/measurements.json',JSON.stringify(measure,null,2));await page.screenshot({path:'.local/fba-origin-overlay-diagnosis/visible-state.png'});
 }finally{await browser.close();}
});
