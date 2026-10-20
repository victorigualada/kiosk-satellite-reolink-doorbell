#!/usr/bin/env python3
"""Check the manifest and run the JVM tests without a device."""
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build' / 'tests'
BUILD.mkdir(parents=True, exist_ok=True)
java_home = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


def run(*args):
    subprocess.run(args, check=True)


def fetch(url):
    # Maven Central rate-limits bursts; retry a few times before failing.
    for attempt in range(5):
        try:
            return urlopen(url, timeout=30).read()
        except HTTPError as error:
            if error.code != 429 or attempt == 4:
                raise
            time.sleep(5 * (attempt + 1))


manifest = json.loads((ROOT / 'kiosk-satellite-plugin.json').read_text())
assert manifest['id'] == 'reolink-doorbell' and manifest['apiVersion'] == 1
assert set(manifest['capabilities']) == {'overlay', 'host.read', 'host.control', 'ha'}
fields = {s['key']: s for s in manifest['settings']}
assert fields['password']['type'] == 'secret' and fields['password']['default'] == ''
assert fields['visitorEntity']['type'] == 'entity' and fields['visitorEntity']['domain'] == 'binary_sensor'
assert fields['lockEntity']['type'] == 'entity' and fields['lockEntity']['domain'] == 'lock'
assert fields['talkEnabled']['default'] is True and fields['talkSeconds']['visibleWhen'] == {'key': 'talkEnabled', 'equals': True}
assert fields['controls']['default'] == 'Bottom'
assert fields['rtspPort']['type'] == 'string' and fields['rtspPort']['default'] == '554'
assert fields['stream']['default'] == 'sub' and fields['stream']['options'] == ['sub', 'main']
for field in manifest['settings']:
    if field['type'] == 'number':
        assert field['min'] <= field['default'] <= field['max'], field['key']
        assert (field['default'] - field['min']) % field['step'] == 0, field['key']
        # Kiosk Satellite rejects a number setting with more than 10000 steps.
        assert 1 <= (field['max'] - field['min']) / field['step'] <= 10000, field['key']
# Text limits Kiosk Satellite enforces at install time.
assert len(manifest['description']) <= 1000
for field in manifest['settings'] + manifest['buttons']:
    assert 0 < len(field['title']) <= 80 and len(field.get('description', '')) <= 400, field.get('key', field.get('id'))
# Conditions must reference an earlier non-secret field.
earlier = {}
for field in manifest['settings']:
    condition = field.get('visibleWhen')
    if condition:
        assert condition['key'] in earlier and earlier[condition['key']]['type'] not in ('secret', 'list'), field['key']
    earlier[field['key']] = field
assert [c['id'] for c in manifest['commands']] == ['show', 'hide', 'ring']
assert [t['id'] for t in manifest['triggers']] == ['ring']
assert [b['id'] for b in manifest['buttons']] == ['validateConnection']

runtime_sources = '\n'.join(p.read_text() for p in (ROOT / 'src').rglob('*.java'))
assert not any(s in runtime_sources for s in ('System.out.', 'System.err.', 'printStackTrace(', 'android.util.Log.')), 'No logging that could leak credentials'
assert 'SurfaceView' not in runtime_sources and 'VideoView' not in runtime_sources, 'Overlays only support TextureView'
assert 'protectsatellite' not in runtime_sources

# Test-only JSON.org dependency; not bundled into the plugin (Android supplies org.json).
url = 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
jar = BUILD / 'json-20250517.jar'
if not jar.exists():
    checksum = fetch(url + '.sha1').decode().strip().split()[0]
    content = fetch(url)
    assert hashlib.sha1(content).hexdigest() == checksum
    jar.write_bytes(content)

sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root, os.environ.get('ANDROID_PLATFORM')))
except ValueError as error:
    raise SystemExit(str(error)) from error
classes = BUILD / 'classes'
classes.mkdir(exist_ok=True)
# Android types only satisfy linkage: no Android view/media APIs execute in these JVM tests.
sources = [str(p) for folder in ('sdk/src', 'src', 'tests') for p in sorted((ROOT / folder).rglob('*.java'))]
run(tool('javac'), '--release', '17', '-Xlint:-options', '-cp', os.pathsep.join((str(jar), platform)), '-d', str(classes), *sources)
run(tool('java'), '-ea', '-cp', os.pathsep.join((str(classes), str(jar), platform)), 'io.github.victorigualada.reolinkdoorbell.CoreTest')
print('PASS: manifest checks')
