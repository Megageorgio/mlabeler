import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Workspace, importPath, MAX_BYTES} from '../workspace.mjs';
import {strings, replaceQuestion} from '../strings.mjs';
import {readFileSync, readdirSync} from 'node:fs';
import {fontHasGlyph} from './font-coverage.mjs';
import {isProjectFile} from '../import-types.mjs';

test('saving through a temporary file preserves the original until replacement succeeds', () => {
  const fs = new Workspace();
  fs.write('/project/voice/a.lab', new Uint8Array([1]));
  fs.write('/project/voice/a.lab.tmp', new Uint8Array([2]));
  assert.equal(fs.rename('/project/voice/a.lab.tmp', '/project/voice/a.lab'), false);
  assert.deepEqual(fs.read('/project/voice/a.lab'), new Uint8Array([1]));
  assert(fs.delete('/project/voice/a.lab'));
  assert(fs.rename('/project/voice/a.lab.tmp', '/project/voice/a.lab'));
  assert.deepEqual(fs.read('/project/voice/a.lab'), new Uint8Array([2]));
});
test('malformed or oversized imports do not replace the previous project or settings', () => {
  const fs = new Workspace(); fs.write('/project/a.lab', new Uint8Array([1])); fs.write('/settings/settings.json', new Uint8Array([2]));
  for (const path of ['../settings/settings.json', '/etc/passwd', 'C:\\a.wav', 'a/../../b.lab', 'a\0.wav']) {
    assert.throws(() => fs.replaceProject(new Map([[path, {data:new Uint8Array([3])}]])));
    assert.equal(fs.read('/project/a.lab')[0], 1);
  }
  assert.throws(() => fs.write('/project/too-big.wav', {byteLength: MAX_BYTES + 1}));
  fs.replaceProject(new Map([['音声/a.wav', {data:new Uint8Array([3])}]]));
  assert.equal(fs.read('/project/音声/a.wav')[0], 3);
  assert.equal(fs.read('/settings/settings.json')[0], 2);
  assert(!fs.exists('/project/a.lab'));
});
test('directory enumeration, file sizes and deleting nonempty folders are consistent', () => {
  const fs = new Workspace(); fs.write('/project/wav/a.wav', new Uint8Array(4)); fs.write('/project/a.lab', new Uint8Array(2));
  assert.deepEqual(fs.list('/project').sort(), ['/project/a.lab', '/project/wav']);
  assert.equal(fs.delete('/project/wav'), false);
  assert.equal(fs.total, 6);
  assert.equal(importPath('音声\\a.wav'), '/project/音声/a.wav');
});
test('every supported language has the same shell messages', () => {
  assert.deepEqual(Object.keys(strings).sort(), ['en','ru','ja','zh','ko','fr','de','es','pt'].sort());
  for (const [code, table] of Object.entries(strings)) {
    assert.deepEqual(Object.keys(table), Object.keys(strings.en));
    assert(Object.values(table).every(s => typeof s === 'string' && s.length));
    assert(replaceQuestion[code]);
  }
});
test('the bundled fallback font covers CJK characters used by the editor translations', () => {
  const b=readFileSync(new URL('../fonts/DroidSansFallback.ttf',import.meta.url));
  let cmap;
  for(let i=0;i<b.readUInt16BE(4);i++) if(b.toString('ascii',12+16*i,16+16*i)==='cmap') cmap=b.readUInt32BE(20+16*i);
  const groups=[];
  for(let i=0;i<b.readUInt16BE(cmap+2);i++) {
    const offset=cmap+b.readUInt32BE(cmap+8+8*i);
    if(b.readUInt16BE(offset)!==12) continue;
    for(let j=0;j<b.readUInt32BE(offset+12);j++) groups.push([b.readUInt32BE(offset+16+12*j),b.readUInt32BE(offset+20+12*j),b.readUInt32BE(offset+24+12*j)]);
  }
  assert(groups.length);
  for(const code of ['Ja','Zh','Ko']) {
    const text=readFileSync(new URL(`../../app/src/commonMain/kotlin/mlabeler/app/i18n/tr/Tr${code}.kt`,import.meta.url),'utf8');
    const points=new Set([...text].map(c=>c.codePointAt(0)).filter(c=>c>=0x2e80 && c<=0xd7ff));
    for(const point of points) assert(groups.some(([a,z,g])=>point>=a && point<=z && g+point-a>0),`${code}: U+${point.toString(16)}`);
  }
});
test('project imports keep supported sidecars and backups but exclude unrelated files', () => {
  for (const name of ['voice.WAV', 'voice.TextGrid', 'oto.ini', 'voice.as12', 'voice.inf', 'head.d', 'head.png', '.mlabeler/workspace.json', '.mlabeler/backup/voice.123.lab']) assert(isProjectFile(name), name);
  for (const name of ['song.mp3', 'model.safetensors', 'app.exe', 'file.js', 'nested.zip', '__MACOSX/voice.wav', '.git/config', '.DS_Store']) assert(!isProjectFile(name), name);
});
test('bundled fonts cover interface symbols and phonetic labels', () => {
  const fonts = ['DroidSansFallback', 'NotoSans-Regular', 'NotoSansSymbols2-Regular', 'NotoSansMath-Regular'].map(name => fontHasGlyph(readFileSync(new URL(`../fonts/${name}.ttf`, import.meta.url))));
  const root = new URL('../../app/src/commonMain/kotlin/mlabeler/app/', import.meta.url);
  const symbols = new Set('ɐɑɒæɓʙβɔɕçɗɖðʤəɚɛɜɡɣɤɦɪɫɬɲŋɳøɵœɹɾʀʁɻʃθʊʌʋʔʒʲːˈˌ̥̃');
  for (const file of readdirSync(root, {recursive:true})) if (file.endsWith('.kt')) {
    for (const c of readFileSync(new URL(file.replaceAll('\\','/'), root), 'utf8')) if (/\p{S}/u.test(c)) symbols.add(c);
  }
  const missing = [...symbols].filter(c => !fonts.some(has => has(c.codePointAt(0))));
  assert.deepEqual(missing, []);
});
