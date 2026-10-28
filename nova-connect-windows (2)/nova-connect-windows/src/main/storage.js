const { app, safeStorage } = require('electron');
const fs = require('fs'), path = require('path');
const file = () => path.join(app.getPath('userData'), 'nova-connect.dat');
const DEFAULT = { subscription: null, servers: [], stats: {}, lastGood: null, proxyBackup: null, proxyOwned: false, variant: null, code: 0, user: null, numbers: {}, nextNumber: 1, autoCode: 0, askInvite: false, inviteDone: false, lastSeenAnn: 0, priceUnit: null, dismissedUpdate: 0, usage: null, subError: null, subUpdatedAt: 0, tunOwned: false, tunState: null,
  settings: { mode: 'proxy', theme: 'dark', autoStart: false, minimizeToTray: true, autoConnect: false, pingInterval: 45, retries: 3 } };
let data = structuredClone(DEFAULT);
function load() {
  try {
    const j = JSON.parse(fs.readFileSync(file(), 'utf8'));
    const p = JSON.parse(j.enc ? safeStorage.decryptString(Buffer.from(j.data, 'base64')) : j.data);
    data = { ...structuredClone(DEFAULT), ...p, settings: { ...DEFAULT.settings, ...p.settings } };
  } catch {}
  return data;
}
function save() {
  const txt = JSON.stringify(data);
  const out = safeStorage.isEncryptionAvailable() ? { enc: true, data: safeStorage.encryptString(txt).toString('base64') } : { enc: false, data: txt };
  const tmp = file() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(out)); fs.renameSync(tmp, file());
}
module.exports = { load, save, get: () => data };
