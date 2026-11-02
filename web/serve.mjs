import http from 'node:http';
import {readFile, stat} from 'node:fs/promises';
import {resolve, extname, sep} from 'node:path';
const root = resolve('../app/build/web-deploy');
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript','.mjs':'text/javascript','.wasm':'application/wasm','.css':'text/css','.json':'application/json','.png':'image/png'};
http.createServer(async (req,res) => {
  try {
    // Test a subdirectory deployment as well as a root deployment.
    const url = new URL(req.url, 'http://localhost');
    const path = decodeURIComponent(url.pathname.replace(/^\/mlabeler\//, '/'));
    let file = resolve(root, '.' + path);
    if (file !== root && !file.startsWith(root + sep)) throw new Error('path');
    if ((await stat(file)).isDirectory()) file = resolve(file,'index.html');
    res.writeHead(200, {'Content-Type':types[extname(file)] || 'application/octet-stream', 'Cache-Control':'no-store'});
    res.end(await readFile(file));
  } catch { res.writeHead(404); res.end('Not found'); }
}).listen(4173, '127.0.0.1');
