#!/usr/bin/env python3
"""Input-bound successful-job reuse and exact-package promotion. Never deploys."""
import argparse
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

SCHEMA = 1
WORKFLOW = '.github/workflows/ci.yml'
JAVA = '21.0.13+9'
RUNNER = 'ubuntu-24.04'
JOBS = {'java': 'Java verification', 'fixtures': 'Browser fixtures', 'browser': 'Browser verification'}
PAYLOADS = {'java': {'application.jar'}, 'fixtures': {'fixtures.tar.gz'}, 'browser': set()}
FIXTURE_TESTS = ('TemplateRenderingTest,CollaborationBoardTemplateTest,'
                 'MarketplaceOrganizationTemplateTest,MarketplaceSafetyTemplateTest,FbaTemplateTest')
FIXTURES = {
    'workspace/.local/browser-fixtures': Path('.local/browser-fixtures'),
    'workspace/.local/fba-preview': Path('.local/fba-preview'),
    'tmp/marketplace-organization-review': Path('/tmp/marketplace-organization-review'),
    'tmp/sku008-010-review': Path('/tmp/sku008-010-review'),
    'tmp/variation-trial-operator-review': Path('/tmp/variation-trial-operator-review'),
}
REQUIRED_FIXTURES = {
    'workspace/.local/browser-fixtures/accounts-final.html',
    'workspace/.local/browser-fixtures/connections-final.html',
    'workspace/.local/browser-fixtures/global-catalogue.html',
    'workspace/.local/browser-fixtures/collaboration-board.html',
    'workspace/.local/browser-fixtures/personal-collaboration.html',
    'workspace/.local/browser-fixtures/shared-confirm.html',
    'workspace/.local/fba-preview/stock.html',
    'tmp/marketplace-organization-review/marketplace.html',
    'tmp/marketplace-organization-review/empty-parent.html',
    'tmp/marketplace-organization-review/expired.html',
    'tmp/sku008-010-review/marketplace.html',
    'tmp/variation-trial-operator-review/operator.html',
}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args])


def checked_revision(value):
    if not re.fullmatch(r'[0-9a-f]{40}', value):
        raise ValueError('Expected immutable 40-character revision')
    return value


def fingerprint(revision, kind, environment):
    """Conservative complete tracked tree; only browser test files are not Java inputs.

    Git tree records bind paths, modes and content object IDs. All workflows, CI
    tools, Java tests/resources, build files and application assets are included.
    No changed workflow can claim an old result by merely forging its manifest.
    """
    checked_revision(revision)
    records = git('ls-tree', '-r', '-z', '--full-tree', revision).split(b'\0')
    selected = []
    for record in records:
        if not record:
            continue
        metadata, name = record.split(b'\t', 1)
        if metadata.split()[1] != b'blob':
            raise ValueError('Submodule inputs require an explicit validation contract')
        if kind != 'browser' and name.startswith(b'src/test/js/'):
            continue
        selected.append(record)
    return sha(canonical({'schema': SCHEMA, 'kind': kind, 'environment': environment,
                          'tree': sha(b'\0'.join(selected))}))


def browser_image():
    package = json.loads(Path('package.json').read_text())
    lock = json.loads(Path('package-lock.json').read_text())
    version = lock['packages']['node_modules/playwright']['version']
    if package['devDependencies']['playwright'] != version or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Prepared image requires an exact locked Playwright version')
    request = urllib.request.Request(
        f'https://mcr.microsoft.com/v2/playwright/manifests/v{version}-noble',
        headers={'Accept': 'application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
        digest = response.headers.get('Docker-Content-Digest', '')
    if digest != 'sha256:' + sha(data):
        raise ValueError('Prepared browser manifest digest was not confirmed')
    index = json.loads(data)
    if not any(x.get('platform', {}).get('os') == 'linux' and x.get('platform', {}).get('architecture') == 'amd64'
               for x in index.get('manifests', [])):
        raise ValueError('Prepared browser image has no Linux amd64 runtime')
    return version, 'mcr.microsoft.com/playwright@' + digest


def chrome_identity(root=Path('/opt/google/chrome')):
    binary = root / 'chrome'
    if not binary.is_file():
        raise ValueError('Prepared runner is missing branded Google Chrome')
    files = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            files.append([str(path.relative_to(root)), 'link', os.readlink(path)])
        elif path.is_file():
            digest = hashlib.sha256()
            with path.open('rb') as source:
                while block := source.read(1024**2):
                    digest.update(block)
            files.append([str(path.relative_to(root)), path.stat().st_mode & 0o777, digest.hexdigest()])
    return {'version': subprocess.check_output([str(binary), '--version'], text=True).strip(),
            'treeSha256': sha(canonical(files))}


def job_environment(kind, plan):
    required = ['ImageOS', 'ImageVersion', 'RUNNER_ARCH']
    if any(not os.environ.get(k) for k in required):
        raise ValueError('Runner image identity is required for result reuse')
    host = {k: os.environ[k] for k in required}
    if host['RUNNER_ARCH'] != 'X64':
        raise ValueError('Only the reviewed x64 runner contract is supported')
    if kind == 'browser':
        return {'runner': RUNNER, **host, **plan['browser'], 'chrome': chrome_identity()}
    java = subprocess.check_output(['java', '-version'], text=True, stderr=subprocess.STDOUT)
    if JAVA not in java:
        raise ValueError('Verification job is not using the pinned Temurin runtime')
    return {'runner': RUNNER, **host, 'jdk': 'temurin-' + JAVA, 'jdkRuntime': java,
            'maven': subprocess.check_output(['mvn', '--version'], text=True).splitlines()[0]}


def assert_host(plan, kind):
    for key in ('ImageOS', 'ImageVersion', 'RUNNER_ARCH'):
        if os.environ.get(key) != plan['environments'][kind][key]:
            raise ValueError('Runner changed after planning; invalidate reuse and retry')
    if kind == 'browser' and chrome_identity() != plan['environments'][kind]['chrome']:
        raise ValueError('Prepared branded Chrome runtime changed after planning')


def merge_contracts(plan, contracts):
    for contract in contracts:
        if contract['schema'] != SCHEMA or contract['revision'] != plan['revision']:
            raise ValueError('Verification contracts target different source inputs')
        for kind, key in contract['keys'].items():
            if kind not in JOBS or fingerprint(plan['revision'], kind, contract['environments'][kind]) != key:
                raise ValueError('Verification contract key differs from actual inputs')
            if kind in plan['keys'] and (plan['keys'][kind] != key or plan['environments'][kind] != contract['environments'][kind]):
                raise ValueError('Conflicting verification environments for the same job')
            plan['keys'][kind] = key
            plan['environments'][kind] = contract['environments'][kind]
    return plan


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, repository=None, token=None):
        self.repository = repository or os.environ['GITHUB_REPOSITORY']
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', self.repository):
            raise ValueError('Invalid repository')
        self.token = token or os.environ['GH_TOKEN']

    def request(self, path):
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('API path must stay on GitHub')
        return urllib.request.Request('https://api.github.com' + path,
                                      headers={'Authorization': 'Bearer ' + self.token,
                                               'Accept': 'application/vnd.github+json',
                                               'X-GitHub-Api-Version': '2022-11-28'})

    def get(self, path):
        with urllib.request.build_opener(NoRedirect).open(self.request(path), timeout=30) as response:
            return json.load(response)

    def download(self, artifact, destination):
        # Authorization is never forwarded to the signed external download URL.
        try:
            urllib.request.build_opener(NoRedirect).open(
                self.request(f'/repos/{self.repository}/actions/artifacts/{int(artifact["id"])}/zip'), timeout=30)
        except urllib.error.HTTPError as response:
            if response.code != 302:
                raise
            location = response.headers['Location']
        else:
            raise ValueError('Expected signed artifact download redirect')
        if urllib.parse.urlparse(location).scheme != 'https':
            raise ValueError('Artifact transport must use HTTPS')
        digest = hashlib.sha256()
        with urllib.request.urlopen(location, timeout=90) as response, tempfile.TemporaryFile() as archive:
            size = 0
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > 512 * 1024**2:
                    raise ValueError('Unexpected artifact size')
                digest.update(block)
                archive.write(block)
            if artifact.get('digest') != 'sha256:' + digest.hexdigest():
                raise ValueError('GitHub artifact archive digest mismatch')
            archive.seek(0)
            with zipfile.ZipFile(archive) as z:
                if len(z.infolist()) > 4 or len({i.filename for i in z.infolist()}) != len(z.infolist()):
                    raise ValueError('Unexpected or duplicate artifact members')
                for item in z.infolist():
                    if PurePosixPath(item.filename).name != item.filename or item.is_dir():
                        raise ValueError('Unexpected nested artifact member')
                    if item.file_size > 256 * 1024**2:
                        raise ValueError('Unexpected payload size')
                destination.mkdir(parents=True, exist_ok=True)
                for item in z.infolist():
                    (destination / item.filename).write_bytes(z.read(item))


def validate_proof(result, kind, plan, api, artifact=None):
    revision = checked_revision(result['source_revision'])
    if result['schema'] != SCHEMA or result['kind'] != kind or result['key'] != plan['keys'][kind]:
        raise ValueError('Result input identity mismatch')
    if result['environment'] != plan['environments'][kind] or result['job'] != JOBS[kind]:
        raise ValueError('Result environment or job mismatch')
    run = api.get(f'/repos/{api.repository}/actions/runs/{int(result["run_id"])}')
    current_pr = (os.environ.get('GITHUB_EVENT_NAME') == 'pull_request'
                  and str(result['run_id']) == os.environ.get('GITHUB_RUN_ID')
                  and run['event'] == 'pull_request' and revision == plan['revision'])
    if (run['repository']['full_name'] != api.repository or run['path'] != WORKFLOW
            or (not current_pr and (run['head_repository']['full_name'] != api.repository
                                    or run['head_sha'] != revision
                                    or run['event'] not in ('push', 'workflow_dispatch')))):
        raise ValueError('Untrusted result producer')
    jobs = api.get(f'/repos/{api.repository}/actions/runs/{int(result["run_id"])}/jobs?filter=all&per_page=100')['jobs']
    # A successful upload step is insufficient: the entire original verification
    # job must finish successfully, even when a different job failed that run.
    passed = [j for j in jobs if j['name'] == JOBS[kind] and j['conclusion'] == 'success'
              and j['run_attempt'] == result['run_attempt']]
    if len(passed) != 1:
        raise ValueError('Original verification job did not pass')
    if artifact is not None:
        timestamp = lambda value: datetime.fromisoformat(value.replace('Z', '+00:00'))
        if not timestamp(passed[0]['started_at']) <= timestamp(artifact['created_at']) <= timestamp(passed[0]['completed_at']):
            raise ValueError('Artifact was not uploaded by the claimed successful job attempt')
    try:
        git('cat-file', '-e', revision + '^{commit}')
    except subprocess.CalledProcessError:
        subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', revision], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if fingerprint(revision, kind, plan['environments'][kind]) != plan['keys'][kind]:
        raise ValueError('Original source inputs do not match the requested result')
    return result


def validate_result(directory, kind, plan, api, artifact=None):
    result = json.loads((directory / 'result.json').read_text())
    if artifact is not None:
        producer = artifact['workflow_run']
        current_pr = (os.environ.get('GITHUB_EVENT_NAME') == 'pull_request'
                      and str(result['run_id']) == os.environ.get('GITHUB_RUN_ID'))
        if (producer['id'] != result['run_id'] or (not current_pr and
                (producer['head_sha'] != result['source_revision']
                 or producer['repository_id'] != producer['head_repository_id']))):
            raise ValueError('Artifact producer differs from the claimed successful job')
    validate_proof(result, kind, plan, api, artifact)
    payloads = result['payloads']
    if set(payloads) != PAYLOADS[kind] or {p.name for p in directory.iterdir()} != set(payloads) | {'result.json'}:
        raise ValueError('Unexpected result payload set')
    for name, digest in payloads.items():
        if sha((directory / name).read_bytes()) != digest:
            raise ValueError('Result payload digest mismatch')
    return result


def reuse(kind, plan, api):
    name = f'validation-{kind}-{plan["keys"][kind]}'
    artifacts = api.get(f'/repos/{api.repository}/actions/artifacts?name={name}&per_page=100')['artifacts']
    for artifact in artifacts:
        if artifact['name'] != name or artifact['expired']:
            continue
        run = artifact.get('workflow_run', {})
        if run.get('head_repository_id') != run.get('repository_id'):
            continue
        try:
            with tempfile.TemporaryDirectory() as tmp:
                folder = Path(tmp)
                api.download(artifact, folder)
                validate_result(folder, kind, plan, api, artifact)
            return str(artifact['id'])
        except (ValueError, KeyError, OSError, subprocess.CalledProcessError):
            print(f'Ignoring invalid {kind} artifact {artifact["id"]}; fresh verification required')
    return ''


def load_result(artifact_id, kind, plan, output, api):
    artifact = api.get(f'/repos/{api.repository}/actions/artifacts/{int(artifact_id)}')
    if artifact['expired'] or artifact['name'] != f'validation-{kind}-{plan["keys"][kind]}':
        raise ValueError('Requested successful result is unavailable or has changed')
    api.download(artifact, output)
    return validate_result(output, kind, plan, api, artifact)


def record(kind, plan, output):
    if {p.name for p in output.iterdir()} != PAYLOADS[kind]:
        raise ValueError('Unexpected fresh verification payloads')
    value = {'schema': SCHEMA, 'kind': kind, 'key': plan['keys'][kind],
             'source_revision': checked_revision(os.environ['GITHUB_SHA']),
             'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
             'job': JOBS[kind], 'environment': plan['environments'][kind],
             'payloads': {name: sha((output / name).read_bytes()) for name in PAYLOADS[kind]}}
    (output / 'result.json').write_bytes(canonical(value))


def fixture_bundle(output):
    files = {}
    for name in REQUIRED_FIXTURES:
        matches = [(prefix, root) for prefix, root in FIXTURES.items() if name.startswith(prefix + '/')]
        if len(matches) != 1:
            raise ValueError('Unregistered browser prerequisite')
        prefix, root = matches[0]
        path = root / name[len(prefix) + 1:]
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing explicit regular browser prerequisite: ' + name)
        files[name] = path
    with tarfile.open(output, 'w:gz') as archive:
        for name, path in sorted(files.items()):
            archive.add(path, arcname=name, recursive=False)


def restore_fixtures(source):
    with tarfile.open(source, 'r:gz') as archive:
        members = archive.getmembers()
        names = {m.name for m in members}
        if not REQUIRED_FIXTURES <= names or len(names) != len(members):
            raise ValueError('Incomplete or duplicate fixture bundle')
        for member in members:
            roots = [(prefix, root) for prefix, root in FIXTURES.items() if member.name.startswith(prefix + '/')]
            if len(roots) != 1 or not member.isfile() or member.size > 5 * 1024**2:
                raise ValueError('Unsafe fixture entry')
            prefix, root = roots[0]
            suffix = member.name[len(prefix) + 1:]
            if PurePosixPath(suffix).name != suffix or not suffix.endswith('.html'):
                raise ValueError('Unsafe fixture path')
            root.mkdir(parents=True, exist_ok=True)
            target = root / suffix
            if target.is_symlink():
                raise ValueError('Unsafe existing fixture target')
            target.write_bytes(archive.extractfile(member).read())


def output(values):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as target:
        for key, value in values.items():
            target.write(f'{key}={value}\n')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['plan', 'job-plan', 'merge', 'host', 'load', 'record', 'fixtures', 'restore', 'compose'])
    parser.add_argument('--plan', default='.ci-plan.json')
    parser.add_argument('--kind', choices=list(JOBS))
    parser.add_argument('--artifact')
    parser.add_argument('--contract', action='append', default=[])
    parser.add_argument('--output', default='.ci-result')
    args = parser.parse_args()
    if args.command == 'plan':
        version, image = browser_image()
        revision = checked_revision(os.environ['GITHUB_SHA'])
        plan = {'schema': SCHEMA, 'revision': revision, 'environments': {}, 'keys': {},
                'browser': {'image': image, 'playwright': version}}
        Path(args.plan).write_bytes(canonical(plan))
        output({'plan': canonical(plan).decode(), 'image': image})
        return
    if args.command == 'fixtures':
        fixture_bundle(Path(args.output))
        return
    if args.command == 'restore':
        restore_fixtures(Path(args.output))
        return
    plan = json.loads(Path(args.plan).read_text())
    if args.command in ('merge', 'job-plan'):
        plan = merge_contracts(plan, [json.loads(value) for value in args.contract])
        if args.command == 'job-plan':
            fixture_identity = None
            if args.kind == 'browser':
                directory = Path('.ci-result/fixtures')
                result = load_result(args.artifact, 'fixtures', plan, directory, GitHub())
                fixture_identity = {'fixtureManifestSha256': sha((directory / 'result.json').read_bytes()),
                                    'fixturePayloadSha256': result['payloads']['fixtures.tar.gz']}
            environment = job_environment(args.kind, plan)
            if fixture_identity is not None:
                environment.update(fixture_identity)
            plan['environments'][args.kind] = environment
            plan['keys'][args.kind] = fingerprint(plan['revision'], args.kind, environment)
            # Runtime identity belongs to this job, not the separate planning VM.
            # GitHub can roll multiple image builds through a single workflow.
            try:
                reused = reuse(args.kind, plan, GitHub()) if os.environ['GITHUB_EVENT_NAME'] != 'pull_request' else ''
            except (OSError, ValueError, KeyError):
                print(f'Prior {args.kind} proof unavailable; running fresh verification')
                reused = ''
            output({'plan': canonical(plan).decode(), 'key': plan['keys'][args.kind], 'reuse': reused})
        Path(args.plan).write_bytes(canonical(plan))
    elif args.command == 'host':
        assert_host(plan, args.kind)
    elif args.command == 'record':
        record(args.kind, plan, Path(args.output))
    elif args.command == 'load':
        load_result(args.artifact, args.kind, plan, Path(args.output), GitHub())
    elif args.command == 'compose':
        root = Path(args.output)
        records = {kind: validate_result(root / kind, kind, plan, GitHub()) for kind in JOBS}
        java = records['java']
        jar = root / 'java/application.jar'
        with zipfile.ZipFile(jar) as z:
            pom = z.read('META-INF/maven/com.nextaicommerce/next-ai-commerce/pom.properties').decode()
            version = re.search(r'(?m)^version=(\d+\.\d+\.\d+)$', pom).group(1)
        folder = root / 'verified'
        folder.mkdir()
        name = f'next-ai-commerce-{version}-{plan["revision"]}.jar'
        (folder / name).write_bytes(jar.read_bytes())
        provenance = {'schema': SCHEMA, 'validated_revision': plan['revision'], 'artifact_revision': java['source_revision'],
                      'version': version, 'sha256': sha(jar.read_bytes()), 'bytes': jar.stat().st_size,
                      'plan': plan, 'results': records,
                      'run_id': int(os.environ['GITHUB_RUN_ID'])}
        (folder / 'verification.json').write_bytes(canonical(provenance))
        (folder / (name + '.sha256')).write_text(provenance['sha256'] + '  ' + name + '\n')
        output({'version': version, 'sha256': provenance['sha256'], 'jar': str(folder / name)})


if __name__ == '__main__':
    main()
