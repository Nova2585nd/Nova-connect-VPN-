// حالت TUN در ویندوز: tun2socks + درایور Wintun؛ نیازمند دسترسی ادمین
const { app } = require('electron'); const { execFile, spawn } = require('child_process');
const dns = require('dns').promises, net = require('net'), path = require('path'), fs = require('fs'); const log = require('./logger');
const NAME = 'NovaTun', ADDR = '198.18.0.1', HOP = '198.18.0.2';
let proc = null, exitCb = null;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const dir = () => (app.isPackaged ? path.join(process.resourcesPath, 'tun') : path.join(__dirname, '../../resources/tun'));
const available = () => process.platform === 'win32' && fs.existsSync(path.join(dir(), 'tun2socks.exe')) && fs.existsSync(path.join(dir(), 'wintun.dll'));
const run = (cmd, args, t = 15000) => new Promise((res, rej) => execFile(cmd, args, { windowsHide: true, timeout: t }, (e, out, err) => (e ? rej(new Error(((out || '') + (err || '')).trim() || e.message)) : res(out || ''))));
async function isAdmin() { if (process.platform !== 'win32') return false; try { await run('fltmc', [], 6000); return true; } catch { return false; } }
const setExitHandler = (fn) => { exitCb = fn; };

// آدرس سرور به IPv4 تبدیل می‌شود تا یک مسیر ثابت برای عبور مستقیم (بدون حلقه) بسازیم
async function prepareServer(s) {
  const fam = net.isIP(s.address);
  if (fam === 6) throw new Error('آدرس IPv6 در حالت TUN پشتیبانی نمی‌شود.');
  if (fam === 4) return { server: s, ip: s.address };
  const r = await dns.lookup(s.address, { family: 4 }).catch(() => null);
  if (!r) throw new Error('آدرس سرور پیدا نشد (خطای DNS).');
  const o = { ...s, address: r.address };
  if (s.tls !== 'none' && !s.sni) o.sni = s.address;
  if (!s.host && ['ws', 'httpupgrade', 'xhttp', 'http', 'h2'].includes(s.network)) o.host = s.address;
  return { server: o, ip: r.address };
}
async function defaultRoute() {
  const out = await run('powershell', ['-NoProfile', '-NonInteractive', '-Command', 'Get-NetRoute -DestinationPrefix 0.0.0.0/0 | Sort-Object {$_.RouteMetric+$_.InterfaceMetric} | Select-Object -First 1 NextHop,InterfaceIndex | ConvertTo-Json -Compress']);
  const j = JSON.parse(out); return { gw: j.NextHop, idx: j.InterfaceIndex };
}
async function enable(store, save, { socksPort, ip }) {
  if (!available()) throw new Error('اجزای حالت TUN (tun2socks و wintun) همراه برنامه نیست.');
  if (!(await isAdmin())) throw new Error('برای حالت TUN برنامه باید با دسترسی ادمین اجرا شود.');
  await restore(store, save);
  try {
    const { gw, idx } = await defaultRoute();
    const extra = await dns.resolve4('nova2585nd.ir').catch(() => []); // درخواست‌های بک‌اند هم مستقیم بروند
    const ips = [ip, ...extra.filter((x) => x !== ip)];
    store.tunOwned = true; store.tunState = { ips }; save();
    for (const x of ips) await run('route', ['add', x, 'mask', '255.255.255.255', gw, 'metric', '1', ...(gw === '0.0.0.0' ? ['if', String(idx)] : [])]);
    const p = spawn(path.join(dir(), 'tun2socks.exe'), ['-device', 'tun://' + NAME, '-proxy', 'socks5://127.0.0.1:' + socksPort, '-loglevel', 'warn'], { cwd: dir(), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    proc = p; let tail = '', exited = false; const add = (d) => { tail = (tail + d).slice(-500); }; p.stdout.on('data', add); p.stderr.on('data', add);
    p.on('error', (e) => { tail += e.message; exited = true; });
    p.on('exit', () => { exited = true; if (!p.novaStop && exitCb) exitCb(); });
    let up = false;
    for (let i = 0; i < 48 && !up; i++) { if (exited) throw new Error('tun2socks بسته شد: ' + tail); await sleep(250); up = (await run('netsh', ['interface', 'ipv4', 'show', 'interfaces']).catch(() => '')).includes(NAME); }
    if (!up) throw new Error('کارت شبکه مجازی ساخته نشد. ' + tail);
    await run('netsh', ['interface', 'ipv4', 'set', 'address', `name=${NAME}`, 'source=static', `address=${ADDR}`, 'mask=255.255.0.0']);
    await run('netsh', ['interface', 'ipv4', 'set', 'dnsservers', `name=${NAME}`, 'source=static', 'address=1.1.1.1', 'register=none', 'validate=no']);
    await run('netsh', ['interface', 'ipv4', 'add', 'dnsservers', `name=${NAME}`, 'address=8.8.8.8', 'index=2', 'validate=no']).catch(() => {});
    await run('netsh', ['interface', 'ipv4', 'set', 'interface', `interface=${NAME}`, 'metric=1']).catch(() => {});
    for (const pre of ['0.0.0.0/1', '128.0.0.0/1']) await run('netsh', ['interface', 'ipv4', 'add', 'route', `prefix=${pre}`, `interface=${NAME}`, `nexthop=${HOP}`, 'metric=1', 'store=active']);
  } catch (e) { log.error('tun enable failed:', e.message); await restore(store, save).catch(() => {}); throw new Error('راه‌اندازی TUN ناموفق بود: ' + e.message.slice(0, 160)); }
}
async function restore(store, save) {
  if (!store.tunOwned && !proc) return;
  if (proc) { proc.novaStop = true; try { proc.kill(); } catch {} proc = null; } else await run('taskkill', ['/IM', 'tun2socks.exe', '/F'], 5000).catch(() => {});
  await sleep(400);
  for (const ip of (store.tunState && store.tunState.ips) || []) await run('route', ['delete', ip], 5000).catch(() => {});
  store.tunOwned = false; store.tunState = null; save();
}
module.exports = { available, isAdmin, prepareServer, enable, restore, setExitHandler };
