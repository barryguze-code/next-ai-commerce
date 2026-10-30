const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(process.env.RECEIVING_WORK_SCRIPT||'src/main/resources/static/js/receiving-work.js','utf8');
const formatSource=source.slice(source.indexOf('const number='),source.indexOf('const doc='));
function context(extra={}){
 const ctx=vm.createContext(extra);
 vm.runInContext(formatSource+'\nglobalThis.formatQuantity=quantity;',ctx);
 return ctx;
}
test('case equivalents preserve total each, show loose units, and never guess missing packs',()=>{
 const {formatQuantity:q}=context();
 for(const [units,pack,expected] of [
  [378,18,'21 cases · 378 each'],[342,18,'19 cases · 342 each'],
  [324,18,'18 cases · 324 each'],[360,18,'20 cases · 360 each'],[720,18,'40 cases · 720 each'],
  [0,18,'0 cases · 0 each'],[18,18,'1 case · 18 each'],[20,18,'1 case + 2 loose · 20 each'],
  [2,18,'0 cases + 2 loose · 2 each'],[2.5,2,'1 case + 0.5 loose · 2.5 each'],
  [18,undefined,'18 each'],[18,0,'18 each'],[18,-1,'18 each'],[18,'bad','18 each']
 ])assert.equal(q(units,{unitsPerCase:pack}),expected);
});
function cell(){return {dataset:{},classList:{toggle(){}},removeAttribute(){},textContent:'',innerHTML:''};}
test('initial and refreshed rows retain each-based sorting and closed/exception states',()=>{
 const cells=Object.fromEntries(['expected','received','remaining','expiration','condition'].map(k=>[k,cell()]));
 const row={dataset:{},querySelector(selector){return cells[selector.match(/"(.*?)"/)[1]];}};
 const ctx=context({detail:()=>({catalogPack:99,shortage:2,batches:'[]'}),shelf:()=>'',date:()=>'',conditionNames:{SHORT_SHIPPED:'Short shipped'}});
 vm.runInContext(source.slice(source.indexOf('function receiptQuantities('),source.indexOf('const time='))+'\nglobalThis.render=renderLine;',ctx);
 const line={id:'line',unitsPerCase:18,expected:360,received:342,remaining:18};
 ctx.render(row,line);
 assert.equal(cells.expected.textContent,'20 cases · 360 each');
 assert.equal(cells.received.textContent,'◔ 19 cases · 342 each');
 assert.match(cells.remaining.innerHTML,/1 case · 18 each/);
 assert.match(cells.remaining.innerHTML,/2 short shipped/);
 assert.equal(cells.received.dataset.sortValue,'342');
 assert.match(cells.received.title,/In progress/);
 ctx.render(row,{...line,received:360,remaining:0,closed:true});
 assert.equal(cells.remaining.innerHTML,'Closed<small>2 short shipped</small>');
 assert.equal(cells.received.textContent,'✓ 20 cases · 360 each');
});
test('receipt history displays case equivalents without changing quantities or writing data',async()=>{
 const cards=[],l={id:'line',documentId:'doc',product:'Product',unitsPerCase:18,closed:true};
 const receipt={id:'r',quantity:378,onHand:360,reserved:18,disposition:'SELLABLE',location:'P-9',undone:false};
 let requests=0;
 const ctx=context({line:()=>l,doc:()=>({}),label:()=>'',open(){},viewSequence:0,
  fetch:async(url,opts)=>{requests++;assert.equal(url,'/app/receiving/work/lines/line/receipts');assert.equal(opts.method,undefined);return {ok:true,json:async()=>[receipt]};},
  body:{replaceChildren(){},append(card){cards.push(card);}},document:{createElement:()=>({dataset:{}})},
  conditionNames:{SELLABLE:'Received'},escape:String,time:()=>'',date:()=>'',canEdit:false,message(text){throw Error(text);}});
 vm.runInContext(source.slice(source.indexOf('async function review('),source.indexOf('function undoReceipt('))+'\nglobalThis.reviewHistory=review;',ctx);
 await ctx.reviewHistory('line');
 assert.equal(requests,1);assert.equal(cards.length,1);
 assert.match(cards[0].innerHTML,/21 cases · 378 each · Received/);
 assert.match(cards[0].innerHTML,/20 cases · 360 each on hand · 1 case · 18 each reserved/);
 assert.match(cards[0].title,/18 each per invoice case/);
 assert.equal(receipt.quantity,378);
});
test('Received excludes exceptions while Remaining keeps resolved balance and explains every outcome',()=>{
 let facts;
 const ctx=context({detail:()=>facts,shelf:()=>'',date:()=>'',conditionNames:{SELLABLE:'Received',DAMAGED:'Damaged',MISPICKED:'Mispick',SHORT_SHIPPED:'Short shipped',EXPIRED:'Expired',SOON_EXPIRED:'Short shelf life',OVER_SHIPPED:'Overage'}});
 vm.runInContext(source.slice(source.indexOf('function receiptQuantities('),source.indexOf('const time='))+'\nglobalThis.render=renderLine;globalThis.breakdown=receiptQuantities;globalThis.explanations=receiptExceptions;',ctx);
 for(const [batches,received,shortage,expectedSellable,notes] of [
  [[['SELLABLE',9],['MISPICKED',3]],12,0,9,['3 mispick received']],
  [[['SELLABLE',9],['DAMAGED',3]],12,0,9,['3 damaged received']],
  [[['SHORT_SHIPPED',24]],0,24,0,['24 short shipped']],
  [[['SELLABLE',6],['DAMAGED',2],['MISPICKED',3],['SHORT_SHIPPED',1]],11,1,6,['2 damaged received','3 mispick received','1 short shipped']],
  [[['SELLABLE',6],['EXPIRED',2],['SOON_EXPIRED',3],['OVER_SHIPPED',1]],12,0,10,['2 expired received']],
  // Undone entries are absent from the server batch list; they must not be subtracted again.
  [[['SELLABLE',9]],9,0,9,[]],
  [[],12,0,12,[]]
 ]){
  facts={shortage,batches:JSON.stringify(batches.map(([condition,quantity])=>({condition,quantity})))};
  const cells=Object.fromEntries(['expected','received','remaining','expiration','condition'].map(k=>[k,cell()]));
  const row={dataset:{},querySelector(selector){return cells[selector.match(/"(.*?)"/)[1]];}};
  const l={id:'line',unitsPerCase:1,expected:12,received,remaining:0};
  ctx.render(row,l);
  assert.equal(cells.received.textContent,`✓ ${expectedSellable} cases · ${expectedSellable} each`);
  assert.equal(cells.received.dataset.sortValue,String(expectedSellable));
  assert.match(cells.remaining.innerHTML,/^0 cases · 0 each/);
  for(const note of notes)assert.ok(cells.remaining.innerHTML.includes(note),note);
  if(notes.includes('24 short shipped'))assert.equal(cells.remaining.innerHTML.match(/24 short shipped/g).length,1);
  assert.equal(l.received,received);assert.equal(l.remaining,0);
  const saved=ctx.breakdown(l,facts);
  // The same formatter also handles the unsaved table preview with both saved and entered exceptions.
  assert.match(ctx.explanations({...saved.totals,DAMAGED:(saved.totals.DAMAGED||0)+1},saved.shortage),/damaged received/);
 }
});

test('receiving date labels expose anomalous centuries without changing valid dates',()=>{
 const ctx=vm.createContext({});
 vm.runInContext(source.slice(source.indexOf('const date='),source.indexOf('const shelf='))+'\nglobalThis.receiptDate=date;',ctx);
 assert.equal(ctx.receiptDate('0027-07-14'),'07/14/0027');
 assert.equal(ctx.receiptDate('2027-07-14'),'07/14/27');
 assert.equal(ctx.receiptDate(null),'No expiration');
});
