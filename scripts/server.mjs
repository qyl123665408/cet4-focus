import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
const getArg = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const root = path.resolve(process.cwd(), getArg('--root', '.'));
const port = Number(getArg('--port', '4173'));
const host = getArg('--host', '127.0.0.1');
const mimeTypes = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon'
};

const server = http.createServer((request, response) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('Method Not Allowed');
    return;
  }
  const requestPath = decodeURIComponent(new URL(request.url, `http://${host}:${port}`).pathname);
  const relativePath = requestPath === '/' ? 'index.html' : requestPath.replace(/^\/+/, '');
  const candidate = path.resolve(root, relativePath);
  if (!candidate.startsWith(root + path.sep) && candidate !== root) {
    response.writeHead(403);
    response.end('Forbidden');
    return;
  }
  const filePath = fs.existsSync(candidate) && fs.statSync(candidate).isFile() ? candidate : path.join(root, 'index.html');
  if (!fs.existsSync(filePath)) {
    response.writeHead(404);
    response.end('Not Found');
    return;
  }
  const contentType = mimeTypes[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
  response.writeHead(200, { 'Content-Type': contentType, 'Cache-Control': 'no-cache' });
  if (request.method === 'HEAD') response.end();
  else fs.createReadStream(filePath).pipe(response);
});

server.listen(port, host, () => {
  console.log(`四级 Focus running at http://${host}:${port}/`);
  console.log(`Serving ${root}`);
});
