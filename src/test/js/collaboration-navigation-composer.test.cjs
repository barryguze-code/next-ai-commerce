const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/collaboration.js','utf8');
test('origin navigation uses the selected thread identity, not the order being viewed',()=>{
 const anchor={dataset:{},setAttribute(){}};
 const start=source.indexOf('  function sourceTableUrl('),end=source.indexOf('  window.openRecordCollaboration=',start);
 const navigate=vm.runInNewContext(source.slice(start,end)+';setParentLink',{
  URL,location:{origin:'http://localhost:8080'},state:{key:'ORDER-123',label:'Order'},$:()=>anchor
 });
 navigate('/app/marketplace-skus','MARKETPLACE_SKU','SKU-ABC','Product');
 assert.equal(anchor.href,'/app/marketplace-skus?q=SKU-ABC');
 navigate('https://untrusted.example/path','MARKETPLACE_SKU','SKU-ABC','Product');
 assert.equal(anchor.hidden,true);
 assert.match(source,/setParentLink\(conversation\.review\.parentUrl,conversation\.review\.subjectType,conversation\.review\.subjectKey,conversation\.review\.subjectLabel\)/);
});
test('Enter sends once; Shift+Enter, IME and mention selection do not submit',()=>{
 const start=source.indexOf('  function wireEnterToSend('),end=source.indexOf('  function wireAttachments(',start);
 const wire=vm.runInNewContext('('+source.slice(start,end).trim()+')',{$:()=>({})});
 let keydown,sends=0;
 const field={dataset:{},addEventListener:(type,fn)=>keydown=fn};wire(field,{requestSubmit:()=>sends++});
 for(const props of [{shiftKey:true},{isComposing:true},{defaultPrevented:true}])keydown({key:'Enter',preventDefault(){},...props});
 assert.equal(sends,0);keydown({key:'Enter',preventDefault(){}});assert.equal(sends,1);
});
