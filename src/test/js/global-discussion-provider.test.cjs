const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
const transport=source.match(/^  const api=.*$/m)[0],members=source.match(/^  async function members\(\).*$/m)[0];
test('global discussion requests always use the platform realm; existing account providers remain private',()=>{
 const context={state:{type:'GLOBAL_PRODUCT'}};vm.createContext(context);vm.runInContext(transport+'\nthis.route=api;',context);
 for(const path of ['/subject?subjectType=GLOBAL_PRODUCT','/summaries?entityType=GLOBAL_PRODUCT','/reviews/000/reply','/attachments/000'])assert.equal(context.route(path),'/app/platform/catalog/discussions'+path);
 context.state={type:'CATALOG'};assert.equal(context.route('/attachments/000'),'/app/collaboration/attachments/000');assert.equal(context.route('/summaries','GLOBAL_PRODUCT'),'/app/platform/catalog/discussions/summaries');
});
test('platform member choices cannot reuse a cached private-account member list',async()=>{
 const calls=[],context={state:{type:'CATALOG'},Map,fetch:async url=>{calls.push(url);return {ok:true,json:async()=>[url.includes('/platform/')?'Platform administrator':'Private teammate']};}};vm.createContext(context);vm.runInContext('const memberRequests=new Map();\n'+transport+'\n'+members+'\nthis.members=members;',context);
 assert.equal((await context.members())[0],'Private teammate');context.state={type:'GLOBAL_PRODUCT'};assert.equal((await context.members())[0],'Platform administrator');context.state={type:'CATALOG'};assert.equal((await context.members())[0],'Private teammate');assert.equal(calls.length,2);
});
