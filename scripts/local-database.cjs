#!/usr/bin/env node
// Local-only PostgreSQL lifecycle. Run from Terminal; macOS sandboxes may deny PostgreSQL IPC.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=path.resolve(__dirname,'..'),local=path.join(root,'.local'),bin=path.join(local,'postgres18','pgsql','bin');
const data=path.join(local,'postgres-data'),secret=path.join(local,'database-password');
const port='55432',user='nextcommerce_local';
fs.mkdirSync(local,{recursive:true,mode:0o700});
for(const tool of ['initdb','pg_ctl','postgres','psql','pg_dump','pg_restore']){
  if(!fs.existsSync(path.join(bin,tool)))throw Error('Local PostgreSQL is missing '+tool+'. Complete the installation in docs/local-development.md before starting.');
}
function run(name,args,options={}){return cp.execFileSync(path.join(bin,name),args,{stdio:'inherit',...options});}
function password(){if(!fs.existsSync(secret))fs.writeFileSync(secret,crypto.randomBytes(32).toString('hex'),{mode:0o600,flag:'wx'});return fs.readFileSync(secret,'utf8').trim();}
function env(){return {...process.env,PGPASSWORD:password(),PGHOST:'127.0.0.1',PGPORT:port,PGUSER:'postgres'};}
function sql(text,db='postgres'){return run('psql',['-X','-v','ON_ERROR_STOP=1','-At','-d',db,'-c',text],{env:env(),stdio:['ignore','pipe','inherit']}).toString().trim();}
function start(){
  const pw=password();
  if(fs.existsSync(path.join(data,'PG_VERSION'))&&fs.readFileSync(path.join(data,'PG_VERSION'),'utf8').trim()!=='18')
    throw Error('Existing data uses another PostgreSQL major version. It was preserved; migrate it explicitly before using PostgreSQL 18.');
  if(!fs.existsSync(path.join(data,'PG_VERSION'))){
    if(fs.existsSync(data)&&fs.readdirSync(data).length)throw Error('Refusing to initialize a non-empty data directory. Existing files were preserved.');
    run('initdb',['-D',data,'-U','postgres','--auth=scram-sha-256','--pwfile='+secret,'--encoding=UTF8']);
  }
  const status=cp.spawnSync(path.join(bin,'pg_ctl'),['-D',data,'status'],{stdio:'ignore'});
  if(status.status!==0)run('pg_ctl',['-D',data,'-l',path.join(local,'postgres.log'),'-o',`-h 127.0.0.1 -p ${port} -k ${local} -c max_connections=50`,'-w','start']);
  if(!sql("SELECT 1 FROM pg_roles WHERE rolname='nextcommerce_local'"))sql(`CREATE ROLE ${user} LOGIN PASSWORD '${pw}'`);
  for(const database of ['next_ai_commerce_local','next_ai_commerce_test']){
    if(!sql(`SELECT 1 FROM pg_database WHERE datname='${database}'`))sql(`CREATE DATABASE ${database} OWNER ${user}`);
  }
  const config=path.join(local,'app.properties');
  if(!fs.existsSync(config))fs.writeFileSync(config,[
    'spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_local',
    'spring.datasource.username='+user,'spring.datasource.password='+pw,
    'app.credentials.encryption-key='+crypto.randomBytes(32).toString('base64'),
    'app.amazon.sync-enabled=true','app.amazon.write-enabled=false','app.amazon.listing-actions-enabled=false',
    'app.scheduling.enabled=true','app.integrations.external-enabled=true','app.mail.enabled=false',
    'app.mail.provider=disabled',''].join('\n'),{mode:0o600,flag:'wx'});
  console.log('Local PostgreSQL is ready on 127.0.0.1:55432. Development and test databases are separate.');
}
switch(process.argv[2]||'start'){
  case 'start':start();break;
  case 'status':run('pg_ctl',['-D',data,'status']);break;
  case 'stop':run('pg_ctl',['-D',data,'-m','fast','-w','stop']);break;
  default:throw Error('Use start, status or stop. No data-deletion operation is provided.');
}
