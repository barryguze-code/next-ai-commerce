const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve('src/main/resources');
const fragment=fs.readFileSync(path.join(root,'templates/fragments/stock-editor.html'),'utf8').replace(/<script[^>]*>[\s\S]*?<\/script>/g,'');
const options={items:[{id:'item-a',name:'Ginger',itemCode:'381005',expirationRequired:true,defaultLocationId:'main'},{id:'item-b',name:'Turmeric',expirationRequired:false,defaultLocationId:'main'}],locations:[{id:'main',code:'MAIN',name:'Main storage',status:'ACTIVE'}]};
test('shared editor supports zero stock, explicit expiration, component selection and CSRF',async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
  try{
    const page=await browser.newPage();let submitted;
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      if(url.pathname==='/app/inventory/adjustment-options')return route.fulfill({json:options});
      if(url.pathname==='/app/inventory/adjustment-positions')return route.fulfill({json:[]});
      if(url.pathname==='/app/inventory/adjustments/inline'){submitted=new URLSearchParams(route.request().postData());return route.fulfill({json:{message:'Saved'}})}
      return route.fulfill({contentType:'text/html',body:fragment});
    });
    await page.goto('http://stock.test/');
    await page.locator('#stock-editor form').evaluate(form=>{const input=document.createElement('input');input.type='hidden';input.name='_csrf';input.value='fixture-token';form.append(input)});
    await page.addScriptTag({path:path.join(root,'static/js/stock-editor.js')});
    await page.evaluate(()=>openSharedInventoryAdjustment({dataset:{item:'item-a'}}));
    await page.getByRole('button',{name:'Save adjustment',exact:true}).waitFor();
    await page.locator('[name=amount]').fill('6');
    await page.locator('[name=expirationDate]').fill('2027-01-16');
    assert.match(await page.locator('[data-stock-balance]').textContent(),/0 on hand/);
    await page.getByRole('button',{name:'Save adjustment',exact:true}).click();
    await page.getByText('Saved',{exact:true}).waitFor();
    assert.equal(submitted.get('itemId'),'item-a');assert.equal(submitted.get('quantityChange'),'6');
    assert.equal(submitted.get('expirationDate'),'2027-01-16');assert.equal(submitted.get('_csrf'),'fixture-token');
    assert.equal(await page.getByRole('button',{name:'Save adjustment',exact:true}).isDisabled(),true);
  }finally{await browser.close()}
});

test('manual receiving uses catalogue search and requires an explanation',async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
  try{
    const page=await browser.newPage();let submitted;
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      if(url.pathname==='/app/catalog/product-search')return route.fulfill({json:[{id:'item-a',name:'Ginger',vendorItemCode:'381005'}]});
      if(url.pathname==='/app/inventory/adjustment-options')return route.fulfill({json:{...options,items:[options.items[0]]}});
      if(url.pathname==='/app/inventory/adjustment-positions')return route.fulfill({json:[]});
      if(url.pathname==='/app/inventory/receipts/inline'){submitted=new URLSearchParams(route.request().postData());return route.fulfill({json:{message:'Received'}})}
      return route.fulfill({contentType:'text/html',body:fragment});
    });
    await page.goto('http://stock.test/');await page.addScriptTag({path:path.join(root,'static/js/stock-editor.js')});
    await page.evaluate(()=>openSharedInventoryReceipt());await page.locator('[data-stock-search]').fill('381005');
    await page.getByRole('button',{name:'Ginger 381005'}).click();
    await page.locator('[name=amount]').fill('24');await page.locator('[name=expirationDate]').fill('2027-01-16');
    assert.equal(await page.locator('[name=notes]').getAttribute('required'),'');
    await page.locator('[name=notes]').fill('Unexpected delivery');await page.getByRole('button',{name:'Receive item',exact:true}).click();
    await page.getByText('Received',{exact:true}).waitFor();assert.equal(submitted.get('quantity'),'24');assert.equal(submitted.get('notes'),'Unexpected delivery');
  }finally{await browser.close()}
});

test('zero-stock catalogue result opens item movement history from its title',async()=>{
  const browser=await chromium.launch({headless:true,...(process.env.TEST_BROWSER_CHANNEL?{channel:process.env.TEST_BROWSER_CHANNEL}:{})});
  try{
    const page=await browser.newPage();
    await page.route('**/*',async route=>{
      const url=new URL(route.request().url());
      if(url.pathname==='/app/catalog/product-search')return route.fulfill({json:[{id:'item-a',name:'Cheese Farmer',vendorItemCode:'789826'}]});
      return route.fulfill({contentType:'text/html',body:fragment+'<input id="inventory-search"><table class="available-inventory-table"><thead><tr>'+Array(8).fill('<th></th>').join('')+'</tr></thead><tbody></tbody></table>'});
    });
    await page.goto('http://stock.test/');
    await page.addScriptTag({path:path.join(root,'static/js/stock-editor.js')});
    await page.evaluate(()=>{window.openInventoryHistory=row=>window.__openedItem=row.dataset.item});
    await page.addScriptTag({path:path.join(root,'static/js/stock-entry-points.js')});
    await page.locator('#inventory-search').fill('789826');
    const title=page.getByRole('button',{name:'Open inventory details for Cheese Farmer'});
    await title.click();
    assert.equal(await page.evaluate(()=>window.__openedItem),'item-a');
  }finally{await browser.close()}
});
