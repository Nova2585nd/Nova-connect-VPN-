// بک‌اند Nova: همه درخواست‌ها از پروسه‌ی main انجام می‌شود (بک‌اند CORS ندارد)
const BASE = 'https://nova2585nd.ir/api';
class ApiError extends Error { constructor(status, msg) { super(msg); this.status = status; } }
async function call(method, p, body) {
  let r;
  try {
    r = await fetch(BASE + p, { method, headers: { Accept: 'application/json', 'User-Agent': 'NovaConnect-Windows', ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(10000) });
  } catch (e) { throw new ApiError(0, e.name === 'TimeoutError' ? 'زمان پاسخ سرور تمام شد.' : 'اتصال به سرور برقرار نشد.'); }
  const text = await r.text();
  if (!r.ok) { let d = ''; try { d = JSON.parse(text).detail; } catch {} throw new ApiError(r.status, typeof d === 'string' && d ? d : `خطای سرور (${r.status})`); }
  try { return JSON.parse(text); } catch { throw new ApiError(0, 'پاسخ سرور نامعتبر بود.'); }
}
const toUser = (j) => { const t = +j.total_gig || 0, u = +j.used_gig || 0; return { total: t, used: u, remaining: j.remaining_gig != null ? +j.remaining_gig : Math.max(0, t - u), exhausted: j.exhausted != null ? !!j.exhausted : u >= t }; };
module.exports = {
  ApiError,
  user: async (c) => toUser(await call('GET', `/users/${c}`)),
  // ثبت‌نام خودکار: بدون ورودی؛ کد ۶ رقمی یکتا و حجم اولیه را سرور می‌سازد
  register: async () => { const j = await call('POST', '/users/', {}); const c = +j.subscription_code; if (!(c >= 100000 && c <= 999999)) throw new ApiError(0, 'پاسخ سرور برای ساخت حساب نامعتبر بود.'); return { code: c, user: toUser(j) }; },
  report: async (code, dev, bytes) => toUser(await call('POST', '/usage/report', { code, device_id: dev, total_bytes: Math.floor(bytes) })),
  subscriptions: async () => (await call('GET', '/subscriptions/')).map((x) => x.link).filter(Boolean),
  announcements: async () => (await call('GET', '/announcements/')).map((x) => ({ id: +x.id, text: String(x.text), createdAt: String(x.created_at || '') })),
  priceUnit: async () => +(await call('GET', '/settings/price')).price_per_gig || 0,
  inviteBonus: async () => { const j = await call('GET', '/settings/invite-bonus'); for (const k of ['gig', 'invite_bonus', 'value']) if (j[k] != null) return +j[k]; return null; },
  invite: (c) => call('POST', `/users/invite/${c}`, {}),
  checkVersion: async (cur) => { const j = await call('GET', `/versions/check?current_code=${cur}&platform=windows`); const l = j.latest || null;
    return { available: !!j.update_available, mandatory: !!j.mandatory, latest: l ? { name: String(l.version_name || ''), code: +l.version_code || 0, link: String(l.download_link || ''), changelog: String(l.changelog || '') } : null }; },
};
