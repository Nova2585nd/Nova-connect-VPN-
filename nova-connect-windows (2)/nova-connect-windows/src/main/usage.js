// شمارش مصرف این نصب (تجمعی) و گزارش به بک‌اند؛ طبق قرارداد /usage/report
const crypto = require('crypto'); const api = require('./api'); const log = require('./logger');
const GB = 1024 ** 3, MB = 1024 * 1024;
let db, hooks = {}, nextTry = 0, failures = 0, busy = false;
function init(d, h) { db = d; hooks = h; if (!db.usage || !db.usage.dev) db.usage = { dev: crypto.randomUUID(), cum: 0, rep: 0 }; }
const u = () => db.usage;
const add = (n) => { if (n > 0) u().cum += Math.floor(n); };
const unreported = () => Math.max(0, u().cum - u().rep);
const discardPending = () => { u().rep = u().cum; }; // مصرف حساب قبلی به حساب جدید منتقل نشود
const estimate = () => (db.user ? Math.max(0, db.user.total - db.user.used - unreported() / GB) : null);
const unsynced = () => unreported() > 0 && failures > 0;
async function report(force) {
  if (!db.code || busy) return false; const now = Date.now(); if (!force && now < nextTry) return false;
  const snap = u().cum; if (snap <= u().rep) return true; busy = true;
  try { const r = await api.report(db.code, u().dev, snap); if (snap > u().rep) u().rep = snap; failures = 0; hooks.onUser(r); return true; }
  catch (e) { failures++; nextTry = now + Math.min(30000 * 2 ** Math.min(failures, 5), 600000); log.error('report failed:', e.message); if (e.status === 404) hooks.onDeleted(); hooks.changed(); return false; }
  finally { busy = false; }
}
// ۵ دقیقه یک‌بار هنگام اتصال؛ ۳۰ ثانیه وقتی کمتر از ۱ گیگ مانده؛ فوری وقتی ۱۰۰ مگابایت گزارش‌نشده جمع شد
function start(isConnected) {
  let last = 0, first = true;
  setInterval(async () => {
    if (!db.code) return; const now = Date.now(), un = unreported(), est = estimate(), conn = isConnected(), interval = est != null && est < 1 ? 30000 : 300000;
    if (un > 0 && ((conn && now - last >= interval) || un >= 100 * MB || first || (!conn && now - last >= 60000))) { await report(false); last = now; first = false; }
    const e2 = estimate(); if (conn && e2 != null && e2 <= 0) { await report(true); hooks.onQuotaOut(); }
  }, 2000);
}
module.exports = { init, add, unreported, estimate, report, unsynced, start, discardPending };
