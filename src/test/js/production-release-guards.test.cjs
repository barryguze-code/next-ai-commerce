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

const catalogueFn=script.slice(script.indexOf('# BEGIN CATALOGUE MINOR BASELINE'),script.indexOf('# END CATALOGUE MINOR BASELINE'));
function catalogueCheck(mode='good',marker='OWNER_APPROVED_CATALOGUE_MINOR_1_9'){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'catalogue-release-guard-')),config=path.join(dir,'start');
 const names=['SPRING_FLYWAY_ENABLED','AMAZON_PRODUCT_FEES_ENABLED','AMAZON_PRODUCT_FEES_BATCH_ENABLED','AMAZON_PRODUCT_FEES_RECURRING_ENABLED','AMAZON_FEE_EVIDENCE_ENABLED','AMAZON_FEE_APPROVALS_ENABLED','AMAZON_FEE_PILOT_ENABLED','AMAZON_FEE_PILOT_ONLY_SCHEDULING'];
 fs.writeFileSync(config,names.map(n=>n+'='+(mode==='enabled'&&n==='AMAZON_FEE_APPROVALS_ENABLED'?'true':'false')).join('\n')+'\nAMAZON_SYNC_ENABLED=true\nAPP_SCHEDULING_ENABLED=true\n',{mode:0o600});
 const mocks=`sha256sum(){ case "$1" in */next-ai-commerce.jar) if [[ "$mode" == jar ]]; then echo wrong; else echo fec818b5a4ff56b33d264f41c0c6f42a9dd6fac318057af507b23d954270a4dd; fi;; *) if [[ "$mode" == start ]]; then echo wrong; else echo e606097b8e990ee6675de0a0c78b8fd59328c5aa9e88ae12053d6fdbe49cbe95; fi;; esac; }
 sudo(){ case "\${*: -1}" in *max*) if [[ "$mode" == schema ]]; then echo 115; else echo 114; fi;; *'NOT success'*) if [[ "$mode" == failed ]]; then echo 1; else echo 0; fi;; *) if [[ "$mode" == count ]]; then echo 113; else echo 114; fi;; esac; }`;
 try{return spawnSync('bash',['-c','set -eu\n'+fn.slice(0,fn.indexOf('# BEGIN CATALOGUE MINOR BASELINE'))+'\n'+catalogueFn+'\n'+mocks+'\nruntime_config=$1; mode=$2; verify_catalogue_minor_baseline "$3"','guard',config,mode,marker],{encoding:'utf8'});}
 finally{fs.rmSync(dir,{recursive:true,force:true});}
}
test('catalogue minor preserves reviewed live workers with exact prior artifact/config/schema and new capabilities off',()=>{
 const result=catalogueCheck();assert.equal(result.status,0,result.stderr);
});
test('catalogue minor rejects baseline drift, schema failures, enabled new capability or wrong approval scope',()=>{
 for(const mode of ['jar','start','schema','count','failed','enabled'])assert.notEqual(catalogueCheck(mode).status,0,mode);
 assert.notEqual(catalogueCheck('good','OWNER_APPROVED_UI_HOTFIX_NO_NEW_BACKUP').status,0);
 assert.notEqual(catalogueCheck('good','').status,0);
});
test('catalogue minor retains a fresh database dump and existing application/configuration rollback',()=>{
 assert.match(script,/elif \[\[ "\$version" == "1\.9\.0" \]\]; then\n  verify_catalogue_minor_baseline/);
 const backup=script.slice(script.indexOf('if [[ "$ui_hotfix" == true ]]; then',script.indexOf('reuse_verified_dump()')),script.indexOf('# END VERIFIED BACKUP REUSE'));
 assert.match(backup,/else\n  sudo -u postgres pg_dump --format=custom/);
 assert.doesNotMatch(backup,/1\.9\.0/);
 assert.match(script,/cp -p "\$runtime_config" "\$backup_dir\/start"/);
 assert.match(script,/cp -L \/opt\/next-ai-commerce\/next-ai-commerce\.jar "\$backup_dir\/previous\.jar"/);
});
