const $ = (s) => document.querySelector(s), $$ = (s) => [...document.querySelectorAll(s)];
const sv = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
const ICONS = { power: sv('<path d="M12 3v9"/><path d="M6.4 6.6a8 8 0 1 0 11.2 0"/>'), refresh: sv('<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>'), download: sv('<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>'),
  bell: sv('<path d="M6 8a6 6 0 0 1 12 0c0 7 3 8 3 8H3s3-1 3-8M10.3 21a1.9 1.9 0 0 0 3.4 0"/>'), home: sv('<path d="M3 11l9-8 9 8M5 10v10h5v-6h4v6h5V10"/>'),
  list: sv('<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>'), cart: sv('<circle cx="9" cy="20" r="1.5"/><circle cx="18" cy="20" r="1.5"/><path d="M2 3h3l3 12h11l2-8H6"/>'),
  gear: sv('<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>') };
$$('[data-ico]').forEach((e) => { e.innerHTML = ICONS[e.dataset.ico] || ''; });

let S = null, view = 'none', tab = 'home', speedHist = [], dismissedRun = 0, autoTimer = null, exhDismissed = false, lastNonce = -1, wErr = '', shareText = '';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fa = (n) => String(n).replace(/\d/g, (d) => '۰۱۲۳۴۵۶۷۸۹'[d]);
const gb = (v) => (v == null ? '—' : fa(Number(v).toFixed(2)));
const TABS = { home: 'خانه', servers: 'سرورها', sub: 'اشتراک', settings: 'تنظیمات' };
const STATUS = { idle: 'متصل نیست', connecting: 'در حال اتصال', connected: 'متصل', reconnecting: 'در حال اتصال مجدد', switching: 'در حال تغییر سرور', error: 'خطا در اتصال' };
const TIPS = ['اولین اجرا کمی طول می‌کشد؛ هر سرور واقعاً با ترافیک آزمایش می‌شود، نه فقط پینگ.', 'اگر سروری با تنظیمات معمولی کار نکند، برنامه خودش حالت‌های سازگار را امتحان می‌کند.', 'این مرحله فقط یک بار انجام می‌شود و نتیجه ذخیره می‌شود.', 'می‌توانید «ادامه در پس‌زمینه» را بزنید؛ کار متوقف نمی‌شود.'];

function toast(m, bad) { const t = $('#toast'); t.textContent = m; t.className = 'show' + (bad ? ' bad' : ''); clearTimeout(toast.t); toast.t = setTimeout(() => { t.className = ''; }, 3600); }
function show(v) { if (v === view) return; const old = $('#v-' + view); if (old) { old.classList.remove('active'); old.classList.add('leave'); setTimeout(() => old.classList.remove('leave'), 400); } view = v; $('#v-' + v).classList.add('active'); if (v === 'sub') window.nova.subOpen(); }
const sheet = (id, on) => $(id).classList.toggle('show', on);
function modal(title, body, copy = true) { $('#mTitle').textContent = title; $('#mBody').textContent = body; $('#mCopy').classList.toggle('hidden', !copy); sheet('#modal', true); }
$('#mClose').onclick = () => sheet('#modal', false); $('#mCopy').onclick = async () => { await window.nova.copy($('#mBody').textContent); toast('کپی شد'); };
const byId = (id) => S.servers.find((x) => x.id === id);
const pingText = (s) => (!s ? 'نامشخص' : s.status === 'testing' ? 'در حال بررسی' : s.status === 'ok' ? fa(s.latency) + ' ms' : s.status === 'fail' ? 'ناموفق' : 'نامشخص');
const reasonText = (s) => (s.status !== 'fail' ? '' : s.reason === 'core' ? 'هسته اجرا نشد' : s.reason === 'noresp' ? (s.tcp ? 'سرور باز است ولی ترافیک عبور نکرد' : 'پاسخ نداد') : '');
const quality = (s) => (s.status !== 'ok' ? 'bad' : s.latency < 150 ? 'q4' : s.latency < 350 ? 'q3' : s.latency < 700 ? 'q2' : 'q1');
const label = (s) => `#${fa(s.num)}  ${s.name}`;
const speedTxt = (b) => (b >= 1048576 ? fa((b / 1048576).toFixed(1)) + ' MB/s' : fa(Math.round(b / 1024)) + ' KB/s');
const pct = (rem, total) => Math.max(0, Math.min(100, Math.floor((rem / total) * 100)));
async function connect(id, auto) { const r = await window.nova.connect(id, auto); if (r && r.ok === false) toast(r.error, true); }
async function onMain() { const st = S.conn.status; if (st === 'idle') return connect(null, true); if (st === 'error') return connect(S.conn.currentId, true); await window.nova.disconnect(); }
function go(t) { tab = t; if (S && S.account) { show(t); render(); } }

// ---------- رندر ----------
function render() {
  if (!S) return; document.documentElement.dataset.theme = S.settings.theme;
  const P = S.progress, showP = !!(P && P.run !== dismissedRun && (P.active || (P.done && view === 'progress')));
  document.body.classList.toggle('in-shell', !!S.account && !showP);
  if (showP) { if (view !== 'progress') show('progress'); renderProgress(P); }
  else if (!S.account) { if (view !== 'welcome') show('welcome'); $('#wErr').textContent = wErr || S.welcomeNotice || ''; }
  else if (!['home', 'servers', 'sub', 'settings'].includes(view)) show(tab);
  if (S.account) { renderTop(); renderHome(); renderServers(); renderSub(); renderSettings(); }
  renderOverlays(showP);
}
function renderTop() {
  $('#ttl').textContent = TABS[tab]; $('#codeTag').textContent = 'کد ' + fa(S.account.code); $$('.bnav button').forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
  const un = S.announcements.filter((a) => a.id > S.lastSeenAnn).length; $('#bellN').textContent = fa(un); $('#bellN').classList.toggle('hidden', !un);
}
function renderHome() {
  const c = S.conn, st = c.status, busy = ['connecting', 'reconnecting', 'switching'].includes(st), a = S.account;
  $('#hero').dataset.s = st === 'connected' ? 'connected' : busy ? 'busy' : st === 'error' ? 'error' : 'idle';
  $('#statusText').textContent = S.testing && st === 'idle' ? (S.discovering ? 'در حال سازگارسازی با سرور' : 'در حال بررسی سرورها') : STATUS[st];
  $('#statusMsg').textContent = c.message || (st === 'idle' ? 'برای اتصال روی دکمه بزنید' : '');
  $('#dot').className = 'dot ' + (st === 'connected' ? 'connected' : busy ? 'busy' : st === 'error' ? 'error' : '');
  $('#coreBanner').classList.toggle('hidden', !S.coreIssue);
  const labels = ['راه‌اندازی هسته Xray', 'آزمون عبور ترافیک از سرور', 'ساخت مسیر اتصال', 'متصل شدن']; $('#busyCard').classList.toggle('hidden', !(st === 'connecting'));
  const bs = $('#busySteps'); if (bs.children.length !== 4) { bs.innerHTML = ''; labels.forEach(() => { const li = document.createElement('li'); li.innerHTML = '<i class="ic"></i><div><b></b></div>'; bs.append(li); }); }
  labels.forEach((l, i) => { const li = bs.children[i]; li.className = 'step ' + (c.step > i + 1 ? 'done' : c.step === i + 1 ? 'active' : 'pending'); li.querySelector('b').textContent = l; });
  const cur = byId(c.currentId) || byId(S.defaultId), rec = byId(S.recommendedId);
  $('#recName').textContent = rec ? label(rec) : S.testing ? 'در حال بررسی…' : 'سرور سالمی تأیید نشد'; $('#recPing').textContent = rec ? pingText(rec) : '—';
  $('#recNote').textContent = rec && cur && rec.id === cur.id && st === 'connected' ? 'همان سرور فعلی' : '';
  $('#recBtn').classList.toggle('hidden', !rec || (st === 'connected' && cur && rec.id === cur.id));
  $('#curName').textContent = cur ? label(cur) : '—'; $('#curPing').textContent = cur ? pingText(cur) : '—';
  $('#curState').textContent = cur ? (st === 'connected' ? 'متصل' : cur.id === S.defaultId ? 'آخرین سرور انتخابی' : '') : '';
  const live = st === 'connected'; $('#down').textContent = live && c.down != null ? speedTxt(c.down) : '—'; $('#up').textContent = live && c.up != null ? speedTxt(c.up) : '—';
  const tun = S.settings.mode === 'tun'; $('#modeChip').textContent = tun ? (S.isAdmin ? 'حالت TUN · همه برنامه‌ها' : 'TUN · نیاز به اجرا با ادمین') : 'پروکسی سیستم'; $('#modeChip').classList.toggle('warn', tun && !S.isAdmin);
  const u = a.user, rem = a.estimate != null ? a.estimate : u && u.remaining; $('#qRem').textContent = gb(rem) + ' گیگ'; $('#qSync').classList.toggle('hidden', !a.unsynced);
  if (u && u.total > 0 && rem != null) { const p = pct(rem, u.total); $('#qBar').style.width = p + '%'; $('#qPct').textContent = fa(p) + '٪ باقی‌مانده'; $('#qPct').classList.remove('hidden'); $('#qTotal').textContent = 'از ' + gb(u.total) + ' گیگ کل'; }
  else { $('#qPct').classList.add('hidden'); $('#qTotal').textContent = ''; }
  drawSpark();
}
function drawSpark() {
  const cv = $('#spark'), g = cv.getContext('2d'), w = cv.width, h = cv.height; g.clearRect(0, 0, w, h); $('#spHint').classList.toggle('hidden', speedHist.length >= 2); $('#spNow').textContent = speedHist.length ? speedTxt(speedHist[speedHist.length - 1]) : '';
  if (speedHist.length < 2) return; const mx = Math.max(...speedHist, 50 * 1024), step = w / (speedHist.length - 1), col = getComputedStyle(document.documentElement).getPropertyValue('--acc2').trim() || '#34D399';
  const grd = g.createLinearGradient(0, 0, 0, h); grd.addColorStop(0, col + '88'); grd.addColorStop(1, col + '00');
  g.beginPath(); speedHist.forEach((v, i) => { const x = i * step, y = h - 6 - (v / mx) * (h - 14); i ? g.lineTo(x, y) : g.moveTo(x, y); }); g.strokeStyle = col; g.lineWidth = 2.4; g.lineJoin = 'round'; g.stroke(); g.lineTo(w, h); g.lineTo(0, h); g.closePath(); g.fillStyle = grd; g.fill();
}
const nodes = new Map();
function makeNode(s, i) {
  const n = document.createElement('div'); n.className = 'srv glass enter'; n.style.setProperty('--i', Math.min(i, 12)); n.dataset.id = s.id;
  n.innerHTML = '<span class="num"></span><div class="bars"><i></i><i></i><i></i><i></i></div><div class="nm"><b></b><small></small></div><div class="ping"></div>';
  const b = document.createElement('button'); b.className = 'btn small'; b.textContent = 'اتصال'; b.onclick = () => connect(n.dataset.id, false); n.append(b);
  n.addEventListener('animationend', () => n.classList.remove('enter'), { once: true }); return n;
}
function renderServers() { // همیشه بر اساس پینگ مرتب است
  const arr = [...S.servers].sort((a, b) => (a.status === 'ok' ? a.latency : 1e9) - (b.status === 'ok' ? b.latency : 1e9));
  $('#retest').disabled = S.testing; $('#retest').querySelector('i').classList.toggle('icon-spin', S.testing);
  const t = S.lastCheckAt ? new Date(S.lastCheckAt).toLocaleTimeString('fa-IR') : 'هنوز بررسی نشده';
  $('#meta').textContent = `${fa(S.servers.length)} کانفیگ · آخرین بررسی: ${t}` + (S.subError ? ' · ' + S.subError : '');
  const list = $('#list'), seen = new Set();
  arr.forEach((s, i) => {
    let n = nodes.get(s.id); if (!n) { n = makeNode(s, i); nodes.set(s.id, n); }
    const live = s.id === S.conn.currentId && S.conn.status === 'connected'; n.classList.toggle('cur', live); n.classList.toggle('testing', s.status === 'testing');
    n.querySelector('.num').textContent = fa(s.num); n.querySelector('.bars').className = 'bars ' + quality(s); n.querySelector('b').textContent = s.name;
    const small = n.querySelector('small'); small.textContent = ''; const tag = (t, c) => { const e = document.createElement('span'); e.className = 'badge ' + (c || ''); e.textContent = t; small.append(e); };
    if (live) tag('فعلی', 'cur'); if (s.id === S.recommendedId) tag('پیشنهادی'); small.append(s.protocol + (reasonText(s) ? ' · ' + reasonText(s) : ''));
    const p = n.querySelector('.ping'); p.textContent = pingText(s); p.className = 'ping ' + (s.status === 'ok' ? 'ok' : s.status === 'fail' ? 'fail' : 'wait') + (s.status === 'testing' ? ' skel' : '');
    seen.add(s.id); const at = list.children[i]; if (at !== n) list.insertBefore(n, at || null);
  });
  for (const [id, n] of nodes) if (!seen.has(id)) { n.remove(); nodes.delete(id); }
}
function renderSub() {
  const a = S.account, u = a.user, rem = a.estimate != null ? a.estimate : u && u.remaining;
  const p = u && u.total > 0 && rem != null ? pct(rem, u.total) : 0; $('#ringc').style.setProperty('--p', p); $('#ringV').textContent = gb(rem); $('#ringP').textContent = u && u.total > 0 ? fa(p) + '٪ باقی‌مانده' : '';
  $('#sCode').textContent = fa(a.code); $('#sTotal').textContent = u ? gb(u.total) + ' گیگ' : '—'; $('#sUsed').textContent = u ? gb(u.used) + ' گیگ' : '—'; $('#sPrice').textContent = S.priceUnit ? fa(S.priceUnit.toLocaleString('en')) + ' تومان' : '—';
  $('#sSync').textContent = (a.syncedAt ? 'آخرین همگام‌سازی: ' + new Date(a.syncedAt).toLocaleTimeString('fa-IR') : 'هنوز همگام نشده') + (a.unsynced ? ' · مصرف همگام نشده' : '');
  calcBuy(); const bt = S.inviteBonus == null ? 'مقداری' : fa(Number.isInteger(S.inviteBonus) ? S.inviteBonus : S.inviteBonus.toFixed(2));
  $('#invText').textContent = `با هر نفری که با کد شما وارد برنامه شود و کد را ثبت کند، ${bt} گیگ هدیه به حجم شما اضافه می‌شود.`; $('#invCodeV').textContent = fa(a.code);
}
function gigsVal() { const v = parseFloat($('#gigs').value.replace(/[،,]/g, '.').replace(/[۰-۹]/g, (d) => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d))); return isFinite(v) ? v : 0; }
function calcBuy() { const g = gigsVal(), pr = S.priceUnit, tot = pr ? Math.round(g * pr) : null; $('#gTotal').textContent = g > 0 && tot != null ? fa(tot.toLocaleString('en')) + ' تومان' : '—'; $('#tgBtn').disabled = !(g > 0 && tot != null); }
function renderSettings() {
  $$('[data-k]').forEach((el) => { el.checked = !!S.settings[el.dataset.k]; });
  $$('[data-seg]').forEach((g) => g.querySelectorAll('button').forEach((b) => b.classList.toggle('on', String(S.settings[g.dataset.seg]) === b.dataset.v)));
  $('#ver').textContent = `v${S.version} (کد ${fa(S.versionCode)})`; $('#adminTxt').textContent = S.isAdmin ? 'بله' : 'خیر';
  $('#variantBox').classList.toggle('hidden', !S.variant); if (S.variant) $('#variantTxt').textContent = S.variant;
  $('#modeNote').textContent = S.settings.mode === 'tun' ? (S.isAdmin ? 'ترافیک همه برنامه‌ها از مسیر برنامه عبور می‌کند.' : 'برای کار کردن باید برنامه با دسترسی ادمین اجرا شود.') : 'فقط برنامه‌هایی که تنظیمات پروکسی ویندوز را رعایت می‌کنند.';
}
function renderProgress(P) {
  const ok = P.done && P.result && P.result.ok, bad = P.done && !ok;
  $('#pTitle').textContent = !P.done ? 'در حال آماده‌سازی…' : ok ? 'همه‌چیز آماده است ✓' : 'سروری تأیید نشد';
  $('#pSub').textContent = !P.done ? 'برنامه سرورها را یکی‌یکی با ترافیک واقعی آزمایش می‌کند.' : ok ? 'بهترین سرور: ' + P.result.name : P.result.core ? 'هسته اجرا نشد؛ «تشخیص مشکل» را بزنید.' : 'هیچ سروری ترافیک عبور نداد؛ «تشخیص مشکل» علت را نشان می‌دهد.';
  $('#pBar').style.width = P.pct + '%'; $('#pPct').textContent = fa(P.pct) + '٪'; document.querySelector('.p-orb').classList.toggle('done', P.done);
  const ul = $('#pSteps'); if (ul.children.length !== P.steps.length) { ul.innerHTML = ''; P.steps.forEach(() => { const li = document.createElement('li'); li.innerHTML = '<i class="ic"></i><div><b></b><small></small></div>'; ul.append(li); }); }
  P.steps.forEach((s, i) => { const li = ul.children[i]; li.className = 'step ' + s.st; li.querySelector('b').textContent = s.label; li.querySelector('small').textContent = fa(s.detail || ''); });
  $('#pHide').classList.toggle('hidden', P.done); $('#pDone').classList.toggle('hidden', !P.done); $('#pDiag').classList.toggle('hidden', !bad);
  $('#pCodeBox').classList.toggle('hidden', !(ok && S.account)); if (ok && S.account) $('#pCode').textContent = fa(S.account.code);
  clearTimeout(autoTimer); if (ok) autoTimer = setTimeout(() => { dismissedRun = P.run; render(); }, 6000);
}
function renderOverlays(showP) {
  const a = S.account; if (!a) { ['#annP', '#quotaM', '#upS', '#invS'].forEach((i) => sheet(i, false)); return; }
  const out = (a.user && a.user.exhausted) || (a.estimate != null && a.estimate <= 0);
  if (!out) exhDismissed = false; if (S.quotaNonce !== lastNonce) { lastNonce = S.quotaNonce; exhDismissed = false; }
  const up = S.update, inv = S.askInvite && !showP && !up;
  sheet('#upS', !!up && !showP); if (up) { $('#upVer').textContent = 'نسخه ' + fa(up.name); $('#upSub').textContent = up.mandatory ? 'برای اینکه همه‌چیز بی‌دردسر و روان کار کند، لطفاً یک‌بار برنامه را به‌روز کنید. فقط چند لحظه طول می‌کشد.' : 'با این نسخه برنامه سریع‌تر و بهتر شده است.';
    $('#upLogBox').classList.toggle('hidden', !up.changelog); $('#upLog').textContent = up.changelog; $('#upLater').classList.toggle('hidden', up.mandatory); }
  sheet('#invS', inv); if (inv) $('#invSub').textContent = `این مرحله اختیاری است. اگر کسی تو را دعوت کرده، کدش را وارد کن تا ${S.inviteBonus == null ? 'مقداری' : fa(S.inviteBonus)} گیگ هدیه به او برسد.`;
  sheet('#quotaM', !!out && !exhDismissed && !showP && !up && !inv);
  if ($('#annP').classList.contains('show')) fillAnn();
}
function fillAnn() { const l = $('#annList'); l.textContent = ''; if (!S.announcements.length) l.textContent = 'اطلاعیه‌ای وجود ندارد.';
  S.announcements.forEach((x) => { const d = document.createElement('div'); d.className = 'glass'; d.textContent = x.text; const s = document.createElement('small'); s.textContent = fa(x.createdAt.slice(0, 16).replace('T', ' ')); d.append(s); l.append(d); }); }

// ---------- رویدادها ----------
function onState(s) {
  S = s; const c = s.conn; if (c.status === 'connected' && typeof c.down === 'number') { speedHist.push(c.down); if (speedHist.length > 40) speedHist.shift(); } else if (c.status !== 'connected') speedHist = [];
  render();
}
$$('.bnav button').forEach((b) => (b.onclick = () => go(b.dataset.tab)));
$('#bell').onclick = () => { fillAnn(); sheet('#annP', true); window.nova.annSeen(); }; $('#annX').onclick = () => sheet('#annP', false);
$('#qX').onclick = () => { exhDismissed = true; render(); }; $('#qBuy').onclick = () => { exhDismissed = true; go('sub'); };
$('#qCard').onclick = () => go('sub'); $('#modeChip').onclick = () => go('settings');
$('#mainBtn').onclick = onMain; $('#recBtn').onclick = () => S.recommendedId && connect(S.recommendedId, false);
$('#wHave').onclick = () => { $('#wBox').classList.toggle('hidden'); $('#wCode').focus(); };
async function login(fn, btn) { wErr = ''; $('#wErr').textContent = ''; btn.disabled = true; const r = await fn(); btn.disabled = false; if (r && r.ok === false) { wErr = r.error; render(); } else wErr = ''; }
$('#wAuto').onclick = () => login(() => window.nova.setupAuto(), $('#wAuto'));
$('#wGo').onclick = () => { const c = $('#wCode').value.trim(); if (!/^\d{6}$/.test(c)) { wErr = 'شناسه باید ۶ رقم باشد.'; return render(); } login(() => window.nova.setupCode(c), $('#wGo')); };
$('#wCode').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#wGo').click(); });
$('#retest').onclick = () => window.nova.testAll();
$('#refreshSub').onclick = async () => { const r = await window.nova.syncList(); toast(r.ok ? 'لیست به‌روزرسانی شد' : r.error, !r.ok); };
$('#sRefresh').onclick = () => { window.nova.subOpen(); toast('وضعیت در حال به‌روزرسانی است'); };
$('#gigs').addEventListener('input', calcBuy);
$('#tgBtn').onclick = async () => { const g = gigsVal(), tot = Math.round(g * S.priceUnit); const r = await window.nova.tg(`سلام، می‌خواهم حجم بخرم.\nکد اشتراک: ${S.account.code}\nمقدار: ${$('#gigs').value} گیگ\nمبلغ: ${tot} تومان`); toast(r && r.ok ? 'متن پیام کپی شد؛ اگر خودکار وارد نشد، آن را پیست کنید.' : 'تلگرام باز نشد. متن پیام کپی شد؛ به آیدی @Nova_2585 مراجعه کنید.', !(r && r.ok)); };
$('#supNote').onclick = async () => { await window.nova.copy('@Nova_2585'); toast('آیدی کپی شد'); };
$('#invCode').onclick = async () => { await window.nova.copy(String(S.account.code)); toast('کد دعوت کپی شد'); };
$('#invBtn').onclick = () => {
  const bt = S.inviteBonus == null ? '' : ''; const link = S.latestLink ? 'دانلود آخرین نسخه: ' + S.latestLink + '\n' : '';
  shareText = `سلام! با Nova Connect به اتصال سریع و امن برس 🚀\n${link}کد دعوت من: ${S.account.code}\nبعد از ورود، کد دعوت را وارد کن.${bt}`;
  const l = $('#shareList'); l.textContent = ''; const t = encodeURIComponent(shareText), lk = encodeURIComponent(S.latestLink || 'https://t.me/Nova_2585');
  [['تلگرام', `https://t.me/share/url?url=${lk}&text=${t}`], ['X (توییتر)', `https://x.com/intent/post?text=${t}`], ['واتس‌اپ', `https://wa.me/?text=${t}`]].forEach(([n, u]) => { const b = document.createElement('button'); b.className = 'btn wide'; b.textContent = n; b.onclick = () => { window.nova.openUrl(u); sheet('#shareM', false); }; l.append(b); });
  const cp = document.createElement('button'); cp.className = 'btn wide'; cp.textContent = 'کپی متن دعوت'; cp.onclick = async () => { await window.nova.copy(shareText); toast('متن دعوت کپی شد'); sheet('#shareM', false); }; l.append(cp); sheet('#shareM', true);
};
$('#shareX').onclick = () => sheet('#shareM', false);
$('#upGo').onclick = () => S.update && window.nova.openUrl(S.update.link); $('#upLater').onclick = () => S.update && window.nova.updateDismiss(S.update.code);
$('#invGo').onclick = async () => { const c = $('#invIn').value.trim(); if (!/^\d{6}$/.test(c)) { $('#invErr').textContent = 'کد دعوت ۶ رقمی است.'; return; } $('#invGo').disabled = true; const r = await window.nova.inviteRedeem(c); $('#invGo').disabled = false;
  if (r.ok) { toast('کد دعوت ثبت شد. ممنون!'); $('#invErr').textContent = ''; } else $('#invErr').textContent = r.error; };
$('#invSkip').onclick = () => window.nova.inviteDismiss();
$$('[data-k]').forEach((el) => (el.onchange = () => window.nova.setSettings({ [el.dataset.k]: el.checked })));
$$('[data-seg]').forEach((g) => g.querySelectorAll('button').forEach((b) => (b.onclick = async () => {
  const r = await window.nova.setSettings({ [g.dataset.seg]: g.hasAttribute('data-num') ? +b.dataset.v : b.dataset.v });
  if (r && r.needAdmin) { if (confirm('حالت TUN به دسترسی ادمین نیاز دارد.\nبرنامه الان با دسترسی ادمین دوباره باز شود؟')) { const a = await window.nova.restartAdmin(); if (a && a.ok === false) toast(a.error, true); } }
  else if (r && r.ok === false) toast(r.error, true); else if (r && r.note) toast(r.note);
})));
$('#variantReset').onclick = async () => { await window.nova.resetVariant(); toast('بازنشانی شد؛ سرورها دوباره بررسی می‌شوند'); };
async function openDiag() { modal('تشخیص مشکل اتصال', 'در حال بررسی… (تا حدود ۳۰ ثانیه)', false); const r = await window.nova.diagnose(); modal('تشخیص مشکل اتصال', r.ok ? r.text : r.error); }
$('#diagBtn').onclick = openDiag; $('#coreDiag').onclick = openDiag; $('#pDiag').onclick = openDiag;
$('#showLogs').onclick = async () => modal('لاگ‌ها', (await window.nova.getLogs()) || 'لاگی ثبت نشده است.');
$('#logout').onclick = async () => { if (confirm('از حساب خارج شوید؟ برای ورود دوباره، شناسه ۶ رقمی لازم است.')) { await window.nova.logout(); tab = 'home'; } };
$('#quitBtn').onclick = () => window.nova.quit();
$('#pHide').onclick = () => { if (S.progress) dismissedRun = S.progress.run; render(); toast('کار در پس‌زمینه ادامه دارد'); };
$('#pDone').onclick = () => { if (S.progress) dismissedRun = S.progress.run; render(); };
setInterval(() => { const P = S && S.progress; if (P && view === 'progress') { const sec = Math.floor((Date.now() - P.startedAt) / 1000); $('#pElapsed').textContent = fa(Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0')); }
  const c = S && S.conn; $('#dur').textContent = c && c.status === 'connected' && c.since ? fa(new Date(Date.now() - c.since).toISOString().substr(11, 8)) : '—'; }, 1000);
let tipI = 0; setInterval(() => { const t = $('#pTip'); t.style.opacity = 0; setTimeout(() => { t.textContent = TIPS[tipI++ % TIPS.length]; t.style.opacity = 1; }, 400); }, 6000); $('#pTip').textContent = TIPS[0];
window.onerror = (m) => toast('خطای رابط: ' + m, true);
(async () => {
  window.nova.onState(onState);
  for (let i = 0; i < 12 && !S; i++) { try { S = await window.nova.getState(); } catch { await sleep(400); } }
  if (!S) return toast('ارتباط با هسته برنامه برقرار نشد. برنامه را دوباره باز کنید.', true);
  render(); setInterval(async () => { try { S = await window.nova.getState(); render(); } catch {} }, 4000);
})();
