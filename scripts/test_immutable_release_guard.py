#!/usr/bin/env python3
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.immutable_release_guard import MAIN_CLASS, validate_jar, validate_request

VERSION = '26.2.16'
HEAD = 'a' * 40
DESCRIPTOR = f'''name: FluffyMachines
version: {VERSION}
api-version: '1.21.11'
main: io.ncbpfluffybear.fluffymachines.FluffyMachines
'''
CLASS = b'\xca\xfe\xba\xbe\x00\x00\x00\x41'


class ReleaseRequestTest(unittest.TestCase):
    def request(self, **overrides):
        values = dict(event='workflow_dispatch', ref='refs/heads/master', enabled='true',
                      requested=VERSION, expected=VERSION, source=HEAD, current_main=HEAD)
        values.update(overrides)
        validate_request(**values)

    def test_explicit_current_main_numeric_version_is_accepted(self):
        self.request()

    def test_ordinary_push_pull_request_and_tag_events_cannot_publish(self):
        for event in ('push', 'pull_request', 'workflow_run', 'schedule', ''):
            with self.subTest(event=event), self.assertRaises(ValueError):
                self.request(event=event)

    def test_tag_and_feature_branch_dispatches_cannot_publish(self):
        for ref in ('refs/tags/v26.2.16', 'refs/heads/fix/test', 'refs/pull/1/merge', ''):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                self.request(ref=ref)

    def test_absent_or_false_confirmation_cannot_publish(self):
        for enabled in ('false', '', 'True', '1'):
            with self.subTest(enabled=enabled), self.assertRaises(ValueError):
                self.request(enabled=enabled)

    def test_version_mismatch_or_non_numeric_input_is_rejected(self):
        for version in ('26.2.15', 'v26.2.16', '26.2.16-SNAPSHOT', '', '26.2.16\n', '../x'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.request(requested=version)

    def test_stale_or_invalid_source_cannot_publish(self):
        for override in ({'current_main': 'b' * 40}, {'source': ''}, {'source': 'master'}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                self.request(**override)


class ReleaseJarTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / f'SF_FluffyMachines{VERSION}.jar'

    def write(self, descriptor=DESCRIPTOR, main=CLASS, wrapped=False):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            if wrapped:
                archive.writestr(self.jar.name, b'not a raw plugin')
            else:
                archive.writestr('plugin.yml', descriptor)
                archive.writestr(MAIN_CLASS, main)
        return hashlib.sha256(self.jar.read_bytes()).hexdigest()

    def test_canonical_raw_jar_is_accepted(self):
        digest = self.write()
        self.assertEqual(digest, validate_jar(self.jar, VERSION, digest))

    def test_transfer_digest_mismatch_is_rejected(self):
        self.write()
        with self.assertRaises(ValueError):
            validate_jar(self.jar, VERSION, '0' * 64)

    def test_missing_digest_cannot_disable_verification(self):
        self.write()
        with self.assertRaises(ValueError):
            validate_jar(self.jar, VERSION, '')

    def test_wrong_plugin_version_name_api_or_main_is_rejected(self):
        for before, after in ((VERSION, '26.2.15'), ('name: FluffyMachines', 'name: Other'),
                              ("api-version: '1.21.11'", "api-version: '26.3'"),
                              ('main: io.ncbpfluffybear.fluffymachines.FluffyMachines', 'main: other.Main')):
            with self.subTest(before=before):
                digest = self.write(DESCRIPTOR.replace(before, after))
                with self.assertRaises(ValueError):
                    validate_jar(self.jar, VERSION, digest)

    def test_duplicate_descriptor_identity_is_rejected(self):
        digest = self.write(DESCRIPTOR + f'version: {VERSION}\n')
        with self.assertRaises(ValueError):
            validate_jar(self.jar, VERSION, digest)

    def test_wrapper_zip_is_not_a_raw_jar(self):
        digest = self.write(wrapped=True)
        with self.assertRaises(KeyError):
            validate_jar(self.jar, VERSION, digest)

    def test_newer_or_invalid_class_header_is_rejected(self):
        for main in (CLASS[:7] + b'\x45', b'invalid!', b''):
            with self.subTest(main=main):
                digest = self.write(main=main)
                with self.assertRaises(ValueError):
                    validate_jar(self.jar, VERSION, digest)

    def test_wrong_filename_or_missing_file_is_rejected(self):
        digest = self.write()
        wrong = self.jar.with_name('renamed.jar')
        self.jar.rename(wrong)
        with self.assertRaises(ValueError):
            validate_jar(wrong, VERSION, digest)
        with self.assertRaises(FileNotFoundError):
            validate_jar(self.jar, VERSION, digest)

    def test_workflow_keeps_publication_explicit_and_immutable(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/build.yml').read_text()
        self.assertNotIn('--clobber', workflow)
        self.assertIn('permissions:\n  contents: read', workflow)
        self.assertIn('default: false', workflow)
        self.assertIn("github.event_name == 'workflow_dispatch'", workflow)
        self.assertIn("github.ref == 'refs/heads/master'", workflow)
        self.assertIn('needs: [build, raw-artifact-preflight]', workflow)
        self.assertIn('skip-decompress: true', workflow)
        self.assertIn('validate_request(', workflow)
        self.assertIn('--verify-tag', workflow)
        self.assertIn('gh api --method POST', workflow)
        self.assertIn('check_endpoint_absent', workflow)
        self.assertIn('published-verification', workflow)

# Execute the actual workflow publishing shell against a recording, offline gh
# stub. This verifies that rejected requests cannot reach tag/release mutations.
class PublisherShellTest(unittest.TestCase):
    def exercise(self, mode='success', **overrides):
        import json
        import os
        import shutil
        import subprocess
        import sys
        import textwrap
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build.yml').read_text()
        marker = '      - name: Validate request, create a new tag/release, and verify published bytes\n'
        self.assertEqual(1, workflow.count(marker))
        shell = textwrap.dedent(workflow.split(marker, 1)[1].split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / 'scripts').mkdir()
            shutil.copy(root / 'scripts/immutable_release_guard.py', work / 'scripts/immutable_release_guard.py')
            (work / 'dist').mkdir()
            jar = work / 'dist' / f'SF_FluffyMachines{VERSION}.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('plugin.yml', DESCRIPTOR)
                archive.writestr(MAIN_CLASS, CLASS)
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            binary = work / 'bin'
            binary.mkdir()
            fake = binary / 'gh'
            fake.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''\
                import json, os, shutil, sys
                from pathlib import Path
                args = sys.argv[1:]
                with open(os.environ['FAKE_GH_LOG'], 'a') as log:
                    log.write(json.dumps(args) + '\\n')
                mode = os.environ['FAKE_GH_MODE']
                if args[0] == 'api' and args[1].endswith('/git/ref/heads/master'):
                    print('b' * 40 if mode == 'stale-head' else 'a' * 40)
                elif args[:3] == ['api', '--method', 'POST']:
                    if mode == 'tag-race':
                        print('gh: Reference already exists (HTTP 422)', file=sys.stderr)
                        sys.exit(1)
                    print(json.dumps({'object': {'sha': 'a' * 40}}))
                elif args[0] == 'api' and '--jq' in args and '/git/ref/tags/' in args[1]:
                    print('a' * 40)
                elif args[0] == 'api':
                    if mode == 'forbidden':
                        print('gh: Forbidden (HTTP 403)', file=sys.stderr)
                        sys.exit(1)
                    if mode == 'existing-release' and '/releases/tags/' in args[1]:
                        print('{}')
                    elif mode == 'existing-tag' and '/git/ref/tags/' in args[1]:
                        print('{}')
                    else:
                        print('gh: Not Found (HTTP 404)', file=sys.stderr)
                        sys.exit(1)
                elif args[:2] == ['release', 'create']:
                    if '--verify-tag' not in args:
                        sys.exit('Missing verify-tag')
                    print('Offline release creation receipt')
                elif args[:2] == ['release', 'download']:
                    target = Path(args[args.index('--dir') + 1]) / os.environ['OUTPUT_NAME']
                    shutil.copy(Path('dist') / os.environ['OUTPUT_NAME'], target)
                    if mode == 'bad-download':
                        target.write_bytes(b'corrupted transfer')
                else:
                    sys.exit('Unknown gh command; no network is allowed')
                '''))
            fake.chmod(0o755)
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                       GH_TOKEN='offline-test-only', GITHUB_REPOSITORY='wickidcow/SF_FluffyMachines',
                       GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/master',
                       RELEASE_ENABLED='true', REQUESTED_VERSION=VERSION,
                       LEGACY_ARTIFACT_VERSION=VERSION, GITHUB_SHA=HEAD,
                       OUTPUT_NAME=jar.name, EXPECTED_SHA256=digest, RUNNER_TEMP=str(work),
                       FAKE_GH_MODE=mode, FAKE_GH_LOG=str(work / 'gh.jsonl'))
            env.update(overrides)
            result = subprocess.run(['bash', '-c', shell], cwd=work, env=env,
                                    capture_output=True, text=True, timeout=30)
            calls = [json.loads(line) for line in (work / 'gh.jsonl').read_text().splitlines()]
            return result, calls

    def assert_no_mutation(self, result, calls):
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(args[:3] == ['api', '--method', 'POST'] for args in calls), calls)
        self.assertFalse(any(args[:2] == ['release', 'create'] for args in calls), calls)

    def test_publish_is_explicit_new_tag_and_verified_raw_roundtrip(self):
        result, calls = self.exercise()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, sum(args[:3] == ['api', '--method', 'POST'] for args in calls))
        self.assertEqual(1, sum(args[:2] == ['release', 'create'] for args in calls))
        self.assertEqual(1, sum(args[:2] == ['release', 'download'] for args in calls))

    def test_existing_release_or_tag_cannot_be_overwritten(self):
        for mode in ('existing-release', 'existing-tag'):
            with self.subTest(mode=mode):
                self.assert_no_mutation(*self.exercise(mode))

    def test_authorization_error_is_not_treated_as_missing_release(self):
        self.assert_no_mutation(*self.exercise('forbidden'))

    def test_stale_main_or_wrong_version_cannot_mutate(self):
        self.assert_no_mutation(*self.exercise('stale-head'))
        self.assert_no_mutation(*self.exercise(REQUESTED_VERSION='26.2.17'))

    def test_push_and_feature_branch_requests_cannot_mutate(self):
        for overrides in ({'GITHUB_EVENT_NAME': 'push'}, {'RELEASE_ENABLED': 'false'},
                          {'GITHUB_REF': 'refs/heads/topic'}, {'GITHUB_REF': 'refs/tags/v26.2.16'}):
            with self.subTest(overrides=overrides):
                self.assert_no_mutation(*self.exercise(**overrides))

    def test_changed_artifact_is_rejected_before_mutation(self):
        self.assert_no_mutation(*self.exercise(EXPECTED_SHA256='0' * 64))

    def test_concurrent_tag_creation_fails_without_releasing(self):
        result, calls = self.exercise('tag-race')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(any(args[:2] == ['release', 'create'] for args in calls))

    def test_corrupt_published_download_fails_verification(self):
        result, calls = self.exercise('bad-download')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('SHA-256 differs', result.stderr)


if __name__ == '__main__':
    unittest.main()
