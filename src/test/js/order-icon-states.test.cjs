const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
function element(tag){return {tag,dataset:{},style:{},children:[],attrs:{},classList:{toggle(){},remove(){}},setAttribute(k,v){this.attrs[k]=v},getAttribute(k){return this.attrs[k]},hasAttribute(k){return Object.hasOwn(this.attrs,k)},prepend(n){n.parent=this;this.children.unshift(n)},append(n){n.parent=this;this.children.push(n)},remove(){this.parent.children=this.parent.children.filter(n=>n!==this)},querySelector(selector){return this.children.find(n=>selector.startsWith('.')?n.className===selector.slice(1):selector==='img[data-collaboration-icon]'?n.tag==='img'&&'collaborationIcon' in n.dataset:n.tag===selector)}};}
const decorate=vm.runInNewContext('('+source.slice(source.indexOf('function decorateRecordConversationButton'),source.indexOf('  async function refreshRecordSummaries'))+')',{window:{},document:{createElement:element}});
test('collaboration PNG state follows origin, assignment, unread and related counts',()=>{
  for(const [counts,file,badge] of [
    [{activeCount:'0'},'collaboration-no-message-gray',undefined],
    [{activeCount:'2',originCount:'2'},'collaboration-blue','2'],
    [{activeCount:'2',originCount:'2',unreadMessageCount:'1'},'collaboration-blue','2'],
    [{activeCount:'2',originCount:'2',mineCount:'1'},'collaboration-assigned-red','2'],
    [{activeCount:'3',originCount:'0',relatedCount:'3'},'collaboration-no-message-gray',undefined]
  ]){const button=element('button');button.dataset=counts;button.append(element('svg'));decorate(button);decorate(button);assert.equal(button.querySelector('svg'),undefined);assert.equal(button.children.filter(n=>n.tag==='img').length,1);assert.equal(button.querySelector('img').attrs.src,'/images/platform/table/'+file+'.png');assert.equal(button.querySelector('b')?.textContent,badge);}
});
test('repeated decoration keeps one indirect counter and persisted unread state',()=>{
  const button=element('button');button.dataset={activeCount:'3',originCount:'0',relatedCount:'3',unreadMessageCount:'1'};
  decorate(button);decorate(button);
  assert.equal(button.children.filter(n=>n.className==='collaboration-indirect-count').length,1);
  assert.equal(button.querySelector('.collaboration-indirect-count').textContent,'3');
  assert.equal(button.dataset.readState,'unread');
  button.dataset.unreadMessageCount='0';decorate(button);assert.equal(button.dataset.readState,'read');
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
