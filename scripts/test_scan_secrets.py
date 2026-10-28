#!/usr/bin/env python3
"""Regression checks for release auditing; uses constructed, nonfunctional fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('scan-secrets.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ReleaseAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, content):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)

    def test_private_values_and_urls_fail_without_reporting_value(self):
        values = ['sk-' + 'x' * 28, 'ws-' + 'a' * 16,
                  'https://' + 'private-service.invalid-domain.org/v1',
                  '/' + 'Users/' + 'fictional-person/work/file',
                  'fictional-person@' + 'private-mail.invalid-domain.org']
        for value in values:
            self.write('fixture.txt', value)
            findings = audit.audit(self.root)
            self.assertTrue(findings)
            self.assertNotIn(value, str(findings))

    def test_reserved_urls_and_public_build_links_are_allowed(self):
        self.write('fixture.txt', 'https://text.example.org/v1\n'
                   'https://repo.maven.apache.org/maven2\n'
                   'https://android.googlesource.com/platform/frameworks/support/\n'
                   'https://user:secret@example.org\nhttps://example.org?key=test')
        self.assertEqual([], audit.audit(self.root, True))

    def test_release_rejects_ignored_artifacts(self):
        self.write('.local/settings.txt', 'not a secret')
        self.write('local.properties', 'sdk.dir=/example/sdk')
        self.write('record.db', 'synthetic')
        self.assertEqual([], audit.audit(self.root))
        self.assertEqual(3, len(audit.audit(self.root, True)))

    def test_production_sources_cannot_bundle_service_url_examples(self):
        for name in ['app/src/main/java/Config.java', 'core/src/main/java/Config.java',
                     'app/src/main/res/values/strings.xml']:
            for value in ['wss://speech.example.org/inference', 'https://text.example.org/v1',
                          'wss://github.com/inference']:
                self.write(name, value)
                self.assertIn((name, 'bundled service URL'), audit.audit(self.root))
                (self.root / name).unlink()

    def test_synthetic_test_endpoints_and_public_project_links_remain_valid(self):
        self.write('core/src/test/java/ConfigTest.java', 'wss://speech.example.org/inference')
        self.write('app/src/main/res/values/strings.xml',
                   'https://github.com/nooneuse/meeting-assistant\nhttp://schemas.android.com/apk/res/android')
        self.assertEqual([], audit.audit(self.root, True))

    def test_public_host_does_not_exempt_credentials_or_real_emails(self):
        for value in ['https://' + 'user:synthetic@' + 'github.com/project',
                      'https://' + 'github.com/project?token=synthetic',
                      'fictional-person@' + 'github.com',
                      'https://' + 'android.googlesource.com/project?token=synthetic']:
            self.write('fixture.txt', value)
            self.assertTrue(audit.audit(self.root))

    def test_wrapper_exception_requires_verified_binary(self):
        self.write('gradle/wrapper/gradle-wrapper.jar', 'not the official wrapper')
        self.assertTrue(audit.audit(self.root, True))

    def test_symlinks_and_unknown_binaries_fail(self):
        self.write('readme.md', 'synthetic')
        (self.root / 'linked').symlink_to(self.root / 'readme.md')
        (self.root / 'unexpected.bin').write_bytes(b'\xff\xfe')
        self.assertEqual(2, len(audit.audit(self.root, True)))

    def test_upstream_legal_exception_requires_exact_reviewed_bytes(self):
        import shutil
        source_root = Path(__file__).resolve().parent.parent
        for name in audit.VERIFIED_LEGAL:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_root / name, target)
        self.assertEqual([], audit.audit(self.root, True))
        target.write_text(target.read_text() + '\n' + 'sk-' + 'x' * 28)
        self.assertIn('unverified upstream legal text', str(audit.audit(self.root, True)))


if __name__ == '__main__':
    unittest.main()
