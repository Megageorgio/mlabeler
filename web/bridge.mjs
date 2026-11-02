import {zip, Unzip, AsyncUnzipInflate} from 'fflate';
import iconv from 'iconv-lite';
import {Buffer} from 'buffer';
import {Workspace, importPath, MAX_BYTES, MAX_FILES} from './workspace.mjs';
import {strings, replaceQuestion} from './strings.mjs';
import {PROJECT_ACCEPT, isProjectFile, matchesAccept} from './import-types.mjs';

const $ = id => document.getElementById(id);
let language = navigator.language.split('-')[0];
if (!strings[language]) language = 'en';
const t = key => strings[language][key] || strings.en[key] || key;
const binary = bytes => { let s = ''; for (let i = 0; i < bytes.length; i += 16384) s += String.fromCharCode(...bytes.subarray(i, i + 16384)); return s; };
const bytes = s => Uint8Array.from(s, c => c.charCodeAt(0));
let db, persistenceFailed = false, projectChanged = false, unsaved = false, busy = false, appBusy = false, projectVersion = 0;
let imported, save, beforeImport, persistTimer;
const fallbackFonts = new Map();
let pendingPaths = new Set(), replaceStored = false;
const ws = new Workspace(path => {
  if (path === '/project') replaceStored = true; else pendingPaths.add(path);
  if (path === '/project' || path.startsWith('/project/')) { projectChanged = true; projectVersion++; }
  schedulePersist(); status();
});
function notice(key) { $('notice').querySelector('span').textContent = t(key); $('notice').hidden = false; }
function error(e) { console.error(e); notice(strings.en[e?.message] ? e.message : 'failed'); }
function status() {
  $('storage-status').textContent = t(busy || appBusy ? 'working' : persistenceFailed ? 'memory' : projectChanged || unsaved ? 'changed' : 'saved');
  for (const id of ['import-files','import-folder','export']) $(id).disabled = busy || appBusy || !imported;
}
function setLanguage(code) {
  language = strings[code] ? code : 'en'; document.documentElement.lang = language;
  for (const [id,key] of Object.entries({'import-files':'files','import-folder':'folder','export':'download','local-hint':'local','loading-text':'loading'})) $(id).textContent = t(key);
  $('dismiss').setAttribute('aria-label', t('dismiss')); status();
  document.querySelector('nav').setAttribute('aria-label', t('project'));
}
function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('mlabeler-web', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('files');
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('memory'));
    request.onsuccess = () => resolve(request.result);
  });
}
async function restore() {
  try {
    db = await openDatabase();
    const tx = db.transaction('files', 'readonly');
    const entries = await new Promise((resolve, reject) => {
      const out = []; const request = tx.objectStore('files').openCursor();
      request.onerror = () => reject(request.error);
      request.onsuccess = () => { const cursor = request.result; if (cursor) { out.push([cursor.key, cursor.value]); cursor.continue(); } else resolve(out); };
    });
    for (const [path, file] of entries) ws.write(path, file.data, file.modified, false);
  } catch (e) { persistenceFailed = true; notice('memory'); }
  if (!ws.exists('/settings/settings.json')) ws.write('/settings/settings.json', new TextEncoder().encode(JSON.stringify({setupDone: true, seenHelp: true, tipsAtStart: false, language, otherAudio: false})), Date.now(), false);
  else try { language = JSON.parse(new TextDecoder().decode(ws.read('/settings/settings.json'))).language || language; } catch {}
  setLanguage(language);
}
function schedulePersist() { clearTimeout(persistTimer); persistTimer = setTimeout(persist, 300); }
let persisting = Promise.resolve();
function persist() {
  if (!db) return Promise.resolve();
  const replace = replaceStored; replaceStored = false;
  const paths = pendingPaths; pendingPaths = new Set();
  if (!replace && !paths.size) return persisting.catch(() => {});
  const snapshot = replace ? [...ws.files] : [...paths].map(path => [path, ws.files.get(path)]);
  persisting = persisting.catch(() => {}).then(() => new Promise((resolve, reject) => {
    const tx = db.transaction('files', 'readwrite');
    const store = tx.objectStore('files'); if (replace) store.clear();
    for (const [path,file] of snapshot) { if (file) store.put(file, path); else store.delete(path); }
    tx.oncomplete = () => { persistenceFailed = false; status(); resolve(); };
    tx.onabort = tx.onerror = () => { replaceStored ||= replace; for (const path of paths) pendingPaths.add(path); persistenceFailed = true; notice('memory'); status(); reject(tx.error); };
  }));
  // Persistence failure must not prevent manual download or produce an unhandled rejection.
  return persisting.catch(() => {});
}
async function readZip(file, skipped) {
  if (file.size > MAX_BYTES) throw new Error('tooLarge');
  if (file.size < 22) throw new Error('failed');
  const tail = new Uint8Array(await file.slice(Math.max(0,file.size-65557)).arrayBuffer());
  let endRecord = false;
  for (let i=tail.length-22;i>=0;i--) if (tail[i]===80 && tail[i+1]===75 && tail[i+2]===5 && tail[i+3]===6 && i+22+tail[i+20]+256*tail[i+21]===tail.length) { endRecord=true; break; }
  if (!endRecord) throw new Error('failed');
  const incoming = new Map(); let total = 0, count = 0;
  await new Promise(async (resolve, reject) => {
    let failed = false; let pending = 0, ended = false;
    const streams = [];
    const fail = e => { if (failed) return; failed = true; streams.forEach(s => s.terminate()); reject(e); };
    const unzip = new Unzip(entry => {
      if (failed) return;
      try {
        if (entry.name.endsWith('/')) return;
        importPath(entry.name);
        if (!isProjectFile(entry.name)) { skipped.value = true; return; }
        if (++count > MAX_FILES || incoming.has(entry.name) || (entry.originalSize || 0) > MAX_BYTES) throw new Error('tooLarge');
        incoming.set(entry.name, null); pending++; streams.push(entry);
        const chunks = []; let size = 0;
        entry.ondata = (err, chunk, final) => {
          if (failed) return;
          if (err) return fail(err);
          total += chunk.length; size += chunk.length;
          if (total > MAX_BYTES) return fail(new Error('tooLarge'));
          chunks.push(chunk);
          if (final) {
            const data = new Uint8Array(size); let offset = 0;
            for (const c of chunks) { data.set(c, offset); offset += c.length; }
            incoming.set(entry.name, {data, modified: Date.now()}); pending--;
            if (ended && !pending) resolve();
          }
        };
        entry.start();
      } catch (e) { fail(e); }
    });
    unzip.register(AsyncUnzipInflate);
    try {
      const reader = file.stream().getReader();
      while (!failed) { const {done,value} = await reader.read(); if (done) break; unzip.push(value, false); }
      if (failed) { await reader.cancel(); return; }
      unzip.push(new Uint8Array(), true); ended = true;
      if (!pending) resolve();
    } catch (e) { fail(e); }
  });
  if (!incoming.size) throw new Error('unsupportedFiles');
  return incoming;
}
async function importFiles(files) {
  if (!files.length) return;
  busy = true; status();
  try {
    let incoming;
    const skipped = {value: false};
    if (files.length === 1 && /\.zip$/i.test(files[0].name)) incoming = await readZip(files[0], skipped);
    else {
      const accepted = files.filter(f => isProjectFile(f.webkitRelativePath || f.name));
      skipped.value = accepted.length !== files.length;
      files = accepted;
      if (!files.length) throw new Error('unsupportedFiles');
      if (files.length > MAX_FILES || files.reduce((n,f) => n + f.size, 0) > MAX_BYTES) throw new Error('tooLarge');
      incoming = new Map();
      for (const f of files) {
        const name = f.webkitRelativePath ? f.webkitRelativePath.split('/').slice(1).join('/') : f.name;
        importPath(name);
        if (incoming.has(name)) throw new Error('invalidPath');
        incoming.set(name, {data: new Uint8Array(await f.arrayBuffer()), modified: f.lastModified});
      }
    }
    // Validate all imported paths and the size before asking to replace the previous project.
    const validation = new Workspace(); validation.replaceProject(incoming);
    if ((projectChanged || unsaved) && !confirm(replaceQuestion[language] || replaceQuestion.en)) return;
    if (!await new Promise(resolve => beforeImport(resolve))) return;
    ws.replaceProject(incoming);
    await persist(); imported('/project');
    if (skipped.value) notice('skippedFiles');
  } catch (e) { error(e); }
  finally { busy = false; status(); }
}
async function download() {
  busy = true; status();
  try {
    const okay = await new Promise(resolve => save(resolve));
    if (!okay) throw new Error('downloadFailed');
    const version = projectVersion;
    const files = Object.create(null);
    for (const [path,file] of ws.files) if (path.startsWith('/project/')) files[path.slice(9)] = [file.data.slice(), {level: /\.(wav|flac|mp3|ogg|zip)$/i.test(path) ? 0 : 6}];
    const archive = await new Promise((resolve,reject) => zip(files, (err,data) => err ? reject(err) : resolve(data)));
    const url = URL.createObjectURL(new Blob([archive], {type: 'application/zip'}));
    const link = document.createElement('a'); link.href = url; link.download = 'mLabeler-project.zip';
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 60000);
    // Edits made while an archive was being compressed still need downloading.
    if (projectVersion === version) projectChanged = false;
    await persist();
  } catch (e) { error(e); }
  finally { busy = false; status(); }
}

let context, source, playbackStart = 0, playbackOffset = 0, playbackLength = 0, playbackRate = 0, looped = false;
const Context = globalThis.AudioContext || globalThis.webkitAudioContext;
const audioContext = () => { if (!Context) throw new Error('audio'); return context ||= new Context(); };
function stop() { const old = source; source = null; if (old) { old.onended = null; old.stop(); old.disconnect(); } }
function play(data, rate, from, to, loop) {
  stop();
  if (!Context) { notice('audio'); return; }
  const c = audioContext(); const raw = bytes(data); const floats = new Float32Array(raw.buffer);
  const buffer = c.createBuffer(1, floats.length, rate); buffer.copyToChannel(floats, 0);
  const node = c.createBufferSource(); source = node; node.buffer = buffer; node.loop = loop; node.connect(c.destination);
  playbackOffset = from; playbackLength = to - from; playbackRate = rate; looped = loop;
  c.resume().then(() => { if (source !== node) return; playbackStart = c.currentTime; node.start(); });
  node.onended = () => { if (source === node) { source = null; node.disconnect(); } };
}
let stream, recordingContext, recorder, recordingSource, recordActive = false, recordGeneration = 0;
async function microphonePermission(done) {
  try { stream?.getTracks().forEach(t => t.stop()); stream = await navigator.mediaDevices.getUserMedia({audio: {echoCancellation:false, noiseSuppression:false, autoGainControl:false}}); stream.getTracks().forEach(t => { t.onended = stopRecording; }); done(true); }
  catch { done(false); notice('microphone'); }
}
async function record(rate, chunk) {
  const generation = ++recordGeneration;
  recordActive = true;
  try {
    if (!stream) throw new Error('microphone');
    recordingContext = new Context();
    await recordingContext.audioWorklet.addModule(new URL('recorder-worklet.js', location.href));
    if (generation !== recordGeneration) return;
    recorder = new AudioWorkletNode(recordingContext, 'mlabeler-recorder');
    // Continuous linear resampling with phase carried across worklet messages.
    let pending = new Float32Array(0), phase = 0;
    const ratio = recordingContext.sampleRate / rate;
    recorder.port.onmessage = e => {
      if (!recordActive) return;
      const joined = new Float32Array(pending.length + e.data.length); joined.set(pending); joined.set(e.data, pending.length);
      const out = [];
      while (phase + 1 < joined.length) { const i = Math.floor(phase), f = phase-i; out.push(joined[i]*(1-f) + joined[i+1]*f); phase += ratio; }
      const used = Math.min(Math.floor(phase), joined.length); pending = joined.slice(used); phase -= used;
      if (out.length) chunk(binary(new Uint8Array(new Float32Array(out).buffer)));
    };
    recordingSource = recordingContext.createMediaStreamSource(stream); recordingSource.connect(recorder);
    // A zero-gain connection keeps processing active without monitoring the microphone.
    const gain = recordingContext.createGain(); gain.gain.value = 0; recorder.connect(gain); gain.connect(recordingContext.destination);
    recordActive = true; await recordingContext.resume();
  } catch (e) { if (generation === recordGeneration) { stopRecording(); notice('microphone'); } }
}
function stopRecording() {
  recordGeneration++;
  recordActive = false; recorder?.disconnect(); recordingSource?.disconnect();
  recorder = recordingSource = null;
  stream?.getTracks().forEach(t => t.stop()); stream = null;
  recordingContext?.close(); recordingContext = null;
}

globalThis.MLabelerWeb = {
  exists: p => ws.exists(p), isDirectory: p => ws.isDirectory(p), list: p => JSON.stringify(ws.list(p)),
  read: p => binary(ws.read(p)), write: (p,s) => ws.write(p, bytes(s)), mkdirs: p => ws.mkdirs(p),
  size: p => ws.files.get(p)?.data.length || 0, modified: p => ws.files.get(p)?.modified || 0,
  delete: p => ws.delete(p), rename: (a,b) => ws.rename(a,b),
  decode: (s,charset) => new TextDecoder(charset).decode(bytes(s)),
  encode: (s,charset) => binary(iconv.encode(s, charset)), normalize: s => s.normalize('NFC'),
  mobile: () => matchMedia('(pointer: coarse)').matches || innerWidth < 700,
  mac: () => /Mac/i.test(navigator.userAgentData?.platform || navigator.platform),
  language: () => language, date: n => new Date(n).toLocaleString(language),
  font: name => fallbackFonts.get(name),
  play, stop, position: () => !source ? -1 : playbackOffset + (looped ? Math.floor((context.currentTime-playbackStart)*playbackRate) % playbackLength : Math.min(playbackLength, Math.floor((context.currentTime-playbackStart)*playbackRate))),
  playing: () => !!source, microphonePermission, record, stopRecording, recording: () => recordActive,
  bind: (onImport, onSave, onBeforeImport) => { imported = onImport; save = onSave; beforeImport = onBeforeImport; $('web-toolbar').hidden = false; $('loading').hidden = true; status(); },
  setLanguage, setBusy: value => { if (appBusy !== value) { appBusy = value; status(); } },
  setTheme: json => {
    const theme = JSON.parse(json), style = document.documentElement.style;
    for (const [key, value] of Object.entries(theme)) if (!['dark','mono'].includes(key)) style.setProperty(`--${key}`, String(value));
    style.colorScheme = theme.dark ? 'dark' : 'light';
    style.setProperty('--font', theme.mono ? 'monospace' : 'Arial, sans-serif');
    document.querySelector('meta[name="theme-color"]').content = theme.bg;
  },
  setUnsaved: value => { if (unsaved !== value) { unsaved = value; status(); } },
  pickFile: (accept, done) => {
    const input = document.createElement('input'); input.type = 'file'; input.accept = accept; input.hidden = true; document.body.append(input);
    input.oncancel = () => { input.remove(); done(null); };
    input.onchange = async () => {
      try {
        const file = input.files[0];
        if (!file) return done(null);
        if (!matchesAccept(file.name, accept)) throw new Error('unsupportedFile');
        if (file.size > MAX_BYTES) throw new Error('tooLarge');
        const path = '/settings/imports/' + file.name.replace(/[\\/]/g, '_');
        ws.write(path, new Uint8Array(await file.arrayBuffer())); done(path);
      } catch (e) { error(e); done(null); }
      finally { input.remove(); }
    };
    input.click();
  },
  importProject: openFolder,
  infer: (data, rate, language, known, confidence, progress, done, failed) => {
    inferenceWorker ||= new Worker(new URL('inference/inference-bootstrap.js', location.href));
    inferenceWorker.onmessage = e => {
      if (e.data.type === 'progress') progress(e.data.value);
      else if (e.data.type === 'result') done(e.data.value);
      else { console.error(e.data.value); failed(t('failed')); cancelInference(); }
    };
    inferenceWorker.onerror = e => { console.error(e.message); failed(t('failed')); cancelInference(); };
    const buffer = bytes(data).buffer;
    inferenceWorker.postMessage({buffer, rate, language, known, confidence}, [buffer]);
  },
  cancelInference,
};
let inferenceWorker;
function cancelInference() { inferenceWorker?.terminate(); inferenceWorker = null; }

function openFolder() {
  if (busy || appBusy || !imported) return;
  if (!('webkitdirectory' in $('folder-input'))) { notice('folderUnavailable'); return; }
  $('folder-input').value = ''; $('folder-input').click();
}
$('file-input').accept = PROJECT_ACCEPT;
for (const [button,input] of [['import-files','file-input'],['import-folder','folder-input']]) {
  $(button).onclick = input === 'folder-input' ? openFolder : () => { $(input).value = ''; $(input).click(); };
  $(input).onchange = () => importFiles(Array.from($(input).files));
}
$('export').onclick = download;
$('dismiss').onclick = () => { $('notice').hidden = true; };
window.addEventListener('beforeunload', e => { if (unsaved || projectChanged) { e.preventDefault(); e.returnValue = ''; } });
document.addEventListener('visibilitychange', () => { if (document.hidden) { stop(); stopRecording(); persist(); } });
window.addEventListener('pagehide', () => { stop(); stopRecording(); persist(); });
// Compose 1.9 observes window resize, but the shell can change the canvas container size
// without resizing the window (toolbar wrapping, translated text, mobile keyboard).
let resizeFrame;
new ResizeObserver(() => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => window.dispatchEvent(new Event('resize')));
}).observe($('app'));
function fitVisualViewport() {
  if (window.visualViewport?.scale === 1) document.body.style.height = window.visualViewport.height + 'px';
}
window.visualViewport?.addEventListener('resize', fitVisualViewport);
fitVisualViewport();
// Prevent dropping files from navigating away and losing the current project.
document.addEventListener('dragover', e => e.preventDefault());
document.addEventListener('drop', e => { e.preventDefault(); if (busy || appBusy || !imported) return; const files = Array.from(e.dataTransfer.files); if (files.length) importFiles(files); });
setLanguage(language);
async function boot() {
  // WasmGC feature probe. Older browsers receive a readable message instead of an empty canvas.
  const gc = new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,95,1,127,0]);
  if (typeof WebAssembly === 'undefined' || !WebAssembly.validate(gc)) { $('loading-text').textContent = t('unsupported'); return; }
  await Promise.all([restore(), ...['DroidSansFallback', 'NotoSans-Regular', 'NotoSansSymbols2-Regular', 'NotoSansMath-Regular'].map(async name => {
    const r = await fetch(`fonts/${name}.ttf`);
    if (!r.ok) throw new Error('Font download failed');
    fallbackFonts.set(name, binary(new Uint8Array(await r.arrayBuffer())));
  })]);
  const script = document.createElement('script'); script.src = 'mlabeler.js';
  script.onerror = () => { $('loading-text').textContent = t('failed'); };
  document.body.append(script);
}
boot().catch(e => { error(e); $('loading-text').textContent = t('failed'); });
