const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const script=fs.readFileSync('scripts/production-release.sh','utf8');
const fn=script.slice(script.indexOf('require_release_false()'),script.indexOf('# BEGIN REVIEWED RELEASE GUARDS'));
function check(content){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'release-guard-'));
 const file=path.join(dir,'config');fs.writeFileSync(file,content,{mode:0o600});
 try{return spawnSync('bash',['-c',fn+'\nruntime_config=$1\nrequire_release_false AMAZON_WRITE_ENABLED','guard-test',file],{encoding:'utf8'});}
 finally{fs.rmSync(dir,{recursive:true,force:true});}
}
test('release guard accepts exactly one explicit disabled setting',()=>{
 assert.equal(check('export AMAZON_WRITE_ENABLED=false\n').status,0);
});
test('release guard rejects missing, enabled, duplicate or computed settings without printing values',()=>{
 for(const text of ['','AMAZON_WRITE_ENABLED=true\n','AMAZON_WRITE_ENABLED=false\nAMAZON_WRITE_ENABLED=true\n','AMAZON_WRITE_ENABLED=false\nAMAZON_WRITE_ENABLED=false\n','AMAZON_WRITE_ENABLED=${PRIVATE_TOKEN}\n','# AMAZON_WRITE_ENABLED=false\n']){
  const r=check(text);assert.notEqual(r.status,0);assert.match(r.stderr,/requires one explicit false setting/);assert.doesNotMatch(r.stderr,/PRIVATE_TOKEN/);
 }
});
test('deployment does not force startup enqueue and checks migration dependencies before installation',()=>{
 assert.doesNotMatch(script,/RECONCILIATION_ENABLED=true/);
 assert.ok(script.indexOf('required_schema_count=')<script.indexOf('package_dir='));
 assert.match(script,/test "\$required_schema_count" = 9/);
 assert.match(script,/test "\$migration_count" = 22/);
});
