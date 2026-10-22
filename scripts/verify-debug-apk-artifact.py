#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
"""Verify an already-built arm64 Debug APK; never builds, signs or installs it."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from apk_signing_identity import signing_certificates

apk = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2])
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
build_tools = sorted((sdk / 'build-tools').glob('*/apksigner'), reverse=True)
assert build_tools, 'Android signing verification tools are unavailable'
signer = build_tools[0]
aapt = signer.parent / 'aapt2'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'APK CRC validation failed'
    entries = archive.namelist()
    abis = sorted({p.split('/')[1] for p in entries if p.startswith('lib/') and p.endswith('.so')})
    assert abis == ['arm64-v8a'], abis
    assert all(f'lib/arm64-v8a/{name}' in entries for name in ['libmpv.so', 'libplayer.so', 'libplacebo.so', 'libudfread.so'])
    assert not any(p.endswith('/librife_vfi.so') for p in entries)
signature = subprocess.check_output([str(signer), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
print('Cryptographic apksigner verification output:\n' + signature, flush=True)
certificates = signing_certificates(signature)
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
package = next(line for line in badging.splitlines() if line.startswith('package: '))
identity = dict(re.findall(r"(\w+)='([^']*)'", package))
assert identity.get('name') == 'app.infinity.mpvz.debug', identity
with apk.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
record = {'sourceCommit': os.environ.get('GITHUB_SHA'), 'apk': apk.name,
    'sizeBytes': apk.stat().st_size, 'sha256': digest, 'abis': abis,
    'package': identity, 'apkCrcVerified': True, 'apkSignatureVerified': True,
    'signingCertificateSha256': certificates[0]['sha256'],
    'signingCertificates': certificates,
    'signatureOutput': signature,
    'locales': next((line for line in badging.splitlines() if line.startswith('locales:')), '')}
output.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps(record, indent=2), flush=True)
