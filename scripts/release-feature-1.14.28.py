#!/usr/bin/env python3
"""Reviewed normal feature entry point after exact full CI. No data restore or integration-policy reset."""
import os,sys,json,hashlib,pathlib,subprocess,shutil,zipfile,tempfile,time,re,fcntl
P=pathlib.Path
APP=P('/opt/next-ai-commerce');VERSION='1.14.28'
BASE='a62ae7a8ad7fc727ff227486a42aa19f41da57e049c1aa02aadf1793b0ab9327'
POLICY='afa81cc0e337ef77995c95bf6b42ca1cd3e5957c61857525178d76bd5e69c68b'
START_SHA='1d64a7491d679e3dcfade6c60e2daf1da5d14b1d2a9e1f15a516956199a4fb4b'
FLAGS={'APP_FBA_REPLENISHMENT_ENABLED':'true','APP_AMAZON_FBA_MANUAL_ENABLED':'true','APP_FBA_SHIPMENT_WRITE_ENABLED':'false','APP_FBA_CHARGE_WRITE_ENABLED':'false'}
MIGRATIONS={'V145__fba_replenishment_plans.sql':'5db0a6374f3afb8e939733d8eccbdb12a352a1976b1a63ec3067f42effacb446','V146__fba_replenishment_documents.sql':'69a898c6dca8ca154489801fd4f9ce82a15b9eb9178e17a31230e03bc59a0be4'}
CAP=1536*1024**2;MARGIN=1024**3;SCRATCH=256*1024**2
GUARD_ENTRY='BOOT-INF/classes/com/nextaicommerce/platform/fba/FbaDepletionRepository.class'
def digest(p):return hashlib.sha256(P(p).read_bytes()).hexdigest()
def run(args,**kw):return subprocess.run(args,check=True,**kw)
def read(args):return subprocess.check_output(args,text=True).strip()
def query(sql):return read(['sudo','-u','postgres','psql','-d','next_ai_commerce','-XAtqc',sql])
def env(pid):return dict(v.split('=',1) for v in P('/proc/'+str(pid)+'/environ').read_text().split('\0') if '=' in v)
def fingerprint(values):return hashlib.sha256(json.dumps({k:v for k,v in values.items() if k.startswith(('APP_','AMAZON_','SHOPIFY_','FBA_','FEDEX_','SPRING_','MAIL_','DB_')) and k not in ('APP_BUILD_VERSION','APP_ADMIN_PASSWORD') and k not in FLAGS},sort_keys=True).encode()).hexdigest()
def free():return shutil.disk_usage(APP).free
def require_space(required):
 if free()<required:raise RuntimeError('Insufficient reviewed release space; runtime unchanged')
def new_start(old):
 text=old.decode();assert all(k+'=' not in text for k in FLAGS),'New flags already configured; review baseline'
 text,n=re.subn(r'APP_BUILD_VERSION=1\.14\.27\b','APP_BUILD_VERSION='+VERSION,text);assert n==1,'Unexpected build declarations'
 assert len(re.findall(r'(?m)^exec\s',text))==1,'Unexpected launcher structure'
 return re.sub(r'(?m)^(exec\s)',lambda m:''.join('export '+k+'='+v+'\n' for k,v in FLAGS.items())+m[1],text,count=1).encode()
def wait_health():
 for _ in range(60):
  result=subprocess.run(['curl','-fsS','--max-time','3','http://127.0.0.1:8080/login'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if result.returncode==0 and read(['systemctl','is-active','next-ai-commerce'])=='active':return
  time.sleep(1)
 raise RuntimeError('Application startup smoke failed')
def queue():return int(query("SELECT count(*) FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.tenant_id=j.tenant_id AND r.id=j.sync_run_id WHERE r.sync_profile='FBA_MANUAL' AND j.status IN ('QUEUED','WAITING','RUNNING')"))
def bounded_backup(path,log):
 with open(log,'wb') as err,open(path,'xb') as target:
  proc=subprocess.Popen(['timeout','600','sudo','-u','postgres','pg_dump','--format=custom','--compress=zstd:6','--lock-wait-timeout=5s','next_ai_commerce'],stdout=subprocess.PIPE,stderr=err);size=0
  try:
   while True:
    chunk=proc.stdout.read(1024**2)
    if not chunk:break
    size+=len(chunk)
    if size>CAP:raise RuntimeError('Backup exceeded reviewed cap; no database/service change')
    target.write(chunk)
   target.flush();os.fsync(target.fileno())
   if proc.wait()!=0 or size==0:raise RuntimeError('Backup failed; runtime unchanged')
  except BaseException:
   proc.terminate()
   try:proc.wait(timeout=10)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
   raise
 run(['pg_restore','--list',str(path)],stdout=subprocess.DEVNULL)
def main():
 package,expected,rollback,rollback_sha,revision,guard_sha= sys.argv[1:];package=P(package);rollback=P(rollback)
 assert re.fullmatch('[0-9a-f]{40}',revision) and all(re.fullmatch('[0-9a-f]{64}',v) for v in (expected,rollback_sha,guard_sha))
 os.umask(0o077);lock=open(APP/'.release.lock','a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 live=APP/'next-ai-commerce.jar';start=APP/'bin/start';assert read(['systemctl','is-active','next-ai-commerce'])=='active'
 pid=read(['systemctl','show','next-ai-commerce','-p','MainPID','--value']);before=env(pid)
 assert before.get('APP_BUILD_VERSION')=='1.14.27' and digest(live)==BASE and digest(start)==START_SHA and fingerprint(before)==POLICY
 assert digest(package)==expected and digest(rollback)==rollback_sha and queue()==0
 assert query("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")=='144'
 prepared_start=new_start(start.read_bytes());largest=max(package.stat().st_size,rollback.stat().st_size,live.stat().st_size)
 require_space(CAP+5*largest+SCRATCH+MARGIN)
 with zipfile.ZipFile(package) as new,zipfile.ZipFile(live) as old,zipfile.ZipFile(rollback) as back:
  prior=[n for n in old.namelist() if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')];assert len(prior)==144 and all(new.read(n)==old.read(n) for n in prior)
  additions={n for n in new.namelist() if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql')}-set(prior);assert additions=={'BOOT-INF/classes/db/migration/'+n for n in MIGRATIONS}
  assert all(hashlib.sha256(new.read('BOOT-INF/classes/db/migration/'+n)).hexdigest()==h for n,h in MIGRATIONS.items())
  libs={n for n in old.namelist() if n.startswith('BOOT-INF/lib/')};assert libs=={n for n in new.namelist() if n.startswith('BOOT-INF/lib/')} and all(new.read(n)==old.read(n) for n in libs)
  assert set(back.namelist())==set(old.namelist()) and [n for n in old.namelist() if old.read(n)!=back.read(n)]==[GUARD_ENTRY]
  assert hashlib.sha256(back.read(GUARD_ENTRY)).hexdigest()==guard_sha
 evidence=P(tempfile.mkdtemp(prefix='.deploy-'+VERSION+'-',dir=APP/'releases'));evidence.chmod(0o700)
 shutil.copy2(start,evidence/'start');shutil.copy2(live,evidence/'previous.jar');guarded=APP/'releases'/('next-ai-commerce-1.14.27-schema146-rollback-'+rollback_sha[:12]+'.jar');shutil.copy2(rollback,guarded);os.chown(guarded,int(read(['id','-u','nextaicommerce'])),int(read(['id','-g','nextaicommerce'])));guarded.chmod(0o640)
 (evidence/'baseline-policy.json').write_text(json.dumps({'fingerprint':POLICY}))
 bounded_backup(evidence/'database.dump',evidence/'backup.log');require_space(3*largest+SCRATCH+MARGIN)
 (evidence/'backup-manifest.json').write_text(json.dumps({'databaseSha256':digest(evidence/'database.dump'),'bytes':(evidence/'database.dump').stat().st_size,'previousSha256':BASE,'rollbackSha256':rollback_sha,'revision':revision}))
 libs_dir=evidence/'libs';migrations=evidence/'migrations';libs_dir.mkdir();migrations.mkdir()
 with zipfile.ZipFile(package) as z:
  for n in z.namelist():
   if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):(libs_dir/P(n).name).write_bytes(z.read(n))
   if n.startswith('BOOT-INF/classes/db/migration/') and n.endswith('.sql'):(migrations/P(n).name).write_bytes(z.read(n))
 helper=P(__file__).with_name('ApplyFbaReleaseMigrations.java');assert helper.is_file()
 migration_env=dict(os.environ);migration_env.update(RELEASE_DB_URL=before.get('SPRING_DATASOURCE_URL',before.get('DB_URL','')),RELEASE_DB_USER=before.get('SPRING_DATASOURCE_USERNAME',before.get('DB_USER',before.get('DB_USERNAME',''))),RELEASE_DB_PASSWORD=before.get('SPRING_DATASOURCE_PASSWORD',before.get('DB_PASSWORD','')))
 java=['java','-cp',str(libs_dir/'*'),str(helper),str(migrations)]
 with open(evidence/'migration.log','ab') as log:run(java+['validate'],env=migration_env,stdout=log,stderr=subprocess.STDOUT)
 assert digest(live)==BASE and digest(start)==START_SHA and fingerprint(env(pid))==POLICY and queue()==0
 stopped=False
 try:
  require_space(2*largest+MARGIN);run(['systemctl','stop','next-ai-commerce']);stopped=True;assert not P('/proc/'+pid).exists()
  require_space(2*largest+MARGIN)
  with open(evidence/'migration.log','ab') as log:run(java+['migrate'],env=migration_env,stdout=log,stderr=subprocess.STDOUT)
  assert query("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")=='146'
  require_space(largest+MARGIN);release=APP/'releases'/('next-ai-commerce-'+VERSION+'-'+revision[:12]+'.jar');shutil.copy2(package,release);os.chown(release,int(read(['id','-u','nextaicommerce'])),int(read(['id','-g','nextaicommerce'])));release.chmod(0o640)
  temp=APP/'next-ai-commerce.jar.release-new';assert not temp.exists();temp.symlink_to(release);os.replace(temp,live)
  start.write_bytes(prepared_start);run(['systemctl','start','next-ai-commerce']);wait_health()
  after_pid=read(['systemctl','show','next-ai-commerce','-p','MainPID','--value']);after=env(after_pid)
  assert digest(live)==expected and after.get('APP_BUILD_VERSION')==VERSION and fingerprint(after)==POLICY and all(after.get(k)==v for k,v in FLAGS.items())
  assert queue()==0,'Manual FBA queue changed; investigate before acceptance'
  run(['curl','-fsS','--max-time','15','https://app.nextaicommerce.com/login'],stdout=subprocess.DEVNULL)
  receipt={'version':VERSION,'revision':revision,'sha256':expected,'pid':after_pid,'schema':146,'policyPreserved':True,'flags':FLAGS,'manualQueue':0,'backup':str(evidence),'rollbackSha256':rollback_sha};(evidence/'completion.json').write_text(json.dumps(receipt));print(json.dumps(receipt))
 except BaseException:
  if stopped:
   run(['systemctl','stop','next-ai-commerce']);start.write_bytes((evidence/'start').read_bytes());temp=APP/'next-ai-commerce.jar.release-rollback';assert not temp.exists();temp.symlink_to(guarded);os.replace(temp,live);run(['systemctl','start','next-ai-commerce']);wait_health();assert fingerprint(env(read(['systemctl','show','next-ai-commerce','-p','MainPID','--value'])))==POLICY
   print('Guarded compatible application rollback completed; forward schema and backup retained. No data restore.')
  raise
if __name__=='__main__':main()
