const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');
test('all shared action meanings use the new owner artwork without changing other icons',()=>{
 const context={window:{},document:{documentElement:{},querySelectorAll:()=>[]},MutationObserver:class{observe(){}}};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/platform-icons.js','utf8'),context);
 for(const name of ['actions','alert','actions-ai-human','actions-ai-human-alert','actionable-items','table-actions','ai-action-required'])assert.equal(context.window.NextAiIcons.source(name),'/images/platform/optimized32/actions-owner-20261111.png?v=1.14.4');
 assert.match(context.window.NextAiIcons.source('mapping'),/sku-mapped/);assert.match(context.window.NextAiIcons.source('collaboration'),/collaboration-default/);
});
