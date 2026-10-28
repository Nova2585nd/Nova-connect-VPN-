const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, clipboard, shell } = require('electron');
const path = require('path'), fs = require('fs'), { execFile } = require('child_process');
const log = require('./logger'), storage = require('./storage'), sub = require('./subscription');
const monitor = require('./monitor'), selector = require('./selector'), network = require('./network'), xray = require('./xray'), diag = require('./diag'), tun = require('./tun'), api = require('./api'), usage = require('./usage');
const { Connection } = require('./connection'); const { renderIcon } = require('./icon');

let win, tray, db, conn, quitting = false, testing = false, discovering = false, lastCheckAt = null, pushTimer = null, coreIssue = null, adminFlag = false, progress = null;
let announcements = [], inviteBonus = null, updateInfo = null, latestLink = '', welcomeNotice = null, quotaNonce = 0, userSyncedAt = 0;
const VCODE = require('../../package.json').novaCode || 1;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const assets = (f) => path.join(__dirname, '../../assets', f);
let iconPath = assets('icon.png');
if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => { if (win) { win.show(); win.focus(); } });
process.on('uncaughtException', (e) => log.error('uncaught', e && e.message)); process.on('unhandledRejection', (e) => log.error('unhandled', e && e.message));

const VLABEL = { nofp: 'بدون اثرانگشت TLS', h1: 'ALPN ساده (http/1.1)', ipv4: 'فقط IPv4', insecure: 'بدون اعتبارسنجی گواهی سرور' };
const save = () => { try { storage.save(); } catch (e) { log.error('save failed', e.message); } };
const shownUpdate = () => (updateInfo && (updateInfo.mandatory || updateInfo.code !== db.dismissedUpdate) ? updateInfo : null);
function snapshot() {
  const rec = selector.recommended(db.servers, db.stats), def = selector.defaultServer(db.servers, db.stats, db.lastGood);
  return { conn: conn.state, version: app.getVersion(), versionCode: VCODE, settings: db.settings, testing, discovering, lastCheckAt, recommendedId: rec, defaultId: def, coreIssue, progress,
    isAdmin: adminFlag, tunAvailable: tun.available(), variant: db.variant ? VLABEL[db.variant] : null,
    account: db.code ? { code: db.code, user: db.user, estimate: usage.estimate(), syncedAt: userSyncedAt, unsynced: usage.unsynced() } : null,
    subError: db.subError, subUpdatedAt: db.subUpdatedAt, announcements, lastSeenAnn: db.lastSeenAnn, priceUnit: db.priceUnit, inviteBonus, inviteDone: db.inviteDone, askInvite: db.askInvite,
    update: shownUpdate(), latestLink, welcomeNotice, quotaNonce,
    servers: db.servers.map((s) => ({ id: s.uid, num: db.numbers[s.uid] || 0, name: s.name, protocol: s.protocol, ...db.stats[s.uid] })) };
}
function push() { clearTimeout(pushTimer); pushTimer = setTimeout(() => { if (win && !win.isDestroyed()) win.webContents.send('state', snapshot()); updateTray(); }, 50); }

// ---- مدل پیشرفت برای صفحه «آماده‌سازی» ----
const STEPS = [['code', 'تأیید کد اشتراک'], ['fetch', 'دریافت لیست کانفیگ‌ها'], ['test', 'سنجش واقعی سرورها'], ['compat', 'سازگارسازی با سرور'], ['pick', 'انتخاب بهترین سرور']];
function pStart() { progress = { run: Date.now(), active: true, done: false, pct: 2, startedAt: Date.now(), result: null, steps: STEPS.map(([id, label]) => ({ id, label, st: 'pending', detail: '' })) }; push(); }
function pStep(id, st, detail, pct) {
  if (!progress) return; const s = progress.steps.find((x) => x.id === id); if (s) { s.st = st; if (detail !== undefined) s.detail = detail; }
  if (pct != null) progress.pct = Math.max(progress.pct, Math.round(pct)); push();
}
function pEnd(result) { if (!progress) return; progress.active = false; progress.done = true; progress.pct = 100; progress.result = result; push(); }

async function runTests(opts = {}) {
  if (testing || !db.servers.length) return; testing = true; const setup = !!opts.setup, n = db.servers.length; let done = 0;
  for (const s of db.servers) if (!db.stats[s.uid]) db.stats[s.uid] = { status: 'testing', latency: null };
  if (setup) pStep('test', 'active', `0 از ${n}`, 22);
  push(); let coreErr = null, anyOk = false;
  const mk = (track) => (s, r) => {
    const o = db.stats[s.uid] || {};
    if (r.ms != null) { db.stats[s.uid] = { status: 'ok', latency: r.ms, at: Date.now(), fails: 0 }; anyOk = true; }
    else { if (r.reason === 'core') coreErr = r.detail; log.error('test failed:', r.reason, r.detail || '');
      db.stats[s.uid] = { status: 'fail', latency: null, reason: r.reason, detail: r.detail, tcp: !!r.tcp, at: Date.now(), fails: (o.fails || 0) + 1, badUntil: o.badUntil }; }
    if (track && setup) { done++; pStep('test', 'active', `${done} از ${n}`, 22 + 33 * done / n); }
    push();
  };
  try {
    await monitor.testAll(db.servers, mk(true), db.variant);
    if (!anyOk && !coreErr) { // احتمالاً یک ناسازگاری سراسری در کانفیگ است؛ حالت‌های سازگاری را امتحان می‌کنیم
      discovering = true; if (setup) { pStep('test', 'done', `${n} سرور بررسی شد؛ هیچ‌کدام ترافیک عبور ندادند`, 50); pStep('compat', 'active', 'در حال امتحان حالت‌های سازگاری…', 52); } push();
      const v = await monitor.discover(db.servers, (i, total, variant) => { if (setup) pStep('compat', 'active', `مرحله ${Math.min(i + 1, total)} از ${total} — ${VLABEL[variant]}`, 50 + 40 * i / total); });
      discovering = false;
      if (v) { db.variant = v; log.info('compat variant:', v); if (setup) { pStep('compat', 'done', VLABEL[v], 90); pStep('pick', 'active', 'بررسی دوباره سرورها با حالت سازگار…', 92); } await monitor.testAll(db.servers, mk(false), v); }
      else if (setup) pStep('compat', 'done', 'حالت سازگار پیدا نشد', 90);
    } else if (setup) { pStep('test', 'done', `${n} سرور بررسی شد`, 90); pStep('compat', 'skipped', 'لازم نبود', 90); }
  } catch (e) { log.error('tests crashed', e.message); }
  discovering = false; coreIssue = anyOk ? null : coreErr; testing = false; lastCheckAt = Date.now(); save();
  if (setup) {
    const id = selector.recommended(db.servers, db.stats), s = db.servers.find((x) => x.uid === id);
    pStep('pick', 'done', s ? s.name : 'هیچ سرور سالمی تأیید نشد', 99); pEnd({ ok: !!s, name: s ? s.name : '', core: !!coreErr });
  }
  push();
}


// ---- حساب، اشتراک (کانفیگ‌ها از لینک‌های سرور)، مصرف ----
const errMsg = (e) => (e && e.status === 404 ? 'کد اشتراک درست نیست.' : e && e.status !== undefined ? e.message : 'پاسخ سرور نامعتبر بود.');
function applyUser(u) { db.user = u; userSyncedAt = Date.now(); save(); push(); }
async function refreshUser() { if (!db.code) return false; try { applyUser(await api.user(db.code)); return true; } catch (e) { if (e.status === 404) onAccountDeleted(); return false; } }
async function logout() { await conn.disconnect(); usage.discardPending(); Object.assign(db, { code: 0, user: null, servers: [], stats: {}, lastGood: null, variant: null, subError: null }); progress = null; coreIssue = null; save(); push(); }
async function onAccountDeleted() { if (!db.code) return; welcomeNotice = 'این حساب دیگر وجود ندارد. لطفاً دوباره وارد شوید.'; await logout(); }
async function quotaOut() { await conn.disconnect('حجم اشتراک شما تمام شد.'); quotaNonce++; push(); }

function applyServers(list) {
  const ids = new Set(list.map((s) => s.uid)); for (const id of ids) if (!db.numbers[id]) db.numbers[id] = db.nextNumber++; // شماره‌ی هر کانفیگ ثابت می‌ماند
  for (const k of Object.keys(db.stats)) if (!ids.has(k)) delete db.stats[k]; db.servers = list;
}
async function syncSubscriptions(setup) {
  if (setup) pStep('fetch', 'active', 'در حال دریافت لینک‌ها از سرور…', 10);
  try {
    const links = await api.subscriptions(); if (!links.length) throw new Error('هیچ لینک اشتراکی روی سرور ثبت نشده است.');
    const merged = new Map(); let failed = 0;
    for (const [i, l] of links.entries()) {
      if (setup) pStep('fetch', 'active', `لینک ${i + 1} از ${links.length}`, 10 + 10 * i / links.length);
      try { (await sub.fetchSubscription(l)).servers.forEach((s) => merged.set(s.uid, s)); } catch (e) { failed++; log.error('sub link failed:', e.message); }
    }
    if (!merged.size) throw new Error('هیچ کانفیگی از لینک‌ها دریافت نشد.');
    applyServers([...merged.values()]); db.subError = failed ? `${failed} لینک دریافت نشد` : null; db.subUpdatedAt = Date.now(); save(); push();
    if (setup) pStep('fetch', 'done', `${db.servers.length} کانفیگ`, 20); return null;
  } catch (e) { const m = e.message || 'دریافت کانفیگ‌ها ناموفق بود.'; if (db.servers.length) { db.subError = m; save(); push(); } return m; }
}
async function finishSetup(c, u, msg) {
  db.code = c; db.variant = null; applyUser(u); pStep('code', 'done', msg, 8);
  const err = await syncSubscriptions(true); if (err) { db.code = 0; db.user = null; progress = null; save(); push(); return err; }
  runTests({ setup: true }); return null;
}
async function setupWithCode(c) {
  pStart(); pStep('code', 'active', 'در حال بررسی کد…', 3);
  let u; try { u = await api.user(c); } catch (e) { progress = null; push(); return errMsg(e); }
  return finishSetup(c, u, 'کد تأیید شد');
}
// ورود بدون شناسه: حساب را سرور می‌سازد؛ اگر همین نصب قبلاً حساب ساخته باشد، همان دوباره استفاده می‌شود
async function setupAuto() {
  pStart(); pStep('code', 'active', 'در حال ساخت حساب شما…', 3);
  try {
    let c = 0, u = null;
    if (db.autoCode) { try { u = await api.user(db.autoCode); c = db.autoCode; } catch (e) { if (e.status !== 404) throw e; } }
    if (!u) { const r = await api.register(); c = r.code; u = r.user; db.autoCode = c; db.askInvite = !db.inviteDone; save(); }
    return await finishSetup(c, u, 'حساب آماده شد');
  } catch (e) { progress = null; push(); return errMsg(e); }
}
let launchSynced = false;
async function syncOnLaunch() { if (!db.code || launchSynced || (progress && progress.active)) return; launchSynced = true; await syncSubscriptions(false); if (db.servers.length) runTests(); }

async function refreshAnnouncements() { try { announcements = await api.announcements(); push(); } catch {} }
async function checkUpdate() {
  try {
    const r = await api.checkVersion(VCODE), l = r.latest; if (l) latestLink = l.link;
    // بک‌اند فعلی پلتفرم ندارد؛ فقط لینک نصب‌کننده‌ی ویندوز (exe/msi) به‌عنوان آپدیت پذیرفته می‌شود
    updateInfo = r.available && l && /^https:\/\/\S+\.(exe|msi)(\?\S*)?$/i.test(l.link) ? { available: true, mandatory: r.mandatory, name: l.name, code: l.code, link: l.link, changelog: l.changelog } : null; push();
  } catch {}
}
async function connectGuard() { // اگر حجم تمام شده باشد، وضعیت از سرور دوباره خوانده می‌شود (شاید حجم اضافه شده)
  if (!db.code) return 'ابتدا وارد شوید.';
  const out = () => (db.user && db.user.exhausted) || (usage.estimate() != null && usage.estimate() <= 0);
  if (out()) { await usage.report(true); await refreshUser(); }
  if (!db.code) return 'ابتدا وارد شوید.';
  if (out()) { quotaNonce++; push(); return 'حجم اشتراک شما تمام شده است. از بخش «اشتراک» می‌توانید حجم بخرید.'; }
  return null;
}
// auto=true (دکمه‌ی اصلی): اگر پینگ سرور فعلی ناموفق بود، بهترین سرور انتخاب می‌شود
async function connectFlow(id, auto) {
  let t = id || selector.defaultServer(db.servers, db.stats, db.lastGood);
  if (auto && (!t || (db.stats[t] && db.stats[t].status === 'fail'))) { const rec = selector.recommended(db.servers, db.stats); if (rec) t = rec; }
  if (!t) return { ok: false, error: 'سروری برای اتصال وجود ندارد.' };
  if (db.settings.mode === 'tun' && !adminFlag) return { ok: false, error: 'حالت TUN نیاز به دسترسی ادمین دارد. در تنظیمات، حالت را دوباره انتخاب کنید.' };
  const err = await connectGuard(); if (err) return { ok: false, error: err };
  conn.connect(t); return { ok: true };
}
async function redeemInvite(c) {
  if (c === db.code) return 'نمی‌توانید کد خودتان را وارد کنید.'; if (db.inviteDone) return 'قبلاً کد دعوت ثبت کرده‌اید.';
  try { await api.user(c); await api.invite(c); db.inviteDone = true; db.askInvite = false; save(); push(); return null; }
  catch (e) { return e.status === 404 ? 'کد دعوت درست نیست.' : e.status !== undefined ? e.message : 'ثبت کد ناموفق بود.'; }
}
// تلگرام: اول مستقیم با خود برنامه (tg://)، بعد t.me؛ متن همیشه کپی می‌شود
async function openTelegram(text) {
  clipboard.writeText(text); const enc = encodeURIComponent(text);
  for (const u of [`tg://resolve?domain=Nova_2585&text=${enc}`, `https://t.me/Nova_2585?text=${enc}`]) { try { await shell.openExternal(u); return { ok: true }; } catch {} }
  return { ok: false };
}
const wrap = (fn) => async (...a) => { try { return await fn(...a); } catch (e) { log.error('ipc error', e.message); return { ok: false, error: e.message }; } };

async function ensureIcon() {
  if (fs.existsSync(iconPath)) return;
  try { iconPath = path.join(app.getPath('userData'), 'icon.png'); if (!fs.existsSync(iconPath)) fs.writeFileSync(iconPath, await renderIcon(assets('logo.svg'), 512)); } catch (e) { log.error('icon', e.message); }
}
function createWindow() {
  win = new BrowserWindow({ width: 480, height: 800, minWidth: 380, minHeight: 600, show: false, backgroundColor: '#060B09', title: 'Nova Connect',
    icon: nativeImage.createFromPath(iconPath), webPreferences: { preload: path.join(__dirname, 'preload.js'), nodeIntegration: false, contextIsolation: true, sandbox: true } });
  win.setMenu(null); win.loadFile(path.join(__dirname, '../renderer/index.html'));
  win.once('ready-to-show', () => win.show());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' })); win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.on('close', (e) => { if (!quitting && db.settings.minimizeToTray) { e.preventDefault(); win.hide(); } });
}
function makeTray() {
  tray = new Tray(nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 }));
  tray.setToolTip('Nova Connect'); tray.on('click', () => { win.show(); win.focus(); }); updateTray();
}
function updateTray() {
  if (!tray) return; const on = conn.state.status === 'connected';
  tray.setContextMenu(Menu.buildFromTemplate([{ label: 'نمایش برنامه', click: () => win.show() },
    { label: on ? 'قطع اتصال' : 'اتصال', click: () => (on ? conn.disconnect() : connectDefault()) }, { type: 'separator' }, { label: 'خروج کامل', click: () => quit() }]));
}
const connectDefault = () => connectFlow(null, true);
async function quit() { if (quitting) return; quitting = true; try { await conn.disconnect(); await Promise.race([usage.report(true), sleep(3000)]); } catch {} save(); xray.killAll(); app.exit(0); }

// بازکردن مجدد برنامه با دسترسی ادمین (برای حالت TUN)
function relaunchAsAdmin() {
  return new Promise((resolve) => {
    if (!app.isPackaged) return resolve({ ok: false, error: 'این قابلیت فقط در نسخه نصبی کار می‌کند.' });
    app.releaseSingleInstanceLock();
    const exe = process.execPath.replace(/'/g, "''");
    execFile('powershell', ['-NoProfile', '-NonInteractive', '-Command', `Start-Process -FilePath '${exe}' -Verb RunAs`], { windowsHide: true, timeout: 120000 }, (e) => {
      if (e) { app.requestSingleInstanceLock(); resolve({ ok: false, error: 'درخواست دسترسی ادمین لغو شد.' }); } else resolve({ ok: true });
    });
  });
}

function registerIpc() { // قبل از ساخت پنجره ثبت می‌شود تا هیچ درخواستی بی‌پاسخ نماند
  ipcMain.handle('state', () => snapshot());
  ipcMain.handle('acct:auto', wrap(async () => { welcomeNotice = null; const e = await setupAuto(); return e ? { ok: false, error: e } : { ok: true }; }));
  ipcMain.handle('acct:code', wrap(async (_e, c) => { welcomeNotice = null; const e = await setupWithCode(+c); return e ? { ok: false, error: e } : { ok: true }; }));
  ipcMain.handle('acct:logout', wrap(async () => { await logout(); return { ok: true }; }));
  ipcMain.handle('sub:sync', wrap(async () => { const e = await syncSubscriptions(false); if (!e) runTests(); return e ? { ok: false, error: e } : { ok: true }; }));
  ipcMain.handle('sub:open', wrap(async () => { refreshUser(); api.priceUnit().then((p) => { db.priceUnit = p; save(); push(); }).catch(() => {}); api.inviteBonus().then((b) => { inviteBonus = b; push(); }).catch(() => {}); return { ok: true }; }));
  ipcMain.handle('ann:seen', () => { db.lastSeenAnn = announcements.reduce((m, a) => Math.max(m, a.id), db.lastSeenAnn); save(); push(); return true; });
  ipcMain.handle('update:dismiss', (_e, c) => { db.dismissedUpdate = c; save(); push(); return true; });
  ipcMain.handle('open:url', (_e, u) => { if (/^(https:\/\/|tg:\/\/)/i.test(u)) { shell.openExternal(u).catch(() => {}); return true; } return false; });
  ipcMain.handle('tg:open', wrap((_e, t) => openTelegram(String(t).slice(0, 2000))));
  ipcMain.handle('invite:redeem', wrap(async (_e, c) => { const e = await redeemInvite(+c); return e ? { ok: false, error: e } : { ok: true }; }));
  ipcMain.handle('invite:dismiss', () => { db.askInvite = false; save(); push(); return true; });
  ipcMain.handle('conn:connect', wrap((_e, id, auto) => connectFlow(id, !!auto)));
  ipcMain.handle('conn:disconnect', wrap(async () => { await conn.disconnect(); return { ok: true }; }));
  ipcMain.handle('test', wrap(async () => { runTests(); return { ok: true }; }));
  ipcMain.handle('settings', wrap(async (_e, p) => {
    const ok = { theme: ['dark', 'light'], autoStart: 'b', minimizeToTray: 'b', autoConnect: 'b', pingInterval: [30, 45, 60, 120], retries: [2, 3, 4, 5], mode: ['proxy', 'tun'] };
    if (p && p.mode === 'tun') {
      if (!tun.available()) return { ok: false, error: 'اجزای حالت TUN در این نسخه موجود نیست.' };
      if (!adminFlag) return { ok: false, needAdmin: true };
    }
    for (const [k, v] of Object.entries(p || {})) if (k in ok && (ok[k] === 'b' ? typeof v === 'boolean' : ok[k].includes(v))) db.settings[k] = v;
    app.setLoginItemSettings({ openAtLogin: !!db.settings.autoStart }); save(); push();
    return { ok: true, note: p && p.mode && conn.state.status !== 'idle' ? 'حالت جدید از اتصال بعدی اعمال می‌شود.' : '' };
  }));
  ipcMain.handle('admin:restart', wrap(async () => {
    await conn.disconnect(); db.settings.mode = 'tun'; save();
    const r = await relaunchAsAdmin(); if (!r.ok) { db.settings.mode = 'proxy'; save(); push(); return r; }
    quitting = true; xray.killAll(); setTimeout(() => app.exit(0), 300); return { ok: true };
  }));
  ipcMain.handle('variant:reset', wrap(async () => { db.variant = null; save(); push(); runTests(); return { ok: true }; }));
  ipcMain.handle('logs', () => log.tail(200));
  ipcMain.handle('diag', wrap(async () => ({ ok: true, text: await diag.run(db) })));
  ipcMain.handle('copy', (_e, t) => { clipboard.writeText(String(t).slice(0, 20000)); return true; });
  ipcMain.handle('quit', () => quit());
}

app.whenReady().then(async () => {
  log.init(path.join(app.getPath('userData'), 'logs')); db = storage.load();
  conn = new Connection(db, save, runTests); conn.on('change', push);
  usage.init(db, { onUser: applyUser, onDeleted: onAccountDeleted, onQuotaOut: quotaOut, changed: push }); usage.start(() => conn.state.status === 'connected');
  registerIpc(); adminFlag = await tun.isAdmin();
  if (db.proxyOwned) await network.restore(db, save).catch(() => {}); // بازیابی بعد از بسته‌شدن ناگهانی قبلی
  if (db.tunOwned) await tun.restore(db, save).catch(() => {});
  await ensureIcon(); createWindow(); makeTray();
  setInterval(() => { const active = (win && win.isVisible()) || conn.state.status === 'connected';
    if (active && !testing && Date.now() - (lastCheckAt || 0) >= db.settings.pingInterval * 1000) runTests(); }, 5000);
  setInterval(save, 10000);
  if (db.code) { refreshUser(); syncOnLaunch(); }
  refreshAnnouncements(); checkUpdate();
  setInterval(() => { if (win && win.isVisible()) refreshAnnouncements(); }, 15 * 60000); setInterval(checkUpdate, 6 * 3600000);
  if (db.settings.autoConnect && db.servers.length) setTimeout(connectDefault, 2500);
});
app.on('before-quit', (e) => { if (!quitting) { e.preventDefault(); quit(); } });
app.on('window-all-closed', () => {});
