const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/resources/static/js/fee-variance.js','utf8');
const helpers=source.slice(source.indexOf(' const economicKey='),source.indexOf(' const delta='));
const context=vm.createContext({});vm.runInContext(helpers+';this.earlier=earlierDifferences;',context);
const old={order_item_id:'ITEM',fee_type:'FBA',currency:'USD',charge_id:'OLD',expected_amount:'10',actual_amount:'12',state:'HIGHER',current_revision:false,same_economic_as_current:false};
test('reprojection of current immutable charge is not an earlier economic difference',()=>{assert.equal(context.earlier([{...old,same_economic_as_current:true}]).length,0);});
test('distinct prior charge revisions stay accessible without duplicate derivations',()=>{const rows=context.earlier([old,{...old},{...old,charge_id:'CURRENT',current_revision:true},{...old,charge_id:'OTHER',state:'AWAITING'}]);assert.equal(rows.length,1);assert.equal(rows[0].charge_id,'OLD');});
