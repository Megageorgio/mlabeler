// Audio supported by the browser edition plus labels and voicebank/project sidecars.
// Keep the predicate separate from `accept`: directory pickers and drops ignore accept.
export const PROJECT_EXTENSIONS = ['wav', 'lab', 'textgrid', 'txt', 'ds', 'csv', 'seg', 'trans', 'inf', 'ini', 'json', 'ust', 'yaml', 'yml', 'png', 'jpg', 'jpeg', 'webp', 'bmp', 'frq', 'd'];
export const PROJECT_ACCEPT = [...PROJECT_EXTENSIONS, ...Array.from({length: 10}, (_, i) => `as${i}`), 'zip'].map(ext => `.${ext}`).join(',');
export function isProjectFile(path) {
  const parts = path.replaceAll('\\', '/').split('/');
  if (parts.some(p => p === '__MACOSX' || (p.startsWith('.') && p !== '.mlabeler'))) return false;
  const ext = parts.at(-1).split('.').at(-1).toLowerCase();
  return PROJECT_EXTENSIONS.includes(ext) || /^as\d+$/.test(ext);
}
export function matchesAccept(name, accept) {
  return !accept || accept.split(',').some(ext => name.toLowerCase().endsWith(ext.trim().toLowerCase()));
}
