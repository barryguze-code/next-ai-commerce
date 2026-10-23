const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('FBA cost editor opens when unrelated FBM rate service is unavailable',async()=>{
 const source=fs.readFileSync('src/main/resources/static/js/profit.js','utf8');
 const button={dataset:{profitKey:'A'},textContent:'Missing costs'},view={fba:true,lines:[]};let click,opened=false;
 const context=vm.createContext({document:{addEventListener:(name,fn)=>click=fn},views:new Map([[button,view]]),request:0,base:'/profit',URLSearchParams,
  json:async url=>{if(url.endsWith('/rates'))throw Error('FBM rate service unavailable');return {enabled:false};},loadDefaults:async()=>[],render(){},dialog:{showModal(){opened=true}},field:()=>null});
 vm.runInContext(source.slice(source.indexOf(" document.addEventListener('click',async"),source.indexOf(" field('[data-profit-close]')")),context);
 await click({target:{closest:()=>button}});
 assert.equal(opened,true,'FBA editor must not depend on FBM rates');
 assert.equal(button.textContent,'Missing costs');
});
