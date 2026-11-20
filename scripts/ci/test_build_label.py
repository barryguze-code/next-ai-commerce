"""Pure regression checks for the existing launcher's build-label format."""
import unittest

from promote_application import replace_build_label


class BuildLabelTests(unittest.TestCase):
    def test_standalone_assignment_preserves_bytes(self):
        for prefix in (b'', b'export ', b'export\t'):
            for ending in (b'', b'\n', b'\r\n'):
                original = b'#!/bin/sh\n' + prefix + b'APP_BUILD_VERSION=1.14.28' + ending
                self.assertEqual(replace_build_label(original, '1.14.29'),
                                 original.replace(b'1.14.28', b'1.14.29'))

    def test_shared_export_line_preserves_other_assignments_and_spacing(self):
        original = (b'#!/bin/sh\nexport DB_URL=jdbc:postgresql://127.0.0.1/example '
                    b'MAIL_ENABLED=false\tAPP_BUILD_VERSION=1.14.28  FEATURE_ENABLED=true\r\n'
                    b'exec java -jar app.jar\n')
        self.assertEqual(replace_build_label(original, '1.14.29'),
                         original.replace(b'APP_BUILD_VERSION=1.14.28', b'APP_BUILD_VERSION=1.14.29'))

    def test_unsafe_or_ambiguous_assignments_fail_closed(self):
        for original in (
            b'export FEATURE_ENABLED=true\n',
            b'export APP_BUILD_VERSION=1.14.28 APP_BUILD_VERSION=1.14.28\n',
            b'APP_BUILD_VERSION=1.14.28\nAPP_BUILD_VERSION=1.14.28\n',
            b'export OTHER_APP_BUILD_VERSION=1.14.28\n',
            b'# APP_BUILD_VERSION=1.14.28\n',
            b'echo APP_BUILD_VERSION=1.14.28\n',
            b'export APP_BUILD_VERSION="1.14.28"\n',
            b"export APP_BUILD_VERSION='1.14.28'\n",
            b'export APP_BUILD_VERSION=$VERSION\n',
            b'export APP_BUILD_VERSION=1.14.28-local\n',
            b'export APP_BUILD_VERSION=1.14.28;echo x\n',
            b'export APP_BUILD_VERSION=1.14.28 OTHER=$(command)\n',
            b'export APP_BUILD_VERSION=1.14.28 # note\n',
        ):
            with self.subTest(original=original), self.assertRaises(ValueError):
                replace_build_label(original, '1.14.29')

    def test_invalid_new_version_fails_closed(self):
        for version in ('1.14.29-local', '1.14.29\n', '1.14.29;echo x', ''):
            with self.subTest(version=version), self.assertRaises(ValueError):
                replace_build_label(b'APP_BUILD_VERSION=1.14.28\n', version)


if __name__ == '__main__':
    unittest.main()
