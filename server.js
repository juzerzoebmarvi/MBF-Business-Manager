const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.DEFAULT_APP_PORT || process.env.WEB_PORT || 3000);
const HOST = '0.0.0.0';
const HTML_PATH = path.join(__dirname, 'app', 'src', 'main', 'assets', 'index.html');
const PUBLIC_DIR = path.resolve(__dirname, 'public');
const BAT_PATH = path.join(__dirname, 'Start-Business-Manager.bat');

const MIME_TYPES = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.zip': 'application/zip',
  '.bat': 'application/x-bat',
  '.command': 'text/plain; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8'
};

process.on('uncaughtException', (err) => {
  console.error('Server uncaughtException:', err && err.stack ? err.stack : err);
});

process.on('unhandledRejection', (err) => {
  console.error('Server unhandledRejection:', err && err.stack ? err.stack : err);
});

function sendHtml(res, isHead, headers = {}) {
  fs.readFile(HTML_PATH, (err, data) => {
    if (err) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      if (!isHead) res.end('Error loading Business Manager: ' + err.message);
      else res.end();
      return;
    }
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Content-Length': data.length,
      'Cache-Control': 'no-cache',
      ...headers
    });
    if (!isHead) res.end(data);
    else res.end();
  });
}

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const isHead = req.method === 'HEAD';
  let reqPath = '/';
  try {
    reqPath = decodeURIComponent((req.url || '/').split('?')[0]);
  } catch (e) {
    reqPath = '/';
  }

  if (reqPath === '/health') {
    const body = JSON.stringify({ status: 'ok', app: 'Business Manager', port: PORT });
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
      'Cache-Control': 'no-store'
    });
    if (!isHead) res.end(body);
    else res.end();
    return;
  }

  if (reqPath === '/download' || reqPath === '/business-manager-web.html') {
    sendHtml(res, isHead, {
      'Content-Disposition': 'attachment; filename="business-manager-web.html"'
    });
    return;
  }

  if (reqPath === '/run-laptop.bat' || reqPath === '/run-business-manager.bat' || reqPath === '/Start-Business-Manager.bat') {
    fs.readFile(BAT_PATH, (err, data) => {
      if (err) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
        if (!isHead) res.end('Launcher not found');
        else res.end();
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'application/x-bat',
        'Content-Length': data.length,
        'Content-Disposition': 'attachment; filename="Start-Business-Manager.bat"',
        'Cache-Control': 'no-cache'
      });
      if (!isHead) res.end(data);
      else res.end();
    });
    return;
  }

  // Serve static files from public/ safely (except public/index.html, which always resolves to the latest synced HTML_PATH)
  if (reqPath !== '/' && reqPath !== '' && reqPath !== '/index.html') {
    const rel = reqPath.replace(/^\/+/, '');
    const staticFilePath = path.resolve(PUBLIC_DIR, rel);
    const relative = path.relative(PUBLIC_DIR, staticFilePath);
    if (relative && !relative.startsWith('..') && !path.isAbsolute(relative)) {
      try {
        if (fs.existsSync(staticFilePath)) {
          const stat = fs.statSync(staticFilePath);
          if (stat.isFile()) {
            const ext = path.extname(staticFilePath).toLowerCase();
            const mime = MIME_TYPES[ext] || 'application/octet-stream';
            const headers = {
              'Content-Type': mime,
              'Content-Length': stat.size,
              'Cache-Control': (ext === '.js' || ext === '.json' || ext === '.zip' || ext === '.bat')
                ? 'no-cache'
                : 'public, max-age=3600'
            };
            if (reqPath === '/sw.js') {
              headers['Service-Worker-Allowed'] = '/';
            }
            res.writeHead(200, headers);
            if (isHead) {
              res.end();
              return;
            }
            const stream = fs.createReadStream(staticFilePath);
            stream.on('error', () => {
              if (!res.headersSent) res.writeHead(500);
              res.end();
            });
            stream.pipe(res);
            return;
          }
        }
      } catch (e) {
        console.error('Static serve error:', e);
      }
    }
  }

  // Default: serve the latest synced Business Manager app
  sendHtml(res, isHead);
});

server.on('error', (err) => {
  console.error('Server listen error:', err && err.message ? err.message : err);
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  console.log(`Business Manager Web Server running on http://${HOST}:${PORT}`);
});
