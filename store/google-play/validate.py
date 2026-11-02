"""Check the prepared Play listing's text limits, image dimensions and archive safety."""
import hashlib
import json
import struct
import zlib
from pathlib import Path

BASE = Path(__file__).resolve().parent


def png_info(path):
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', path
    pos = 8
    width = height = colour = depth = None
    ended = False
    while pos < len(data):
        size = struct.unpack_from('>I', data, pos)[0]
        kind = data[pos+4:pos+8]
        body = data[pos+8:pos+8+size]
        assert len(body) == size, path
        crc = struct.unpack_from('>I', data, pos+8+size)[0]
        assert zlib.crc32(kind+body) & 0xffffffff == crc, (path, kind)
        if kind == b'IHDR':
            width, height, depth, colour = struct.unpack('>IIBB', body[:10])
        pos += size + 12
        if kind == b'IEND':
            ended = True
            break
    assert ended and pos == len(data), path
    return {'width':width, 'height':height, 'bitDepth':depth, 'colourType':colour, 'bytes':len(data), 'sha256':hashlib.sha256(data).hexdigest()}


def validate():
    info = {'texts':{}, 'images':{}, 'publicPrivacyPolicyPublished':False}
    limits = {'title.txt':30,'short-description.txt':80,'full-description.txt':4000,'release-notes.txt':500}
    for lang in ['en-US','ru-RU']:
        for name, limit in limits.items():
            path = BASE/'listing'/lang/name
            value = path.read_text(encoding='utf-8').strip()
            assert value and len(value) <= limit, path
            if name in ['title.txt','short-description.txt']:
                assert '\n' not in value, path
            info['texts'][path.relative_to(BASE).as_posix()] = {'characters':len(value),'limit':limit}
        shots = sorted((BASE/'assets/screenshots'/lang).glob('*.png'))
        assert len(shots) == 4, (lang, len(shots))
    for path in (BASE/'assets').rglob('*.png'):
        image = png_info(path)
        assert image['bitDepth'] == 8, path
        if path.name == 'icon-512.png':
            assert (image['width'],image['height'],image['colourType']) == (512,512,6), path
            assert image['bytes'] <= 1024*1024, path
        elif path.name.startswith('feature-graphic-'):
            assert (image['width'],image['height'],image['colourType']) == (1024,500,2), path
            assert image['bytes'] <= 15*1024*1024, path
        else:
            assert (image['width'],image['height'],image['colourType']) == (1920,1080,2), path
            assert image['bytes'] <= 8*1024*1024, path
        info['images'][path.relative_to(BASE).as_posix()] = image
    for path in BASE.rglob('*'):
        if path.is_file():
            assert path.suffix.lower() not in {'.jks','.keystore','.p12','.wav','.mp3','.lab','.aab','.apk'}, path
    for lang in ['en','ru']:
        text = (BASE/'privacy'/f'privacy-{lang}.html').read_text(encoding='utf-8')
        assert 'm@msan.pw' in text and 'pw.msan.mlabeler' in text
    return info


if __name__ == '__main__':
    result = validate()
    (BASE/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f"Validated {len(result['texts'])} text fields and {len(result['images'])} images.")
