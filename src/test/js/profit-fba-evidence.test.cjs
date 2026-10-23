const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
test('FBA calculator separates charges once, keeps zero distinct from missing and labels imported evidence',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{for(const theme of ['light','dark'])for(const width of [390,1440])for(const placement of [3,0,null]){
  const page=await browser.newPage({viewport:{width,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  const profit=placement==null?null:57-placement;
  const view={key:'SKU',kind:'SKU',title:'Synthetic FBA product',currency:'USD',date:'2026-10-22',fba:true,fbaFee:5,fbaReferralFee:15,inboundShippingCost:2,placementFee:placement,
   inboundSource:'Estimate · FBA-FIXTURE · charges posted 2026-10-09',feeSourceSummary:'Amazon fee report · imported 2026-10-14 UTC',feeSource:'Amazon fee report · imported 2026-10-14 12:00:00 UTC · 8 days since import; freshness not assessed',
   lines:[{sku:'SKU',quantity:1,unitPrice:100,productCost:20,otherCost:1,customerShipping:0}],packages:[],totals:{profit,productCost:20,referralFee:15,otherCost:1}};
  const fragment=fs.readFileSync('src/main/resources/templates/fragments/profit.html','utf8'),dialog=fragment.slice(fragment.indexOf('<dialog'),fragment.indexOf('</dialog>')+9);
  await page.route('**/*',route=>{const url=route.request().url();assert.equal(route.request().method(),'GET','verification must never save data');
   if(url.includes('/defaults'))return route.fulfill({json:[]});
   if(url.includes('/sku-pricing'))return route.fulfill({json:{enabled:false}});
   if(url.includes('/app/inventory/profit'))return route.fulfill({json:{SKU:view}});
   return route.fulfill({contentType:'text/html; charset=utf-8',body:`<html data-theme="${theme}"><head><meta charset="UTF-8"></head><body class="app-page"><button id="profit" data-profit-kind="SKU" data-profit-key="SKU">Review costs</button>${dialog}</body></html>`});});
  await page.goto('http://localhost/isolated-fba-evidence');
  for(const name of ['app.css','brand.css','platform-ui.css','ux-components.css','profit.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+name});
  await page.addScriptTag({path:'src/main/resources/static/js/platform-tooltips.js'});await page.addScriptTag({path:'src/main/resources/static/js/profit.js'});await page.locator('#profit .profit-calculator-art').waitFor();
  await page.locator('#profit').click();await page.locator('#profit-dialog[open]').waitFor();await page.locator('#profit-dialog').evaluate(e=>Promise.all(e.getAnimations().map(a=>a.finished)));await page.locator('[data-profit-totals] dt').first().waitFor();
  const values=await page.locator('[data-profit-totals]').evaluate(e=>Object.fromEntries([...e.querySelectorAll('dt')].map(n=>[n.textContent,n.nextElementSibling.childNodes[0].textContent])));
  assert.equal(values['Shipping to FBA'],'$2.00');assert.equal(values['Placement fee'],placement==null?'Placement fee unavailable':'$'+placement.toFixed(2));
  assert.equal(values['Estimated profit'],profit==null?'Missing costs — see above':'$'+profit.toFixed(2));
  assert.match(await page.locator('[data-profit-totals]').innerText(),/Amazon fee report · imported 2026-10-14 UTC/);
  assert.equal(await page.locator('#profit-dialog').isVisible(),true);
  if(process.env.FBA_EVIDENCE_SCREENSHOT_DIR&&placement===3){fs.mkdirSync(process.env.FBA_EVIDENCE_SCREENSHOT_DIR,{recursive:true});fs.writeFileSync(process.env.FBA_EVIDENCE_SCREENSHOT_DIR+`/fixture-${theme}-${width}.json`,JSON.stringify(await page.evaluate(()=>({dialogs:[...document.querySelectorAll('dialog')].map(e=>({id:e.id,open:e.open,modal:e.matches(':modal'),opacity:getComputedStyle(e).opacity,filter:getComputedStyle(e).filter,animation:getComputedStyle(e).animation,background:getComputedStyle(e).backgroundColor})),ancestors:[document.documentElement,document.body].map(e=>({tag:e.tagName,opacity:getComputedStyle(e).opacity,filter:getComputedStyle(e).filter})),center:document.elementsFromPoint(innerWidth/2,innerHeight/2).map(e=>({tag:e.tagName,id:e.id,cls:e.className}))})),null,2));await page.screenshot({path:process.env.FBA_EVIDENCE_SCREENSHOT_DIR+`/isolated-costs-${theme}-${width}.png`});}
  const evidence=page.locator('.profit-cost-evidence').first();await evidence.focus();assert.match(await page.locator('#platform-tooltip').innerText(),/8 days since import; freshness not assessed/);await page.keyboard.press('Escape');
  assert.equal(await page.locator('[data-profit-close]').innerText(),'×');
  const background=await page.locator('#profit-dialog').evaluate(e=>getComputedStyle(e).backgroundColor);assert.equal(background,theme==='dark'?'rgb(21, 31, 44)':'rgb(255, 255, 255)');
  const evidenceBlocks=await page.locator('.profit-cost-evidence').evaluateAll(es=>es.every(e=>getComputedStyle(e).display==='block'&&e.getBoundingClientRect().width<=e.parentElement.getBoundingClientRect().width));assert.equal(evidenceBlocks,true);
  assert.equal(await page.locator('.profit-product').inputValue(),'20.00');
  assert.deepEqual(errors,[]);
  await page.close();
 }}finally{await browser.close();}
});
