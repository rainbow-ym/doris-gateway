// Local, read-only UI review against the test server. Never ship credentials.
const http = require('http');
const fs = require('fs');
const path = require('path');
const password = process.env.QA_ADMIN_PASSWORD;
if (!password) throw new Error('QA_ADMIN_PASSWORD is required');
http.createServer(async (req, res) => {
  if (req.method !== 'GET') { res.writeHead(405); return res.end('Read-only preview'); }
  if (req.url === '/') {
    res.writeHead(200, {'Content-Type':'text/html; charset=utf-8'});
    return res.end(fs.readFileSync(path.join(__dirname,'../admin/src/main/resources/static/index.html')));
  }
  if (!req.url.startsWith('/api/')) { res.writeHead(404); return res.end(); }
  try {
    const target = new URL('http://192.168.232.132:18080');
    target.pathname = req.url.split('?')[0]; target.search = req.url.includes('?') ? req.url.substring(req.url.indexOf('?')) : '';
    const upstream = await fetch(target, {headers:{Authorization:'Basic '+Buffer.from('admin:'+password).toString('base64')}});
    res.writeHead(upstream.status, {'Content-Type':'application/json; charset=utf-8'});
    res.end(await upstream.text());
  } catch (_) { res.writeHead(502); res.end('{"message":"QA upstream unavailable"}'); }
}).listen(18081,'127.0.0.1',()=>console.log('Read-only UI preview at http://127.0.0.1:18081'));
