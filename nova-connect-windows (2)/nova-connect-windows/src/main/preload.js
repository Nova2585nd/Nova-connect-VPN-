const { contextBridge, ipcRenderer } = require('electron');
const call = (ch, ...a) => ipcRenderer.invoke(ch, ...a);
contextBridge.exposeInMainWorld('nova', {
  getState: () => call('state'), onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  setupAuto: () => call('acct:auto'), setupCode: (c) => call('acct:code', String(c)), logout: () => call('acct:logout'),
  syncList: () => call('sub:sync'), testAll: () => call('test'), subOpen: () => call('sub:open'),
  connect: (id, auto) => call('conn:connect', id ? String(id) : null, !!auto), disconnect: () => call('conn:disconnect'),
  annSeen: () => call('ann:seen'), updateDismiss: (c) => call('update:dismiss', +c), openUrl: (u) => call('open:url', String(u)), tg: (t) => call('tg:open', String(t)),
  inviteRedeem: (c) => call('invite:redeem', String(c)), inviteDismiss: () => call('invite:dismiss'),
  setSettings: (p) => call('settings', p), getLogs: () => call('logs'), diagnose: () => call('diag'), copy: (t) => call('copy', String(t)),
  restartAdmin: () => call('admin:restart'), resetVariant: () => call('variant:reset'), quit: () => call('quit'),
});
