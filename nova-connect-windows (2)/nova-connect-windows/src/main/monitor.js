const http = require('http'), https = require('https'), tls = require('tls'), net = require('net');
const { XrayProc } = require('./xray');
// مثل v2rayN از HTTPS استفاده می‌کنیم؛ بسیاری از سرورها پورت ۸۰ را مسدود می‌کنند
const TEST_URLS = ['https://www.google.com/generate_204', 'https://cp.cloudflare.com/generate_204', 'http://www.gstatic.com/generate_204'];

function probeHttp(port, url, timeout) {
  return new Promise((res) => {
    const u = new URL(url), t0 = Date.now();
    const req = http.request({ host: '127.0.0.1', port, method: 'GET', path: url, headers: { Host: u.host, 'User-Agent': 'NovaConnect', Connection: 'close' }, timeout }, (r) => {
      r.resume(); r.on('end', () => res(r.statusCode === 204 || r.statusCode === 200 ? { ms: Date.now() - t0 } : { err: 'http ' + r.statusCode })); r.on('error', () => res({ err: 'read' }));
    });
    req.on('timeout', () => { req.destroy(); res({ err: 'timeout' }); }); req.on('error', (e) => res({ err: e.code || 'error' })); req.end();
  });
}
function probeHttps(port, url, timeout) {
  return new Promise((res) => {
    const u = new URL(url), t0 = Date.now(); let done = false, sock = null;
    const fin = (r) => { if (done) return; done = true; clearTimeout(tm); try { sock && sock.destroy(); } catch {} res(r); };
    const tm = setTimeout(() => fin({ err: 'timeout' }), timeout);
    const req = http.request({ host: '127.0.0.1', port, method: 'CONNECT', path: `${u.hostname}:443`, headers: { Host: `${u.hostname}:443` } });
    req.on('connect', (r, socket) => {
      sock = socket; socket.on('error', () => fin({ err: 'socket' }));
      if (r.statusCode !== 200) return fin({ err: 'proxy ' + r.statusCode });
      const t = tls.connect({ socket, servername: u.hostname }); sock = t; let buf = '';
      t.once('secureConnect', () => t.write(`GET ${u.pathname} HTTP/1.1\r\nHost: ${u.hostname}\r\nUser-Agent: NovaConnect\r\nConnection: close\r\n\r\n`));
      t.on('data', (d) => { buf += d; const m = /^HTTP\/1\.[01] (\d{3})/.exec(buf); if (m) fin(m[1] === '204' || m[1] === '200' ? { ms: Date.now() - t0 } : { err: 'http ' + m[1] }); });
      t.on('error', (e) => fin({ err: 'tls ' + (e.code || '') })); t.on('close', () => fin({ err: 'closed' }));
    });
    req.on('error', (e) => fin({ err: e.code || 'error' })); req.end();
  });
}
// تأخیر یک درخواست روی اتصال باز و برقرارشده (همان چیزی که کاربر در هر درخواست حس می‌کند)
function probeWarm(port, url, timeout) {
  return new Promise((res) => {
    const u = new URL(url); if (u.protocol !== 'https:') return res({ err: 'n/a' });
    let done = false, sock = null, stage = 0, t0 = 0, buf = '';
    const fin = (r) => { if (done) return; done = true; clearTimeout(tm); try { sock && sock.destroy(); } catch {} res(r); };
    const tm = setTimeout(() => fin({ err: 'timeout' }), timeout);
    const get = `GET ${u.pathname} HTTP/1.1\r\nHost: ${u.hostname}\r\nUser-Agent: NovaConnect\r\nConnection: keep-alive\r\n\r\n`;
    const req = http.request({ host: '127.0.0.1', port, method: 'CONNECT', path: `${u.hostname}:443`, headers: { Host: `${u.hostname}:443` } });
    req.on('connect', (r, socket) => {
      sock = socket; socket.on('error', () => fin({ err: 'socket' })); if (r.statusCode !== 200) return fin({ err: 'proxy' });
      const t = tls.connect({ socket, servername: u.hostname }); sock = t;
      t.once('secureConnect', () => t.write(get));
      t.on('data', (d) => { buf += d; if (buf.indexOf('\r\n\r\n') < 0) return; const code = (/^HTTP\/1\.[01] (\d{3})/.exec(buf) || [])[1]; buf = '';
        if (code !== '204') return fin({ err: 'http ' + code }); if (stage === 0) { stage = 1; t0 = Date.now(); t.write(get); } else fin({ ms: Date.now() - t0 }); });
      t.on('error', (e) => fin({ err: 'tls ' + (e.code || '') })); t.on('close', () => fin({ err: 'closed' }));
    });
    req.on('error', (e) => fin({ err: e.code || 'error' })); req.end();
  });
}
const probe = (port, url, timeout) => (url.startsWith('https') ? probeHttps(port, url, timeout) : probeHttp(port, url, timeout));
async function probeAny(port, urls = TEST_URLS, timeout = 6000) {
  let last = { err: 'none' };
  for (const u of urls) { const r = await probe(port, u, timeout); if (r.ms != null) return r; last = r; }
  return last;
}
const tcpOk = (host, port) => new Promise((res) => { const c = net.connect({ host, port, timeout: 4000 }); c.once('connect', () => { c.destroy(); res(true); }); c.once('timeout', () => { c.destroy(); res(false); }); c.once('error', () => res(false)); });

const { applyVariant, VARIANTS } = require('./xray-config');
async function once(srv) {
  const p = new XrayProc();
  try {
    await p.start(srv); const a = await probeAny(p.httpPort);
    if (a.ms != null) { const b = a.ms < 4000 ? await probeWarm(p.httpPort, TEST_URLS[0], 6000) : {}; return { ms: b.ms != null ? Math.min(a.ms, b.ms) : a.ms }; }
    return { reason: 'noresp', detail: a.err };
  } catch (e) { return { reason: 'core', detail: e.message + (e.detail ? ' | ' + e.detail : '') }; } finally { p.stop(); }
}
async function testServer(server, variant) {
  const r = await once(applyVariant(server, variant));
  if (r.reason === 'noresp') r.tcp = await tcpOk(server.address, server.port);
  return r;
}
async function testAll(servers, onResult, variant, concurrency = 3) {
  let i = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, servers.length) }, async () => {
    while (i < servers.length) { const s = servers[i++]; onResult(s, await testServer(s, variant)); }
  }));
}
// وقتی هیچ سروری عبور نمی‌دهد ولی TCP باز است، حالت سازگاری مناسب را پیدا می‌کند
async function discover(servers, onStep) {
  const cands = []; for (const s of servers) { if (cands.length >= 2) break; if (await tcpOk(s.address, s.port)) cands.push(s); }
  if (!cands.length) return null;
  const total = VARIANTS.length;
  for (let i = 0; i < total; i++) {
    const v = VARIANTS[i]; if (onStep) onStep(i, total, v);
    const rs = await Promise.all(cands.map((s) => once(applyVariant(s, v))));
    if (rs.some((r) => r.reason === 'core')) return null;
    if (onStep) onStep(i + 1, total, v);
    if (rs.some((r) => r.ms != null)) return v;
  }
  return null;
}
module.exports = { probe, probeAny, testAll, discover, once, TEST_URLS, tcpOk };
