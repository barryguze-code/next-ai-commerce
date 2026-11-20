const test=require('node:test'),assert=require('node:assert/strict');
const p=require('../../main/resources/static/js/fba-replenishment-state.js');
test('case equivalents preserve mapped product each and exact whole SKU units',()=>{
 const parts=[{item:'tea',each:2,masterCase:5}];assert.deepEqual(p.caseQuantity(parts,3),{cases:1,each:1,totalEach:6,masterCase:5,multiplier:2});
 assert.equal(p.skuQuantity(parts,1,1),3);assert.throws(()=>p.skuQuantity(parts,1,0),/whole SKU/);
 assert.equal(p.skuQuantity(parts,2,0),5);assert.throws(()=>p.skuQuantity(parts,-1,0));assert.throws(()=>p.skuQuantity(parts,1,0.5));
});
test('unknown cases and mixed components never invent a combined case size',()=>{
 assert.equal(p.caseQuantity([{item:'a',each:1,masterCase:null}],7),null);
 assert.equal(p.caseQuantity([{item:'a',each:1,masterCase:4},{item:'b',each:1,masterCase:6}],7),null);
 assert.equal(p.caseQuantity([{item:'a',each:1,masterCase:1.5}],7),null);
 assert.equal(p.caseQuantity([{item:'a',each:1,masterCase:4}],null),null);
});
test('single-case packing suggestions keep every canonical SKU unit and actual confirmation false',()=>{
 const c=[{sku:'tea',item:'a',quantity:1,masterCase:4}],profiles=[{item:'a',length:12,width:8,height:6,dimensionUnit:'in',netWeight:2,weightUnit:'lb',expirationRequired:false}];
 const cartons=p.cartonSuggestions([{warehouse:'w',seller_sku:'tea',account_catalog_item_id:'a',each_per_sku:1,quantity:10,dispatched:0,reservation_status:'ACTIVE'}],c,profiles,{dimensionUnit:'in',weightUnit:'lb'});
 assert.deepEqual(cartons.map(x=>x.contents.tea),[4,4,2]);assert.ok(cartons.every(x=>x.actualConfirmed===false));assert.equal(cartons[2].length,12);assert.equal(cartons[2].weight,null,'generic catalogue weight has no verified case basis');
});
test('mixed mappings and multiple origins remain in packing suggestions without dropped SKU units',()=>{
 const components=[{sku:'mix',item:'a',quantity:1,masterCase:4},{sku:'mix',item:'b',quantity:2,masterCase:6}];
 const allocations=['west','east'].flatMap(warehouse=>components.map(c=>({warehouse_id:warehouse,seller_sku:'mix',account_catalog_item_id:c.item,each_per_sku:c.quantity,quantity:3*c.quantity,dispatched:0,reservation_status:'ACTIVE'})));
 const cartons=p.cartonSuggestions(allocations,components,[],{dimensionUnit:'cm',weightUnit:'kg'});assert.deepEqual(cartons.map(c=>[c.warehouse,c.contents.mix]),[['west',3],['east',3]]);assert.ok(cartons.every(c=>c.length===null&&c.weight===null&&!c.actualConfirmed));
});
