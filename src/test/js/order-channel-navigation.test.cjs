const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/order-smart-filters.js','utf8');
const handler=source.slice(source.indexOf("document.addEventListener('click',e=>{const a="),source.indexOf('let scheduled=false;'));
function navigate(href){let listener;const context={document:{addEventListener:(name,fn)=>listener=fn},URL,location:{href:'https://example.test/app/orders?f_channel=FBM&f_sku=ABC',origin:'https://example.test'},domain:'/app/orders',current:new URLSearchParams('f_channel=FBM&f_sku=ABC')};vm.runInNewContext(handler,context);const link={href,textContent:'FBA'};listener({target:{closest:()=>link}});return new URL(link.href);}
test('explicit FBA selection overrides persisted FBM while retaining other filters',()=>{const url=navigate('https://example.test/app/orders?f_channel=FBA');assert.equal(url.searchParams.get('f_channel'),'FBA');assert.equal(url.searchParams.get('f_sku'),'ABC');});
test('explicit All clears the channel instead of restoring FBM',()=>{assert.equal(navigate('https://example.test/app/orders?f_channel=').searchParams.get('f_channel'),'');});
test('ordinary status navigation preserves the current channel',()=>{assert.equal(navigate('https://example.test/app/orders?status=SHIPPED').searchParams.get('f_channel'),'FBM');});
