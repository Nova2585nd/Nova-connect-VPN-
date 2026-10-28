// گزارش تشخیصی برای پیداکردن علت مشکل (بدون آدرس یا اطلاعات محرمانه سرورها)
const fs = require('fs'), { execFile } = require('child_process');
const { XrayProc, binaryPath } = require('./xray'); const { applyVariant, VARIANTS } = require('./xray-config'); const monitor = require('./monitor'); const selector = require('./selector'); const log = require('./logger');
async function run(db) {
  const L = []; const add = (...a) => L.push(a.join(' '));
  add('Nova Connect diagnostic', new Date().toISOString(), process.platform, process.arch, 'electron', process.versions.electron);
  let bin; try { bin = binaryPath(); add('xray file: OK', fs.statSync(bin).size, 'bytes'); } catch (e) { add('xray file: FAIL', e.message); return L.join('\n'); }
  await new Promise((r) => execFile(bin, ['version'], { timeout: 10000, windowsHide: true }, (e, out) => { add('xray version:', e ? 'FAIL ' + e.message : out.split('\n')[0]); r(); }));
  const id = selector.defaultServer(db.servers, db.stats, db.lastGood), s = db.servers.find((x) => x.uid === id) || db.servers[0];
  if (!s) { add('no servers in subscription'); return L.join('\n'); }
  add('server:', s.protocol, 'net=' + s.network, 'security=' + s.tls, 'flow=' + (s.flow || '-'));
  add('tcp reachable:', await monitor.tcpOk(s.address, s.port));
  const scrub = (t) => String(t || '').split(s.address).join('<addr>').split(s.sni || '\u0000').join('<sni>').split(s.host || '\u0000').join('<host>');
  for (const v of [null, ...VARIANTS]) {
    const p = new XrayProc();
    try {
      await p.start({ ...applyVariant(s, v), logLevel: 'info' }, { validate: !v });
      const r = await monitor.probe(p.httpPort, monitor.TEST_URLS[0], 9000);
      add('variant', v || 'default', '->', r.ms != null ? 'OK ' + r.ms + ' ms' : 'FAIL ' + r.err);
      if (r.ms == null && !v) add('xray log:', scrub(p.tail).slice(-700).replace(/\n/g, ' | '));
    } catch (e) { add('variant', v || 'default', '-> core FAIL', e.message, scrub(e.detail)); } finally { p.stop(); }
  }
  return log.redact(L.join('\n'));
}
module.exports = { run };
