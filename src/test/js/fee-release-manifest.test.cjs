const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const flags=['AMAZON_PRODUCT_FEES_ENABLED','AMAZON_PRODUCT_FEES_BATCH_ENABLED','AMAZON_PRODUCT_FEES_RECURRING_ENABLED','AMAZON_FEE_EVIDENCE_ENABLED','AMAZON_FEE_VARIANCE_UI_ENABLED','AMAZON_FEE_APPROVALS_ENABLED','AMAZON_FEE_PILOT_ENABLED','AMAZON_FEE_PILOT_ONLY_SCHEDULING','SPRING_FLYWAY_ENABLED'];
function fixture(){
 const expected={revision:'a'.repeat(40),version:'1.8.0',package_sha256:'b'.repeat(64)};
 const source={jar_sha256:'c'.repeat(64),argv_sha256:'d'.repeat(64),start_sha256:'e'.repeat(64)};
 const recovery={format:'FEE_RECOVERY_V1',source_database:'next_ai_commerce',source_max_version:112,dump_sha256:'f'.repeat(64),completed_at:'2026-10-25T00:55:00Z',migration_owner:'nextcommerce',preservation_verified:true,migration_rehearsal_verified:true,previous_application_compatibility_verified:true,ddl_lock_timeout_seconds:10,ddl_statement_timeout_seconds:300};
 const versions=Array.from({length:24},(_,i)=>91+i);
 return {expected,m:{...expected,format:'FEE_RELEASE_V1',stage:'INSTALL_ONLY',expires_at:'2026-10-25T02:00:00Z',source:{...source},database:'next_ai_commerce',application_role:'nextcommerce',migration_owner:'nextcommerce',activation:'SEPARATE_BOUNDED_GRANT',automatic_collection:false},o:{source,database:'next_ai_commerce',application_role:'nextcommerce',migration_versions:versions,failed_migrations:0,role_restricted:true,tables_ready:true,sequences_ready:true,settings:Object.fromEntries(flags.map(f=>[f,'false'])),recovery,dump_sha256:'f'.repeat(64),restore_versions:[...versions],restore_failed_migrations:0,restore_tables_ready:true,activation_plan:{status:'PROPOSED_NOT_AUTHORIZED',publication_mode:'ESTIMATES_AND_EVIDENCE',tenant:'1'.repeat(36),connection:'2'.repeat(36),marketplace:'SYNTHETIC',basis_sha256:'3'.repeat(64),target_count:20,max_http:1,max_oauth:2,max_seconds:300}}};
}
function check(f){return spawnSync('python3',['-c',`import runpy,json,sys,datetime
g=runpy.run_path('scripts/fee-release-manifest.py')
f=json.load(sys.stdin)
g['validate'](f['m'],f['expected'],f['o'],datetime.datetime(2026,10,25,1,0,tzinfo=datetime.timezone.utc))`],{input:JSON.stringify(f),encoding:'utf8'});}
test('reviewed manifest permits staged installation while existing worker policy is preserved',()=>{
 assert.equal(check(fixture()).status,0);
 const script=fs.readFileSync('scripts/production-release.sh','utf8');
 assert.ok(script.indexOf('Reviewed 1.8 manifest guard required')<script.indexOf('package_dir='));
 assert.match(script,/fee_schema_count.*[\s\S]*test "\$fee_schema_count" = 2/);
 assert.doesNotMatch(script,/AMAZON_FEE_EVIDENCE_ENABLED=true|AMAZON_PRODUCT_FEES_ENABLED=true/);
});
test('missing or changed identity, package, recovery, migrations, permissions and flags fail closed',()=>{
 const changes=[
  f=>f.m.revision='0'.repeat(40),f=>f.m.package_sha256='0'.repeat(64),f=>f.m.source.argv_sha256='0'.repeat(64),f=>f.m.source.start_sha256='0'.repeat(64),f=>f.m.source.jar_sha256='0'.repeat(64),
  f=>f.m.expires_at='2026-10-25T00:59:00Z',f=>f.m.expires_at='2026-10-26T00:59:00Z',f=>f.o.failed_migrations=1,f=>f.o.migration_versions.pop(),
  f=>f.o.tables_ready=false,f=>f.o.sequences_ready=false,f=>f.o.role_restricted=false,f=>f.o.database='wrong',f=>f.o.application_role='other',
  f=>f.o.recovery.dump_sha256='0'.repeat(64),f=>f.o.recovery.completed_at='2026-10-24T20:00:00Z',f=>f.o.recovery.source_max_version=114,
  f=>f.o.recovery.preservation_verified=false,f=>f.o.recovery.migration_rehearsal_verified=false,f=>f.o.recovery.previous_application_compatibility_verified=false,
  f=>f.o.restore_versions.pop(),f=>f.o.restore_failed_migrations=1,f=>f.o.restore_tables_ready=false,f=>f.m.automatic_collection=true,f=>f.m.stage='COMPLETE',f=>f.o.activation_plan.max_http=2,f=>f.o.activation_plan.status='AUTHORIZED',
  f=>f.o.recovery.ddl_lock_timeout_seconds=11,f=>f.o.recovery.ddl_statement_timeout_seconds=301,
  ...flags.flatMap(key=>[f=>delete f.o.settings[key],f=>f.o.settings[key]='true'])
 ];
 for(const change of changes){const f=fixture();change(f);assert.notEqual(check(f).status,0);}
});
test('protected workflow transports both exact scripts and requires reviewed manifest checksum',()=>{
 const workflow=fs.readFileSync('.github/workflows/deploy-production.yml','utf8');
 assert.match(workflow,/vars.PRODUCTION_MANIFEST_SHA256/);
 assert.match(workflow,/tar -czf - scripts\/production-release.sh scripts\/fee-release-manifest.py/);
 assert.match(workflow,/FEE_RELEASE_GUARD=/);
 const guard=fs.readFileSync('scripts/fee-release-manifest.py','utf8');
 assert.match(guard,/info.st_uid == 0/);assert.match(guard,/not info.st_mode & 0o077/);assert.match(guard,/digest\(manifest_path\) == manifest_sha/);
 assert.match(guard,/pg_restore', '--list'/);assert.doesNotMatch(guard,/systemctl', 'restart|CREATE TABLE|ALTER TABLE|INSERT INTO|DROP DATABASE/);
});
test('actual datasource role and target come from the live process, with conflicting declarations rejected',()=>{
 const program=`import runpy,sys,json
g=runpy.run_path('scripts/fee-release-manifest.py')
f=json.load(sys.stdin)
assert g['live_datasource'](f['args'].encode(),f['env'].encode()) == ('nextcommerce','next_ai_commerce')`;
 const args='java\0-jar\0app.jar\0--spring.datasource.username=nextcommerce\0--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/next_ai_commerce\0';
 const run=f=>spawnSync('python3',['-c',program],{input:JSON.stringify(f),encoding:'utf8'});
 assert.equal(run({args,env:''}).status,0);
 assert.equal(run({args:'java\0',env:'DB_USER=nextcommerce\0DB_URL=jdbc:postgresql://127.0.0.1:5432/next_ai_commerce\0'}).status,0);
 for(const f of [{args:args.replace('nextcommerce','other'),env:''},{args:args+'--spring.datasource.username=other\0',env:''},{args:args.replace('/next_ai_commerce','/other'),env:''},{args:args.replace('/next_ai_commerce','/next_ai_commerce?user=other'),env:''},{args:'java\0',env:'DB_USER=nextcommerce\0SPRING_DATASOURCE_USERNAME=other\0'}])assert.notEqual(run(f).status,0);
});
test('failed guard stops before download or restart and a valid guard preserves startup and argument bytes',()=>{
 const script=fs.readFileSync('scripts/production-release.sh','utf8');
 const fn=script.slice(script.indexOf('require_release_false()'),script.indexOf('# BEGIN REVIEWED RELEASE GUARDS'));
 const guards=script.slice(script.indexOf('# BEGIN REVIEWED RELEASE GUARDS'),script.indexOf('# END REVIEWED RELEASE GUARDS'));
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'fee-release-stop-'));
 try{
  const config=path.join(dir,'start'),argv=path.join(dir,'argv'),marker=path.join(dir,'operations');
  const configBytes='AMAZON_SYNC_ENABLED=true\nAMAZON_WRITE_ENABLED=true\n'+flags.map(f=>f+'=false\n').join('');
  const argumentBytes=Buffer.from('java\0-jar\0existing.jar\0');fs.writeFileSync(config,configBytes);fs.writeFileSync(argv,argumentBytes);
  for(const status of [1,0]){
   const code=`set -e\nruntime_config=$1\nversion=1.8.0\nrevision=revision\npackage_sha=sha\nFEE_RELEASE_GUARD=reviewed-helper\npython3(){ return ${status}; }\ncurl(){ echo download >> "$2"; }\nsystemctl(){ echo restart >> "$2"; }\n${fn}\n${guards}\ncurl dummy "$2"\nsystemctl dummy "$2"`;
   const r=spawnSync('bash',['-c',code,'guard-check',config,marker,'unused','manifest-sha'],{encoding:'utf8'});
   assert.equal(r.status,status);assert.equal(fs.existsSync(marker),status===0);
   assert.equal(fs.readFileSync(config,'utf8'),configBytes);assert.deepEqual(fs.readFileSync(argv),argumentBytes);
  }
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
