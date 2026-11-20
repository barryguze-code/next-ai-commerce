#!/usr/bin/env node
// Read-only export through the approved local AWS tunnel for a local-UAT refresh.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),cp=require('node:child_process');
const root=path.resolve(__dirname,'..'),bin=path.join(root,'.local','postgres18','pgsql','bin');
const launch=process.argv[2]||'/Users/barryguze/eclipse-workspace/.metadata/.plugins/org.eclipse.debug.core/.launches/NextAiCommerceApplication.launch';
const decode=value=>value.replaceAll('&quot;','"').replaceAll('&apos;',"'").replaceAll('&lt;','<').replaceAll('&gt;','>').replaceAll('&amp;','&');
const xml=fs.readFileSync(launch,'utf8');
const values=Object.fromEntries([...xml.matchAll(/mapEntry key="([^"]+)" value="([^"]*)"/g)].map(match=>[decode(match[1]),decode(match[2])]));
for(const key of ['DB_URL','DB_USER','DB_PASSWORD'])if(!values[key])throw Error('The selected approved launch is missing '+key+'.');
const url=new URL(values.DB_URL.replace(/^jdbc:/,''));
if(url.hostname!=='127.0.0.1'||url.port!=='15432')throw Error('Production export requires the fixed loopback AWS tunnel on port 15432.');
const env={...process.env,PGHOST:url.hostname,PGPORT:url.port,PGDATABASE:url.pathname.slice(1),
  PGUSER:process.env.BACKUP_DB_USER||values.DB_USER,
  PGPASSWORD:process.env.BACKUP_DB_PASSWORD||values.DB_PASSWORD,PGCONNECT_TIMEOUT:'8',PGOPTIONS:''};
delete env.PGSERVICE;
const run=(tool,args,options={})=>cp.execFileSync(path.join(bin,tool),args,{env,encoding:'utf8',maxBuffer:16*1024*1024,...options});
const status=run('psql',['-X','-At','-F','|','-v','ON_ERROR_STOP=1','-c',
  "select current_database(),current_user,(select rolsuper or rolbypassrls from pg_roles where rolname=current_user),current_setting('server_version'),(select count(*) from pg_tables where schemaname='public')"]).trim().split('|');
if(!status[3].startsWith('18.'))throw Error('Production and local PostgreSQL major versions must both be 18.');
if(status[2]!=='t')throw Error('The configured database identity cannot bypass row-level security. Ask the database administrator for an approved complete backup.');
const backupDir=path.join(root,'.local','backups');fs.mkdirSync(backupDir,{recursive:true,mode:0o700});
const stamp=new Date().toISOString().replace(/[:.]/g,'-');const backup=path.join(backupDir,'production-'+stamp+'.dump');
run('pg_dump',['--format=custom','--compress=6','--no-owner','--no-acl','--file='+backup],{stdio:['ignore','ignore','pipe']});
fs.chmodSync(backup,0o600);const bytes=fs.statSync(backup).size;
const checksum=crypto.createHash('sha256').update(fs.readFileSync(backup)).digest('hex');
console.log(JSON.stringify({database:status[0],role:status[1],serverVersion:status[3],publicTables:Number(status[4]),backup,bytes,sha256:checksum}));
