"""Validate the actual AAB, rather than inferring Play restrictions from the source manifest."""
import argparse
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ANDROID = '{http://schemas.android.com/apk/res/android}'
FORBIDDEN = {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.REQUEST_INSTALL_PACKAGES',
    'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO',
}


def check_manifest(xml, version_code):
    manifest = ET.fromstring(xml)
    assert manifest.attrib['package'] == 'pw.msan.mlabeler', 'Unexpected package name'
    assert int(manifest.attrib[ANDROID + 'versionCode']) == version_code, 'Unexpected versionCode'
    permissions = {item.attrib[ANDROID + 'name'] for item in manifest if item.tag.startswith('uses-permission')}
    assert not permissions & FORBIDDEN, f'Forbidden Play permissions: {sorted(permissions & FORBIDDEN)}'
    assert 'android.permission.RECORD_AUDIO' in permissions, 'Recording permission missing'
    application = manifest.find('application')
    assert application is not None
    assert application.get(ANDROID + 'debuggable', 'false') == 'false', 'Debuggable release'
    assert application.get(ANDROID + 'usesCleartextTraffic') == 'false', 'Cleartext traffic enabled'
    assert application.get(ANDROID + 'requestLegacyExternalStorage', 'false') == 'false', 'Legacy storage enabled'
    assert not any('FileProvider' in p.get(ANDROID + 'name', '') for p in application.findall('provider')), 'APK update provider present'
    assert not any('TestDocumentsProvider' in p.get(ANDROID + 'name', '') for p in application.findall('provider')), 'Test provider in production'
    print(f"Play manifest OK: versionCode={version_code}; permissions={', '.join(sorted(permissions))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--bundletool', required=True, type=Path)
    parser.add_argument('--version-code', required=True, type=int)
    args = parser.parse_args()
    cmd = ['java', '-jar', str(args.bundletool)]
    validation = subprocess.run(cmd + ['validate', '--bundle=' + str(args.bundle)], capture_output=True, text=True, encoding='utf-8')
    if validation.returncode:
        raise RuntimeError(validation.stdout + validation.stderr)
    xml = subprocess.check_output(cmd + ['dump', 'manifest', '--bundle=' + str(args.bundle)], text=True, encoding='utf-8')
    check_manifest(xml, args.version_code)
    with zipfile.ZipFile(args.bundle) as archive:
        names = archive.namelist()
        configs = [name for name in names if name.startswith('base/assets/mcfl/') and name.endswith('/model.json')]
        assert any(archive.getinfo(name.replace('/model.json', '/model.safetensors')).file_size > 0
                   for name in configs), 'Built-in quick labelling model missing'
        for name in names:
            if name.endswith('.dex'):
                assert b'TestDocumentsProvider' not in archive.read(name), 'Test fixture code in production'
            if not name.endswith('.so') or not any(abi in name for abi in ('/arm64-v8a/', '/x86_64/')):
                continue
            data = archive.read(name)
            assert data[:6] == b'\x7fELF\x02\x01', f'Unexpected ELF format: {name}'
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            alignments = [struct.unpack_from('<Q', data, offset + i * size + 48)[0] for i in range(count)
                          if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
            assert alignments and min(alignments) >= 16384, f'16 KB page alignment missing: {name}'
        print('Play payload OK: quick labelling model present; 64-bit libraries support 16 KB pages; no test fixture')


if __name__ == '__main__':
    main()
