const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
const script=fs.readFileSync('src/main/resources/static/js/package-name-editor.js','utf8')+'\n'+fs.readFileSync('src/main/resources/static/js/packing-slip-workspace.js','utf8');
async function fixture(run){const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();const calls=[];let fail=false;
 await page.route('http://packing.test/**',async route=>{const request=route.request();if(request.url().endsWith('/'))return route.fulfill({contentType:'text/html',body:`<main class="packing-workspace" data-order-id="order"><select id="packing-package"><option>Pak</option><option>UPS OMBox</option><option value="__custom">Custom</option></select><label id="custom-package-field" hidden><input id="custom-package"></label><button id="rename-package">Rename package</button><button id="print-slip">Print</button><button id="save-package">Save package</button><p id="packing-status"></p><span data-printed-package>Pak</span><dialog id="packing-name-dialog"><h2 id="packing-name-title"></h2><span class="packing-name-combo"><input id="packing-rename-value"><button id="packing-name-toggle">Choose existing package</button></span><div id="packing-name-options" style="position:fixed" hidden></div><p id="packing-name-summary"></p><p id="packing-name-error"></p><button data-name-cancel>Cancel</button><button id="packing-name-confirm">Review</button></dialog></main>`});
 calls.push(request.url());await route.fulfill({status:fail?409:200,contentType:'application/json',body:JSON.stringify(fail?{detail:'Assignments changed. Review again.'}:{source:'Pak',destination:'UPS OMBox',operation:'MERGE',affectedAssignments:2,revision:'fixture'})});});
 await page.goto('http://packing.test/');await page.addScriptTag({content:script});await run(page,calls,()=>fail=true);
 }finally{await browser.close();}}
test('merge is explicit, cancel does not write and success selects destination/removes source',()=>fixture(async(page,calls)=>{
 await page.click('#rename-package');await page.fill('#packing-rename-value','UPS OMBox');assert.equal(calls.length,0);
 await page.click('#packing-name-confirm');await page.getByRole('button',{name:'Merge packages'}).waitFor();assert.equal(calls.length,1);assert.match(await page.textContent('#packing-name-summary'),/2 saved assignments/);
 await page.click('[data-name-cancel]');assert.equal(calls.length,1);
 await page.click('#rename-package');await page.fill('#packing-rename-value','UPS OMBox');await page.click('#packing-name-confirm');await page.getByRole('button',{name:'Merge packages'}).waitFor();await page.click('#packing-name-confirm');await page.locator('#packing-name-dialog').waitFor({state:'hidden'});
 assert.equal(await page.inputValue('#packing-package'),'UPS OMBox');assert.equal(await page.locator('#packing-package option').filter({hasText:/^Pak$/}).count(),0);assert.equal(await page.textContent('[data-printed-package]'),'UPS OMBox');
}));
test('failed merge preserves typed value and original package choice',()=>fixture(async(page,calls,fail)=>{
 await page.click('#rename-package');await page.fill('#packing-rename-value','UPS OMBox');await page.click('#packing-name-confirm');await page.getByRole('button',{name:'Merge packages'}).waitFor();fail();await page.click('#packing-name-confirm');await page.getByText('Assignments changed. Review again.').waitFor();assert.equal(await page.inputValue('#packing-package'),'Pak');assert.equal(await page.inputValue('#packing-rename-value'),'UPS OMBox');assert.equal(await page.locator('#packing-name-confirm').isEnabled(),true);
}));

test('existing package choices allow keyboard selection and unrestricted new names without saving',()=>fixture(async(page,calls)=>{
 await page.click('#rename-package');await page.click('#packing-name-toggle');assert.equal(await page.locator('#packing-name-options button').count(),1);assert.equal(await page.textContent('#packing-name-options'),'UPS OMBox');
 await page.locator('#packing-rename-value').press('ArrowDown');await page.locator('#packing-rename-value').press('Enter');assert.equal(await page.inputValue('#packing-rename-value'),'UPS OMBox');assert.equal(calls.length,0);
 await page.fill('#packing-rename-value','New insulated package');assert.equal(await page.inputValue('#packing-rename-value'),'New insulated package');assert.equal(await page.locator('#packing-name-options').isVisible(),false);assert.equal(calls.length,0);
 await page.click('#packing-name-toggle');await page.locator('#packing-rename-value').press('Escape');assert.equal(await page.locator('#packing-name-options').isVisible(),false);assert.equal(await page.locator('#packing-name-dialog').isVisible(),true);
}));
