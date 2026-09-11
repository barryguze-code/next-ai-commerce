const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/table-preferences.js','utf8');
const preference={schema:2,order:['product','quantity'],visibility:{product:true,quantity:false}};
function fixture(cookies={},stored={},blocked=false){
  const jar=new Map(Object.entries(cookies)),storage=new Map(Object.entries(stored)),writes=[],events={};
  const document={hidden:false,addEventListener(name,handler){events[name]=handler}};
  Object.defineProperty(document,'cookie',{
    get(){return [...jar].map(([name,value])=>name+'='+value).join('; ')},
    set(value){writes.push(value);const [name]=value.split('=');if(value.includes('Max-Age=0'))jar.delete(name);else throw Error('Preference cookie creation is forbidden');}
  });
  const window={addEventListener(name,handler){events[name]=handler},localStorage:{
    getItem(key){if(blocked)throw Error('Blocked');return storage.get(key)??null},
    setItem(key,value){if(blocked)throw Error('Quota exceeded');storage.set(key,value)}
  }};
  const context=vm.createContext({window,document});vm.runInContext(source,context);
  return {api:window.NextAiTablePreferences,jar,storage,writes,events,context};
}
const plain=value=>JSON.parse(JSON.stringify(value));
test('migrates table choices and preserves login, CSRF, theme and unrelated cookies',()=>{
  const f=fixture({'nextai-table-orders':encodeURIComponent(JSON.stringify(preference)),JSESSIONID:'session-sentinel','XSRF-TOKEN':'csrf-sentinel',theme:'dark',other:'value'});
  assert.deepEqual(plain(f.api.read('orders')),preference);
  assert.equal(f.jar.has('nextai-table-orders'),false);
  assert.deepEqual(Object.fromEntries(f.jar),{JSESSIONID:'session-sentinel','XSRF-TOKEN':'csrf-sentinel',theme:'dark',other:'value'});
  assert.ok(f.storage.has('nextai.table.preferences.orders'));
  assert.equal(f.writes.length,1);
  assert.match(f.writes[0],/^nextai-table-orders=; Path=\/; Max-Age=0;/);
});
test('existing browser-local choices win over an older cookie',()=>{
  const newer={...preference,visibility:{product:true,quantity:true}};
  const f=fixture({'nextai-table-orders':encodeURIComponent(JSON.stringify(preference))},{'nextai.table.preferences.orders':JSON.stringify(newer)});
  assert.deepEqual(plain(f.api.read('orders')),newer);
  assert.equal(f.jar.size,0);
});
test('corrupt cookies are removed without breaking the remaining migration',()=>{
  const f=fixture({'nextai-table-bad':'%broken','nextai-table-invalid':encodeURIComponent('[]'),'nextai-table-good':encodeURIComponent(JSON.stringify(preference))});
  assert.equal(f.jar.size,0);
  assert.equal(f.api.read('bad'),null);
  assert.deepEqual(plain(f.api.read('good')),preference);
});
test('blocked storage falls back to memory, never to request cookies',()=>{
  const f=fixture({'nextai-table-orders':encodeURIComponent(JSON.stringify(preference))},{},true);
  assert.deepEqual(plain(f.api.read('orders')),preference);
  f.api.write('inventory',preference);
  assert.deepEqual(plain(f.api.read('inventory')),preference);
  assert.equal(f.jar.size,0);
  assert.equal(f.writes.length,1);
});
test('many table preference writes do not increase request cookies',()=>{
  const f=fixture({JSESSIONID:'session-sentinel'});
  for(let i=0;i<100;i++)f.api.write('table-'+i,preference);
  assert.equal(f.storage.size,100);
  assert.deepEqual(Object.fromEntries(f.jar),{JSESSIONID:'session-sentinel'});
  assert.equal(f.writes.length,0);
});
test('initialization is idempotent and cleans cookies from older tabs on return',()=>{
  const f=fixture();const api=f.api;
  vm.runInContext(source,f.context);assert.equal(f.api,api);
  f.jar.set('nextai-table-later',encodeURIComponent(JSON.stringify(preference)));
  f.events.pageshow();
  assert.deepEqual(plain(f.api.read('later')),preference);
  assert.equal(f.jar.size,0);
});
test('local preferences survive a fresh page context',()=>{
  const first=fixture();first.api.write('orders',preference);
  const next=fixture({},Object.fromEntries(first.storage));
  assert.deepEqual(plain(next.api.read('orders')),preference);
});
