#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Signing-output compatibility and fail-closed verification; no APK compilation."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from apk_signing_identity import signing_certificates

A = 'a1' * 32
B = 'b2' * 32


class SigningIdentityTest(unittest.TestCase):
    def test_legacy_single_signer(self):
        self.assertEqual([{'signer': 'Signer #1', 'sha256': A}],
            signing_certificates(f'Signer #1 certificate SHA-256 digest: {A}\n'))

    def test_sdk_aware_rotation_retains_both_identities(self):
        text = (f'Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {A}\n'
                f'Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {B}\n')
        self.assertEqual([A, B], [c['sha256'] for c in signing_certificates(text)])
        self.assertIn('minSdkVersion=33', signing_certificates(text)[0]['signer'])

    def test_development_release_and_uppercase_windows_output(self):
        text = f'Signer (minSdkVersion=36 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: {A.upper()}\r\n'
        self.assertEqual(A, signing_certificates(text)[0]['sha256'])

    def test_multiple_legacy_signers(self):
        text = f'Signer #1 certificate SHA-256 digest: {A}\nSigner #2 certificate SHA-256 digest: {B}\n'
        self.assertEqual(2, len(signing_certificates(text)))

    def test_source_stamp_and_public_key_are_not_apk_signer_identities(self):
        for text in [f'Source Stamp Signer certificate SHA-256 digest: {A}',
                     f'Signer #1 public key SHA-256 digest: {A}', 'Verifies\n']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                signing_certificates(text)

    def test_malformed_or_short_hash_fails_closed(self):
        for digest in ['abc', 'x' * 64, A + '00', '']:
            with self.subTest(digest=digest), self.assertRaises(ValueError):
                signing_certificates(f'Signer #1 certificate SHA-256 digest: {digest}\n')

    def run_production_verifier(self, directory, signature, verify_error=False):
        directory = Path(directory)
        sdk = directory / 'sdk'
        tools = sdk / 'build-tools/36.0.0'
        tools.mkdir(parents=True)
        (tools / 'apksigner').touch()
        fixture = directory / 'not-an-apk-test-fixture.zip'
        with zipfile.ZipFile(fixture, 'w') as archive:
            for name in ['libmpv.so', 'libplayer.so', 'libplacebo.so', 'libudfread.so']:
                archive.writestr('lib/arm64-v8a/' + name, b'unit-test-fixture-not-native-code')
        report = directory / 'verification.json'
        def output(args, **kwargs):
            if args[1] == 'verify':
                if verify_error:
                    raise subprocess.CalledProcessError(1, args)
                return signature
            self.assertEqual(['dump', 'badging'], args[1:3])
            return "package: name='app.infinity.mpvz.debug' versionCode='1' versionName='fixture'\nlocales: 'en'\n"
        with patch.dict(os.environ, {'ANDROID_HOME': str(sdk)}), patch.object(sys, 'argv', ['verify', str(fixture), str(report)]), patch('subprocess.check_output', side_effect=output), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(ROOT / 'scripts/verify-debug-apk-artifact.py'), run_name='__main__')
        return json.loads(report.read_text())

    def test_real_verifier_accepts_sdk_aware_certificate_records(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_production_verifier(directory,
                f'Verifies\nSigner (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {A}\n')
            self.assertTrue(result['apkSignatureVerified'])
            self.assertEqual(A, result['signingCertificateSha256'])
            self.assertEqual(['arm64-v8a'], result['abis'])

    def test_failed_cryptographic_verification_never_writes_success(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(subprocess.CalledProcessError):
                self.run_production_verifier(directory, f'Signer #1 certificate SHA-256 digest: {A}\n', verify_error=True)
            self.assertFalse((Path(directory) / 'verification.json').exists())

    def test_stamp_only_output_cannot_write_success(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                self.run_production_verifier(directory, f'Source Stamp Signer certificate SHA-256 digest: {A}\n')
            self.assertFalse((Path(directory) / 'verification.json').exists())


if __name__ == '__main__':
    unittest.main()
