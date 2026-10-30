const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {chromium}=require('playwright');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});});
after(async()=>{await browser?.close();});
const script=n=>'src/main/resources/static/js/'+n;
async function fixture(html){const page=await browser.newPage({viewport:{width:900,height:700}});await page.route('**/*',r=>r.request().url()==='http://fixture.test/'?r.fulfill({contentType:'text/html',body:html}):r.abort());await page.goto('http://fixture.test/');await page.addStyleTag({path:'src/main/resources/static/css/table-widget.css'});return page;}
test('shared tab arrows preserve order, reveal selection, resize and dynamic refresh',async()=>{
 const names=Array.from({length:12},(_,i)=>'Synthetic tab '+i); // Test-only overflow, never product tabs.
 const page=await fixture('<div style="width:360px" id="host"><nav class="order-filters" data-table-tabs style="gap:12px">'+names.map((n,i)=>`<a href="#${i}" style="padding:12px" ${i===11?'aria-current="page"':''}>${n}</a>`).join('')+'</nav></div>');
 try{
  await page.addScriptTag({path:script('table-widget.js')});
  const prev=page.getByRole('button',{name:'Show previous table tabs'}),next=page.getByRole('button',{name:'Show next table tabs'});
  await prev.waitFor({state:'visible'});assert.equal(await next.isVisible(),false);
  assert.deepEqual(await page.locator('.order-filters>a').allTextContents(),names);
  await prev.click();await next.waitFor({state:'visible'});assert.equal(await prev.isVisible(),true);
  await page.locator('.order-filters>a').first().focus();await prev.waitFor({state:'hidden'});
  await next.focus();await next.press('Enter');assert.equal(await next.isVisible(),true);assert.equal(await next.evaluate(e=>e===document.activeElement),true);
  await page.evaluate(()=>{document.querySelector('#host').style.width='2200px';});await next.waitFor({state:'hidden'});assert.equal(await prev.isVisible(),false);
  await page.evaluate(()=>{document.querySelector('#host').style.width='360px';document.querySelector('[aria-current]').removeAttribute('aria-current');document.querySelector('.order-filters>a').firstElementChild;document.querySelector('.order-filters>a').setAttribute('aria-current','page');window.NextAiTableWidget.refresh();});await next.waitFor({state:'visible'});
  assert.equal(await page.locator('.table-tab-overflow').count(),1);assert.equal(await prev.isVisible(),false);
  await page.evaluate(()=>{const tabs=document.querySelector('.order-filters');tabs.scrollLeft=10;tabs.dispatchEvent(new Event('scroll'));tabs.querySelector('[aria-current]').removeAttribute('aria-current');const last=tabs.lastElementChild;last.setAttribute('aria-current','page');window.NextAiTableWidget.refresh();});
  await page.waitForFunction(()=>{const tabs=document.querySelector('.order-filters'),a=tabs.lastElementChild.getBoundingClientRect(),b=tabs.getBoundingClientRect();return a.left>=b.left-1&&a.right<=b.right+1;});
  assert.equal(await next.isVisible(),false);
 }finally{await page.close();}
});
for(const server of [false,true])test('catalogue shared toolbar, column preferences and '+(server?'preserved database-owned':'full collection')+' sorting/search',async()=>{
 const page=await fixture('<body class="platform-standard-tables"><section class="data-card"><div class="card-toolbar"><input type="search" aria-label="Catalogue search"></div><div class="table-wrap"><table data-table-widget="catalogue-test" data-table-client-sort><thead><tr><th data-column-required="true">Product</th><th>Size</th></tr></thead><tbody><tr><td>Zulu</td><td>12 oz</td></tr><tr><td>Alpha</td><td>6 oz</td></tr></tbody></table></div>'+(server?'<div class="table-pagination" data-page="0" data-page-max="1" data-page-size="25"><span>Showing 2</span></div>':'')+'</section></body>');
 try{
  for(const n of ['table-preferences.js','table-widget.js','table-data-tools.js'])await page.addScriptTag({path:script(n)});
  assert.equal(await page.getByRole('button',{name:'Download table as CSV'}).count(),1);assert.equal(await page.locator('.table-column-control').count(),1);assert.equal(await page.locator('.table-filter-control').count(),1);
  if(!server){await page.getByRole('button',{name:'Sort Product',exact:true}).click();assert.equal(await page.locator('tbody tr').first().locator('[data-column=product]').textContent(),'Alpha');assert.equal(await page.locator('th.sortable-column[aria-sort=ascending]').count(),1);}else{assert.equal(await page.locator('.table-sort-trigger').count(),0);assert.equal(await page.locator('tbody tr').first().locator('[data-column=product]').textContent(),'Zulu');}
  if(!server){await page.getByRole('searchbox',{name:'Catalogue search'}).fill('Zulu');await page.waitForFunction(()=>document.querySelector('tbody tr').classList.contains('table-data-hidden'));assert.equal(await page.locator('tbody tr:not(.table-data-hidden) [data-column=product]').textContent(),'Zulu');}
  await page.locator('.table-column-control>summary').click();await page.getByRole('button',{name:'Toggle Size',exact:true}).click();assert.equal(await page.locator('thead th[data-column="size"]').isVisible(),false);
 }finally{await page.close();}
});
test('Marketplace titles keep native Ledger links and leave other detail dialogs intact',async()=>{
 const page=await fixture('<table><tbody><tr><td><a class="sku-approved-title" href="/app/inventory/ledger?itemId=synthetic">Mapped product</a><strong class="sku-approved-title">Unmapped product</strong></td></tr><tr><td><div class="product-cell"><div><strong>Other product</strong></div></div></td></tr></tbody></table>');
 try{await page.addScriptTag({path:script('platform-ui.js')});assert.equal(await page.locator('.sku-approved-title[data-ui-product]').count(),0);assert.equal(await page.getByRole('button',{name:'Copy product name',exact:true}).count(),3);assert.equal(await page.getByRole('link',{name:'Mapped product'}).getAttribute('href'),'/app/inventory/ledger?itemId=synthetic');await page.getByRole('button',{name:'View product details: Other product'}).click();assert.equal(await page.locator('#ui-product-dialog').isVisible(),true);}finally{await page.close();}
});
test('Marketplace semantic order and source Ledger scope remain explicit',()=>{
 const html=fs.readFileSync('src/main/resources/templates/marketplace-skus.html','utf8');const nav=html.slice(html.indexOf('<nav class="sku-filter-bar"'),html.indexOf('</nav>',html.indexOf('<nav class="sku-filter-bar"')));
 assert.ok(nav.indexOf('>All <')<nav.indexOf('sku-alert-tab'));assert.ok(nav.indexOf('summary.deleted')<nav.indexOf('sku-alert-tab'));assert.ok(nav.indexOf('data-fee-variance-tab')>nav.indexOf('summary.inactive'));assert.ok(nav.indexOf('data-fee-variance-tab')<nav.indexOf('summary.neverSold'));
 assert.match(nav,/class="sku-alert-tab fee-variance-tab"/);assert.match(html,/\/app\/inventory\/ledger\(itemId=\$\{components\[0\]\.itemId\}/);assert.match(html,/Inventory Ledgers:/);assert.match(html,/mapping-unmapped-badge">Unmapped/);
});
test('UI hotfix backup waiver skips only the new dump; default backup behavior remains',()=>{
 const os=require('node:os'),path=require('node:path'),{spawnSync}=require('node:child_process');
 const source=fs.readFileSync('scripts/production-release.sh','utf8');
 const block=source.slice(source.indexOf('# BEGIN VERIFIED BACKUP REUSE'),source.indexOf('echo "Application-deployment recovery'));
 for(const waiver of [false,true]){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nextai-hotfix-'));fs.writeFileSync(path.join(dir,'previous.jar'),'retained');
  const p=spawnSync('bash',['-c',`set -eu\nbackup_dir="$1";ui_hotfix="$2";version=1.8.1\nsudo(){ printf 'custom-dump'; };pg_restore(){ test -s "$backup_dir/database.dump"; };sha256sum(){ :; };${block}`,'fixture',dir,String(waiver)],{encoding:'utf8'});
  assert.equal(p.status,0,p.stderr);assert.equal(fs.existsSync(path.join(dir,'database.dump')),!waiver);assert.equal(fs.existsSync(path.join(dir,'database-backup-waiver.txt')),waiver);assert.equal(fs.readFileSync(path.join(dir,'previous.jar'),'utf8'),'retained');
  fs.rmSync(dir,{recursive:true,force:true});
 }
 assert.match(source,/version" == "1\.8\.1" && "\$\{4:-\}" == "OWNER_APPROVED_UI_HOTFIX_NO_NEW_BACKUP"/);
});

test('patch metadata reuses feature notes without allowing an undocumented minor',()=>{
 const os=require('node:os'),path=require('node:path'),{spawnSync}=require('node:child_process');
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nextai-release-notes-'));
 try{
  fs.mkdirSync(path.join(dir,'src/main/resources/releases'),{recursive:true});
  fs.writeFileSync(path.join(dir,'src/main/resources/releases/history.json'),JSON.stringify([{version:'1.8.0',title:'Feature release',sections:[{title:'Changes',items:['Existing notes']}]}]));
  for(const [version,pass]of [['1.8.1',true],['1.9.0',false],['1.7.9',false]]){
   fs.writeFileSync(path.join(dir,'pom.xml'),'<project><version>'+version+'</version></project>');
   const p=spawnSync(process.execPath,[path.resolve('scripts/check-release-history.cjs')],{cwd:dir,encoding:'utf8'});assert.equal(p.status===0,pass);
  }
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
