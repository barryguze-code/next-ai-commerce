const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const context={window:{},URLSearchParams};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../../main/resources/static/js/order-inventory-adjust.js'),'utf8'),context);
test('edited expiration overrides the selected existing batch date',()=>{
  const body=context.orderAdjustmentPayload({itemId:'item',locationId:'main',expirationDate:'2026-09-19'},
    {expirationDate:'2026-10-18',quantityChange:'6',reason:'COUNT_CORRECTION'});
  assert.equal(body.get('expirationDate'),'2026-10-18');
  assert.equal(body.get('quantityChange'),'6');
  assert.equal(body.get('locationId'),'main');
});
test('unchanged date targets that batch and blank date is never replaced by an old date',()=>{
  const position={itemId:'item',locationId:'main',expirationDate:'2026-09-19'};
  assert.equal(context.orderAdjustmentPayload(position,{expirationDate:'2026-09-19',quantityChange:'2',reason:'OTHER'}).get('expirationDate'),'2026-09-19');
  assert.equal(context.orderAdjustmentPayload(position,{expirationDate:'',quantityChange:'2',reason:'OTHER'}).has('expirationDate'),false);
});
