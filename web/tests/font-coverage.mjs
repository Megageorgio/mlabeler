// Read Unicode cmap mappings, including fonts with only BMP format-4 tables.
export function fontHasGlyph(b) {
  let cmap;
  for (let i = 0; i < b.readUInt16BE(4); i++) if (b.toString('ascii', 12 + 16*i, 16 + 16*i) === 'cmap') cmap = b.readUInt32BE(20 + 16*i);
  const tables = [];
  for (let i = 0; i < b.readUInt16BE(cmap+2); i++) {
    const p = cmap + b.readUInt32BE(cmap+8+8*i), format = b.readUInt16BE(p);
    if (format === 12) {
      const groups = [];
      for (let j = 0; j < b.readUInt32BE(p+12); j++) groups.push([b.readUInt32BE(p+16+12*j), b.readUInt32BE(p+20+12*j), b.readUInt32BE(p+24+12*j)]);
      tables.push(c => groups.some(([a,z,g]) => c >= a && c <= z && g+c-a > 0));
    } else if (format === 4) {
      const n = b.readUInt16BE(p+6)/2, end = p+14, start = end+2*n+2, delta = start+2*n, range = delta+2*n;
      tables.push(c => {
        for (let j = 0; j < n; j++) {
          if (c > b.readUInt16BE(end+2*j)) continue;
          const a = b.readUInt16BE(start+2*j); if (c < a) return false;
          const d = b.readInt16BE(delta+2*j), r = b.readUInt16BE(range+2*j);
          const g = r ? b.readUInt16BE(range+2*j+r+2*(c-a)) : c;
          return (!r || g > 0) && ((g+d)&65535) !== 0;
        }
        return false;
      });
    }
  }
  return c => tables.some(test => test(c));
}
