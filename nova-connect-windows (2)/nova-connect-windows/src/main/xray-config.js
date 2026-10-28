const list = (s) => (s ? String(s).split(',').map((x) => x.trim()).filter(Boolean) : undefined);
function stream(s) {
  const net = s.network === 'h2' ? 'http' : s.network === 'splithttp' ? 'xhttp' : s.network || 'tcp';
  const o = { network: net, security: s.tls === 'reality' ? 'reality' : s.tls === 'tls' ? 'tls' : 'none' };
  if (net === 'ws') o.wsSettings = { path: s.path || '/', headers: s.host ? { Host: s.host } : {} };
  if (net === 'grpc') o.grpcSettings = { serviceName: s.serviceName || '', multiMode: s.mode === 'multi' };
  if (net === 'httpupgrade') o.httpupgradeSettings = { path: s.path || '/', host: s.host || '' };
  if (net === 'xhttp') o.xhttpSettings = { path: s.path || '/', host: s.host || '', mode: s.mode || 'auto' };
  if (net === 'http') o.httpSettings = { path: s.path || '/', host: list(s.host) };
  if (net === 'tcp' && s.headerType === 'http') o.tcpSettings = { header: { type: 'http', request: { path: [s.path || '/'], headers: { Host: list(s.host) || [] } } } };
  const fpo = s.fp === 'none' ? {} : { fingerprint: s.fp || 'chrome' };
  if (s.ipv4) o.sockopt = { domainStrategy: 'UseIPv4' };
  if (o.security === 'tls') o.tlsSettings = { serverName: s.sni || s.address, ...fpo, alpn: list(s.alpn), allowInsecure: !!s.allowInsecure };
  if (o.security === 'reality') o.realitySettings = { serverName: s.sni || '', ...fpo, publicKey: s.pbk || '', shortId: s.sid || '', spiderX: s.spx || '' };
  return o;
}
function outbound(s) {
  const base = { streamSettings: stream(s) };
  if (s.protocol === 'vless') return { ...base, protocol: 'vless', settings: { vnext: [{ address: s.address, port: s.port, users: [{ id: s.id, encryption: 'none', flow: s.flow || undefined }] }] } };
  if (s.protocol === 'vmess') return { ...base, protocol: 'vmess', settings: { vnext: [{ address: s.address, port: s.port, users: [{ id: s.id, alterId: 0, security: s.security || 'auto' }] }] } };
  if (s.protocol === 'trojan') return { ...base, protocol: 'trojan', settings: { servers: [{ address: s.address, port: s.port, password: s.password }] } };
  if (s.protocol === 'shadowsocks') return { ...base, protocol: 'shadowsocks', settings: { servers: [{ address: s.address, port: s.port, method: s.method, password: s.password }] } };
  throw new Error('پروتکل پشتیبانی نمی‌شود.');
}
function buildConfig(s, { httpPort, socksPort, apiPort }) {
  const inbounds = [{ tag: 'http', listen: '127.0.0.1', port: httpPort, protocol: 'http', settings: {} }];
  if (socksPort) inbounds.push({ tag: 'socks', listen: '127.0.0.1', port: socksPort, protocol: 'socks', settings: { udp: true } });
  const cfg = { log: { loglevel: s.logLevel || 'warning' }, inbounds, outbounds: [{ ...outbound(s), tag: 'proxy' }, { protocol: 'freedom', tag: 'direct' }] };
  if (apiPort) { // آمار ترافیک واقعی برای نمایش سرعت
    cfg.stats = {}; cfg.api = { tag: 'api', services: ['StatsService'] };
    cfg.policy = { system: { statsOutboundUplink: true, statsOutboundDownlink: true } };
    cfg.inbounds.push({ tag: 'api', listen: '127.0.0.1', port: apiPort, protocol: 'dokodemo-door', settings: { address: '127.0.0.1' } });
    cfg.routing = { rules: [{ type: 'field', inboundTag: ['api'], outboundTag: 'api' }] };
  }
  return cfg;
}
// حالت‌های سازگاری: اگر کانفیگ پیش‌فرض عبور نکرد، به‌ترتیب امتحان می‌شوند
const VARIANTS = ['nofp', 'h1', 'ipv4', 'insecure'];
function applyVariant(s, v) {
  if (!v) return s; const o = { ...s };
  if (v === 'nofp') o.fp = 'none'; if (v === 'h1') o.alpn = 'http/1.1'; if (v === 'ipv4') o.ipv4 = true; if (v === 'insecure') o.allowInsecure = true;
  return o;
}
module.exports = { buildConfig, applyVariant, VARIANTS };
