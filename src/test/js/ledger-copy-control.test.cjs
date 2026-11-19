const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
test('shared ledger ASIN text copies without duplicate glyphs or Orders page styles',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1440,height:900}});
 const template=fs.readFileSync('src/main/resources/templates/fragments/inventory-history-panel.html','utf8'),panel=template.slice(template.indexOf('<dialog id="inventory-history-dialog"'),template.indexOf('</dialog>')+9);
 await page.setContent(`<body class="app-page">${panel}</body>`);
 for(const file of ['app.css','brand.css','platform-ui.css','inventory-history-shared.css','inventory-ledger-preview.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+file});
 await page.evaluate(()=>{window.openInventoryHistory=()=>{};document.querySelector('dialog').classList.add('inventory-ledger-preview');document.querySelector('dialog').showModal();});
 await page.addScriptTag({path:'src/main/resources/static/js/inventory-ledger-preview.js'});
 await page.evaluate(()=>NextAiLedgerDesignPreview.renderRelated(document.querySelector('#related-skus'),[{sku:'Exact-9xEA',asin:'B0FRSXTLSR',channel:'AMAZON',itemId:'own-item',itemCode:'377466',quantity:9,price:43.97,currency:'USD',warehouseAvailable:8}]));
 await page.addScriptTag({content:"window.copied=[];Object.defineProperty(navigator,'clipboard',{value:{writeText:async v=>window.copied.push(v)}});"});await page.addScriptTag({path:'src/main/resources/static/js/platform-ui.js'});
 const copy=page.getByRole('button',{name:'Copy ASIN B0FRSXTLSR',exact:true});
 for(const theme of ['light','dark']){await page.evaluate(t=>document.documentElement.dataset.theme=t,theme);await copy.click();assert.equal(await copy.getAttribute('data-ui-copy'),'B0FRSXTLSR');assert.equal(await page.locator('.order-identifier-copy').count(),0);const style=await copy.evaluate(e=>({background:getComputedStyle(e).backgroundColor,border:getComputedStyle(e).borderTopWidth}));assert.equal(style.background,'rgba(0, 0, 0, 0)');assert.equal(style.border,'0px');}
 assert.deepEqual(await page.evaluate(()=>window.copied),['B0FRSXTLSR','B0FRSXTLSR']);assert.match(await page.getByRole('link',{name:'Open B0FRSXTLSR on Amazon',exact:true}).getAttribute('href'),/amazon.com\/dp\/B0FRSXTLSR/);
 }finally{await browser.close();}
});
