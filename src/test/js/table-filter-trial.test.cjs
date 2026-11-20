const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {JSDOM}=require('jsdom');
const source=fs.readFileSync('src/main/resources/static/js/order-smart-filters.js','utf8');
function toolbar(sku,query){
 const widget=sku?'marketplace-skus':'orders',dom=new JSDOM(`<section class="${sku?'sku':'order'}-card table-filter-trial"><div class="sku-view-filters"></div><div data-table-widget="${widget}"><details class="table-filter-control"></details><button class="table-clear-active-filters">Clear</button></div></section>`);
 let result;const document=dom.window.document,current=new URLSearchParams(query),control={document,current,isSku:sku,widget,presets:[],loaded:true,loading:false,renderTags(){},load(){},navigate:v=>result=v,el:(tag,cls,text)=>{const e=document.createElement(tag);e.className=cls||'';e.textContent=text||'';return e},relevant:k=>k.startsWith('f_')||['q','status','smartSort'].includes(k)};
 const start=source.indexOf('function init(){'),end=source.indexOf(" const heading=root.querySelector('.table-grid-header [data-column=\"four-week-sales\"]');",start);
 vm.runInNewContext(source.slice(start,end)+'}\ninit();',control);
 return {document,reset:()=>{document.querySelector('.table-filter-reset').click();return result}};
}
test('search and status alone do not activate filter reset',()=>{const {document}=toolbar(false,'q=tea&status=SHIPPED');assert.equal(document.querySelector('.table-filter-reset').hidden,true);assert.equal(document.querySelector('.order-smart-filter-button').textContent,'');assert.equal(document.querySelector('.table-clear-active-filters'),null)});
for(const sku of [false,true])test(`${sku?'SKU':'Orders'} reset retains search/tab/sort and clears criteria`,()=>{const {document,reset}=toolbar(sku,'q=tea&status=ACTIVE&smartSort=units_desc&f_channel=FBA&f_condition=NEW&f_sku=ABC');assert.ok(document.querySelector('.has-active-filters'));assert.equal(document.querySelector('.table-filter-reset').hidden,false);assert.deepEqual(JSON.parse(JSON.stringify(reset())),sku?{q:'tea',status:'ACTIVE',smartSort:'units_desc',f_condition:'ALL'}:{q:'tea',status:'ACTIVE',smartSort:'units_desc',f_channel:''});assert.equal(document.querySelectorAll('.table-filter-icon-control').length,1)});
test('default New condition is an active criterion; ALL is neutral',()=>{assert.equal(toolbar(true,'f_condition=NEW').document.querySelector('.table-filter-reset').hidden,false);assert.equal(toolbar(true,'f_condition=ALL').document.querySelector('.table-filter-reset').hidden,true)});
