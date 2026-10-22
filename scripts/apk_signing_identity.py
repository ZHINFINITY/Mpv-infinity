#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Parse identities only AFTER apksigner has cryptographically verified an APK."""
import re

_CERTIFICATE = re.compile(
    r'^(Signer (?:#\d+|\(minSdkVersion=\d+(?: \(dev release=true\))?, '
    r'maxSdkVersion=\d+\))) certificate SHA-256 digest:[ \t]*([a-fA-F0-9]{64})[ \t]*$',
    re.MULTILINE,
)


def signing_certificates(verified_output):
    """Return every APK signer, retaining SDK ranges; never use source-stamp hashes."""
    certificates = [
        {'signer': label, 'sha256': digest.lower()}
        for label, digest in _CERTIFICATE.findall(verified_output.replace('\r\n', '\n'))
    ]
    if not certificates:
        raise ValueError('Verified APK signing certificate SHA-256 digest is missing')
    return certificates
