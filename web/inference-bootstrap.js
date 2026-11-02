// Loaded only when quick labelling is requested. No microphone, files or network uploads.
let run, pending, config, weights;
const binary = bytes => { let s = ''; for (let i=0;i<bytes.length;i+=16384) s += String.fromCharCode(...bytes.subarray(i,i+16384)); return s; };
self.MLabelerInference = {
  config: () => config,
  weights: () => { const value = weights; weights = null; return value; },
  install: callback => { run = callback; if (pending) dispatch(pending); },
  progress: value => postMessage({type:'progress', value}),
  result: value => postMessage({type:'result', value}),
  failure: value => postMessage({type:'failure', value}),
};
function dispatch(job) { pending = null; run(binary(new Uint8Array(job.buffer)), job.rate, job.language, job.known, job.confidence); }
self.onmessage = e => { if (run) dispatch(e.data); else pending = e.data; };
async function boot() {
  const root = '../mcfl/MCFL_multi13_s_v0.0.1/';
  const [c,w] = await Promise.all([fetch(root+'model.json'), fetch(root+'model.safetensors')]);
  if (!c.ok || !w.ok) throw new Error('Model download failed');
  config = await c.text(); weights = binary(new Uint8Array(await w.arrayBuffer()));
  importScripts('inference.js');
}
boot().catch(e => postMessage({type:'failure', value:e.message}));
