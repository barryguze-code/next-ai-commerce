const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const path=require('node:path');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});});
after(async()=>{await browser?.close();});
const history=Array.from({length:7},(_,i)=>({version:`1.0.${6-i}`,title:`Release ${6-i}`,sections:[{title:'Updates',items:['Readable release details']}]}));
async function fixture(options={}){
  const page=await browser.newPage(options.mobile?{viewport:{width:390,height:844}}:{});
  await page.route('http://release.test/**',route=>route.fulfill(route.request().url().endsWith('/history')?
    {status:options.error?500:200,contentType:'application/json',body:JSON.stringify(history)}:
    {contentType:'text/html',body:`<html ${options.dark?'data-theme="dark"':''}><body><input aria-label="Order search" value="Lily"><div class="sidebar-user"><a class="sidebar-build-info release-trigger" href="/app/releases" data-release-version="1.0.6" data-release-user="test-user" data-release-notification="true"><span>v1.0.6</span><span class="release-unread-dot" hidden></span></a></div></body></html>`}));
  await page.goto('http://release.test/app/orders');
  await page.addStyleTag({path:path.resolve('src/main/resources/static/css/release-panel.css')});
  await page.addScriptTag({path:path.resolve('src/main/resources/static/js/release-panel.js')});
  return page;
}
test('release panel keeps page state, shows five recent versions and all history, and remembers read state',async()=>{
  const page=await fixture();
  assert.equal(await page.locator('.release-unread-dot').isVisible(),true);
  await page.getByRole('link',{name:'v1.0.6'}).click();
  await page.getByRole('heading',{name:'Release 6',exact:true}).waitFor();
  assert.equal(await page.locator('#release-panel-version option').count(),5);
  assert.equal(await page.locator('.release-history-item').count(),7);
  assert.equal(await page.locator('.release-unread-dot').isVisible(),false);
  await page.getByRole('button',{name:'v1.0.0 Release 0'}).click();
  assert.equal(await page.getByRole('heading',{name:'Release 0',exact:true}).isVisible(),true);
  await page.getByRole('button',{name:'Back to workspace'}).click();
  assert.equal(page.url(),'http://release.test/app/orders');
  assert.equal(await page.getByLabel('Order search').inputValue(),'Lily');
  assert.equal(await page.evaluate(()=>localStorage.getItem('nextai.release.seen:test-user')),'1.0.6');
  await page.close();
});
test('failed loading retains unread indication and offers a readable error',async()=>{
  const page=await fixture({error:true});await page.getByRole('link',{name:'v1.0.6'}).click();
  await page.getByRole('status').filter({hasText:'could not be loaded'}).waitFor();
  assert.equal(await page.locator('.release-unread-dot').isVisible(),true);await page.close();
});
test('dark mobile panel stays inside viewport and supports Escape',async()=>{
  const page=await fixture({mobile:true,dark:true});await page.getByRole('link',{name:'v1.0.6'}).click();
  await page.getByRole('heading',{name:'Release 6',exact:true}).waitFor();
  const bounds=await page.locator('.release-panel').boundingBox();assert.ok(bounds.x>=0&&bounds.width<=390);
  assert.equal(await page.locator('.release-panel').evaluate(el=>getComputedStyle(el).color),'rgb(232, 239, 248)');
  await page.keyboard.press('Escape');assert.equal(await page.locator('.release-panel').isVisible(),false);await page.close();
});
