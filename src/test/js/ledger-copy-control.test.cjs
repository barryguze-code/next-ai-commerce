const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{chromium}=require('playwright');
test('shared ledger ASIN copy uses the platform icon without Orders page styles',async()=>{
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1440,height:900}});
 const template=fs.readFileSync('src/main/resources/templates/fragments/inventory-history-panel.html','utf8'),panel=template.slice(template.indexOf('<dialog id="inventory-history-dialog"'),template.indexOf('</dialog>')+9);
 await page.setContent(`<body class="app-page">${panel}</body>`);
 for(const file of ['app.css','brand.css','platform-ui.css','inventory-history-shared.css','inventory-ledger-preview.css'])await page.addStyleTag({path:'src/main/resources/static/css/'+file});
 await page.evaluate(()=>{window.openInventoryHistory=()=>{};document.querySelector('dialog').classList.add('inventory-ledger-preview');document.querySelector('dialog').showModal();});
 await page.addScriptTag({path:'src/main/resources/static/js/inventory-ledger-preview.js'});
 await page.evaluate(()=>NextAiLedgerDesignPreview.renderRelated(document.querySelector('#related-skus'),[{sku:'Exact-9xEA',asin:'B0FRSXTLSR',channel:'AMAZON',itemId:'own-item',itemCode:'377466',quantity:9,price:43.97,currency:'USD',warehouseAvailable:8}]));
 const copy=page.getByRole('button',{name:'Copy B0FRSXTLSR',exact:true});
 for(const theme of ['light','dark']){await page.evaluate(t=>document.documentElement.dataset.theme=t,theme);
 const style=await copy.evaluate(e=>{const s=getComputedStyle(e),svg=getComputedStyle(e.querySelector('svg'));const link=e.previousElementSibling.getBoundingClientRect(),r=e.getBoundingClientRect();return{background:s.backgroundColor,border:s.borderTopWidth,width:r.width,height:r.height,iconWidth:svg.width,iconHeight:svg.height,fill:svg.fill,stroke:svg.stroke,gap:r.left-link.right};});
 assert.equal(style.background,'rgba(0, 0, 0, 0)');assert.equal(style.border,'0px');assert.equal(style.width,22);assert.equal(style.height,24);assert.equal(style.iconWidth,'14px');assert.equal(style.iconHeight,'14px');assert.equal(style.fill,'none');assert.notEqual(style.stroke,'none');assert.ok(style.gap>=0&&style.gap<=8,'copy stays next to ASIN');
 await copy.focus();assert.equal(await copy.evaluate(e=>getComputedStyle(e).outlineStyle),'solid');}
 assert.equal(await copy.getAttribute('data-order-copy'),'B0FRSXTLSR');assert.match(await page.getByRole('link',{name:'B0FRSXTLSR',exact:true}).getAttribute('href'),/amazon.com\/dp\/B0FRSXTLSR/);
 }finally{await browser.close();}
});
