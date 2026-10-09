const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
test('batched refresh carries new message totals and unread status to every matching order row',async()=>{
 const nodes=Array.from({length:50},()=>({dataset:{entityType:'ORDER',entityId:'order-1'},classList:{toggle(){},remove(){}},hasAttribute:()=>false,querySelector:s=>s.startsWith('img')?{getAttribute(){},setAttribute(){}}:null,append(){},setAttribute(){}}));
 let response={activeCount:1,originCount:1,directMessageCount:5,unreadMessageCount:1},calls=0,release;
 const fetch=async()=>{calls++;await new Promise(r=>release=r);return {ok:true,json:async()=>({'order-1':response})};};
 const code=source.slice(source.indexOf('  function decorateRecordConversationButton('),source.indexOf('  async function markRecordConversationActive('));
 const refresh=vm.runInNewContext('let summariesRefreshing=false;'+code+';refreshRecordSummaries',{$$:()=>nodes,window:{NextAiIcons:{source:s=>s}},document:{createElement:()=>({setAttribute(){}})},navigator:{onLine:true},URLSearchParams,AbortSignal,fetch});
 const pending=refresh();await refresh();assert.equal(calls,1);release();await pending;
 assert.ok(nodes.every(n=>n.dataset.directMessageCount==='5'&&n.dataset.readState==='unread'));
 response={...response,unreadMessageCount:0};const read=refresh();release();await read;assert.ok(nodes.every(n=>n.dataset.readState==='read'));
 response={...response,directMessageCount:6,unreadMessageCount:1};const newer=refresh();release();await newer;assert.ok(nodes.every(n=>n.dataset.directMessageCount==='6'&&n.dataset.readState==='unread'));
 delete response.unreadMessageCount;const stale=refresh();release();await stale;assert.ok(nodes.every(n=>n.dataset.readState==='unknown'));
});
