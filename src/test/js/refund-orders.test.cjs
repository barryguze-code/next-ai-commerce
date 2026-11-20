const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
test('refund drawer scopes pagination, renders safe text and returns focus',async()=>{
 const calls=[],parts={},listeners={};let dialog,focused=false;
 const make=tag=>({tag,children:[],setAttribute(){},append(...x){this.children.push(...x)},replaceChildren(...x){this.children=x},querySelector(s){return parts[s]??=(make('part'))},addEventListener(k,fn){listeners[k]=fn},showModal(){this.open=true},close(){this.open=false;listeners.close()},getBoundingClientRect(){return {left:0,right:760,top:0,bottom:800}}});
 const window={};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/refund-orders.js','utf8'),{window,document:{createElement:make,body:{append(n){dialog=n}}},URLSearchParams,AbortController,Intl,Date,fetch:async(url)=>{calls.push(url);return {ok:true,json:async()=>({asOf:'2026-10-06T12:00:00Z',hasMore:true,rows:[{orderId:'123',sellerSku:'SKU',title:'<script>bad</script>',imageUrl:'javascript:bad',postedDate:'2026-10-05',currency:'USD',amount:10,units:null}]})}}});
 window.NextAiRefundOrders({sku:'SKU',week:2},{focus(){focused=true}});await new Promise(setImmediate);
 assert.match(calls[0],/sku=SKU&days=28&week=2&page=0/);
 const card=parts['.refund-order-list'].children[0];assert.equal(card.children[0].src,'/images/channels/amazon-seller.png');assert.equal(card.children[1].children[0].textContent,'<script>bad</script>');assert.equal(card.children[2].children[1].textContent,'Refunded units pending');
 parts['[data-next]'].onclick();await new Promise(setImmediate);assert.match(calls[1],/asOf=2026-10-06T12%3A00%3A00Z&page=1/);
 dialog.close();assert.equal(focused,true);
 window.NextAiRefundOrders({days:30});await new Promise(setImmediate);assert.match(calls[2],/sku=&days=30&week=-1&page=0/);
});
test('product drawer uses product scope across stores and labels shared bundle evidence',async()=>{
 const {JSDOM}=require('jsdom'),dom=new JSDOM('',{runScripts:'outside-only',url:'http://localhost'}),w=dom.window,calls=[];
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true};w.HTMLDialogElement.prototype.close=function(){this.open=false};
 w.fetch=async url=>{calls.push(url);return {ok:true,json:async()=>({asOf:'2026-11-17T12:00:00Z',hasMore:true,rows:[{orderId:'123',sellerSku:'BUNDLE-6',storeName:'Amazon US',title:'Shared product bundle',postedDate:'2026-11-16',currency:'CAD',amount:12,units:1,sharedBundle:true,orderUrl:'https://sellercentral.amazon.com/orders-v3/order/123'}]})}};
 w.eval(fs.readFileSync('src/main/resources/static/js/refund-orders.js','utf8'));
 w.NextAiRefundOrders({product:'product-id',productName:'Cheese',account:'Food account',week:1});await new Promise(setImmediate);
 assert.match(calls[0],/^\/app\/orders\/product-refund-history\?product=product-id&days=28&week=1&page=0$/);assert.doesNotMatch(calls[0],/sku=/);
 assert.match(w.document.querySelector('[data-context]').textContent,/Cheese · All mapped Amazon SKUs · Food account/);
 assert.match(w.document.querySelector('.refund-order-list').textContent,/Amazon US/);assert.match(w.document.querySelector('.refund-order-list').textContent,/Shared bundle refund · full SKU amount; not allocated/);
 assert.equal(w.document.querySelector('article a').href,'https://sellercentral.amazon.com/orders-v3/order/123');
 w.document.querySelector('[data-next]').click();await new Promise(setImmediate);assert.match(calls[1],/product=product-id/);assert.match(calls[1],/asOf=2026-11-17T12%3A00%3A00Z&page=1/);w.close();
});
