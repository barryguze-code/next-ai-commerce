const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const context={window:{},document:{readyState:'loading',addEventListener(){}}};
vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8'),context);
const cell=context.window.NextAiTableDataTools.csvCell;
test('CSV escapes commas and quotes',()=>assert.equal(cell('Salt, "fine"'),'"Salt, ""fine"""'));
test('CSV guards spreadsheet formulas',()=>{for(const prefix of ['=','+','-','@'])assert.equal(cell(prefix+'A1'),'"\''+prefix+'A1"')});
test('CSV handles missing values and whitespace',()=>{assert.equal(cell(null),'""');assert.equal(cell('  hello\nworld  '),'"hello world"');assert.equal(cell('USD 12.50'),'"USD 12.50"')});
test('Shopify Orders use atomic native fields without Amazon identifiers or guessed fees',()=>{
 const tools=context.window.NextAiTableDataTools,root={dataset:{tableWidget:'orders',provider:'SHOPIFY'}};
 const columns=tools.exportColumns(root,[{id:'product',title:'Product'},{id:'order',title:'Order'},{id:'quantity',title:'Qty'},{id:'sales',title:'Sales'},{id:'profit',title:'Profit'},{id:'four-week-sales',title:'Sales history'}]);
 const row={dataset:{exportProduct:'Pizza',exportSku:'000001-EAx3',exportMapping:'000001 × 3',exportOrder:'#1001',exportDate:'2026-10-27T12:00:00Z',exportPayment:'PENDING',exportStatus:'UNFULFILLED',exportCancelled:'false',exportReadiness:'Reserved',exportQuantity:'3',exportOpen:'3',exportSales:'30.00',exportCurrency:'USD'},querySelector(selector){return selector==='.item-weekly-sales'?{dataset:{exportWeeks:'1 | 2 | 3 | 4'}}:null}};
 const names=Array.from(columns,c=>c.title),values=Array.from(tools.exportValues(root,row,columns));
 assert.equal(names.includes('ASIN'),false);assert.equal(names.includes('Amazon status'),false);assert.equal(names.includes('Buy Box'),false);assert.equal(names.includes('Referral fee'),false);
 assert.equal(values[names.indexOf('SKU')],'000001-EAx3');assert.equal(values[names.indexOf('Payment status')],'PENDING');assert.equal(values[names.indexOf('Estimated profit')],'');assert.equal(values[names.indexOf('Calculation state')],'Fees unavailable');assert.equal(values[names.indexOf('Week 4 current units')],'4');
});
test('CSV excludes record controls and action menus',()=>{
 const cols=context.window.NextAiTableDataTools.exportColumns({dataset:{tableWidget:'orders'}},[{id:'column-0',title:'Record'},{id:'quantity',title:'Quantity'},{id:'action',title:'Actions'}]);
 assert.deepEqual(Array.from(cols,c=>c.title),['Quantity']);
});
test('Orders export compound cells as individually named fields',()=>{
 const tools=context.window.NextAiTableDataTools,root={dataset:{tableWidget:'orders'}};
 const columns=tools.exportColumns(root,[{id:'product',title:'Product'},{id:'order',title:'Order'},{id:'sales',title:'Sales'}]);
 const row={dataset:{exportProduct:'Yogurt, Honey',exportSku:'SKU-1',exportAsin:'B0123',exportMapping:'63792 × 1',exportOrder:'114-123',exportDate:'2026-10-08T12:00:00Z',exportStatus:'Unshipped',exportChannel:'FBM',exportReadiness:'READY_TO_SHIP',exportSales:'24.97',exportCurrency:'USD',exportShipping:'0',exportBuybox:'24.97',exportBuyboxCurrency:'USD'},querySelector(){return null}};
 const values=tools.exportValues(root,row,columns);
 assert.equal(columns.length,14);assert.equal(values.length,14);
 assert.equal(values[0],'Yogurt, Honey');assert.equal(values[4],'114-123');assert.equal(values[9],'24.97');
 assert.equal(columns[5].title,'Order date');assert.equal(columns[12].title,'Buy Box');
 assert.equal(values.map(cell).join(',').includes('"Yogurt, Honey","SKU-1"'),true);
});
