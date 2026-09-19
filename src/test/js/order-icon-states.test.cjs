const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
function element(tag){return {tag,dataset:{},style:{},children:[],attrs:{},classList:{toggle(){},remove(){}},setAttribute(k,v){this.attrs[k]=v},getAttribute(k){return this.attrs[k]},prepend(n){n.parent=this;this.children.unshift(n)},append(n){n.parent=this;this.children.push(n)},remove(){this.parent.children=this.parent.children.filter(n=>n!==this)},querySelector(selector){return this.children.find(n=>selector==='svg'?n.tag==='svg':selector==='b'?n.tag==='b':n.tag==='img'&&'collaborationIcon' in n.dataset)}};}
const decorate=vm.runInNewContext('('+source.slice(source.indexOf('function decorateRecordConversationButton'),source.indexOf('  async function refreshRecordSummaries'))+')',{document:{createElement:element}});
test('collaboration PNG state follows origin, assignment, unread and related counts',()=>{
  for(const [counts,file,badge] of [
    [{activeCount:'0'},'collaboration-no-message-gray',undefined],
    [{activeCount:'2',originCount:'2'},'collaboration-blue','2'],
    [{activeCount:'2',originCount:'2',unreadCount:'1'},'collaboration-unread-blue','2'],
    [{activeCount:'2',originCount:'2',mineCount:'1'},'collaboration-assigned-red','2'],
    [{activeCount:'3',originCount:'0',relatedCount:'3'},'collaboration-no-message-gray','3']
  ]){const button=element('button');button.dataset=counts;button.append(element('svg'));decorate(button);decorate(button);assert.equal(button.querySelector('svg'),undefined);assert.equal(button.children.filter(n=>n.tag==='img').length,1);assert.equal(button.querySelector('img').attrs.src,'/images/platform/'+file+'.png');assert.equal(button.querySelector('b')?.textContent,badge);}
});
test('closing the last conversation removes its count and colored state',()=>{
  const button=element('button');button.dataset={activeCount:'1',originCount:'1'};decorate(button);
  button.dataset={activeCount:'0',originCount:'0',closedCount:'1'};decorate(button);
  assert.equal(button.querySelector('b'),undefined);assert.equal(button.dataset.contextTone,'empty');
});
test('boot decorates every button and observes inserted rows',()=>{
  assert.ok(source.includes("$$('.collaboration-row-button').forEach(decorateRecordConversationButton)"));
  assert.ok(source.includes("node.querySelectorAll('.collaboration-row-button')"));
});
