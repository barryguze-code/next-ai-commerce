const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {JSDOM}=require('jsdom');
test('document removal keeps errors visible and preserves other selected rows',async()=>{
 const dom=new JSDOM('<button data-open-selected>Receive</button><span data-selected-count></span><button data-clear-selected>Clear</button><input id="buy-shipping-csrf" data-header="X-CSRF-TOKEN" value="token"><table><tr><td><input data-source-select type="checkbox" value="one"><button data-delete-document="one" data-document-label="Invoice 1">Delete document</button></td></tr><tr><td><input data-source-select type="checkbox" value="two"></td></tr></table>',{url:'http://localhost',runScripts:'outside-only'});
 const w=dom.window;w.HTMLDialogElement.prototype.showModal=function(){this.open=true};w.HTMLDialogElement.prototype.close=function(){this.open=false};
 let calls=[],fail=true;w.fetch=async(url,options)=>{calls.push({url,options});return {ok:!fail,json:async()=>({message:fail?'Receipt history prevents deletion.':'Removed'})}};
 w.eval(fs.readFileSync('src/main/resources/static/js/receiving-documents.js','utf8'));
 const other=w.document.querySelector('[value="two"]');other.checked=true;other.dispatchEvent(new w.Event('change',{bubbles:true}));
 w.document.querySelector('[data-delete-document]').click();w.document.querySelector('[data-confirm]').click();await new Promise(r=>setImmediate(r));
 assert.match(w.document.querySelector('[role="alert"]').textContent,/Receipt history/);assert.equal(w.document.querySelectorAll('tr').length,2);
 fail=false;w.document.querySelector('[data-confirm]').click();await new Promise(r=>setImmediate(r));
 assert.equal(w.document.querySelectorAll('tr').length,1);assert.equal(other.checked,true);assert.equal(w.document.querySelector('dialog'),null);
 assert.equal(calls[0].url,'/app/receiving/work/remove/one');assert.equal(calls[0].options.headers['X-CSRF-TOKEN'],'token');
 assert.equal(JSON.parse(calls[0].options.body).requestId,JSON.parse(calls[1].options.body).requestId);dom.window.close();
});
