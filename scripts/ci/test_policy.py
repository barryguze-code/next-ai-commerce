#!/usr/bin/env python3
"""Focused workflow/provenance/recovery contracts; no application/database tests."""
import copy
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
import zipfile

import validation as v
import promote_application as server
import promote


class SourceProofTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        subprocess.run(['git', 'init', '-q'], check=True)
        for name in ['src/main/java/Main.java', 'src/main/resources/app.css', 'src/test/java/Test.java',
                     'src/test/js/test.cjs', 'pom.xml', 'package-lock.json', '.github/workflows/ci.yml',
                     '.github/workflows/deploy-production.yml', 'scripts/ci/validation.py']:
            self.write(name, 'original')
        self.original = self.commit()
        self.env = {'runner': 'ubuntu-24.04', 'ImageVersion': 'exact-image', 'jdk': 'exact-java'}

    def tearDown(self):
        os.chdir(self.previous)
        self.temp.cleanup()

    def write(self, name, value):
        p = Path(name)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(value)

    def commit(self):
        subprocess.run(['git', 'add', '.'], check=True)
        subprocess.run(['git', '-c', 'user.name=CI Policy Test', '-c', 'user.email=ci@example.invalid',
                        'commit', '-qm', 'fixture'], check=True)
        return v.git('rev-parse', 'HEAD').decode().strip()

    def plan(self, revision=None):
        revision = revision or self.original
        return {'revision': revision, 'keys': {k: v.fingerprint(revision, k, self.env) for k in v.JOBS},
                'environments': {k: self.env for k in v.JOBS}}

    def result(self, kind='java'):
        return {'schema': 1, 'kind': kind, 'key': self.plan()['keys'][kind], 'source_revision': self.original,
                'run_id': 123, 'run_attempt': 1, 'job': v.JOBS[kind], 'environment': self.env,
                'payloads': {'application.jar': v.sha(b'jar')} if kind == 'java' else {}}

    def api(self, conclusion='success', fork=False, event='push', overall='failure'):
        outer = self
        class API:
            repository = 'owner/repository'
            def get(self, path):
                if '/jobs?' in path:
                    return {'jobs': [{'name': name, 'conclusion': conclusion, 'run_attempt': 1,
                                     'started_at': '2026-11-19T00:00:00Z',
                                     'completed_at': '2026-11-19T00:10:00Z'} for name in v.JOBS.values()]}
                return {'repository': {'full_name': self.repository},
                        'head_repository': {'full_name': 'attacker/fork' if fork else self.repository},
                        'head_sha': outer.original, 'path': v.WORKFLOW, 'event': event, 'conclusion': overall}
        return API()

    def test_browser_test_only_change_reuses_java_and_fixtures_but_not_browser(self):
        before = self.plan()
        self.write('src/test/js/test.cjs', 'wait for actual response')
        after = self.plan(self.commit())
        self.assertEqual(before['keys']['java'], after['keys']['java'])
        self.assertEqual(before['keys']['fixtures'], after['keys']['fixtures'])
        self.assertNotEqual(before['keys']['browser'], after['keys']['browser'])
        # The original successful Java job remains useful even if its browser job failed.
        v.validate_proof(self.result(), 'java', after, self.api(overall='failure'))

    def test_every_application_build_dependency_and_workflow_change_invalidates(self):
        before = self.plan()
        for file in ['src/main/java/Main.java', 'src/main/resources/app.css', 'src/test/java/Test.java',
                     'pom.xml', 'package-lock.json', '.github/workflows/ci.yml',
                     '.github/workflows/deploy-production.yml', 'scripts/ci/validation.py']:
            with self.subTest(file=file):
                self.write(file, 'changed')
                after = self.plan(self.commit())
                for kind in v.JOBS:
                    self.assertNotEqual(before['keys'][kind], after['keys'][kind])
                self.write(file, 'original')
                self.commit()

    def test_runner_and_prepared_image_changes_invalidate_results(self):
        key = v.fingerprint(self.original, 'browser', self.env)
        for field in ['ImageVersion', 'jdk', 'image', 'playwright', 'node']:
            self.assertNotEqual(key, v.fingerprint(self.original, 'browser', {**self.env, field: 'changed'}))

    def test_failed_original_job_never_becomes_reuse(self):
        for conclusion in ['failure', 'cancelled', 'skipped', None]:
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                v.validate_proof(self.result(), 'java', self.plan(), self.api(conclusion=conclusion))

    def test_fork_and_other_run_pull_request_proofs_are_rejected(self):
        for api in [self.api(fork=True), self.api(event='pull_request')]:
            with self.assertRaises(ValueError):
                v.validate_proof(self.result(), 'java', self.plan(), api)

    def test_current_pull_request_can_verify_itself_but_cannot_promote(self):
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_RUN_ID': '123'}):
            v.validate_proof(self.result(), 'java', self.plan(), self.api(fork=True, event='pull_request'))
        with self.assertRaises(ValueError):
            promote.prepare(123, self.original, 'a' * 64, Path('package'), self.api(event='pull_request', overall='success'))

    def test_forged_matching_manifest_does_not_substitute_original_source_inputs(self):
        self.write('src/main/java/Main.java', 'different application')
        plan = self.plan(self.commit())
        forged = {**self.result(), 'key': plan['keys']['java']}
        with self.assertRaisesRegex(ValueError, 'Original source inputs'):
            v.validate_proof(forged, 'java', plan, self.api())

    def test_changed_artifact_payload_or_extra_file_is_rejected(self):
        directory = Path('result')
        directory.mkdir()
        (directory / 'result.json').write_text(json.dumps(self.result()))
        (directory / 'application.jar').write_bytes(b'jar')
        v.validate_result(directory, 'java', self.plan(), self.api())
        (directory / 'application.jar').write_bytes(b'other revision')
        with self.assertRaisesRegex(ValueError, 'digest'):
            v.validate_result(directory, 'java', self.plan(), self.api())
        (directory / 'application.jar').write_bytes(b'jar')
        (directory / 'extra').write_text('unexpected')
        with self.assertRaisesRegex(ValueError, 'payload set'):
            v.validate_result(directory, 'java', self.plan(), self.api())

    def test_forged_cache_artifact_cannot_claim_another_successful_run(self):
        directory = Path('result')
        directory.mkdir()
        (directory / 'result.json').write_text(json.dumps(self.result()))
        (directory / 'application.jar').write_bytes(b'jar')
        valid = {'created_at': '2026-11-19T00:05:00Z',
                 'workflow_run': {'id': 123, 'head_sha': self.original, 'repository_id': 1, 'head_repository_id': 1}}
        v.validate_result(directory, 'java', self.plan(), self.api(), valid)
        for field, value in [('id', 456), ('head_sha', 'a' * 40), ('head_repository_id', 2)]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'producer'):
                v.validate_result(directory, 'java', self.plan(), self.api(),
                                  {**valid, 'workflow_run': {**valid['workflow_run'], field: value}})
        with self.assertRaisesRegex(ValueError, 'job attempt'):
            v.validate_result(directory, 'java', self.plan(), self.api(),
                              {**valid, 'created_at': '2026-11-18T00:05:00Z'})

    def test_altered_workflow_origin_and_retried_job_attempt_are_rejected(self):
        result = self.result()
        result['run_attempt'] = 2
        with self.assertRaisesRegex(ValueError, 'did not pass'):
            v.validate_proof(result, 'java', self.plan(), self.api())
        result = self.result()
        result['source_revision'] = 'b' * 40
        with self.assertRaisesRegex(ValueError, 'Untrusted'):
            v.validate_proof(result, 'java', self.plan(), self.api())


class FixtureTests(unittest.TestCase):
    def test_explicit_bundle_roundtrip_and_missing_prerequisite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            directory = root / 'input'
            directory.mkdir()
            (directory / 'one.html').write_text('real rendered prerequisite')
            with patch.object(v, 'FIXTURES', {'workspace/fixtures': directory}), \
                 patch.object(v, 'REQUIRED_FIXTURES', {'workspace/fixtures/one.html'}):
                v.fixture_bundle(root / 'bundle.tar.gz')
                (directory / 'one.html').unlink()
                v.restore_fixtures(root / 'bundle.tar.gz')
                self.assertEqual((directory / 'one.html').read_text(), 'real rendered prerequisite')
                with patch.object(v, 'REQUIRED_FIXTURES', {'workspace/fixtures/missing.html'}), self.assertRaises(ValueError):
                    v.fixture_bundle(root / 'missing.tar.gz')

    def test_unsafe_tar_paths_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, is_link in [('workspace/fixtures/../outside.html', False),
                                  ('/tmp/outside.html', False), ('workspace/fixtures/link.html', True)]:
                with self.subTest(name=name):
                    archive = root / 'bad.tar.gz'
                    with tarfile.open(archive, 'w:gz') as tar:
                        item = tarfile.TarInfo(name)
                        if is_link:
                            item.type = tarfile.SYMTYPE
                            item.linkname = '/etc/passwd'
                        tar.addfile(item, io.BytesIO())
                    with patch.object(v, 'FIXTURES', {'workspace/fixtures': root / 'output'}), \
                         patch.object(v, 'REQUIRED_FIXTURES', set()), self.assertRaises(ValueError):
                        v.restore_fixtures(archive)


class ArtifactTransportTests(unittest.TestCase):
    def download(self, names, bad_digest=False):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            for name in names:
                archive.writestr(name, '{}')
        payload = data.getvalue()
        class Redirect:
            def open(self, request, **kwargs):
                if not request.full_url.startswith('https://api.github.com/repos/owner/repository/'):
                    raise AssertionError('Token-bearing request left GitHub API')
                raise urllib.error.HTTPError(request.full_url, 302, '', {'Location': 'https://signed.example/artifact'}, None)
        def signed(url, **kwargs):
            self.assertIsInstance(url, str, 'Signed download must have no Authorization request headers')
            self.assertEqual(url, 'https://signed.example/artifact')
            return io.BytesIO(payload)
        with tempfile.TemporaryDirectory() as tmp, patch.object(v.urllib.request, 'build_opener', return_value=Redirect()), \
             patch.object(v.urllib.request, 'urlopen', side_effect=signed):
            v.GitHub('owner/repository', 'fixture-token').download(
                {'id': 1, 'digest': 'sha256:' + ('a' * 64 if bad_digest else v.sha(payload))}, Path(tmp))

    def test_signed_download_drops_authorization_and_checks_archive_digest(self):
        self.download(['result.json'])
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            self.download(['result.json'], bad_digest=True)

    def test_nested_or_excess_artifact_members_cannot_escape_result_directory(self):
        for names in [['../outside'], ['/absolute'], ['dir/result.json'], ['one', 'two', 'three', 'four', 'five']]:
            with self.subTest(names=names), self.assertRaises(ValueError):
                self.download(names)


class PreparedRuntimeTests(unittest.TestCase):
    def test_chrome_companion_content_changes_runtime_identity(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(v.subprocess, 'check_output', return_value='Google Chrome fixture'):
            root = Path(tmp)
            (root / 'chrome').write_bytes(b'fixture executable')
            (root / 'resources.pak').write_bytes(b'first')
            first = v.chrome_identity(root)
            (root / 'resources.pak').write_bytes(b'changed')
            self.assertNotEqual(first, v.chrome_identity(root))
            (root / 'chrome').unlink()
            with self.assertRaisesRegex(ValueError, 'missing branded'):
                v.chrome_identity(root)


class ServerPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'releases').mkdir()
        (self.root / 'bin').mkdir()
        self.app_patch = patch.object(server, 'APP', self.root)
        self.app_patch.start()
        self.original = b'export APP_BUILD_VERSION=1.14.28\n\nexport AMAZON_WRITE_ENABLED=true\nexec java -jar app.jar\n'
        (self.root / 'bin/start').write_bytes(self.original)
        self.jar(self.root / 'releases/previous.jar')
        (self.root / 'next-ai-commerce.jar').symlink_to(self.root / 'releases/previous.jar')
        self.values = {'APP_BUILD_VERSION': '1.14.28', 'AMAZON_WRITE_ENABLED': 'true',
                       'SHOPIFY_ORDER_POLLING_ENABLED': 'true', 'MAIL_ENABLED': 'true',
                       'APP_FBA_SHIPMENT_WRITE_ENABLED': 'false', 'APP_FBA_CHARGE_WRITE_ENABLED': 'false',
                       'SPRING_FLYWAY_ENABLED': 'false', 'DB_PASSWORD': 'fixture-secret'}
        self.plan = {'schema': 1, 'revision': 'a' * 40, 'version': '1.14.29', 'packageSha256': '',
                     'baselineJarSha256': server.sha(self.root / 'next-ai-commerce.jar'),
                     'baselineLauncherSha256': server.sha(self.root / 'bin/start'),
                     'policySha256': server.policy(self.values), 'schemaHistorySha256': 'c' * 64,
                     'rollbackJar': str(self.root / 'releases/previous.jar'),
                     'rollbackSha256': server.sha(self.root / 'releases/previous.jar'),
                     'rollbackSchemaHistorySha256': 'c' * 64,
                     'operationSha256': server.sha(Path(server.__file__))}
        self.input = self.root / 'input.jar'
        self.jar(self.input, application=b'new verified app')
        self.plan['packageSha256'] = server.sha(self.input)
        self.plan['packageBytes'] = self.input.stat().st_size
        self.calls = []

    def tearDown(self):
        self.app_patch.stop()
        self.temp.cleanup()

    def jar(self, path, application=b'app', extra=False):
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('BOOT-INF/classes/App.class', application)
            jar.writestr('BOOT-INF/classes/db/migration/V1__one.sql', 'unchanged')
            if extra:
                jar.writestr('BOOT-INF/classes/db/migration/V2__two.sql', 'requires separate operation')

    def execute(self, fail_health=False, disk=100 * 1024**3):
        path = self.root / 'plan.json'
        path.write_text(json.dumps(self.plan))
        def read(args):
            if args[:2] == ['systemctl', 'is-active']:
                return 'active'
            if args[0] == 'systemctl':
                return '90000000'
            return str(os.getuid() if '-u' in args else os.getgid())
        def run(args, **kwargs):
            self.calls.append(args)
            if '-o' in args:
                shutil.copy2(self.input, args[args.index('-o') + 1])
            if args[:2] == ['systemctl', 'start']:
                self.values['APP_BUILD_VERSION'] = ('1.14.29' if (self.root / 'bin/start').read_bytes() != self.original else '1.14.28')
            return subprocess.CompletedProcess(args, 0)
        def download(url, destination, expected_bytes):
            self.calls.append(['download', expected_bytes])
            self.assertEqual(expected_bytes, self.input.stat().st_size)
            shutil.copy2(self.input, destination)
        health_calls = []
        def health():
            health_calls.append(True)
            if fail_health and len(health_calls) == 1:
                raise ValueError('Startup failed')
        with contextlib.redirect_stdout(io.StringIO()), \
             patch.object(sys, 'argv', ['promote', str(path)]), patch.object(server, 'read', side_effect=read), \
             patch.object(server, 'environment', side_effect=lambda pid: dict(self.values)), \
             patch.object(server, 'schema_identity', return_value='c' * 64), \
             patch.object(server.subprocess, 'run', side_effect=run), \
             patch.object(server, 'download', side_effect=download), \
             patch.object(server, 'backup', side_effect=lambda p: p.write_bytes(b'protected recovery')), \
             patch.object(server, 'health', side_effect=health), \
             patch.object(server.shutil, 'disk_usage', return_value=shutil._ntuple_diskusage(disk * 2, disk, disk)):
            server.main()

    def test_application_only_promotion_changes_version_and_preserves_all_policy(self):
        before = server.policy(self.values)
        self.execute()
        self.assertEqual(server.policy(self.values), before)
        self.assertEqual((self.root / 'bin/start').read_bytes(), self.original.replace(b'1.14.28', b'1.14.29'))
        self.assertEqual(server.sha(self.root / 'next-ai-commerce.jar'), self.plan['packageSha256'])
        self.assertEqual([c[:2] for c in self.calls if c[0] == 'systemctl'], [['systemctl', 'stop'], ['systemctl', 'start']])

    def test_migration_change_is_blocked_before_service_stop(self):
        self.jar(self.input, extra=True)
        self.plan['packageSha256'] = server.sha(self.input)
        self.plan['packageBytes'] = self.input.stat().st_size
        with self.assertRaisesRegex(ValueError, 'schema operation'):
            self.execute()
        self.assertFalse(any(c[0] == 'systemctl' for c in self.calls))

    def test_policy_drift_is_blocked_without_resetting_flags(self):
        self.values['AMAZON_WRITE_ENABLED'] = 'false'
        with self.assertRaisesRegex(ValueError, 'baseline'):
            self.execute()
        self.assertEqual(self.values['AMAZON_WRITE_ENABLED'], 'false')
        self.assertEqual(self.calls, [])

    def test_disk_budget_failure_never_stops_service_or_cleans_recovery(self):
        with self.assertRaisesRegex(ValueError, 'disk budget'):
            self.execute(disk=1024)
        self.assertEqual(self.calls, [], 'Low disk must prevent any download, backup or service operation')
        self.assertFalse(list((self.root / 'releases').glob('.promotion-*')))
        self.assertTrue((self.root / 'releases/previous.jar').exists())

    def test_failed_startup_restores_exact_compatible_artifact_and_configuration(self):
        with self.assertRaisesRegex(ValueError, 'Startup failed'):
            self.execute(fail_health=True)
        self.assertEqual((self.root / 'bin/start').read_bytes(), self.original)
        self.assertEqual(server.sha(self.root / 'next-ai-commerce.jar'), self.plan['rollbackSha256'])
        self.assertFalse(any('pg_restore' in c or 'migrate' in c for c in self.calls))
        self.assertTrue(list((self.root / 'releases').glob('.promotion-*/database.dump')))

    def test_incompatible_recovery_and_unreviewed_plan_fields_are_rejected(self):
        for key, value in [('rollbackSchemaHistorySha256', 'd' * 64), ('rollbackSha256', 'f' * 64), ('rollbackJar', '/etc/passwd'),
                           ('activateAmazon', True), ('revision', 'branch-name')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                server.validate_plan({**self.plan, key: value})
        for size in [0, -1, True, 513 * 1024**2]:
            with self.subTest(size=size), self.assertRaisesRegex(ValueError, 'size'):
                server.validate_plan({**self.plan, 'packageBytes': size})

    def test_every_existing_integration_flag_remains_part_of_policy_identity(self):
        for key in ['MAIL_ENABLED', 'AMAZON_WRITE_ENABLED', 'SHOPIFY_ORDER_POLLING_ENABLED',
                    'APP_FBA_SHIPMENT_WRITE_ENABLED', 'APP_FBA_CHARGE_WRITE_ENABLED', 'DB_PASSWORD']:
            self.assertNotEqual(server.policy(self.values), server.policy({**self.values, key: 'changed'}))
        self.assertEqual(server.policy(self.values), server.policy({**self.values, 'APP_BUILD_VERSION': '1.14.29'}))


class DownloadBoundTests(unittest.TestCase):
    def download(self, payload, expected, length=None):
        response = io.BytesIO(payload)
        response.headers = {} if length is None else {'Content-Length': str(length)}
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(server.urllib.request, 'build_opener') as opener:
            opener.return_value.open.return_value = response
            destination = Path(temp) / 'candidate.jar'
            try:
                server.download('https://example.invalid/package.jar', destination, expected)
            finally:
                self.assertLessEqual(destination.stat().st_size if destination.exists() else 0, expected)
            return destination.read_bytes()

    def test_exact_size_stream_succeeds_without_rebuilding(self):
        self.assertEqual(self.download(b'jar', 3, 3), b'jar')

    def test_oversized_advertised_or_streamed_and_incomplete_artifacts_are_rejected(self):
        for payload, expected, length in [(b'large', 3, 5), (b'large', 3, None), (b'no', 3, None)]:
            with self.subTest(length=length), self.assertRaises(ValueError):
                self.download(payload, expected, length)


class WorkflowContractTests(unittest.TestCase):
    def test_parallel_graph_and_fail_closed_aggregate_keep_all_existing_tests(self):
        root = Path(__file__).resolve().parents[2]
        ci = (root / '.github/workflows/ci.yml').read_text()
        self.assertIn('name: Java verification\n    needs: plan', ci)
        self.assertIn('name: Browser fixtures\n    needs: plan', ci)
        self.assertIn('name: Browser verification\n    needs: [plan, fixtures]', ci)
        self.assertIn('needs: [plan, java, fixtures, browser]', ci)
        self.assertIn('test "$PLAN" = success', ci)
        self.assertIn('mvn --batch-mode verify', ci)
        self.assertIn('-Dtest=' + v.FIXTURE_TESTS, ci)
        self.assertIn('cancel-in-progress: true', ci)
        self.assertIn('cancel-in-progress: false', (root / '.github/workflows/deploy-production.yml').read_text())
        self.assertIn('npm test', (root / 'scripts/ci/browser.sh').read_text())
        self.assertIn("channel:'chrome'", (root / 'scripts/ci/browser-runtime.cjs').read_text())
        self.assertIn('-v /opt/google/chrome:/opt/google/chrome:ro', ci)
        for directory in ['/tmp/marketplace-organization-review', '/tmp/sku008-010-review', '/tmp/variation-trial-operator-review']:
            self.assertIn('-v ' + directory + ':' + directory + ':ro', ci)

    def test_promotion_is_manual_protected_no_build_retest_or_legacy_flag_reset(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/deploy-production.yml').read_text()
        self.assertIn('workflow_dispatch:', workflow)
        self.assertNotIn('  push:', workflow)
        self.assertIn('environment: production', workflow)
        self.assertIn('github.event.repository.default_branch', workflow)
        self.assertIn('vars.PRODUCTION_MANIFEST_SHA256', workflow)
        for forbidden in ['mvn ', 'npm test', 'production-release.sh', 'release_version=1.14.9', 'npx playwright install']:
            self.assertNotIn(forbidden, workflow)


if __name__ == '__main__':
    unittest.main()
