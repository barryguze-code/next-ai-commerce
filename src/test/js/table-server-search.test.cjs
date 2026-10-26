const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/table-data-tools.js','utf8');const start=source.indexOf('function bindServerSearch(input)'),end=source.indexOf('function headers(',start),code=source.slice(start,end)+'\nbindServerSearch(input);';
function fixture(href='http://local.test/app/platform/catalog?q=old&page=4&goToPage=5&size=50&sort=status&direction=desc&columnFilters=keep',saved){
 const events={},forms={},timers=new Map(),navigations=[],stored=new Map();let id=0;
 const location={href,pathname:new URL(href).pathname,assign:u=>navigations.push(new URL(u))};if(saved)stored.set('nextai.table.search-focus.'+location.pathname,JSON.stringify(saved));
 const input={name:'q',dataset:{},value:'old',selectionStart:2,selectionEnd:2,form:{addEventListener:(name,fn)=>forms[name]=fn},addEventListener:(name,fn)=>events[name]=fn,setAttribute:(k,v)=>input[k]=v,focus:()=>input.focused=true,setSelectionRange:(a,b)=>input.range=[a,b]};
 const context={input,location,URL,sessionStorage:{getItem:k=>stored.get(k),removeItem:k=>stored.delete(k),setItem:(k,v)=>stored.set(k,v)},setTimeout:(fn,delay)=>{assert.equal(delay,700);timers.set(++id,fn);return id;},clearTimeout:i=>timers.delete(i)};vm.runInNewContext(code,context);
 return {input,events,forms,navigations,stored,context,flush:()=>{const list=[...timers.values()];timers.clear();list.forEach(fn=>fn());}};
}
test('shared server search debounces Orders-style, resets paging and preserves other URL state',()=>{
 const f=fixture();f.input.value='a';f.events.input();f.flush();assert.equal(f.navigations.length,0);
 f.input.value='cheese';f.events.input();f.input.value='cheddar';f.events.input();f.flush();assert.equal(f.navigations.length,1);const next=f.navigations[0];assert.equal(next.searchParams.get('q'),'cheddar');for(const [k,v]of [['size','50'],['sort','status'],['direction','desc'],['columnFilters','keep']])assert.equal(next.searchParams.get(k),v);assert.equal(next.searchParams.has('page'),false);assert.equal(next.searchParams.has('goToPage'),false);assert.equal(f.input['aria-busy'],'true');
 f.input.value='';f.events.input();f.flush();assert.equal(f.navigations[1].searchParams.get('q'),'');
});
test('composition and manual submit cancel pending searches; matching navigation restores typing focus',()=>{
 const f=fixture();f.events.compositionstart();f.input.value='typing';f.events.input();f.flush();assert.equal(f.navigations.length,0);f.events.compositionend();f.forms.submit();f.flush();assert.equal(f.navigations.length,0);
 const restored=fixture('http://local.test/app/platform/catalog?q=cheddar',{q:'cheddar',start:3,end:6});assert.equal(restored.input.focused,true);assert.deepEqual(restored.input.range,[3,6]);const before=Object.keys(restored.events).length;vm.runInNewContext(code,restored.context);assert.equal(Object.keys(restored.events).length,before);
});
