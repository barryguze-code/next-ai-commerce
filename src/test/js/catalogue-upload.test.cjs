const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
test('catalogue picker accumulates files, drop appends, remove and limits preserve valid selection',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage();await page.setContent(`<form data-catalog-upload><div data-catalog-drop><input type="file" name="file" multiple required><button type="button" data-catalog-add-files>Add files</button><ul data-catalog-files></ul></div><p data-catalog-upload-error hidden></p><button type="submit">Review files</button></form>`);await page.addScriptTag({path:'src/main/resources/static/js/catalogue-upload.js'});
 const file=name=>({name,mimeType:'text/csv',buffer:Buffer.from('SKU,Description\n001,Product')});
 await page.locator('input[type=file]').setInputFiles(file('first.csv'));await page.locator('input[type=file]').setInputFiles(file('second.csv'));
 assert.equal(await page.locator('[data-catalog-files] li').count(),2);assert.equal(await page.locator('[data-catalog-add-files]').textContent(),'Add more files');
 await page.locator('[data-catalog-drop]').evaluate(el=>{const data=new DataTransfer();data.items.add(new File(['SKU,Description\n003,Product'],'third.csv',{type:'text/csv'}));el.dispatchEvent(new DragEvent('drop',{dataTransfer:data,bubbles:true}));});
 assert.equal(await page.locator('[data-catalog-files] li').count(),3);await page.getByRole('button',{name:'Remove second.csv'}).click();assert.equal(await page.locator('input[type=file]').evaluate(e=>e.files.length),2);
 await page.locator('input[type=file]').setInputFiles(file('bad.exe'));assert.match(await page.locator('[data-catalog-upload-error]').textContent(),/CSV, TSV or XLSX/);assert.equal(await page.locator('input[type=file]').evaluate(e=>e.files.length),2);
 const html=fs.readFileSync('src/main/resources/templates/account-catalog.html','utf8');assert.match(html,/catalogue-template-owner.png/);assert.match(html,/aria-label="Open catalogue template in a new tab"/);assert.match(html,/uploadVendors\) == 1/);assert.match(html,/data-command-id="upload-catalogue" data-action-image/);
 }finally{await browser.close();}
});
