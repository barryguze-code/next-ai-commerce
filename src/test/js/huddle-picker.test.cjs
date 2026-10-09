const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {chromium}=require('playwright');
test('teammate picker supports search, presence, filters, keyboard dismissal and compact layout',async()=>{
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const page=await browser.newPage({viewport:{width:1280,height:800}});
  await page.route('http://picker.test/',r=>r.fulfill({contentType:'text/html',body:'<body class="app-page" style="font-family:Arial"><header class="workspace-header" style="display:flex;justify-content:flex-end"><div class="context-menu"></div></header></body>'}));
  await page.route('**/app/collaboration/teammates',r=>r.fulfill({json:[{id:'one',name:'ibcorellc',email:'one@example.test'},{id:'two',name:'Offline teammate',email:'two@example.test'}]}));
  await page.goto('http://picker.test/');
  await page.addStyleTag({path:path.resolve('src/main/resources/static/css/huddle-floating.css')});
  await page.addScriptTag({path:path.resolve('src/main/resources/static/js/huddle-floating.js')});
  await page.evaluate(()=>window.LiveHuddleUI.event({type:'WELCOME',self:{id:'me'},canHuddle:true,online:[{id:'one',name:'ibcorellc',email:'one@example.test'}],huddles:[]},()=>true));
  const trigger=page.getByRole('button',{name:'Open huddle and online teammates'});
  await trigger.click();
  const search=page.getByRole('searchbox',{name:'Search teammates'});
  assert.equal(await search.evaluate(n=>n===document.activeElement),true);
  assert.equal(await page.getByRole('button',{name:'Online',exact:true}).getAttribute('aria-pressed'),'true');
  assert.equal(await page.getByRole('button',{name:'ibcorellc · Online'}).isEnabled(),true);
  await search.fill('missing');assert.equal(await page.getByRole('status').innerText(),'No matching teammates.');
  await search.fill('');await page.getByRole('button',{name:'All teammates',exact:true}).click();
  await page.getByRole('button',{name:'Offline teammate · Offline'}).waitFor();
  assert.equal(await page.getByRole('button',{name:'Offline teammate · Offline'}).isDisabled(),true);
  await search.focus();await search.press('Escape');
  assert.equal(await trigger.getAttribute('aria-expanded'),'false');assert.equal(await trigger.evaluate(n=>n===document.activeElement),true);
  await trigger.click();await page.setViewportSize({width:375,height:650});
  const box=await page.locator('.huddle-people-panel').boundingBox();assert.ok(box.x>=0&&box.x+box.width<=375);
  assert.equal(await page.locator('.huddle-people-panel [title]').count(),0);
  await page.setViewportSize({width:1280,height:800});
  await page.locator('.huddle-people-panel').screenshot({path:'/tmp/huddle-picker-verified.png'});
 }finally{await browser.close();}
});
