#!/usr/bin/env python3
"""Fail closed unless the APK embeds this exact experimental shader for both players."""
import ast
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1])
source = (root/'app/src/main/cpp/mpvflow/mpvflow_gpu.c').read_text()
match = re.search(r'static const char shader_synthesize\[\] =\n(.*?);\n', source, re.S)
shader = ''.join(ast.literal_eval(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', match[1])).encode()
assert b'if(!softBlend) boundaryTrust*=region3LocalTrust*region3TransitionTrust' in shader
libraries = ['lib/arm64-v8a/libmpv.so','lib/arm64-v8a/libmedia3flow_vulkan.so']
with zipfile.ZipFile(apk) as archive:
    for name in libraries:
        data = archive.read(name)
        assert shader in data, f'{name} does not contain the exact changed shared synthesis shader'
result = {'apk': apk.name, 'apk_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
          'shader_sha256': hashlib.sha256(shader).hexdigest(), 'shader_bytes':len(shader),
          'verified_libraries':libraries, 'commit':__import__('os').environ.get('GITHUB_SHA'),
          'claim':'Exact shader embedding verified; no phone visual/performance claim.'}
print(json.dumps(result, indent=2))
(apk.parent/'region3-shader-verification.json').write_text(json.dumps(result,indent=2)+'\n')
