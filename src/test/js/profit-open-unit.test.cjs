const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('FBA cost editor opens when unrelated FBM rate service is unavailable',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
 const button={dataset:{profitKey:'A'},textContent:'Missing costs'},view={fba:true,lines:[]};let click,opened=false;
 const context=vm.createContext({document:{addEventListener:(name,fn)=>click=fn},views:new Map([[button,view]]),request:0,base:'/profit',URLSearchParams,
  json:async url=>{if(url.endsWith('/rates'))throw Error('FBM rate service unavailable');return {enabled:false};},loadPackageChoices:async()=>[],loadDefaults:async()=>[],showPricePublishing(){},render(){},showLoading(){},dialog:{showModal(){opened=true}},field:()=>null});
 vm.runInContext(source.slice(source.indexOf(" document.addEventListener('click',async"),source.indexOf(" field('[data-profit-close]')")),context);
 await click({target:{closest:()=>button}});
 assert.equal(opened,true,'FBA editor must not depend on FBM rates');
 assert.equal(button.textContent,'Missing costs');
});
test('Shopify opens the shared read-only calculator without Amazon defaults, publishing support or extra requests',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');const button={dataset:{profitKey:'shopify-order'},textContent:'Missing costs'},view={kind:'SHOPIFY_ORDER',lines:[]};let click,opened=false;
 const context=vm.createContext({document:{addEventListener:(name,fn)=>click=fn},views:new Map([[button,view]]),request:0,base:'/profit',URLSearchParams,
 json:async()=>{throw Error('Shopify must not request Amazon settings')},loadDefaults:async()=>{throw Error('Must not load Amazon defaults')},showPricePublishing(){},render(){},showLoading(){},dialog:{showModal(){opened=true}},field:()=>null});
 vm.runInContext(source.slice(source.indexOf(" document.addEventListener('click',async"),source.indexOf(" field('[data-profit-close]')")),context);await click({target:{closest:()=>button}});assert.equal(opened,true);
});
test('Pending calculator opens loading state before its first data request completes',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');const button={dataset:{profitKey:'pending'},textContent:'Pending'};let click,opened=false,finish;
 const context=vm.createContext({document:{addEventListener:(name,fn)=>click=fn},views:new Map(),request:0,base:'/profit',URLSearchParams,
  hydrate:()=>new Promise(resolve=>finish=resolve),showLoading(){opened=true},showPricePublishing(){},render(){},dialog:{showModal(){opened=true}},field:()=>null});
 vm.runInContext(source.slice(source.indexOf(" document.addEventListener('click',async"),source.indexOf(" field('[data-profit-close]')")),context);
 click({target:{closest:()=>button}});
 assert.equal(opened,true,'Loading dialog must open before waiting for profit data');
});
test('cost details render without waiting for optional Amazon publishing support',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8'),button={dataset:{profitKey:'sku'}},view={kind:'SKU',fba:false,date:'2026-11-16',lines:[]};let click,rendered=false,release;
 const support=new Promise(resolve=>release=resolve),context=vm.createContext({document:{addEventListener:(n,fn)=>click=fn},views:new Map([[button,view]]),request:0,base:'/profit',URLSearchParams,
 json:async url=>url.includes('/price-support')?support:[],loadPackageChoices:async()=>[],loadDefaults:async()=>[],showPricePublishing(){},render(){rendered=true;},showLoading(){},dialog:{showModal(){}},field:()=>null});
 vm.runInContext(source.slice(source.indexOf(" document.addEventListener('click',async"),source.indexOf(" field('[data-profit-close]')")),context);
 const opening=click({target:{closest:()=>button}});await new Promise(resolve=>setImmediate(resolve));const beforeOptional=rendered;release({enabled:false});await opening;assert.equal(beforeOptional,true,'Publishing availability must not hold back SKU cost details');
});
