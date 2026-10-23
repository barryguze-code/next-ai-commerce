const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const resources=path.resolve('src/main/resources');
const template=fs.readFileSync(path.join(resources,'templates/marketplace-skus.html'),'utf8');
const main=template.slice(template.indexOf('<main '),template.indexOf('</main>')+7);
let browser;
before(async()=>{browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})})});
after(async()=>{await browser?.close()});

// Use the page's actual markup and styles, with synthetic data in place of its
// Thymeleaf model. Server rendering is separately covered by TemplateRenderingTest.
async function fixture(width=1600,theme='light'){
  const page=await browser.newPage({viewport:{width,height:1000}});
  await page.route('http://sku.test/**',route=>{
    const url=new URL(route.request().url());
    if(url.pathname.startsWith('/css/')||url.pathname.startsWith('/js/')||url.pathname.startsWith('/images/')){
      const file=path.join(resources,'static',url.pathname);
      return fs.existsSync(file)?route.fulfill({path:file}):route.fulfill({status:404,body:''});
    }
    return route.fulfill({contentType:'text/html',body:'<!doctype html><html data-theme="'+theme+'"><body class="app-page"><aside class="sidebar">Navigation</aside>'+main+'</body></html>'});
  });
  await page.goto('http://sku.test/app/marketplace-skus?q=bundle&status=ALL&sort=status&direction=asc');
  await page.evaluate(()=>{
    document.querySelectorAll('.empty-state').forEach(el=>el.remove());
    const table=document.querySelector('.sku-table'),row=table.querySelector('.sku-approved-title').closest('tr');
    [...table.tBodies[0].rows].filter(candidate=>candidate!==row).forEach(candidate=>candidate.remove());
    row.dataset.title='Long listing title with a complete multi-component bundle that must remain readable';
    row.dataset.sku='BUNDLE-1';
    row.querySelector('.sku-approved-title').textContent=row.dataset.title;
    row.querySelector('.sku-copy-value.sku-code').textContent='BUNDLE-1';
    row.querySelector('.sku-mapping-values').innerHTML='<span class="sku-identifier-line">COMPONENT-A × 1</span><span class="sku-identifier-line">COMPONENT-B × 2</span>';
    row.querySelector('.collaboration-row-button').dataset.entityId='BUNDLE-1';
    row.querySelector('[data-label="Available"]').textContent='62';
    row.querySelector('[data-label="Profit"]').innerHTML='<button class="profit-open"><span>0.00</span><small>Est. / SKU</small></button>';
    document.querySelector('.sku-summary strong').textContent='25.0%';
    const pager=document.querySelector('.table-pagination');
    Object.assign(pager.dataset,{page:'0',pageMax:'1',pageSize:'25'});
    // Permission-hidden controls must stay hidden despite presentation rules.
    row.querySelector('.sku-open-actions').hidden=true;
    window.originalMapping=row.querySelector('.sku-mapping-icon');
  });
  for(const css of ['app','brand','access','table-widget','marketplace-skus','marketplace-sku-design','mapping-widget','platform-ui','fba','marketplace-sku-orders'])
    await page.addStyleTag({path:path.join(resources,'static/css',css+'.css')});
  for(const js of ['table-preferences','platform-icons','table-widget','table-data-tools','platform-ui'])
    await page.addScriptTag({path:path.join(resources,'static/js',js+'.js')});
  await page.locator('.sku-table.table-widget').waitFor();
  if(width>760)await page.waitForFunction(()=>getComputedStyle(document.querySelector('.sku-table tbody tr')).backgroundColor===getComputedStyle(document.querySelector('.sku-summary')).backgroundColor);
  return page;
}

for(const width of [1600,1440,1024])for(const theme of ['light','dark'])test('Precision composition remains contained at '+width+'px '+theme,async()=>{
  const page=await fixture(width,theme);
  try{
    const result=await page.evaluate(()=>{
      const rect=selector=>document.querySelector(selector).getBoundingClientRect();
      const style=selector=>getComputedStyle(document.querySelector(selector));
      const wrap=rect('.sku-table-wrap');
      return {pageWidth:document.documentElement.scrollWidth,viewport:innerWidth,
        tableWidth:rect('.sku-table').width,wrapRight:wrap.right,
        overviewBackground:style('.sku-summary').backgroundColor,
        firstMetricBackground:style('.sku-summary article').backgroundColor,
        rowBackground:style('.sku-table tbody tr').backgroundColor,
        toolbars:document.querySelectorAll('.table-standard-toolbar').length,
        searchCount:document.querySelectorAll('input[name=q][type=search]').length,
        toolbarY:rect('.table-standard-toolbar').y,tabsY:rect('.sku-filter-bar').y,
        toolbarPrecedesTabs:!!(document.querySelector('.table-standard-toolbar').compareDocumentPosition(document.querySelector('.sku-filter-bar'))&Node.DOCUMENT_POSITION_FOLLOWING),
        titleWhiteSpace:style('.sku-approved-title').whiteSpace,
        titleHeight:rect('.sku-approved-title').height,
        hiddenAction:document.querySelector('.sku-open-actions').hidden,
        sameMapping:window.originalMapping===document.querySelector('.sku-mapping-icon')};
    });
    assert.equal(result.pageWidth,result.viewport,'table must not widen the page');
    assert.ok(result.tableWidth>=1240,'desktop readability boundary');
    assert.ok(result.wrapRight<=width,'scroll wrapper stays inside workspace');
    assert.notEqual(result.overviewBackground,'rgba(0, 0, 0, 0)');
    assert.equal(result.firstMetricBackground,'rgba(0, 0, 0, 0)','metrics share one surface');
    assert.equal(result.rowBackground,result.overviewBackground,'row surfaces remain neutral in both themes');
    assert.equal(result.toolbars,1);assert.equal(result.searchCount,1);
    assert.ok(result.toolbarY<result.tabsY,'single toolbar precedes status navigation');
    assert.equal(result.toolbarPrecedesTabs,true,'reading order agrees with visual order');
    assert.equal(result.titleWhiteSpace,'normal');assert.ok(result.titleHeight>20);
    assert.equal(result.hiddenAction,true);assert.equal(result.sameMapping,true);
    assert.equal(await page.locator('.sku-mapping-values').textContent(),'COMPONENT-A × 1COMPONENT-B × 2');
  }finally{await page.close()}
});

test('preferences, canonical values and existing controls survive refresh',async()=>{
  const page=await fixture();
  try{
    const before=await page.evaluate(()=>{
      const root=document.querySelector('.sku-table'),row=root.tBodies[0].rows[0];
      const cols=[...root.tHead.rows[0].cells].map((el,index)=>({id:el.dataset.column,title:el.textContent.trim(),index}));
      const selected=cols.filter(c=>!root.tHead.rows[0].cells[c.index].hidden);
      return {values:window.NextAiTableDataTools.exportValues(root,row,window.NextAiTableDataTools.exportColumns(root,selected)),
        keys:cols.map(c=>c.id),layout:root.dataset.layoutVersion,
        mapping:row.querySelector('.sku-mapping-icon').getAttribute('onclick')};
    });
    assert.ok(before.keys.includes('price-shipping'));
    assert.equal(before.layout,'orders-aligned-20261005');
    assert.equal(before.mapping,'NextAiMappingWidget.open(this)');
    await page.evaluate(()=>{window.NextAiTableWidget.refresh();window.NextAiTableDataTools.refresh()});
    assert.equal(await page.locator('.table-standard-toolbar').count(),1);
    const after=await page.evaluate(()=>{
      const root=document.querySelector('.sku-table'),row=root.tBodies[0].rows[0];
      const selected=[...root.tHead.rows[0].cells].map((el,index)=>({id:el.dataset.column,title:el.textContent.trim(),index})).filter(c=>!root.tHead.rows[0].cells[c.index].hidden);
      return window.NextAiTableDataTools.exportValues(root,row,window.NextAiTableDataTools.exportColumns(root,selected));
    });
    assert.deepEqual(after,before.values);
    assert.ok(after.some(v=>v.includes('62')),'stock remains an exported value');
    assert.ok(after.some(v=>v.includes('0.00')),'zero profit remains distinct from missing');
    await page.locator('.table-column-control>summary').click();
    await page.getByRole('button',{name:'Toggle Shipping template',exact:true}).click();
    assert.equal(await page.locator('th[data-column=shipping-template]').isVisible(),true);
    assert.ok(await page.evaluate(()=>JSON.parse(localStorage.getItem('nextai.table.preferences.marketplace-skus')).visibility['shipping-template']));
  }finally{await page.close()}
});

test('Phase 1 retains the existing phone geometry instead of implementing new cards',async()=>{
  const page=await fixture(390);
  try{
    assert.equal(await page.locator('.sku-overview-caption').isVisible(),false);
    assert.equal(await page.locator('.sku-table thead').isVisible(),false);
    assert.equal(await page.locator('.sku-table').evaluate(el=>getComputedStyle(el).minWidth),'960px');
    assert.equal(await page.locator('input[name=q][type=search]').isVisible(),true);
  }finally{await page.close()}
});
