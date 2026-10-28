const { EventEmitter } = require('events');
const { XrayProc } = require('./xray'); const { applyVariant } = require('./xray-config');
const { probeAny } = require('./monitor');
const selector = require('./selector');
const network = require('./network'); const tun = require('./tun');
const log = require('./logger'); const usage = require('./usage');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Connection extends EventEmitter {
  constructor(db, save, runTests) {
    super(); Object.assign(this, { db, save, runTests });
    this.gen = 0; this.proc = null; this.health = null; this.netOn = false; this.recovering = false;
    this.state = { status: 'idle', currentId: null, message: '', since: null, up: null, down: null, step: 0 };
    this.lastCounter = { up: 0, down: 0 };
  }
  set(p) { Object.assign(this.state, p); this.emit('change'); }
  server(id) { return this.db.servers.find((s) => s.uid === id); }

  async connect(id) {
    const g = ++this.gen; await this._teardown();
    const s = this.server(id); if (!s) return;
    this.recovering = false; this.set({ status: 'connecting', currentId: id, message: 'راه‌اندازی هسته Xray…', step: 1 });
    try { await this._up(s, g); } catch (e) {
      if (g !== this.gen) return;
      log.error('connect failed:', e.message); await this._teardown(); this.set({ status: 'error', message: e.message, currentId: id }); return;
    }
    if (g !== this.gen) return;
    this._ok(s, g);
  }
  _ok(s, g) { this.db.lastGood = s.uid; this.save(); this.set({ status: 'connected', currentId: s.uid, since: Date.now(), message: '', step: 4 }); this._watch(g); usage.report(true).catch(() => {}); }
  // مصرف از شمارنده‌های هسته؛ با هر ری‌استارت Xray شمارنده صفر می‌شود، پس اختلاف‌ها جمع می‌شوند
  _count(t) { if (!t) return; const l = this.lastCounter || { up: 0, down: 0 }; const dU = t.up >= l.up ? t.up - l.up : t.up, dD = t.down >= l.down ? t.down - l.down : t.down; usage.add(dU + dD); this.lastCounter = t; }

  // اتصال واقعی: اجرای Xray، تست عبور ترافیک، سپس تنظیم مسیر ویندوز؛ در صورت شکست، وضعیت متصل نمایش داده نمی‌شود
  async _up(s, g) {
    const tunMode = this.db.settings.mode === 'tun'; let srv = applyVariant(s, this.db.variant), ip = null;
    if (tunMode) { const r = await tun.prepareServer(srv); srv = r.server; ip = r.ip; }
    const proc = new XrayProc(); await proc.start(srv, { socks: true, validate: true, stats: true });
    if (g !== this.gen) { proc.stop(); return; }
    this.proc = proc; this.lastCounter = { up: 0, down: 0 }; this.set({ step: 2, message: 'آزمون عبور ترافیک از سرور…' });
    proc.once('exit', () => { if (this.proc === proc && g === this.gen) this._lost(g); });
    const pr = await probeAny(proc.httpPort);
    if (pr.ms == null) { log.error('probe failed:', pr.err); throw new Error('هسته اجرا شد ولی ترافیک از این سرور عبور نکرد (' + pr.err + ').'); }
    this.set({ step: 3, message: tunMode ? 'ساخت تونل…' : 'تنظیم مسیر ویندوز…' });
    if (tunMode) { await tun.enable(this.db, this.save, { socksPort: proc.socksPort, ip }); tun.setExitHandler(() => { if (g === this.gen) this._lost(g); }); }
    else await network.enable(this.db, this.save, proc.httpPort);
    this.netOn = true;
  }
  _speed(g) {
    clearInterval(this.speedT); let last = null, lastT = 0, busy = false;
    this.speedT = setInterval(async () => {
      if (g !== this.gen || !this.proc || busy) return; busy = true;
      const t = await this.proc.traffic(); busy = false; const now = Date.now();
      if (!t || g !== this.gen) return;
      this._count(t);
      if (last && now > lastT) this.set({ up: Math.max(0, (t.up - last.up) * 1000 / (now - lastT)), down: Math.max(0, (t.down - last.down) * 1000 / (now - lastT)) });
      last = t; lastT = now;
    }, 2000);
  }
  _watch(g) {
    this._speed(g); clearInterval(this.health); let fails = 0, busy = false;
    this.health = setInterval(async () => {
      if (g !== this.gen) return clearInterval(this.health);
      if (busy || !this.proc) return; busy = true;
      const ms = (await probeAny(this.proc.httpPort, undefined, 6000)).ms; busy = false;
      if (g !== this.gen) return;
      fails = ms == null ? fails + 1 : 0; if (fails >= 2) this._lost(g);
    }, 8000);
  }
  _stopProc() { clearInterval(this.health); clearInterval(this.speedT); this.state.up = this.state.down = null; if (this.proc) { this.proc.stop(); this.proc = null; } }
  async _teardown() { if (this.proc) { try { this._count(await this.proc.traffic()); } catch {} } this._stopProc(); await tun.restore(this.db, this.save).catch((e) => log.error('tun restore failed', e.message)); if (this.netOn || this.db.proxyOwned) { await network.restore(this.db, this.save).catch((e) => log.error('restore failed', e.message)); this.netOn = false; } }

  _lost(g) { if (this.recovering || g !== this.gen) return; this.recovering = true; this._recover(g).catch((e) => log.error('recover crashed', e.message)).finally(() => { this.recovering = false; }); }
  async _recover(g) {
    const cur = this.state.currentId, retries = this.db.settings.retries;
    this._stopProc(); log.info('connection lost, recovering');
    for (let n = 1; n <= retries; n++) {
      this.set({ step: 0, status: 'reconnecting', message: `اتصال قطع شده؛ تلاش مجدد ${n} از ${retries}` });
      await sleep(Math.min(1500 * 2 ** (n - 1), 12000)); if (g !== this.gen) return;
      const s = this.server(cur); if (!s) break;
      try { await this._up(s, g); if (g !== this.gen) return this._stopProc(); this._ok(s, g); return; } catch (e) { this._stopProc(); }
    }
    const st = this.db.stats[cur]; if (st) { st.fails = (st.fails || 0) + 3; st.badUntil = Date.now() + 5 * 60000; }
    let delay = 5000;
    while (g === this.gen) {
      this.set({ status: 'switching', message: 'اتصال مجدد ناموفق بود؛ در حال بررسی سرورهای جایگزین' });
      for (const s of selector.rank(this.db.servers, this.db.stats).slice(0, 5)) {
        if (g !== this.gen) return;
        try { await this._up(s, g); if (g !== this.gen) return this._stopProc(); this._ok(s, g); return; }
        catch { this._stopProc(); const x = this.db.stats[s.uid]; if (x) { x.fails = (x.fails || 0) + 1; x.badUntil = Date.now() + 120000; } }
      }
      this.set({ status: 'error', message: 'هیچ سرور مناسبی پیدا نشد؛ دوباره تلاش می‌کنیم.' });
      await sleep(delay); delay = Math.min(delay * 2, 60000); if (g !== this.gen) return;
      for (const k of Object.keys(this.db.stats)) delete this.db.stats[k].badUntil;
      await this.runTests();
    }
  }
  async disconnect(msg = '') { this.gen++; this.recovering = false; await this._teardown(); this.set({ status: 'idle', message: msg, since: null, step: 0 }); usage.report(true).catch(() => {}); }
}
module.exports = { Connection };
