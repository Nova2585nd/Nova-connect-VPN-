// سرور پیش‌فرض (آخرین سرور موفق) و سرور پیشنهادی (بهترین وضعیت فعلی) دو مفهوم جداگانه‌اند
const score = (st) => st.latency + (st.fails || 0) * 400;
const usable = (st, now = Date.now()) => st && st.status === 'ok' && st.latency != null && !(st.badUntil && st.badUntil > now);
const rank = (servers, stats) => servers.filter((s) => usable(stats[s.uid])).sort((a, b) => score(stats[a.uid]) - score(stats[b.uid]));
const recommended = (servers, stats) => (rank(servers, stats)[0] || {}).uid || null;
function defaultServer(servers, stats, lastGood) {
  const last = servers.find((s) => s.uid === lastGood), rec = recommended(servers, stats);
  if (last) { const st = stats[last.uid]; const dead = st && st.status === 'fail' && (st.fails || 0) >= 3; if (!dead || !rec) return last.uid; }
  if (rec) return rec;
  // هیچ سرور سالمی ثابت نشده: دکمه اتصال نباید بی‌اثر شود؛ سروری که TCP آن باز است یا اولین سرور را امتحان می‌کنیم
  return (servers.find((s) => stats[s.uid] && stats[s.uid].tcp) || servers[0] || {}).uid || null;
}
module.exports = { rank, recommended, defaultServer };
