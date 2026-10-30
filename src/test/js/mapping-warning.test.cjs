const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
test('Orders inline warning preserves the SKU mapping handler and nests the badge in the same trigger',()=>{
 const code=fs.readFileSync('src/main/resources/static/js/order-design.js','utf8');
 const start=code.indexOf("const note=source.cloneNode(false)");
 const end=code.indexOf('lines.append(note);',start)+'lines.append(note);'.length;
 const source={cloneNode(){return {dataset:{sellerSku:'SKU-1'},onclick:'openOrderMapping(this)',append(child){this.child=child;}};}};
 const children=[];
 vm.runInNewContext(code.slice(start,end),{source,document:{createElement(){return {};}},lines:{append(n){children.push(n);}}});
 assert.equal(children[0].textContent,'Needs mapping');
 assert.equal(children[0].onclick,'openOrderMapping(this)');
 assert.equal(children[0].dataset.sellerSku,'SKU-1');
 assert.equal(children[0].child.textContent,'Unmapped');
});
test('Marketplace inline warning uses its existing per-row mapping target',()=>{
 const html=fs.readFileSync('src/main/resources/templates/marketplace-skus.html','utf8');
 assert.ok(html.includes("mappingKey=${'main-'+rowStat.index}"));
 assert.ok(html.includes("${'family-'+mappingKey+'-'+childStat.index}"));
 assert.match(html, /class="mapping-warning-trigger"[^>]+data-target=\|mapping-dialog-\$\{mappingKey\}\|[^>]+onclick="NextAiMappingWidget.open\(this\)"><span class="mapping-unmapped-badge">Unmapped<\/span><\/button>/);
});
test('Buy Box Lost and Unmapped share positive-count alert styling',()=>{
 const html=fs.readFileSync('src/main/resources/templates/fragments/order-tabs.html','utf8');
 assert.ok(html.includes("tab.key=='NEEDS_MAPPING' or tab.key=='BUY_BOX_LOST') and tab.orders>0?'has-issues '"));
});
