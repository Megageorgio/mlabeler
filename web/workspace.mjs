// All paths are virtual. Archive names must never escape the selected project.
export const MAX_BYTES = 256 * 1024 * 1024;
export const MAX_FILES = 20000;
export function cleanPath(path) {
  const parts = path.replaceAll('\\', '/').split('/');
  if (parts.some(p => p === '..' || p.includes('\0') || p.includes(':'))) throw new Error('invalidPath');
  return '/' + parts.filter(p => p && p !== '.').join('/');
}
export function importPath(path) {
  if (/^[\\/]/.test(path)) throw new Error('invalidPath');
  const clean = cleanPath(path);
  if (clean === '/') throw new Error('invalidPath');
  return '/project' + clean;
}
export class Workspace {
  constructor(changed = () => {}) {
    this.files = new Map();
    this.dirs = new Set(['/', '/settings', '/project']);
    this.changed = changed;
    this.total = 0;
  }
  exists(p) { p = cleanPath(p); return this.files.has(p) || this.dirs.has(p); }
  isDirectory(p) { return this.dirs.has(cleanPath(p)); }
  mkdirs(p) {
    p = cleanPath(p);
    let current = '';
    for (const part of p.split('/').filter(Boolean)) {
      current += '/' + part;
      if (this.files.has(current)) throw new Error('invalidPath');
      this.dirs.add(current);
    }
  }
  list(p) {
    p = cleanPath(p);
    if (!this.dirs.has(p)) throw new Error('missingFile');
    const prefix = p === '/' ? '/' : p + '/';
    return [...new Set([...this.dirs, ...this.files.keys()].filter(k => k.startsWith(prefix) && k !== p && !k.slice(prefix.length).includes('/')))];
  }
  read(p) {
    const f = this.files.get(cleanPath(p));
    if (!f) throw new Error('missingFile');
    return f.data;
  }
  write(p, data, modified = Date.now(), notify = true) {
    p = cleanPath(p);
    if (this.dirs.has(p)) throw new Error('invalidPath');
    const old = this.files.get(p)?.data.byteLength || 0;
    if (this.total - old + data.byteLength > MAX_BYTES || (!this.files.has(p) && this.files.size >= MAX_FILES)) throw new Error('tooLarge');
    this.mkdirs(p.slice(0, p.lastIndexOf('/')) || '/');
    this.files.set(p, {data, modified});
    this.total += data.byteLength - old;
    if (notify) this.changed(p);
  }
  delete(p) {
    p = cleanPath(p);
    const file = this.files.get(p);
    if (file) { this.total -= file.data.byteLength; this.files.delete(p); this.changed(p); return true; }
    if (p === '/' || p === '/project' || p === '/settings' || !this.dirs.has(p) || this.list(p).length) return false;
    this.dirs.delete(p); return true;
  }
  rename(from, to) {
    from = cleanPath(from); to = cleanPath(to);
    // App saves use a sibling temporary file, then a rename. Never partially rename a tree.
    if (!this.files.has(from) || this.exists(to)) return false;
    this.mkdirs(to.slice(0, to.lastIndexOf('/')) || '/');
    this.files.set(to, this.files.get(from)); this.files.delete(from);
    this.changed(from); this.changed(to); return true;
  }
  replaceProject(incoming) {
    // Validate everything before replacing anything, including the combined settings budget.
    const next = new Workspace();
    for (const [path, file] of this.files) if (!path.startsWith('/project/')) next.write(path, file.data, file.modified, false);
    const seen = new Set();
    for (const [path, file] of incoming) {
      const target = importPath(path);
      if (seen.has(target)) throw new Error('invalidPath');
      seen.add(target); next.write(target, file.data, file.modified, false);
    }
    this.files = next.files; this.dirs = next.dirs; this.total = next.total;
    this.changed('/project');
  }
}
