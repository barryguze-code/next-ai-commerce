const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
const start=source.indexOf('  function decorateRecordConversationButton(');
const end=source.indexOf('  async function refreshRecordSummaries()',start);
const decorate=vm.runInNewContext('('+source.slice(start,end).trim()+')',{
 window:{NextAiIcons:{source:name=>name}},document:{createElement:()=>({className:'',setAttribute(){}})}
});
test('one thread containing five messages displays five, not one',()=>{
 const children=[],image={getAttribute(){},setAttribute(){}};
 const button={dataset:{activeCount:'1',originCount:'1',directMessageCount:'5',unreadMessageCount:'1'},classList:{toggle(){},remove(){}},hasAttribute:()=>false,querySelector:s=>s.startsWith('img')?image:null,append:n=>children.push(n),setAttribute(){}};
 decorate(button);assert.equal(children.find(n=>n.className==='collaboration-direct-count')?.textContent,'5');assert.equal(button.dataset.readState,'unread');
});
test('background tabs periodically refresh unread summaries without marking messages read',()=>{
 const timerLine=source.split('\n').find(line=>line.includes('setInterval(')&&line.includes('refreshRecordSummaries'));
 let tick,calls=0;
 vm.runInNewContext(timerLine,{setInterval:fn=>tick=fn,document:{hidden:true,hasFocus:()=>false},refreshRecordSummaries:()=>calls++});
 tick();assert.equal(calls,1);
});
for(const [label,data,asset] of [
 ['empty',{},'collaboration-no-message-gray'],
 ['direct read',{activeCount:'1',originCount:'1'},'collaboration-blue'],
 ['inherited informative',{activeCount:'1',originCount:'0',relatedCount:'1'},'collaboration-no-message-gray'],
 ['assigned',{activeCount:'1',mineCount:'1'},'collaboration-assigned-red'],
 ['urgent unread mention',{activeCount:'1',urgentUnreadMentionCount:'2'},'collaboration-assigned-red'],
 ['ordinary unread',{activeCount:'1',originCount:'1',unreadCount:'2'},'collaboration-blue']
])test(label+' conversation badge uses the shared status palette',()=>{
 const image={getAttribute(){},setAttribute(key,value){this[key]=value;}};
 const button={dataset:data,classList:{toggle(){},remove(){}},hasAttribute:()=>false,
  querySelector:selector=>selector.startsWith('img')?image:null,append(){},setAttribute(){}};
 decorate(button);assert.equal(image.src,asset);
});
test('indirect conversations have a separate neutral counter',()=>{
 const children=[];
 const image={getAttribute(){},setAttribute(){}};
 const button={dataset:{activeCount:'3',originCount:'1',relatedCount:'2'},classList:{toggle(){},remove(){}},hasAttribute:()=>false,
  querySelector:s=>s.startsWith('img')?image:null,append:node=>children.push(node),setAttribute(){}};
 decorate(button);
 assert.equal(children.find(n=>n.className==='collaboration-indirect-count')?.textContent,'2');
});
for(const [count,label] of [[0,null],[12,'12'],[123,'123'],[999,'999'],[1000,'999+']])test('counter fits '+count+' and uses persisted unread state',()=>{
 const children=[],image={getAttribute(){},setAttribute(){}};
 const button={dataset:{activeCount:String(count),originCount:String(count),unreadMessageCount:'1'},classList:{toggle(){},remove(){}},hasAttribute:()=>false,querySelector:s=>s.startsWith('img')?image:null,append:n=>children.push(n),setAttribute(){}};
 decorate(button);assert.equal(children.find(n=>n.className==='collaboration-direct-count')?.textContent??null,label);assert.equal(button.dataset.readState,'unread');
 button.dataset.unreadMessageCount='0';decorate(button);assert.equal(button.dataset.readState,'read');
});
