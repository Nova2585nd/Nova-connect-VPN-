// دانلود tun2socks و wintun برای حالت TUN. در صورت خطا ساخت متوقف نمی‌شود؛ فقط حالت TUN غیرفعال می‌ماند.
const fs = require('fs'), path = require('path'), crypto = require('crypto'), os = require('os'), { execFileSync } = require('child_process');
const out = path.join(__dirname, '../resources/tun'); fs.mkdirSync(out, { recursive: true });
const T2S = process.env.TUN2SOCKS_VERSION || '2.5.2', WINTUN = process.env.WINTUN_VERSION || '0.14.1';
const get = async (u) => { const r = await fetch(u, { redirect: 'follow' }); if (!r.ok) throw new Error(`${u} (${r.status})`); return Buffer.from(await r.arrayBuffer()); };
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
function unzip(zip, dest) {
  fs.mkdirSync(dest, { recursive: true });
  if (process.platform === 'win32') execFileSync('powershell', ['-NoProfile', '-Command', `Expand-Archive -Force -Path '${zip}' -DestinationPath '${dest}'`]); else execFileSync('unzip', ['-o', zip, '-d', dest]);
}
const find = (dir, re) => { for (const f of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, f.name); if (f.isDirectory()) { const r = find(p, re); if (r) return r; } else if (re.test(f.name) && /amd64|x64|wintun\.dll|tun2socks.*\.exe/i.test(p)) return p; } return null; };
(async () => {
  try {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nova-tun-'));
    if (!fs.existsSync(path.join(out, 'tun2socks.exe'))) {
      const d = await get(`https://github.com/xjasonlyu/tun2socks/releases/download/v${T2S}/tun2socks-windows-amd64.zip`); console.log('tun2socks sha256:', sha(d));
      const z = path.join(tmp, 't2s.zip'); fs.writeFileSync(z, d); unzip(z, path.join(tmp, 't2s'));
      const f = find(path.join(tmp, 't2s'), /^tun2socks.*\.exe$/i); if (!f) throw new Error('tun2socks.exe داخل فایل نبود'); fs.copyFileSync(f, path.join(out, 'tun2socks.exe'));
    }
    if (!fs.existsSync(path.join(out, 'wintun.dll'))) {
      const d = await get(`https://www.wintun.net/builds/wintun-${WINTUN}.zip`); console.log('wintun sha256:', sha(d), '(با مقدار منتشرشده در wintun.net مقایسه کنید)');
      const z = path.join(tmp, 'wt.zip'); fs.writeFileSync(z, d); unzip(z, path.join(tmp, 'wt'));
      const f = find(path.join(tmp, 'wt'), /^wintun\.dll$/i); if (!f) throw new Error('wintun.dll داخل فایل نبود'); fs.copyFileSync(f, path.join(out, 'wintun.dll'));
    }
    console.log('اجزای TUN آماده شد.');
  } catch (e) { console.warn('هشدار: اجزای TUN آماده نشد؛ حالت TUN در این ساخت غیرفعال است:', e.message); }
})();
