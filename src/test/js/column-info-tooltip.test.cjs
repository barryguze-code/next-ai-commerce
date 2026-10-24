const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
for(const width of [390,1440])test(`column help owns one contained tooltip without sorting ${width}`,async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{const page=await browser.newPage({viewport:{width,height:600},hasTouch:width===390});
 const css=['access','ux-components'].map(n=>fs.readFileSync(path.resolve('src/main/resources/static/css/'+n+'.css'),'utf8')).join('\n');
 await page.setContent(`<style>${css}body{margin:0} .clip{margin-top:20px;overflow:hidden;width:100%}th{height:45px}table{width:100%}</style><div class="clip"><table><thead><tr><th tabindex="0" data-tooltip="Sort this column" aria-sort="none">Current buying cost <button class="column-info" type="button" aria-label="About current buying cost" data-tip="After vendor discount; receiving charges are excluded.">i</button></th></tr></thead><tbody><tr><td>1</td></tr><tr><td>2</td></tr></tbody></table></div>`);
 await page.evaluate(()=>{let h=document.querySelector('th');h.addEventListener('click',()=>h.setAttribute('aria-sort','ascending'));h.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' ')h.setAttribute('aria-sort','ascending');});});
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/platform-tooltips.js')});
 const button=page.getByRole('button',{name:'About current buying cost'});await button.hover();
 const tip=page.getByRole('tooltip');await tip.waitFor({state:'visible'});assert.equal(await tip.textContent(),'After vendor discount; receiving charges are excluded.');assert.equal(await button.evaluate(e=>getComputedStyle(e,'::after').content),'none');
 const r=await tip.boundingBox();assert.ok(r.x>=0&&r.y>=0&&r.x+r.width<=width&&r.y+r.height<=600);
 await button.click();assert.equal(await tip.isVisible(),true);assert.equal(await page.locator('th').getAttribute('aria-sort'),'none');await button.focus();await button.press('Enter');assert.equal(await page.locator('th').getAttribute('aria-sort'),'none');await button.press('Space');assert.equal(await page.locator('th').getAttribute('aria-sort'),'none');if(width===390){await button.tap();assert.equal(await tip.isVisible(),true);}await button.press('Escape');assert.equal(await tip.isVisible(),false);
 await page.locator('th').focus();await page.locator('th').press('Enter');assert.equal(await page.locator('th').getAttribute('aria-sort'),'ascending');
 }finally{await browser.close();}
});

for(const theme of ['light','dark'])test(`column help stays readable after desktop to phone focus scroll (${theme})`,async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
 const page=await browser.newPage({viewport:{width:1764,height:884},hasTouch:true});page.setDefaultTimeout(3000);
 const css=['access','ux-components'].map(n=>fs.readFileSync(path.resolve('src/main/resources/static/css/'+n+'.css'),'utf8')).join('\n');
 await page.setContent(`<html data-theme="${theme}"><style>${css}*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif}.table-wrap{margin:40px 16px;overflow:auto}table{width:1500px;table-layout:fixed}th{height:45px}th:first-child{width:1240px}html[data-theme=dark] body{background:#142436;color:#e1e9f4}</style><div class="table-wrap"><table><thead><tr><th>Products</th><th tabindex="0" aria-sort="none">Current buying cost <button class="column-info" type="button" aria-label="About current buying cost" data-tip="After vendor discount; receiving charges are excluded.">i</button></th></tr></thead></table></div><button>Next</button><button data-tooltip="Short help">Other help</button>`);
 await page.evaluate(()=>{const h=document.querySelector('th:last-child');for(const name of ['click','keydown'])h.addEventListener(name,e=>{if(name==='click'||['Enter',' '].includes(e.key))h.setAttribute('aria-sort','ascending');});});
 await page.addScriptTag({path:path.resolve('src/main/resources/static/js/platform-tooltips.js')});
 const help=page.getByRole('button',{name:'About current buying cost'}),tip=page.getByRole('tooltip');
 await help.press('Enter');await tip.waitFor({state:'visible'});await help.press('Escape');await help.press('Tab');
 await page.setViewportSize({width:390,height:844});
 // Focusing the offscreen heading scrolls the real overflow container.
 await help.press('Enter');
 await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 await help.press('Escape');await help.press('Enter');
 assert.ok(await page.locator('.table-wrap').evaluate(e=>e.scrollLeft)>0,'keyboard focus must exercise horizontal scrolling');
 const check=async(width,height)=>{
  await tip.waitFor({state:'visible',timeout:1500});const r=await tip.boundingBox();
  assert.ok(r.width>=280,`readable help width, got ${r.width}`);
  assert.ok(r.x>=8&&r.x+r.width<=width-7&&r.y>=8&&r.y+r.height<=height-7,JSON.stringify(r));
  assert.equal(await page.locator('th:last-child').getAttribute('aria-sort'),'none');
  assert.equal(await page.locator('[role=tooltip]').count(),1);
 };
 await check(390,844);await help.tap();await check(390,844);
 for(const width of [320,430,760,390]){
  await page.setViewportSize({width,height:844});
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  // An already-open tooltip must be updated or dismissed on resize.
  if(await tip.isVisible())await check(width,844);
  await help.press('Escape');await help.press('Enter');
  await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  await help.press('Enter');await check(width,844);
 }
 await help.press('Escape');assert.equal(await tip.isVisible(),false);assert.equal(await help.evaluate(e=>e===document.activeElement),true);
 await page.getByRole('button',{name:'Other help',exact:true}).focus();
 assert.equal(await tip.textContent(),'Short help');
 const short=await tip.boundingBox();assert.ok(short.width<200,'short shared tooltips remain compact');
 }finally{await browser.close();}
});
