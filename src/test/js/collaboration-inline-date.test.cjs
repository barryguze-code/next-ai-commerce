const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
test('due dates edit inline with masking, cancellation, validation and recoverable save errors',async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
 try{
  const page=await browser.newPage();let fail=false,posted='';
  await page.route('http://dates.test/',r=>r.fulfill({contentType:'text/html',body:'<main></main>'}));
  await page.route('**/app/collaboration/reviews/42/due-date',async r=>{posted=r.request().postData();await r.fulfill({status:fail?500:200,contentType:'application/json',body:JSON.stringify(fail?{}:{id:42,status:'ACTIVE',dueDate:posted.includes('2026-12-15')?'2026-12-15':null,dueAt:null})});});
  await page.goto('http://dates.test/');
  const fragment=fs.readFileSync('src/main/resources/templates/fragments/collaboration-thread.html','utf8');
  const editor=fragment.slice(fragment.indexOf('<div id="collaboration-due-editor"'),fragment.indexOf('</th:block>'));
  await page.setContent(`<body class="app-page"><table><tr data-review-id="42" data-due-at="" data-status="ACTIVE"><td><button data-edit-due data-review-id="42"><span>No due date</span></button><small data-overdue-label hidden>Overdue</small></td></tr></table>${editor}</body>`);
  for(const name of ['collaboration-task-dates','short-date-input'])await page.addStyleTag({path:path.resolve(`src/main/resources/static/css/${name}.css`)});
  for(const name of ['short-date-input','collaboration-task-dates'])await page.addScriptTag({path:path.resolve(`src/main/resources/static/js/${name}.js`)});
  await page.getByRole('button',{name:'No due date'}).click();
  assert.equal(await page.locator('dialog[open]').count(),0);
  assert.equal(await page.locator('td #collaboration-due-editor').count(),1);
  const field=page.getByRole('textbox',{name:'Due date (MM/DD/YY)',exact:true});
  await field.fill('121526');assert.equal(await field.inputValue(),'12/15/26');
  await field.press('Escape');assert.equal(await page.getByRole('button',{name:'No due date'}).isVisible(),true);
  await page.getByRole('button',{name:'No due date'}).click();
  await field.fill('023126');await page.getByRole('button',{name:'Save due date'}).click();assert.equal(posted,'');
  await field.fill('121526');fail=true;await page.getByRole('button',{name:'Save due date'}).click();await page.getByRole('alert').waitFor();assert.equal(await field.isVisible(),true);
  fail=false;await page.getByRole('button',{name:'Save due date'}).click();await page.getByRole('button',{name:'12/15/26'}).waitFor();
  await page.getByRole('button',{name:'12/15/26'}).click();await field.fill('');await page.getByRole('button',{name:'Save due date'}).click();await page.getByRole('button',{name:'No due date'}).waitFor();
 }finally{await browser.close();}
});
