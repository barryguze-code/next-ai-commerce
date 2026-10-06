const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),vm=require('node:vm');
const script=fs.readFileSync('src/main/resources/static/js/sku-refund-history.js','utf8');
function render(entries){
 const make=()=>({dataset:{},style:{},children:[],classList:{add(){}},append(...children){this.children.push(...children)},setAttribute(){}});
 const cell=make();cell.dataset.skuRefunds=JSON.stringify(entries);
 vm.runInNewContext(script,{document:{body:{},querySelectorAll:()=>[cell],createElement:make},MutationObserver:class{observe(){}},Intl,Date,queueMicrotask});return cell;
}
test('unknown units are not invented from refund money',()=>{
 const cell=render([{week:2,currency:'USD',amount:43.97,units:null,unknownUnits:1,unknownAmounts:0}]);
 assert.equal(cell.children[1].textContent,'↩ -$43.97');assert.equal(cell.children[0].children.length,4);
});
test('confirmed units and currencies remain distinct',()=>{
 const cell=render([{week:0,currency:'USD',amount:10,units:1,unknownUnits:0},{week:1,currency:'CAD',amount:20,units:2,unknownUnits:0}]);
 assert.match(cell.children[1].title,/Refunded units: 3/);assert.match(cell.children[1].textContent,/CA\$/);
 assert.equal(cell.children[0].children[0].style.height,'4px');
});
test('no imported records does not falsely assert zero refunds',()=>{const cell=render([]);assert.match(cell.title,/unavailable/);assert.equal(cell.children.length,0)});
test('unrecognized amount is explicitly pending',()=>{const cell=render([{week:0,currency:null,amount:null,units:null,unknownUnits:1,unknownAmounts:1}]);assert.match(cell.children[1].textContent,/Amount pending/)});
test('uses distinct period order total rather than summing weekly counts',()=>{
 const cell=render([{week:0,currency:'USD',amount:300,orders:4,totalOrders:5,unknownOrders:0},{week:1,currency:'USD',amount:151.88,orders:3,totalOrders:5,unknownOrders:0}]);
 assert.equal(cell.children[1].textContent,'↩ -$451.88 · 5 orders');
 assert.match(cell.children[0].children[0].title,/4 orders/);
});
test('missing order identifiers are not reported as a complete count',()=>{
 const cell=render([{week:0,currency:'USD',amount:10,orders:1,totalOrders:1,unknownOrders:1}]);
 assert.match(cell.children[1].textContent,/1 known order/);
});
