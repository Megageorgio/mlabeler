"""Lists the English texts of L("en", "ru") in the app that a translation table (i18n/tr/Tr*.kt) lacks.

usage: python tools/missing-translations.py app/src app/src/commonMain/kotlin/mlabeler/app/i18n/tr [out.json]
"""
import json
import re
import sys
from pathlib import Path

src, trdir = Path(sys.argv[1]), Path(sys.argv[2])


def read_literal(s, i):
    """Kotlin "..." literal at s[i] == '"'; returns (value, end) or None for templates."""
    assert s[i] == '"'
    if s.startswith('"""', i):
        j = s.index('"""', i + 3)
        return s[i + 3:j], j + 3
    out, i = [], i + 1
    while True:
        ch = s[i]
        if ch == '\\':
            n = s[i + 1]
            out.append({'n': '\n', 't': '\t', '"': '"', '\\': '\\', '$': '$', "'": "'", 'r': '\r'}.get(n, n))
            if n == 'u':
                out[-1] = chr(int(s[i + 2:i + 6], 16)); i += 6; continue
            i += 2
        elif ch == '"':
            return ''.join(out), i + 1
        elif ch == '$' and (s[i + 1] == '{' or s[i + 1].isalpha()):
            return None, i  # a template: not a fixed text
        else:
            out.append(ch); i += 1


def read_concat(s, i):
    """String literals joined by + starting at s[i]; returns (value, end) or (None, i)."""
    parts = []
    while True:
        while s[i] in ' \t\r\n':
            i += 1
        if s[i] != '"':
            return (''.join(parts) if parts else None), i
        v, i = read_literal(s, i)
        if v is None:
            return None, i
        parts.append(v)
        k = i
        while s[k] in ' \t\r\n':
            k += 1
        if s[k] == '+':
            i = k + 1
        else:
            return ''.join(parts), i


texts = {}
for f in src.rglob('*.kt'):
    if '/tr/' in f.as_posix():
        continue
    s = f.read_text(encoding='utf-8')
    for m in re.finditer(r'(?:(?<![A-Za-z0-9_.])|(?<=i18n\.))L\(', s):
        en, j = read_concat(s, m.end())
        if en is None:
            continue
        while s[j] in ' \t\r\n':
            j += 1
        if s[j] != ',':
            continue
        texts.setdefault(en, f.name)

missing = {}
for t in sorted(trdir.glob('Tr*.kt')):
    code = t.stem[2:].lower()
    body = t.read_text(encoding='utf-8')
    keys = set()
    for chunk in re.findall(r'"""(.*?)"""', body, re.S):
        for rec in chunk.split('␞'):
            k = rec.find('␟')
            if k > 0:
                keys.add(rec[:k].replace('␤', '\n'))
    num = re.compile(r'\d+')
    def has(en):
        if en in keys:
            return True
        m = num.search(en)
        return bool(m) and en[:m.start()] + '{n}' + en[m.end():] in keys
    missing[code] = [e for e in texts if not has(e)]
print(len(texts), 'texts')
for code, m in missing.items():
    print(code, len(m))
allm = sorted(set().union(*missing.values()))
for e in allm[:80]:
    print(' -', repr(e)[:150], texts[e])
if len(sys.argv) > 3:
    Path(sys.argv[3]).write_text(json.dumps({'missing': missing, 'files': {e: texts[e] for e in allm}}, ensure_ascii=False, indent=1), encoding='utf-8')
