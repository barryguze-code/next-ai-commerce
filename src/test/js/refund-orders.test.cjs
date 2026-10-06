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
