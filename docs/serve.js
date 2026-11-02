#!/usr/bin/env node
/* 简笺网站 · 本地静态服务器
 * 用法: node serve.js [目录] [端口]
 * 默认: ./site 8080
 *
 * 带 gzip 压缩与缓存头，域名穿透/弱网访问时加载更快。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const httpsMod = require('https');

const root = path.resolve(process.argv[2] || path.join(__dirname, 'site'));
const port = Number(process.argv[3] || process.env.PORT || 8080);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8',
  '.apk': 'application/vnd.android.package-archive'
};

const COMPRESSIBLE = new Set(['.html', '.js', '.mjs', '.css', '.json', '.webmanifest', '.svg', '.txt']);
const NO_CACHE = new Set(['.html', '.webmanifest']);
const gzCache = new Map();

function gzipFor(file, mtime) {
  const hit = gzCache.get(file);
  if (hit && hit.mtime === mtime) return hit.gz;
  const gz = zlib.gzipSync(fs.readFileSync(file), { level: 9 });
  gzCache.set(file, { mtime: mtime, gz: gz });
  return gz;
}


/* WebDAV 反向代理：浏览器直连被 CORS 拦截时，网页版可通过同源 /api/dav 走这里 */
function proxyDav(payload, cb) {
  try {
    const u = new URL(payload.url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return cb(new Error('仅支持 http/https'));
    const mod = u.protocol === 'https:' ? httpsMod : http;
    const headers = Object.assign({}, payload.headers || {});
    delete headers['host'];
    const data = payload.body ? Buffer.from(payload.body, 'base64') : null;
    if (data) headers['content-length'] = data.length;
    const req = mod.request({
      method: payload.method || 'PROPFIND',
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + (u.search || ''),
      headers: headers,
      timeout: 30000,
      rejectUnauthorized: false
    }, res => {
      const chunks = [];
      let size = 0;
      res.on('data', c => { size += c.length; if (size <= 16 * 1024 * 1024) chunks.push(c); });
      res.on('end', () => cb(null, {
        status: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('base64')
      }));
    });
    req.on('timeout', () => req.destroy(new Error('上游超时')));
    req.on('error', e => cb(e));
    if (data) req.write(data);
    req.end();
  } catch (e) { cb(e); }
}

http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/api/dav' && req.method === 'POST') {
    let raw = '';
    req.on('data', c => { raw += c; if (raw.length > 24 * 1024 * 1024) req.destroy(); });
    req.on('end', () => {
      let payload = null;
      try { payload = JSON.parse(raw); } catch (e) { }
      if (!payload || !payload.url) {
        res.writeHead(400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: 'BAD_REQUEST' }));
        return;
      }
      proxyDav(payload, (err, out) => {
        if (err) {
          res.writeHead(502, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: String(err.message || err) }));
        } else {
          res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(out));
        }
      });
    });
    return;
  }
  if (p.endsWith('/')) p += 'index.html';
  const file = path.join(root, path.normalize(p).replace(/^(\.\.[/\\])+/, ''));
  if (!file.startsWith(root)) { res.writeHead(403).end('Forbidden'); return; }

  fs.stat(file, (serr, st) => {
    if (serr || !st.isFile()) {
      fs.readFile(path.join(root, 'index.html'), (e2, idx) => {
        if (e2) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('404 Not Found'); return; }
        res.writeHead(200, { 'Content-Type': MIME['.html'], 'Cache-Control': 'no-cache' }).end(idx);
      });
      return;
    }
    const ext = path.extname(file).toLowerCase();
    const headers = {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': NO_CACHE.has(ext) || p.endsWith('sw.js') ? 'no-cache' : 'public, max-age=600',
      'X-Content-Type-Options': 'nosniff',
      'Vary': 'Accept-Encoding'
    };

    const acceptsGzip = /\bgzip\b/.test(req.headers['accept-encoding'] || '');
    if (acceptsGzip && COMPRESSIBLE.has(ext) && st.size > 1024) {
      try {
        const gz = gzipFor(file, st.mtimeMs);
        headers['Content-Encoding'] = 'gzip';
        headers['Content-Length'] = gz.length;
        res.writeHead(200, headers);
        res.end(req.method === 'HEAD' ? undefined : gz);
        return;
      } catch (e) {
        /* 压缩失败时回退到原始内容 */
      }
    }
    headers['Content-Length'] = st.size;
    res.writeHead(200, headers);
    if (req.method === 'HEAD') { res.end(); return; }
    fs.createReadStream(file).pipe(res);
  });
}).listen(port, '127.0.0.1', () => {
  console.log('简笺网站已启动: http://127.0.0.1:' + port + '  (root=' + root + ', gzip 已开启)');
});
