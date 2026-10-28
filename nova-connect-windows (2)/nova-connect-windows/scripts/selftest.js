const assert = require('assert'); const { parseContent } = require('../src/main/subscription');
const { buildConfig } = require('../src/main/xray-config'); const sel = require('../src/main/selector');
const vmess = 'vmess://' + Buffer.from(JSON.stringify({ ps: 'A-vmess', add: 'a.example.com', port: '443', id: '11111111-1111-1111-1111-111111111111', net: 'ws', path: '/w', host: 'a.example.com', tls: 'tls' })).toString('base64');
const vless = 'vless://22222222-2222-2222-2222-222222222222@b.example.com:443?type=grpc&security=reality&sni=x.com&pbk=KEY&sid=ab&fp=chrome&serviceName=svc#%D8%B3%D8%B1%D9%88%D8%B1-B';
const trojan = 'trojan://pass123@c.example.com:443?sni=c.example.com#C';
const ss = 'ss://' + Buffer.from('aes-256-gcm:pw').toString('base64') + '@d.example.com:8388#D';
const text = [vmess, vless, trojan, ss, vless, 'hysteria2://x@y:1', 'garbage'].join('\n');
for (const t of [text, Buffer.from(text).toString('base64')]) {
  const r = parseContent(t); assert.strictEqual(r.servers.length, 4, 'dedupe+parse'); assert.strictEqual(r.unsupported, 2);
}
const { servers } = parseContent(text); assert.strictEqual(servers[1].name, 'سرور-B');
for (const s of servers) { const c = buildConfig(s, { httpPort: 1, socksPort: 2 }); assert.ok(c.outbounds[0].protocol && c.inbounds.length === 2); }
assert.strictEqual(buildConfig(servers[1], { httpPort: 1 }).outbounds[0].streamSettings.security, 'reality');
const [a, b, c] = servers; const stats = { [a.uid]: { status: 'ok', latency: 90 }, [b.uid]: { status: 'ok', latency: 40 }, [c.uid]: { status: 'fail', fails: 3 } };
assert.strictEqual(sel.recommended(servers, stats), b.uid);
assert.strictEqual(sel.defaultServer(servers, stats, a.uid), a.uid, 'آخرین سرور موفق حتی با پینگ بیشتر پیش‌فرض می‌ماند');
assert.strictEqual(sel.defaultServer(servers, stats, c.uid), b.uid, 'سرور خراب پیش‌فرض نمی‌ماند');
console.log('همه تست‌های خالص موفق بودند.');
// بررسی حضور لوگو در برنامه
const fs = require('fs'), path = require('path'); const R = (p) => path.join(__dirname, '..', p);
assert.ok(fs.statSync(R('assets/logo.svg')).size > 500, 'logo.svg موجود نیست');
const html = fs.readFileSync(R('src/renderer/index.html'), 'utf8');
assert.ok((html.match(/assets\/logo\.svg/g) || []).length >= 2, 'لوگو باید در صفحه خوشامد و هدر باشد');
assert.ok(fs.readFileSync(R('package.json'), 'utf8').includes('assets/icon.png'));
const cc = buildConfig(servers[0], { httpPort: 1, apiPort: 3 }); assert.ok(cc.api && cc.stats && cc.routing.rules[0].inboundTag[0] === "api");
console.log('لوگو و پیکربندی آمار ترافیک تأیید شد.');
// هر idای که app.js استفاده می‌کند باید در HTML باشد (جلوگیری از دکمه‌های مرده)
const js = fs.readFileSync(R('src/renderer/app.js'), 'utf8');
for (const m of js.matchAll(/\$\('#([A-Za-z0-9_-]+)'\)/g)) if (!m[1].startsWith('v-')) assert.ok(html.includes(`id="${m[1]}"`), 'id گمشده: ' + m[1]);
const mon = fs.readFileSync(R('src/main/monitor.js'), 'utf8'); assert.ok(mon.includes('https://www.google.com/generate_204'));
console.log('بررسی رابط و آزمون HTTPS تأیید شد.');
// بازگرداندن false از onkeydown ورودی را قفل می‌کرد؛ نباید دوباره پیش بیاید
assert.ok(!/\.onkeydown\s*=/.test(js) && !/\.onkeypress\s*=/.test(js), 'onkeydown نباید با = تنظیم شود');
const { applyVariant } = require('../src/main/xray-config');
assert.strictEqual(buildConfig(applyVariant(servers[0], 'nofp'), { httpPort: 1 }).outbounds[0].streamSettings.tlsSettings.fingerprint, undefined);
assert.strictEqual(buildConfig(applyVariant(servers[0], 'ipv4'), { httpPort: 1 }).outbounds[0].streamSettings.sockopt.domainStrategy, 'UseIPv4');
console.log('تست قفل‌شدن ورودی و حالت‌های سازگاری تأیید شد.');
// allowInsecure با شکل‌های مختلف نوشتن باید شناخته شود (علت خطای گواهی در سرورهای واقعی)
for (const v of ['1', 'true', 'True']) assert.strictEqual(require('../src/main/subscription').parseLink(`vless://33333333-3333-3333-3333-333333333333@e.example.com:443?type=ws&security=tls&allowInsecure=${v}#x`).allowInsecure, true);
assert.strictEqual(require('../src/main/subscription').parseLink('vless://33333333-3333-3333-3333-333333333333@e.example.com:443?type=ws&security=tls&AllowInsecure=1#x').allowInsecure, true);
assert.ok(!JSON.parse(fs.readFileSync(R('package.json'), 'utf8')).build.win.target.some((t) => t.target === 'portable'), 'نسخه Portable نباید ساخته شود');
for (const id of ['pBar', 'pSteps', 'pHide', 'pDone', 'modeChip', 'variantReset']) assert.ok(html.includes(`id="${id}"`), id);
console.log('پارس allowInsecure، صفحه پیشرفت و تنظیم فقط-نصبی تأیید شد.');
// نسخه ۱.۳: یکپارچگی با بک‌اند Nova و ویژگی‌های اندروید
const apiSrc = fs.readFileSync(R('src/main/api.js'), 'utf8'), mainSrc = fs.readFileSync(R('src/main/main.js'), 'utf8'), usageSrc = fs.readFileSync(R('src/main/usage.js'), 'utf8'), pj = JSON.parse(fs.readFileSync(R('package.json'), 'utf8'));
for (const t of ['/usage/report', '/subscriptions/', '/users/invite/', '/settings/invite-bonus', '/versions/check', 'platform=windows', "'/users/'"]) assert.ok(apiSrc.includes(t), 'api: ' + t);
assert.ok(!/sub:add|sub:remove|applySubscription/.test(mainSrc), 'ورود دستی لینک اشتراک باید حذف شده باشد');
assert.ok(mainSrc.includes('onAccountDeleted') && mainSrc.includes('connectFlow') && mainSrc.includes('tg://resolve'), 'منطق حساب حذف‌شده / بهترین سرور / تلگرام');
assert.ok(usageSrc.includes('total_bytes') === false && usageSrc.includes('discardPending') && usageSrc.includes('100 * MB'), 'usage');
assert.ok(Number.isInteger(pj.novaCode) && pj.version === '1.3.0', 'نسخه‌ی عددی و متنی');
const appJs = fs.readFileSync(R('src/renderer/app.js'), 'utf8'); assert.ok(!/byPing|sortPing/.test(appJs), 'مرتب‌سازی فقط بر اساس پینگ'); assert.ok(appJs.includes('speedHist') && appJs.includes('exhDismissed'));
console.log('بررسی یکپارچگی با بک‌اند و ویژگی‌های جدید تأیید شد.');
