const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('src/main/resources/static/js/personal-collaboration.js','utf8');
const start=source.indexOf('    function solvedDuration(');
const duration=vm.runInNewContext(source.slice(start,source.indexOf('    function render()',start))+';solvedDuration');
const created='2026-11-10T00:00:00Z';
for(const [minutes,label] of [[0,'<1m'],[59,'59m'],[60,'1h'],[200,'3h 20m'],[1440,'1d'],[3060,'2d 3h']]){
 test('resolution elapsed '+minutes+' minutes',()=>assert.equal(duration(created,new Date(Date.parse(created)+minutes*60000).toISOString()),label));
}
test('invalid or reversed dates have no resolution claim',()=>{
 assert.equal(duration(created,'invalid'),'');assert.equal(duration(created,'2026-11-09T00:00:00Z'),'');
});
