const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
test('opening details never runs modal enhancement or touches its nested heading',()=>{
 let observer, inspected=false;
 const document={body:{},querySelectorAll:()=>[]};
 const details={matches:selector=>selector==='details',querySelector:()=>{inspected=true;return null;}};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/ux-components.js','utf8'),{
  document,window:{},MutationObserver:class{constructor(callback){observer=callback;}observe(){}}
 });
 observer([{type:'attributes',target:details}]);
 assert.equal(inspected,false);
});
