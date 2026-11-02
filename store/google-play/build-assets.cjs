/* Render the existing vector identity and store banners. Requires Node.js and sharp. */
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');
const folder = __dirname;
const root = path.resolve(folder, '../..');

function banner(subtitle, description) {
  const bars = Array.from({ length: 39 }, (_, i) => {
    const x = 628 + i * 8.2;
    const envelope = Math.pow(Math.sin((i + 1) / 41 * Math.PI), 1.4);
    const h = 22 + envelope * (54 + 152 * Math.abs(Math.sin(i * 1.93) * Math.cos(i * 0.38)));
    return `<path d="M${x.toFixed(2)} ${(257-h/2).toFixed(2)}v${h.toFixed(2)}" stroke="${i>18&&i<25?'#b8ff77':'#80c9aa'}" stroke-opacity="${i>18&&i<25?'1':'.72'}" stroke-width="3" stroke-linecap="round"/>`;
  }).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<defs>
 <linearGradient id="bg"><stop stop-color="#172b29"/><stop offset="1" stop-color="#17232e"/></linearGradient>
 <radialGradient id="glow"><stop stop-color="#5ed063" stop-opacity=".22"/><stop offset="1" stop-color="#5ed063" stop-opacity="0"/></radialGradient>
 <linearGradient id="fade"><stop stop-color="#80c9aa" stop-opacity="0"/><stop offset=".5" stop-color="#80c9aa" stop-opacity=".16"/><stop offset="1" stop-color="#80c9aa" stop-opacity="0"/></linearGradient>
</defs>
<rect width="1024" height="500" fill="url(#bg)"/>
<ellipse cx="805" cy="190" rx="350" ry="330" fill="url(#glow)"/>
<path d="M610 151H975M610 204H975M610 257H975M610 310H975M610 363H975" stroke="url(#fade)"/>
<path d="M628 135V379M710 135V379M792 135V379M874 135V379M956 135V379" stroke="#80c9aa" stroke-opacity=".07"/>
<rect x="777" y="139" width="65" height="236" rx="9" fill="#8df65b" fill-opacity=".055"/>
${bars}
<path d="M793 133V381" stroke="#a1f56d" stroke-width="2"/>
<circle cx="793" cy="133" r="4" fill="#b8ff77"/>
<path d="M72 136h45" stroke="#8df65b" stroke-width="5" stroke-linecap="round"/>
<text x="68" y="220" fill="#f1f7ee" font-family="Segoe UI, DejaVu Sans, sans-serif" font-size="73" font-weight="650" letter-spacing="-2">mLabeler</text>
<text x="72" y="277" fill="#b8ff77" font-family="Segoe UI, DejaVu Sans, sans-serif" font-size="32" font-weight="500">${subtitle}</text>
<text x="72" y="319" fill="#bed0c9" font-family="Segoe UI, DejaVu Sans, sans-serif" font-size="20">${description}</text>
</svg>`;
}

async function main() {
  const assets = path.join(folder, 'assets');
  await fs.mkdir(assets, { recursive: true });
  const icon = (await fs.readFile(path.join(root, 'art/icon.svg'), 'utf8')).replace('rx="74"', 'rx="0"');
  await fs.writeFile(path.join(assets, 'icon-store.svg'), icon);
  await sharp(Buffer.from(icon)).resize(512, 512).ensureAlpha().png().toFile(path.join(assets, 'icon-512.png'));
  for (const [code, title, subtitle] of [
    ['en-US', 'From sound to phonemes', 'Audio annotation and voicebank editing'],
    ['ru-RU', 'От звука — к фонемам', 'Разметка аудио и подготовка войсбанков'],
  ]) {
    const svg = banner(title, subtitle);
    await fs.writeFile(path.join(assets, `feature-graphic-${code}.svg`), svg);
    await sharp(Buffer.from(svg)).removeAlpha().png().toFile(path.join(assets, `feature-graphic-${code}.png`));
  }
  for (const file of await fs.readdir(assets)) {
    if (!file.endsWith('.png')) continue;
    const meta = await sharp(path.join(assets, file)).metadata();
    console.log(`${file}: ${meta.width}x${meta.height}, channels=${meta.channels}, bytes=${(await fs.stat(path.join(assets, file))).size}`);
  }
  // Lossless export of Android captures as RGB PNG; no cropping, scaling or UI changes.
  for (const code of ['en-US', 'ru-RU']) {
    const directory = path.join(assets, 'screenshots', code);
    await fs.mkdir(directory, { recursive: true });
    for (const file of await fs.readdir(directory)) {
      if (!file.endsWith('.png')) continue;
      const output = path.join(directory, file);
      const pixels = await sharp(await fs.readFile(output)).removeAlpha().png().toBuffer();
      await fs.writeFile(output, pixels);
    }
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
