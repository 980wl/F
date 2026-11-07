#!/usr/bin/env python3
"""Generate an F APK update manifest for HTTPS hosting (APK must be signed with same key)."""
import argparse
import hashlib
import json
import pathlib
import re

p = argparse.ArgumentParser()
p.add_argument('--apk', required=True)
p.add_argument('--apk-url', required=True)
p.add_argument('--core-version', required=True)
p.add_argument('--version-code', required=True, type=int)
p.add_argument('--version', required=True)
p.add_argument('--output', default='f-updates.json')
a = p.parse_args()
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', a.core_version):
    p.error('stable core version only, e.g. 1.14.4')
if not a.apk_url.startswith('https://'):
    p.error('APK must be hosted on HTTPS')
source = pathlib.Path(a.apk)
if not source.is_file():
    p.error('APK file does not exist')
sha = hashlib.file_digest(source.open('rb'), 'sha256').hexdigest()
manifest = {
    'version_code': a.version_code,
    'version_name': a.version,
    'core_version': a.core_version,
    'apk_url': a.apk_url,
    'sha256': sha,
}
pathlib.Path(a.output).write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print('Saved', a.output, 'sha256', sha)
