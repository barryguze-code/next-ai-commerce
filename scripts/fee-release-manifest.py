#!/usr/bin/env python3
"""Read-only, fail-closed 1.8 release preflight. Never migrates, enables or restarts."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
from urllib.parse import urlparse, parse_qs

ROOT = Path('/opt/next-ai-commerce')
NEW_FLAGS = ('AMAZON_PRODUCT_FEES_ENABLED', 'AMAZON_PRODUCT_FEES_BATCH_ENABLED',
             'AMAZON_PRODUCT_FEES_RECURRING_ENABLED', 'AMAZON_FEE_EVIDENCE_ENABLED',
             'AMAZON_FEE_VARIANCE_UI_ENABLED', 'AMAZON_FEE_APPROVALS_ENABLED',
             'AMAZON_FEE_PILOT_ENABLED', 'AMAZON_FEE_PILOT_ONLY_SCHEDULING',
             'SPRING_FLYWAY_ENABLED')
TABLES = ('amazon_fee_observations', 'amazon_order_fee_charge_observations',
          'amazon_fee_comparisons', 'amazon_fee_approvals')

def require(condition):
    if not condition:
        raise ValueError('Reviewed release prerequisite does not match')

def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def live_datasource(raw_args, raw_environment):
    args = raw_args.decode().strip('\0').split('\0')
    env = dict(v.split('=', 1) for v in raw_environment.decode().split('\0') if '=' in v)
    require(not any(v.startswith('-Dspring.datasource.') for v in args) and not env.get('SPRING_APPLICATION_JSON'))
    def resolved(key, *env_keys):
        values = [v.split('=', 1)[1] for v in args if v.startswith('--' + key + '=')]
        require(len(values) <= 1)
        if values:
            return values[0]
        candidates = [env[k] for k in env_keys if k in env]
        require(candidates and len(set(candidates)) == 1)
        return candidates[0]
    role = resolved('spring.datasource.username', 'SPRING_DATASOURCE_USERNAME', 'DB_USER')
    url = resolved('spring.datasource.url', 'SPRING_DATASOURCE_URL', 'DB_URL')
    require(url.startswith('jdbc:postgresql://') and re.fullmatch('[a-z_][a-z0-9_]{0,62}', role))
    parsed = urlparse(url.removeprefix('jdbc:'))
    require(parsed.hostname in ('localhost', '127.0.0.1') and (parsed.port or 5432) == 5432
            and parsed.username is None and parsed.password is None
            and not {'user', 'password'} & set(parse_qs(parsed.query)))
    return role, parsed.path.removeprefix('/')

def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def protected(path, parent):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink() and path.resolve().is_relative_to(parent.resolve()))
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o077)
    return path

def validate(m, expected, observed, now):
    require(m['format'] == 'FEE_RELEASE_V1' and m['version'] == '1.8.0')
    require(all(m[k] == expected[k] for k in ('revision', 'version', 'package_sha256')))
    require(m['operation_revision'] == expected['operation_revision'])
    require(m['operation_script_sha256'] == observed['operation_script_sha256']
            and m['operation_guard_sha256'] == observed['operation_guard_sha256'])
    require(m['backup_strategy'] == 'REUSE_VERIFIED_PRE_DDL' and 0 < m['package_bytes'] < 200000000)
    require(now < instant(m['expires_at']) <= now + dt.timedelta(hours=2))
    require(all(re.fullmatch('[a-f0-9]{64}', m['source'][k]) for k in ('jar_sha256', 'argv_sha256', 'start_sha256')))
    require(m['source'] == observed['source'])
    require(m['database'] == observed['database'] == 'next_ai_commerce'
            and m['application_role'] == observed['application_role'] == 'nextcommerce')
    require(observed['migration_versions'] == list(range(91, 115)) and observed['failed_migrations'] == 0)
    require(observed['role_restricted'] and observed['tables_ready'] and observed['sequences_ready'])
    ui = m.get('read_only_ui_enabled', False)
    require(isinstance(ui, bool))
    require(all(observed['settings'].get(k) == ('true' if ui and k == 'AMAZON_FEE_VARIANCE_UI_ENABLED' else 'false') for k in NEW_FLAGS))
    # Existing worker/write policy is bound by the full startup-file checksum, not switched OFF.
    require(m['stage'] == 'INSTALL_ONLY' and m['activation'] == 'SEPARATE_BOUNDED_GRANT' and m['automatic_collection'] is False)
    plan = observed['activation_plan']
    require(plan['status'] == 'PROPOSED_NOT_AUTHORIZED' and plan['publication_mode'] == 'ESTIMATES_AND_EVIDENCE')
    require(all(re.fullmatch('[a-f0-9-]{36}', plan[k]) for k in ('tenant', 'connection')))
    require(plan['marketplace'] and re.fullmatch('[a-f0-9]{64}', plan['basis_sha256']))
    require(0 < plan['target_count'] <= 20 and plan['max_http'] == 1 and 0 < plan['max_oauth'] <= 2 and 0 < plan['max_seconds'] <= 300)
    r = observed['recovery']
    require(r['format'] == 'FEE_RECOVERY_V1' and r['source_database'] == m['database'])
    require(r['source_max_version'] == 112 and r['dump_sha256'] == observed['dump_sha256'])
    require(now - dt.timedelta(hours=2) <= instant(r['completed_at']) <= now)
    require(r['migration_owner'] == m['migration_owner'])
    # These three are separately reviewed operator attestations, not inferred from pg_restore --list.
    require(r['preservation_verified'] is True and r['migration_rehearsal_verified'] is True
            and r['previous_application_compatibility_verified'] is True)
    require(observed['restore_versions'] == list(range(91, 115)) and observed['restore_failed_migrations'] == 0)
    require(observed['restore_tables_ready'])
    require(0 < r['ddl_lock_timeout_seconds'] <= 10 and 0 < r['ddl_statement_timeout_seconds'] <= 300)

def sql(database, query):
    require(re.fullmatch('[a-z][a-z0-9_]{0,62}', database))
    return subprocess.check_output(['sudo', '-u', 'postgres', 'psql', '-d', database, '-XAtqc', query], text=True, stderr=subprocess.DEVNULL).strip()

def versions(database):
    return [int(v) for v in sql(database, "SELECT version FROM public.flyway_schema_history WHERE success AND version::int BETWEEN 91 AND 114 ORDER BY version::int").splitlines()]

def ready(database, owner, app):
    require(re.fullmatch('[a-z_][a-z0-9_]{0,62}', owner) and re.fullmatch('[a-z_][a-z0-9_]{0,62}', app))
    names = ','.join("'" + n + "'" for n in TABLES)
    return sql(database, f"SELECT count(*)=4 AND bool_and(c.relrowsecurity AND c.relforcerowsecurity AND pg_get_userbyid(c.relowner)='{owner}' AND has_table_privilege('{app}',c.oid,'SELECT') AND has_table_privilege('{app}',c.oid,'INSERT')) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN ({names})") == 't'

def main(args):
    require(len(args) == 4 and re.fullmatch('[a-f0-9]{64}', args[3]))
    revision, version, package_sha, manifest_sha = args
    manifest_path = protected(ROOT / 'release-manifests/1.8.0.json', ROOT / 'release-manifests')
    require(digest(manifest_path) == manifest_sha)
    m = json.loads(manifest_path.read_text())
    plan_path = protected(m['activation_plan'], ROOT / 'release-manifests')
    require(digest(plan_path) == m['activation_plan_sha256'])
    activation_plan = json.loads(plan_path.read_text())
    require(re.fullmatch('[a-z_][a-z0-9_]{0,62}', m['application_role']))
    receipt_path = protected(m['recovery_receipt'], ROOT / 'backups')
    require(digest(receipt_path) == m['recovery_receipt_sha256'])
    recovery = json.loads(receipt_path.read_text())
    dump = protected(recovery['dump'], ROOT / 'backups')
    require(stat.S_IMODE(dump.stat().st_mode) == 0o600)
    require(dump.stat().st_size > 0 and int(recovery['dump_bytes']) == dump.stat().st_size)
    subprocess.run(['pg_restore', '--list', str(dump)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    restore_db = recovery['restore_database']
    require(re.fullmatch('next_ai_commerce_release_restore_[a-z0-9_]+', restore_db))
    pid = subprocess.check_output(['systemctl', 'show', 'next-ai-commerce', '-p', 'MainPID', '--value'], text=True).strip()
    require(re.fullmatch('[1-9][0-9]*', pid))
    raw_args = (Path('/proc') / pid / 'cmdline').read_bytes()
    role, database = live_datasource(raw_args, (Path('/proc') / pid / 'environ').read_bytes())
    require(role == m['application_role'] and database == m['database'])
    start = ROOT / 'bin/start'
    text = start.read_text()
    settings = {}
    for key in NEW_FLAGS:
        declarations = re.findall(r'^\s*(?:export\s+)?' + key + r'=(.*)$', text, re.M)
        require(len(declarations) == 1)
        settings[key] = declarations[0].strip()
    seqs = "'public.amazon_order_fee_charge_observations_observation_seq_seq','public.amazon_fee_comparisons_revision_seq_seq'"
    observed = dict(
        source=dict(jar_sha256=digest(ROOT / 'next-ai-commerce.jar'),
                    argv_sha256=digest(Path('/proc') / pid / 'cmdline'), start_sha256=digest(start)),
        database=sql(database, 'SELECT current_database()'), application_role=role, activation_plan=activation_plan,
        migration_versions=versions(database), failed_migrations=int(sql(database, 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success')),
        role_restricted=sql(database, f"SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='{role}'") == 't',
        tables_ready=ready(database, m['migration_owner'], role),
        sequences_ready=sql(database, f"SELECT bool_and(has_sequence_privilege('{role}',s,'USAGE')) FROM unnest(ARRAY[{seqs}]) s") == 't',
        settings=settings, recovery=recovery, dump_sha256=digest(dump), restore_versions=versions(restore_db),
        restore_failed_migrations=int(sql(restore_db, 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success')),
        restore_tables_ready=ready(restore_db, m['migration_owner'], role))
    observed.update(operation_script_sha256=digest(Path(os.environ['FEE_RELEASE_OPERATION_SCRIPT'])),
                    operation_guard_sha256=digest(Path(__file__)))
    validate(m, dict(revision=revision, version=version, package_sha256=package_sha,
                     operation_revision=os.environ['FEE_RELEASE_OPERATION_REVISION']), observed, dt.datetime.now(dt.timezone.utc))
    print('Reviewed 1.8 manifest, preserved runtime, recovery and V113/V114 prerequisites verified. No activation performed.')
    print('FEE_VERIFIED_DUMP=' + str(dump))
    print('FEE_VERIFIED_DUMP_SHA256=' + observed['dump_sha256'])
    print('FEE_VERIFIED_DUMP_BYTES=' + str(dump.stat().st_size))
    print('FEE_PACKAGE_BYTES=' + str(m['package_bytes']))

if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except Exception:
        # Do not expose configuration, private paths or database diagnostic text.
        print('Release manifest preflight failed. Live application unchanged.', file=sys.stderr)
        sys.exit(1)
