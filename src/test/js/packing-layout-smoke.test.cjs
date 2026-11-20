const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),{execFileSync}=require('node:child_process'),{chromium}=require('playwright');
test('compact packing layout smoke keeps exact printed label and controls',async()=>{
 const html=fs.readFileSync('src/main/resources/templates/packing-slip.html','utf8');
 const previous=execFileSync('git',['show','HEAD:src/main/resources/templates/packing-slip.html'],{encoding:'utf8'});
 const labels=s=>s.slice(s.indexOf('  <section class="packing-labels"'),s.indexOf('  </section>\n  </section>',s.indexOf('  <section class="packing-labels"'))+25);
 assert.equal(labels(html),labels(previous));assert.ok(!html.includes('packing-steps'));assert.ok(!html.includes('Save a description and default location'));assert.ok(!html.includes('Choose the package, save the decision'));
 const browser=await chromium.launch({headless:true});try{const page=await browser.newPage({viewport:{width:1440,height:900}});await page.setContent(html);await page.addStyleTag({path:'src/main/resources/static/css/packing-slip.css'});
 const product=page.locator('.packing-product-editor'),preview=page.locator('.packing-labels'),controls=page.locator('[aria-label="Packing-slip settings"]');const a=await product.boundingBox(),b=await preview.boundingBox(),c=await controls.boundingBox();assert.ok(a.x<b.x);assert.ok(c.y>=b.y+b.height);
 for(const id of ['save-package','print-slip','rename-package','packing-complete','veeqo-shortcut'])assert.equal(await page.locator('#'+id).count(),1);
 await page.setViewportSize({width:390,height:844});const m=await product.boundingBox(),n=await controls.boundingBox();assert.ok(n.y>m.y);assert.ok(n.width<=390);
 await page.emulateMedia({media:'print'});assert.equal(await controls.isVisible(),false);assert.equal(await product.isVisible(),false);const label=await page.locator('.packing-slip').boundingBox();assert.equal(label.width,192);assert.equal(label.height,96);
 }finally{await browser.close();}
});
