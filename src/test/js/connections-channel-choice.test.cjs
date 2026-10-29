const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
function fixture(){
 const elements=new Map();for(const id of ['amazon-marketplace-field','new-marketplace','amazon-seller-field','new-seller-identifier','walmart-marketplace','shopify-add-fields','shopify-marketplace','add-connection-form','shopify-credential-form','shopify-verify-form','shopify-connection-dialog'])elements.set(id,{disabled:false,required:false,hidden:false,value:'',dataset:{},addEventListener(){}});
 const context={document:{getElementById:id=>elements.get(id),querySelectorAll:()=>[]}};vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/js/connections.js','utf8'),context);return {elements,choose:c=>context.selectChannel(c)};
}
test('Shopify submits only the exact shop field to its separate creation action',()=>{
 const {elements:e,choose}=fixture();choose('SHOPIFY');assert.equal(e.get('add-connection-form').action,'/app/connections/shopify');assert.equal(e.get('shopify-marketplace').disabled,false);assert.equal(e.get('shopify-marketplace').required,true);for(const id of ['new-marketplace','new-seller-identifier','walmart-marketplace'])assert.equal(e.get(id).disabled,true);
});
test('Switching back preserves Amazon and Walmart field contracts',()=>{
 const {elements:e,choose}=fixture();choose('SHOPIFY');choose('AMAZON');assert.equal(e.get('add-connection-form').action,'/app/connections');assert.equal(e.get('new-marketplace').required,true);assert.equal(e.get('new-seller-identifier').required,true);assert.equal(e.get('shopify-marketplace').disabled,true);assert.equal(e.get('walmart-marketplace').disabled,true);
 choose('WALMART');assert.equal(e.get('walmart-marketplace').disabled,false);assert.equal(e.get('new-marketplace').disabled,true);assert.equal(e.get('new-seller-identifier').disabled,true);assert.equal(e.get('shopify-marketplace').disabled,true);
});
