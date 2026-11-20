#!/usr/bin/env python3
"""Explicit reviewed stocked identity repair. Default is exact rollback-only dry run.
Run locally against disposable fixtures or on the maintenance host with psql peer
access, after the application/publication workers have been stopped and drained.
No credentials, API calls, automatic discovery, backfill or schema changes.
"""
import argparse,json,pathlib,subprocess,uuid,os
p=argparse.ArgumentParser();p.add_argument('manifest',type=pathlib.Path);p.add_argument('--apply',action='store_true');a=p.parse_args()
m=json.loads(a.manifest.read_text())
if a.apply:
 result=subprocess.run(['systemctl','show','next-ai-commerce','-p','LoadState','-p','ActiveState','-p','SubState','-p','MainPID'],capture_output=True,text=True,check=True)
 state=dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line)
 if state!={'LoadState':'loaded','ActiveState':'inactive','SubState':'dead','MainPID':'0'}:raise SystemExit('Application must be fully stopped and drained before apply')
for k in ('tenant','source','target','sourceGlobal','targetGlobal','vendor'):uuid.UUID(m[k])
if a.apply and m.get('publicationHoldConfirmed') is not True:raise SystemExit('Confirm publication hold/drain in reviewed manifest before apply')
script=pathlib.Path(__file__).with_name('stocked-pair.sql').read_text()
encoded=json.dumps(m).replace("'","''")
sql="BEGIN;\nSELECT set_config('app.catalogue_repair','"+encoded+"',true);\n"+script+"\nSELECT json_build_object('request',target_id,'action',action,'details',details) FROM audit_events WHERE tenant_id='"+m['tenant']+"'::uuid AND action='CATALOGUE_STOCKED_RECONCILIATION' AND target_id='"+m['request'].replace("'","''")+"';\n"+('COMMIT;' if a.apply else 'ROLLBACK;')
subprocess.run(['runuser','-u','postgres','--','psql','-X','-h','/var/run/postgresql','-p','5432','-U','postgres','-v','ON_ERROR_STOP=1','-At','-d','next_ai_commerce'],input=sql,text=True,check=True,env={k:v for k,v in os.environ.items() if not k.startswith('PG')})
