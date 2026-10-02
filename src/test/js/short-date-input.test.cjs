const {test}=require('node:test'),assert=require('node:assert/strict');
const {parse,display,mask}=require('../../main/resources/static/js/short-date-input.js');
test('numeric typing inserts separators and rejects nonnumeric characters',()=>{
  assert.equal(mask('100226'),'10/02/26');assert.equal(mask('1'),'1');assert.equal(mask('100'),'10/0');
  assert.equal(mask('10ab02$26'),'10/02/26');assert.equal(mask('10/02/2026'),'10/02/26');
  assert.equal(mask('10022026'),'10/02/26');assert.equal(mask('1002261405',true),'10/02/26 14:05');
  assert.equal(parse(mask('022924')),'2024-02-29');assert.equal(parse(mask('023126')),null);
});
test('short dates submit full ISO years without timezone conversion',()=>{
  assert.equal(parse('9/20/26'),'2026-09-20');assert.equal(display('2026-09-20'),'09/20/26');
  assert.equal(parse('01/01/00'),'2000-01-01');assert.equal(parse('12/31/99'),'2099-12-31');
  assert.equal(parse('09/20/2026'),'2026-09-20');assert.equal(parse(''),'');
});
test('impossible and ambiguous dates are rejected',()=>{
  for(const value of ['02/29/25','04/31/26','13/01/26','01/00/26','20/09/26','01/01/1','01/01/2100'])assert.equal(parse(value),null,value);
  assert.equal(parse('02/29/24'),'2024-02-29');
});
test('date and time controls retain their ISO time',()=>{
  assert.equal(parse('10/02/26 14:05',true),'2026-10-02T14:05');
  assert.equal(display('2026-10-02T14:05'),'10/02/26 14:05');
  assert.equal(parse('10/02/26 24:00',true),null);assert.equal(parse('10/02/26',true),null);
});
