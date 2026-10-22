#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Parse identities only AFTER apksigner has cryptographically verified an APK."""
import re

_SIGNER_LABELS = (
    r'Signer #\d+',
    r'Signer \(minSdkVersion=\d+(?: \(dev release=true\))?, maxSdkVersion=\d+\)',
    r'V2 Signer:',
)
_CERTIFICATE = re.compile(
    r'^(' + '|'.join(_SIGNER_LABELS)
    + r') certificate SHA-256 digest:[ \t]*([a-fA-F0-9]{64})[ \t]*$',
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
