import {test, expect} from '@playwright/test';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import {unzipSync, zipSync} from 'fflate';

function wav(seconds = 1) {
  const n = 16000 * seconds, b = Buffer.alloc(44+n*2);
  b.write('RIFF'); b.writeUInt32LE(b.length-8,4); b.write('WAVEfmt ',8); b.writeUInt32LE(16,16);
  b.writeUInt16LE(1,20); b.writeUInt16LE(1,22); b.writeUInt32LE(16000,24); b.writeUInt32LE(32000,28); b.writeUInt16LE(2,32); b.writeUInt16LE(16,34); b.write('data',36); b.writeUInt32LE(n*2,40);
  for(let i=0;i<n;i++) b.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*220/n)*6000),44+i*2);
  return b;
}
async function boot(page) {
  let fail;
  const startupFailure = new Promise((_, reject) => { fail = reject; });
  const onError = error => fail(new Error(`Editor startup failed: ${error.message}`));
  const onConsole = message => {
    // Driver diagnostics (e.g. unavailable WEBGL_debug_renderer_info) are not fatal.
    // Kotlin coroutine exceptions are logged rather than emitted as pageerror events.
    if (message.type() === 'error' && /^kotlin\.[\w.]+(?:Exception|Error):/.test(message.text())) {
      fail(new Error(`Editor startup failed: ${message.text()}`));
    }
  };
  page.on('pageerror', onError);
  page.on('console', onConsole);
  try {
    await Promise.race([startupFailure, (async () => {
      await page.goto('./');
      await expect(page.locator('#web-toolbar')).toBeVisible({timeout:60000});
      await expect(page.locator('#app canvas')).toBeVisible();
    })()]);
  } finally {
    page.off('pageerror', onError);
    page.off('console', onConsole);
  }
}

test('Open folder selects a directory and filters unsupported files without losing project sidecars', async ({page}, info) => {
  await boot(page);
  const folder = info.outputPath('voicebank');
  await mkdir(folder, {recursive:true});
  await writeFile(`${folder}/test.wav`, wav());
  await writeFile(`${folder}/oto.ini`, 'test.wav=a,0,50,-100,20,10\n');
  await writeFile(`${folder}/test.as12`, 'sidecar');
  await writeFile(`${folder}/unrelated.exe`, 'skip me');
  const chooser = page.waitForEvent('filechooser');
  // Compose's start screen action, not the HTML toolbar button.
  await page.getByText('Open folder…', {exact:true}).dispatchEvent('click');
  const picker = await chooser;
  expect(await picker.element().getAttribute('webkitdirectory')).not.toBeNull();
  await picker.setFiles(folder);
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/test.wav'))).toBe(true);
  await expect(page.locator('#export')).toBeEnabled();
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/oto.ini'))).toBe(true);
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/test.as12'))).toBe(true);
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/unrelated.exe'))).toBe(false);
  await expect(page.locator('#notice')).toContainText('skipped');
  const next = page.waitForEvent('filechooser'); await page.locator('#import-files').click();
  const filePicker = await next;
  expect(await filePicker.element().getAttribute('webkitdirectory')).toBeNull();
  expect(await filePicker.element().getAttribute('accept')).toContain('.wav');
  expect(await filePicker.element().getAttribute('accept')).not.toContain('.mp3');
  await filePicker.setFiles({name:'wrong.mp3', mimeType:'audio/mpeg',buffer:Buffer.from('wrong type')});
  await expect(page.locator('#notice')).toContainText('Select WAV');
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/test.wav'))).toBe(true);
  page.on('dialog', d=>d.accept());
  const archive = zipSync({'test.wav':wav(),'test.lab':Buffer.from('0 10000000 a\n'),'.mlabeler/backup/old.lab':Buffer.from('old label'), 'script.js':Buffer.from('skip'), '__MACOSX/test.wav':wav()});
  await page.locator('#file-input').setInputFiles({name:'project.zip',mimeType:'application/zip',buffer:Buffer.from(archive)});
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/test.lab'))).toBe(true);
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/.mlabeler/backup/old.lab'))).toBe(true);
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/script.js'))).toBe(false);
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/__MACOSX/test.wav'))).toBe(false);
});

test('browser toolbar follows the editor theme and scale', async ({page}, info) => {
  await boot(page);
  await expect(page.locator('#export')).toHaveCSS('background-color', 'rgb(166, 207, 130)');
  for (const [theme, color, radius, scheme] of [['modern-light','rgb(78, 138, 44)','10px','light'], ['retro','rgb(0, 0, 128)','0px','light']]) {
    await page.evaluate(theme => {
      const settings=JSON.parse(MLabelerWeb.read('/settings/settings.json'));
      MLabelerWeb.write('/settings/settings.json',JSON.stringify({...settings,theme,scale:1.25,scaleChosen:true}));
    },theme);
    await page.waitForTimeout(600); await page.reload();
    await expect(page.locator('#web-toolbar')).toBeVisible();
    await expect(page.locator('#export')).toHaveCSS('background-color',color);
    await expect(page.locator('#export')).toHaveCSS('border-radius',radius);
    await expect(page.locator('html')).toHaveCSS('color-scheme',scheme);
    await page.screenshot({path:info.outputPath(`theme-${theme}.png`)});
  }
  // A real settings change must update the shell immediately, without a reload.
  await page.getByRole('button', {name:'Settings', exact:true}).dispatchEvent('click', {bubbles:false});
  await page.getByText('Theme', {exact:true}).dispatchEvent('click', {bubbles:false});
  await page.getByText('Light', {exact:true}).dispatchEvent('click', {bubbles:false});
  await expect(page.locator('#export')).toHaveCSS('background-color','rgb(78, 138, 44)');
});
test('wheel scrolling and zoom stay proportional across browser delta modes', async ({page}, info) => {
  test.skip(info.project.name.includes('phone'), 'Mouse wheel interaction uses the desktop layout');
  await boot(page);
  await page.evaluate(() => {
    const settings = JSON.parse(MLabelerWeb.read('/settings/settings.json'));
    MLabelerWeb.write('/settings/settings.json', JSON.stringify({
      ...settings, status: {left: ['cursor'], right: ['zoom']},
    }));
  });
  await page.waitForTimeout(600);
  await page.reload();
  await expect(page.locator('#web-toolbar')).toBeVisible();
  await page.locator('#file-input').setInputFiles([
    {name: 'long.wav', mimeType: 'audio/wav', buffer: wav(60)},
    {name: 'long.lab', mimeType: 'text/plain', buffer: Buffer.from('0 600000000 a\n')},
  ]);
  // Compose can merge adjacent status texts into one accessibility element.
  const scale = page.getByText(/Scale: [\d.]+ s$/);
  await expect(scale).toBeVisible({timeout: 30000});
  const secondsOnScreen = async () => Number((await scale.innerText()).match(/Scale: ([\d.]+)/)[1]);
  const canvas = await page.locator('#app canvas').boundingBox();
  const point = {x: canvas.x + canvas.width * 0.65, y: canvas.y + canvas.height * 0.5};
  await page.mouse.move(point.x, point.y);
  const before = await secondsOnScreen();
  await page.keyboard.down('Control');
  await page.mouse.wheel(0, -100);
  await page.keyboard.up('Control');
  await expect.poll(secondsOnScreen).toBeLessThan(before);
  expect(before / await secondsOnScreen()).toBeLessThan(1.3);
  // Equivalent physical wheel steps can arrive as pixels, lines or pages.
  for (const [deltaMode, deltaY] of [[0, -100], [1, -3], [2, -1]]) {
    const previous = await secondsOnScreen();
    await page.locator('#app canvas').evaluate((canvas, {point, deltaMode, deltaY}) => {
      canvas.dispatchEvent(new WheelEvent('wheel', {
        bubbles: true, cancelable: true, clientX: point.x, clientY: point.y,
        deltaMode, deltaY, ctrlKey: true,
      }));
    }, {point, deltaMode, deltaY});
    await expect.poll(secondsOnScreen).toBeLessThan(previous);
    expect(previous / await secondsOnScreen()).toBeLessThan(1.3);
  }
  const cursor = page.getByText(/^\d+:\d{2}\.\d{3}(?:\s|$)/).filter({hasNotText: 'Hz'});
  const time = async () => {
    const [minutes, seconds] = (await cursor.innerText()).match(/\d+:\d{2}\.\d{3}/)[0].split(':').map(Number);
    return minutes * 60 + seconds;
  };
  await page.mouse.move(point.x + 1, point.y);
  await expect(cursor).toBeVisible();
  const start = await time();
  await page.mouse.wheel(0, 100);
  await page.mouse.move(point.x + 2, point.y);
  await expect.poll(time).toBeGreaterThan(start);
  expect(await time() - start).toBeLessThan(await secondsOnScreen() / 3);
});

test('imports WAV and labels, plays, downloads a valid project and restores it', async ({page}, info) => {
  await page.addInitScript(() => {
    const NativeAudioContext = window.AudioContext || window.webkitAudioContext;
    if (NativeAudioContext) window.AudioContext = class extends NativeAudioContext {
      constructor(...args) { super(...args); window.testAudioContext = this; }
    };
  });
  const errors=[]; page.on('pageerror', e=>errors.push(e.message));
  const external=[]; page.on('request', req=>{if(!req.url().startsWith('http://127.0.0.1:4173') && !req.url().startsWith('blob:') && !req.url().startsWith('data:')) external.push(req.url());});
  await boot(page);
  // The 7 MB model must not download merely to open the editor.
  expect(await page.evaluate(()=>performance.getEntriesByType('resource').some(e=>e.name.includes('safetensors')))).toBe(false);
  await page.locator('#file-input').setInputFiles([
    {name:'test.wav',mimeType:'audio/wav',buffer:wav()},
    {name:'test.lab',mimeType:'text/plain',buffer:Buffer.from('0 10000000 a\n')},
  ]);
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/.mlabeler'))).toBe(true);
  await expect(page.locator('#export')).toBeEnabled();
  await expect.poll(async()=>{
    const canvas=await page.locator('#app canvas').boundingBox();const app=await page.locator('#app').boundingBox();
    return canvas.y+canvas.height <= app.y+app.height+1;
  }).toBe(true);
  await page.screenshot({path:`test-results/${info.project.name}-editor.png`});
  // Exercise Web Audio in the actual browser engine.
  await page.locator('#app canvas').click({position:{x:100,y:100}});
  if (await page.evaluate(()=>!!(globalThis.AudioContext || globalThis.webkitAudioContext))) {
    await page.evaluate(()=>MLabelerWeb.play(String.fromCharCode(...new Uint8Array(new Float32Array(1600).buffer)),16000,0,1600,false));
    await expect.poll(()=>page.evaluate(()=>({
      state: window.testAudioContext?.state,
      clockAdvanced: window.testAudioContext?.currentTime > 0,
    })), {message:'Web Audio must have a running clock; Linux CI needs an audio server and output sink'}).toEqual({state:'running',clockAdvanced:true});
    await expect.poll(()=>page.evaluate(()=>MLabelerWeb.playing())).toBe(false);
  } else {
    // Playwright's Windows WebKit omits Web Audio; Linux CI checks the actual API.
    expect(process.platform).toBe('win32');
    info.annotations.push({type:'limitation',description:'Windows WebKit build has no Web Audio implementation'});
  }
  const download = page.waitForEvent('download'); await page.locator('#export').click();
  const file = await download; const zipBytes=await readFile(await file.path()); const archive = unzipSync(zipBytes);
  expect(Buffer.from(archive['test.wav'])).toEqual(wav());
  expect(new TextDecoder().decode(archive['test.lab'])).toContain('a');
  const previousTime=await page.evaluate(()=>MLabelerWeb.modified('/project/test.wav'));
  page.on('dialog', dialog=>dialog.accept());
  await page.locator('#file-input').setInputFiles({name:'project.zip',mimeType:'application/zip',buffer:zipBytes});
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.modified('/project/test.wav'))).toBeGreaterThan(previousTime);
  await expect(page.locator('#export')).toBeEnabled();
  await page.waitForTimeout(600);
  await page.reload(); await expect(page.locator('#web-toolbar')).toBeVisible();
  expect(await page.evaluate(()=>MLabelerWeb.exists('/project/test.wav'))).toBe(true);
  const layout=await page.evaluate(()=>({w:document.documentElement.clientWidth,sw:document.documentElement.scrollWidth,h:document.querySelector('#app').getBoundingClientRect().height}));
  expect(layout.sw).toBeLessThanOrEqual(layout.w); expect(layout.h).toBeGreaterThan(400);
  expect(errors).toEqual([]);
  // The installed Edge may have a system antivirus script injected into every page.
  // It is outside this app; all other off-site requests remain failures.
  expect(external.filter(url=>!new URL(url).hostname.endsWith('.kaspersky-labs.com'))).toEqual([]);
});
test('shell supports all editor languages and rejects unsafe archives without losing files', async ({page}) => {
  await boot(page);
  for (const code of ['ru','ja','zh','ko','fr','de','es','pt','en']) {
    await page.evaluate(code=>MLabelerWeb.setLanguage(code),code);
    await expect(page.locator('html')).toHaveAttribute('lang',code);
    expect(await page.locator('#import-files').textContent()).toBeTruthy();
  }
  await page.evaluate(()=>MLabelerWeb.write('/project/original.lab','keep'));
  // Deliberately malformed zip: parsing must fail before replacing the existing workspace.
  await page.locator('#file-input').setInputFiles({name:'broken.zip',mimeType:'application/zip',buffer:Buffer.from('broken archive')});
  await expect(page.locator('#notice')).toBeVisible();
  expect(await page.evaluate(()=>MLabelerWeb.read('/project/original.lab'))).toBe('keep');
});
test('MCFL runs in a worker while the page keeps responding', async ({page}) => {
  await boot(page);
  await page.evaluate(()=>{
    window.inferenceResult=null; window.inferenceFailure=null; window.beats=0;
    window.timer=setInterval(()=>window.beats++,20);
    const pcm=new Uint8Array(new Float32Array(8000).buffer);
    let binary=''; for(let i=0;i<pcm.length;i+=8192) binary+=String.fromCharCode(...pcm.subarray(i,i+8192));
    MLabelerWeb.infer(binary,16000,'en','[]',false,()=>{},r=>window.inferenceResult=JSON.parse(r),e=>window.inferenceFailure=e);
  });
  await expect.poll(()=>page.evaluate(()=>window.inferenceFailure || (window.inferenceResult !== null)),{timeout:60000}).toBe(true);
  expect(await page.evaluate(()=>window.beats)).toBeGreaterThan(3);
  expect(await page.evaluate(()=>Array.isArray(window.inferenceResult))).toBe(true);
  await page.evaluate(()=>{clearInterval(window.timer);MLabelerWeb.cancelInference();});
});

test('renames a label in the editor and includes the pending edit in the download', async ({page}, info) => {
  await boot(page);
  await page.locator('#file-input').setInputFiles([
    {name:'test.wav',mimeType:'audio/wav',buffer:wav()},
    {name:'test.lab',mimeType:'text/plain',buffer:Buffer.from('0 10000000 a\n')},
  ]);
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/.mlabeler'))).toBe(true);
  await expect(page.locator('#export')).toBeEnabled();
  const canvas=page.locator('#app canvas');
  const box=await canvas.boundingBox();
  await canvas.click({position:{x:box.width/2,y:box.height-(info.project.name.includes('phone')?90:74)}});
  await page.getByRole('button',{name:'Rename',exact:true}).first().dispatchEvent('click');
  const input=page.locator('#app input, #app textarea');
  await expect(input).toBeAttached();
  await input.fill('edited');
  const waiting=page.waitForEvent('download');await page.locator('#export').click();
  const download=await waiting;const archive=unzipSync(await readFile(await download.path()));
  expect(new TextDecoder().decode(archive['test.lab'])).toContain('edited');
});

test('downloads still work when browser storage is blocked', async ({page}) => {
  await page.addInitScript(()=>Object.defineProperty(window,'indexedDB',{get(){throw new Error('blocked for test');}}));
  await boot(page);
  await expect(page.locator('#notice')).toBeVisible();
  await page.locator('#file-input').setInputFiles({name:'test.wav',mimeType:'audio/wav',buffer:wav()});
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/.mlabeler'))).toBe(true);
  await expect(page.locator('#export')).toBeEnabled();
  const waiting=page.waitForEvent('download'); await page.locator('#export').click();
  const download=await waiting;const archive=unzipSync(await readFile(await download.path()));
  expect(Buffer.from(archive['test.wav'])).toEqual(wav());
});

test('records microphone samples and releases the microphone when stopped', async ({page}, info) => {
  test.skip(!info.project.name.startsWith('chromium'), 'Synthetic microphone is configured for Chromium');
  await boot(page);
  await page.locator('#app canvas').click({position:{x:100,y:100}});
  await page.evaluate(()=>{
    window.recorded=0;window.micAllowed=null;
    MLabelerWeb.microphonePermission(ok=>{
      window.micAllowed=ok;
      if(ok) MLabelerWeb.record(16000,chunk=>window.recorded+=chunk.length/4);
    });
  });
  await expect.poll(()=>page.evaluate(()=>window.micAllowed)).toBe(true);
  await expect.poll(()=>page.evaluate(()=>window.recorded),{timeout:15000}).toBeGreaterThan(5000);
  expect(await page.evaluate(()=>MLabelerWeb.recording())).toBe(true);
  await page.evaluate(()=>MLabelerWeb.stopRecording());
  expect(await page.evaluate(()=>MLabelerWeb.recording())).toBe(false);
});

test('uses Command shortcuts on macOS', async ({page}, info) => {
  test.skip(info.project.name.includes('phone'), 'Desktop keyboard workflow');
  await page.addInitScript(()=>{
    Object.defineProperty(navigator,'platform',{get:()=> 'MacIntel'});
    Object.defineProperty(navigator,'userAgentData',{get:()=> ({platform:'macOS'})});
  });
  await boot(page);
  expect(await page.evaluate(()=>MLabelerWeb.mac())).toBe(true);
  await page.locator('#file-input').setInputFiles([
    {name:'test.wav',mimeType:'audio/wav',buffer:wav()},
    {name:'test.lab',mimeType:'text/plain',buffer:Buffer.from('0 10000000 a\n')},
  ]);
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.exists('/project/.mlabeler'))).toBe(true);
  await expect(page.locator('#export')).toBeEnabled();
  const canvas=page.locator('#app canvas'),box=await canvas.boundingBox();
  await canvas.click({position:{x:box.width/2,y:box.height-74}});
  await page.getByRole('button',{name:'Rename',exact:true}).first().dispatchEvent('click');
  const input=page.locator('#app input, #app textarea');await expect(input).toBeAttached();
  await input.fill('edited');await input.press('Enter');await expect(input).not.toBeAttached();
  await page.getByRole('button',{name:'Save',exact:true}).first().dispatchEvent('click');
  await expect.poll(()=>page.evaluate(()=>MLabelerWeb.read('/project/test.lab'))).toContain('edited');
  await canvas.focus();await page.keyboard.press('Meta+z');
  const waiting=page.waitForEvent('download');await page.locator('#export').click();
  const download=await waiting;const archive=unzipSync(await readFile(await download.path()));
  expect(new TextDecoder().decode(archive['test.lab'])).not.toContain('edited');
});
