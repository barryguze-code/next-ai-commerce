#!/usr/bin/env python3
"""Verify a successful CI package, prepare existing protected SSM operation inputs."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from validation import GitHub, JOBS, WORKFLOW, canonical, checked_revision, fingerprint, sha, validate_proof
from promote_application import validate_plan


def prepare(run_id, revision, expected_sha, output, api):
    checked_revision(revision)
    if not re.fullmatch(r'[0-9a-f]{64}', expected_sha):
        raise ValueError('Expected exact verified package digest')
    run = api.get(f'/repos/{api.repository}/actions/runs/{int(run_id)}')
    if (run['conclusion'] != 'success' or run['head_sha'] != revision or run['path'] != WORKFLOW
            or run['event'] not in ('push', 'workflow_dispatch')
            or run['repository']['full_name'] != api.repository or run['head_repository']['full_name'] != api.repository):
        raise ValueError('Only a successful same-repository normal CI run can authorize promotion')
    jobs = api.get(f'/repos/{api.repository}/actions/runs/{int(run_id)}/jobs?filter=latest&per_page=100')['jobs']
    if not any(j['name'] == 'test' and j['conclusion'] == 'success' for j in jobs):
        raise ValueError('Verified package aggregate did not pass')
    artifacts = api.get(f'/repos/{api.repository}/actions/runs/{int(run_id)}/artifacts?per_page=100')['artifacts']
    matches = [a for a in artifacts if a['name'] == 'verified-release-' + revision and not a['expired']]
    if len(matches) != 1:
        raise ValueError('Exact verified release artifact is missing or ambiguous')
    api.download(matches[0], output)
    verification = json.loads((output / 'verification.json').read_text())
    plan = verification['plan']
    if (verification['schema'] != 1 or verification['validated_revision'] != revision
            or plan['revision'] != revision or verification['run_id'] != int(run_id)
            or verification['sha256'] != expected_sha):
        raise ValueError('CI package identity does not match the approved promotion')
    for kind in JOBS:
        if fingerprint(revision, kind, plan['environments'][kind]) != plan['keys'][kind]:
            raise ValueError('Verified revision inputs changed')
        validate_proof(verification['results'][kind], kind, plan, api)
    java = verification['results']['java']
    name = 'next-ai-commerce-' + verification['version'] + '-' + revision + '.jar'
    if ({p.name for p in output.iterdir()} != {name, name + '.sha256', 'verification.json'}
            or verification['artifact_revision'] != java['source_revision']
            or java['payloads'] != {'application.jar': expected_sha}
            or verification['bytes'] != (output / name).stat().st_size
            or sha((output / name).read_bytes()) != expected_sha
            or (output / (name + '.sha256')).read_text() != expected_sha + '  ' + name + '\n'):
        raise ValueError('Exact artifact payload/provenance mismatch')
    with zipfile.ZipFile(output / name) as jar:
        properties = jar.read('META-INF/maven/com.nextaicommerce/next-ai-commerce/pom.properties').decode()
        if not re.search(r'(?m)^version=' + re.escape(verification['version']) + r'$', properties):
            raise ValueError('Packaged application version differs')
    return verification


def main():
    api = GitHub()
    revision = checked_revision(os.environ['PROMOTION_REVISION'])
    # Fetch objects for fingerprinting only; never check out or execute the target
    # run's scripts. These verification tools come from the protected workflow ref.
    subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', revision], check=True)
    output = Path('.promotion-package')
    verification = prepare(os.environ['SOURCE_RUN'], revision, os.environ['PACKAGE_SHA256'], output, api)
    plan = json.loads(os.environ['RELEASE_PLAN'])
    validate_plan(plan)
    if sha(canonical(plan)) != os.environ['REVIEWED_PLAN_SHA256']:
        raise ValueError('Baseline/recovery plan differs from the single approved release plan')
    if (plan['revision'] != revision or plan['version'] != verification['version']
            or plan['packageSha256'] != verification['sha256'] or plan['packageBytes'] != verification['bytes']):
        raise ValueError('Reviewed plan targets another artifact')
    operation = Path('scripts/ci/promote_application.py').read_bytes()
    if sha(operation) != plan['operationSha256']:
        raise ValueError('Protected operation code differs from the reviewed plan')
    # Only application code, public provenance and non-secret reviewed hashes are
    # published. No database, credentials, environment dumps or customer files.
    asset = output / ('next-ai-commerce-' + plan['version'] + '-' + revision + '.jar')
    tag = 'build-' + revision
    existing = subprocess.run(['gh', 'release', 'view', tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if existing.returncode:
        subprocess.run(['gh', 'release', 'create', tag, str(asset), str(asset) + '.sha256',
                        str(output / 'verification.json'), '--target', revision, '--prerelease',
                        '--title', 'Verified deployment package ' + revision[:12],
                        '--notes', 'Exact CI artifact; production delivery tracked separately.'], check=True)
    check = Path('.promotion-published-check')
    subprocess.run(['gh', 'release', 'download', tag, '--pattern', asset.name, '--dir', str(check)], check=True)
    if sha((check / asset.name).read_bytes()) != plan['packageSha256']:
        raise ValueError('Published package does not match verified CI bytes')
    code = ("import base64,pathlib,tempfile,subprocess\n"
            "p=pathlib.Path(tempfile.mkdtemp(prefix='.promotion-tools-',dir='/opt/next-ai-commerce/releases'))\n"
            "p.chmod(0o700)\n"
            "(p/'promote.py').write_bytes(base64.b64decode('" + base64.b64encode(operation).decode() + "'))\n"
            "(p/'plan.json').write_bytes(base64.b64decode('" + base64.b64encode(canonical(plan)).decode() + "'))\n"
            "subprocess.run(['python3',str(p/'promote.py'),str(p/'plan.json')],check=True)\n")
    Path('.promotion-command.json').write_text(json.dumps({'commands': ["umask 077\npython3 - <<'PROMOTE'\n" + code + 'PROMOTE'],
                                                        'executionTimeout': ['1200']}))


if __name__ == '__main__':
    main()
