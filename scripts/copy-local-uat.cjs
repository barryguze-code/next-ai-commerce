#!/usr/bin/env node
// Restore a TRUSTED administrator-produced backup into the EMPTY local UAT database only.
// No source database connection is opened, and no production credentials are read.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const root=path.resolve(__dirname,'..'),local=path.join(root,'.local'),bin=path.join(local,'postgres18','pgsql','bin');
const backup=process.argv[2];
if(!backup||!fs.statSync(backup).isFile())throw Error('Pass a trusted PostgreSQL 18 custom-format backup file. See docs/local-development.md.');
const destination={...process.env,PGHOST:'127.0.0.1',PGPORT:'55432',PGUSER:'postgres',
  PGPASSWORD:fs.readFileSync(path.join(local,'database-password'),'utf8').trim(),PGDATABASE:'next_ai_commerce_local',
  PGOPTIONS:'',PGCONNECT_TIMEOUT:'8'};
delete destination.PGSERVICE;
function run(name,args){return cp.execFileSync(path.join(bin,name),args,{env:destination,encoding:'utf8',maxBuffer:8*1024*1024,stdio:['ignore','pipe','pipe']});}
function sql(query){return run('psql',['-X','-At','-v','ON_ERROR_STOP=1','-c',query]).trim();}
function main(){
  if(sql('SELECT current_database()')!=='next_ai_commerce_local')throw Error('Unexpected destination database.');
  if(!sql("SHOW server_version").startsWith('18.'))throw Error('Use PostgreSQL 18 for source/local parity.');
  if(sql("SELECT count(*) FROM pg_tables WHERE schemaname='public'")!=='0')throw Error('The local destination is not empty. Nothing was overwritten.');
  // Validate the archive and filter data entries without reading or displaying their contents.
  const listing=run('pg_restore',['--list',path.resolve(backup)]);
  const filtered=listing.split('\n').filter(line=>!/^\d+; \d+ \d+ TABLE DATA public (marketplace_connection_credentials|shipping_label_artifacts) /.test(line)).join('\n');
  const selection=path.join(local,'uat-restore.list');
  fs.writeFileSync(selection,filtered,{mode:0o600,flag:'wx'});
  console.log('Restoring the approved backup into the empty local UAT database. Marketplace credentials and shipping-label files are excluded.');
  // Set the non-superuser role so every restored object belongs to the application role.
  // Single transaction rolls back an unsuccessful restore; there is no --clean or DROP step.
  run('pg_restore',['--exit-on-error','--single-transaction','--no-owner','--no-acl',
    '--role=nextcommerce_local','--use-list='+selection,'--dbname=next_ai_commerce_local',path.resolve(backup)]);
  // Local debugging deliberately bypasses tenant RLS. Production roles and policies are untouched.
  sql('ALTER ROLE nextcommerce_local BYPASSRLS');
  console.log('Local UAT copy is ready with unrestricted local database visibility. Original backup preserved.');
}
try{main();}catch(error){
  // Database error output can contain row values; do not send it to terminals or chat.
  console.error(error.status!==undefined?'Restore failed and was rolled back. Keep the backup; ask an administrator to review the archive compatibility.':error.message);
  process.exitCode=1;
}
