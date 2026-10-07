#!/usr/bin/env python3
"""Exercise the SIP phone plugin against a mock PBX without a device."""
import os
import sys
from pathlib import Path
import subprocess
import tempfile

from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(root / 'tools/test_generate_translations.py')], check=True)
java_home = os.environ.get('JAVA_HOME')
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = android_platform(sdk_root)
except ValueError as error:
    raise SystemExit(str(error)) from error
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    generated = Path(directory) / 'generated/me/jxl/kiosk/plugins/sip/GermanStrings.java'
    subprocess.run([sys.executable, str(root / 'tools/generate_translations.py'), str(root / 'translations/de.properties'), str(generated)], check=True)
    subprocess.run([tool('javac'), '--release', '8', '-cp', str(platform), '-d', directory, *map(str, sources), str(generated)], check=True)
    for test in ['me.jxl.kiosk.plugins.sip.SipPhoneTest']:
        subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([directory, str(platform)]), test], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_android_sdk.py')], check=True)

subprocess.run([sys.executable, str(root / 'tools/test_plugin_manifest.py')], check=True)

subprocess.run([sys.executable, str(root / "tools/test_plugin_assets.py")], check=True)
