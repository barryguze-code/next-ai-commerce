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
