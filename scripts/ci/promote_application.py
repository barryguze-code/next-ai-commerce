#!/usr/bin/env python3
"""Protected application-only promotion; never migrates, restores or changes policy."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.parse
import zipfile

APP = Path('/opt/next-ai-commerce')
CAP = 1536 * 1024**2
WAL = RESERVE = 1024**3
META = 50 * 1024**2


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while block := source.read(1024**2):
            digest.update(block)
    return digest.hexdigest()


def read(args):
    return subprocess.check_output(args, text=True).strip()


def query(sql):
    return read(['sudo', '-u', 'postgres', 'psql', '-d', 'next_ai_commerce', '-XAtqc', sql])


def environment(pid):
    return dict(value.split('=', 1) for value in Path('/proc/' + pid + '/environ').read_text().split('\0') if '=' in value)


def policy(values):
    # Include existing FBA flags too. Only the version label and existing randomized
    # bootstrap password are excluded; actual stored credentials remain untouched.
    relevant = {k: v for k, v in values.items()
                if k.startswith(('APP_', 'AMAZON_', 'SHOPIFY_', 'FBA_', 'FEDEX_', 'SPRING_', 'MAIL_', 'DB_'))
                and k not in ('APP_BUILD_VERSION', 'APP_ADMIN_PASSWORD')}
    return hashlib.sha256(json.dumps(relevant, sort_keys=True).encode()).hexdigest()


def schema_identity():
    raw = query("SELECT coalesce(json_agg(json_build_array(installed_rank,version,type,checksum,success) ORDER BY installed_rank),'[]'::json)::text FROM flyway_schema_history")
    rows = json.loads(raw)
    if not rows or any(not row[4] for row in rows):
        raise ValueError('Schema history is absent or contains failed migrations')
    return hashlib.sha256(json.dumps(rows, separators=(',', ':')).encode()).hexdigest()


def migrations(jar):
    with zipfile.ZipFile(jar) as archive:
        return {name: archive.read(name) for name in archive.namelist()
                if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}


def validate_plan(plan):
    required = {'schema', 'revision', 'version', 'packageSha256', 'baselineJarSha256',
                'baselineLauncherSha256', 'policySha256', 'schemaHistorySha256',
                'rollbackJar', 'rollbackSha256', 'rollbackSchemaHistorySha256', 'operationSha256', 'packageBytes'}
    if set(plan) != required or plan['schema'] != 1:
        raise ValueError('Unexpected reviewed application-only plan')
    if not re.fullmatch(r'[0-9a-f]{40}', plan['revision']) or not re.fullmatch(r'\d+\.\d+\.\d+', plan['version']):
        raise ValueError('Invalid release identity')
    if type(plan['packageBytes']) is not int or not 0 < plan['packageBytes'] <= 512 * 1024**2:
        raise ValueError('Reviewed exact artifact size is required')
    for key in required - {'schema', 'revision', 'version', 'rollbackJar', 'packageBytes'}:
        if not re.fullmatch(r'[0-9a-f]{64}', plan[key]):
            raise ValueError('Invalid reviewed digest')
    rollback = Path(plan['rollbackJar'])
    if not rollback.is_absolute() or '..' in rollback.parts or rollback.parent != APP / 'releases':
        raise ValueError('Rollback must be the reviewed retained release artifact')
    if (plan['rollbackSchemaHistorySha256'] != plan['schemaHistorySha256']
            or plan['rollbackSha256'] != plan['baselineJarSha256']):
        raise ValueError('Rollback compatibility must cover the unchanged live schema')


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != 'https':
            raise ValueError('Artifact redirects must preserve HTTPS')
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def download(url, destination, expected_bytes):
    deadline = time.monotonic() + 300
    with urllib.request.build_opener(HttpsRedirect).open(url, timeout=15) as response:
        content_length = response.headers.get('Content-Length')
        if content_length is not None and int(content_length) != expected_bytes:
            raise ValueError('Artifact advertised size differs from reviewed bytes')
        with destination.open('xb') as target:
            size = 0
            while block := response.read(1024**2):
                size += len(block)
                if size > expected_bytes or time.monotonic() > deadline:
                    raise ValueError('Artifact stream exceeded reviewed size/time bound; runtime unchanged')
                target.write(block)
            if size != expected_bytes:
                raise ValueError('Artifact stream was incomplete; runtime unchanged')


def backup(destination):
    with destination.open('xb') as target, destination.with_name('backup.log').open('xb') as errors:
        proc = subprocess.Popen(['timeout', '600', 'sudo', '-u', 'postgres', 'pg_dump', '--format=custom',
                                 '--compress=zstd:6', '--lock-wait-timeout=5s', 'next_ai_commerce'],
                                stdout=subprocess.PIPE, stderr=errors)
        size = 0
        try:
            while block := proc.stdout.read(1024**2):
                size += len(block)
                if size > CAP:
                    raise ValueError('Backup cap reached; runtime unchanged, partial recovery retained')
                target.write(block)
            target.flush()
            os.fsync(target.fileno())
            if proc.wait() != 0 or size == 0:
                raise ValueError('Backup failed; runtime unchanged')
        except BaseException:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()
            raise
    subprocess.run(['pg_restore', '--list', str(destination)], check=True, stdout=subprocess.DEVNULL)


def health():
    for _ in range(60):
        ok = subprocess.run(['curl', '-fsS', '--max-time', '3', 'http://127.0.0.1:8080/login'],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if ok and read(['systemctl', 'is-active', 'next-ai-commerce']) == 'active':
            return
        time.sleep(1)
    raise ValueError('Application startup smoke failed')


def main():
    plan = json.loads(Path(sys.argv[1]).read_text())
    validate_plan(plan)
    if sha(Path(__file__)) != plan['operationSha256']:
        raise ValueError('Server operation differs from the reviewed plan')
    os.umask(0o077)
    with (APP / '.release.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        install(plan)


def replace_build_label(original, version):
    """Change one static shell assignment, retaining all other launcher bytes."""
    error = 'Expected exactly one existing build label; no configuration rewrite allowed'
    if original.count(b'APP_BUILD_VERSION=') != 1 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError(error)
    # Accept only a simple assignment command; never interpret shell syntax or
    # match a quoted string, comment, variable expansion or another variable.
    value = rb'[^ \t\r\n\x22\x27`$;|&<>\\]*'
    assignment = rb'[A-Za-z_][A-Za-z0-9_]*=' + value
    command = rb'(?:export[ \t]+)?' + assignment + rb'(?:[ \t]+' + assignment + rb')*[ \t]*'
    token = rb'(?<!\S)APP_BUILD_VERSION=([0-9]+\.[0-9]+\.[0-9]+)(?=[ \t\r\n]|$)'
    for line in original.splitlines(keepends=True):
        match = re.search(token, line)
        if match and re.fullmatch(command, line.rstrip(b'\r\n')):
            offset = original.index(line) + match.start(1)
            return original[:offset] + version.encode() + original[offset + len(match[1]):]
    raise ValueError(error)


def install(plan):
    live, start = APP / 'next-ai-commerce.jar', APP / 'bin/start'
    pid = read(['systemctl', 'show', 'next-ai-commerce', '-p', 'MainPID', '--value'])
    before = environment(pid)
    if (read(['systemctl', 'is-active', 'next-ai-commerce']) != 'active'
            or sha(live) != plan['baselineJarSha256'] or sha(start) != plan['baselineLauncherSha256']
            or policy(before) != plan['policySha256'] or schema_identity() != plan['schemaHistorySha256']
            or before.get('SPRING_FLYWAY_ENABLED', '').lower() != 'false'):
        raise ValueError('Live baseline changed; no installation or policy changes')
    rollback = Path(plan['rollbackJar'])
    if rollback.is_symlink() or sha(rollback) != plan['rollbackSha256']:
        raise ValueError('Reviewed compatible recovery artifact is unavailable')
    original_start = start.read_bytes()
    prepared = replace_build_label(original_start, plan['version'])
    largest = max(plan['packageBytes'], live.stat().st_size, rollback.stat().st_size)
    # All remaining writes are budgeted BEFORE creating a staging directory or
    # opening the download: staged input, installed candidate, retained recovery,
    # capped backup, metadata, WAL growth and the independent retained reserve.
    if shutil.disk_usage(APP).free < plan['packageBytes'] + CAP + 2 * largest + META + WAL + RESERVE:
        raise ValueError('Insufficient reviewed recovery/disk budget before download; runtime unchanged')
    evidence = Path(tempfile.mkdtemp(prefix='.promotion-' + plan['version'] + '-', dir=APP / 'releases'))
    candidate = evidence / 'candidate.jar'
    url = ('https://github.com/barryguze-code/next-ai-commerce/releases/download/build-' + plan['revision']
           + '/next-ai-commerce-' + plan['version'] + '-' + plan['revision'] + '.jar')
    download(url, candidate, plan['packageBytes'])
    if sha(candidate) != plan['packageSha256'] or migrations(candidate) != migrations(live):
        raise ValueError('Artifact differs or requires a separate reviewed schema operation')
    if shutil.disk_usage(APP).free < CAP + 2 * largest + META + WAL + RESERVE:
        raise ValueError('Insufficient reviewed recovery/disk budget; runtime unchanged')
    shutil.copy2(start, evidence / 'start')
    shutil.copy2(rollback, evidence / 'rollback.jar')
    backup(evidence / 'database.dump')
    (evidence / 'recovery.json').write_text(json.dumps({'plan': plan, 'backupSha256': sha(evidence / 'database.dump')}))
    if (sha(live) != plan['baselineJarSha256'] or sha(start) != plan['baselineLauncherSha256']
            or policy(environment(pid)) != plan['policySha256'] or schema_identity() != plan['schemaHistorySha256']
            or sha(rollback) != plan['rollbackSha256']
            or shutil.disk_usage(APP).free < largest + META + WAL + RESERVE):
        raise ValueError('Pre-install baseline or disk budget changed')
    stopped = False
    try:
        stopped = True
        subprocess.run(['systemctl', 'stop', 'next-ai-commerce'], check=True)
        if Path('/proc/' + pid).exists():
            raise ValueError('Previous application did not exit')
        target = APP / 'releases' / ('next-ai-commerce-' + plan['version'] + '-' + plan['revision'][:12] + '.jar')
        if target.exists():
            raise ValueError('Immutable release target already exists')
        shutil.copy2(candidate, target)
        os.chown(target, int(read(['id', '-u', 'nextaicommerce'])), int(read(['id', '-g', 'nextaicommerce'])))
        target.chmod(0o640)
        link = APP / 'next-ai-commerce.jar.promotion'
        if link.exists() or link.is_symlink():
            raise ValueError('Unexpected pending install link')
        link.symlink_to(target)
        os.replace(link, live)
        start.write_bytes(prepared)
        subprocess.run(['systemctl', 'start', 'next-ai-commerce'], check=True)
        health()
        after_pid = read(['systemctl', 'show', 'next-ai-commerce', '-p', 'MainPID', '--value'])
        after = environment(after_pid)
        if (sha(live) != plan['packageSha256'] or policy(after) != plan['policySha256']
                or after.get('APP_BUILD_VERSION') != plan['version'] or schema_identity() != plan['schemaHistorySha256']):
            raise ValueError('Post-install artifact, schema or policy differs')
        subprocess.run(['curl', '-fsS', '--max-time', '15', 'https://app.nextaicommerce.com/login'],
                       check=True, stdout=subprocess.DEVNULL)
        receipt = {'version': plan['version'], 'revision': plan['revision'], 'sha256': sha(live),
                   'policyPreserved': True, 'schemaUnchanged': True, 'pid': after_pid, 'recovery': str(evidence)}
        (evidence / 'completion.json').write_text(json.dumps(receipt))
        print(json.dumps(receipt))
    except BaseException:
        if stopped:
            subprocess.run(['systemctl', 'stop', 'next-ai-commerce'], check=True)
            start.write_bytes(original_start)
            link = APP / 'next-ai-commerce.jar.promotion-rollback'
            if link.exists() or link.is_symlink():
                raise ValueError('Unexpected recovery link; manual recovery required')
            link.symlink_to(rollback)
            os.replace(link, live)
            subprocess.run(['systemctl', 'start', 'next-ai-commerce'], check=True)
            health()
            if policy(environment(read(['systemctl', 'show', 'next-ai-commerce', '-p', 'MainPID', '--value']))) != plan['policySha256']:
                raise ValueError('Recovery policy changed; investigate')
            print('Compatible application/configuration restored; recovery retained; no database restore')
        raise


if __name__ == '__main__':
    main()
