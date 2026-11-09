const { createHash } = require('node:crypto');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const { isInternal, mayOpenExternal } = require('./window-policy.cjs');

function phoneOrigin(input) {
  const url = new URL(input);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) {
    throw new Error('请填写手机提供的 HTTP 地址，不要附带令牌、路径或账号密码。');
  }
  return url.origin;
}

function registerPhoneWindow({ BrowserWindow, ipcMain, shell, dialog }) {
  const page = pathToFileURL(path.join(__dirname, '../phone.html')).href;
  ipcMain.handle('operit-open-phone', async (event, input) => {
    if (event.senderFrame.url !== page || typeof input !== 'string') throw new Error('无权打开手机窗口');
    const origin = phoneOrigin(input);
    // A separate browser partition holds only that phone's web login. It is never copied
    // into the independent shared service or another phone, and has no Node/preload bridge.
    const view = new BrowserWindow({ width: 1180, height: 820, minWidth: 760, minHeight: 560, title: '手机中的助手 · Operit',
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true,
        partition: 'persist:phone-' + createHash('sha256').update(origin).digest('hex') } });
    view.webContents.setWindowOpenHandler(({ url }) => { if (mayOpenExternal(url)) shell.openExternal(url); return { action: 'deny' }; });
    view.webContents.on('will-navigate', (e, url) => { if (!isInternal(url, origin)) { e.preventDefault(); if (mayOpenExternal(url)) shell.openExternal(url); } });
    view.webContents.session.setPermissionRequestHandler(async (contents, permission, callback) => {
      if (!isInternal(contents.getURL(), origin) || permission !== 'media') return callback(false);
      const answer = await dialog.showMessageBox(view, { type: 'question', message: '允许手机聊天页面使用这台电脑的麦克风吗？', buttons: ['允许', '取消'], defaultId: 1, cancelId: 1 });
      callback(answer.response === 0);
    });
    try { await view.loadURL(origin + '/'); return { connected: true }; }
    catch (error) { view.close(); throw new Error('无法连接手机，请检查手机 HTTP 服务和网络：' + error.message); }
  });
  return function open() {
    const setup = new BrowserWindow({ width: 660, height: 520, title: '连接手机中的助手',
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, preload: path.join(__dirname, 'phone-preload.cjs') } });
    setup.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    setup.webContents.on('will-navigate', e => e.preventDefault());
    setup.loadURL(page);
  };
}
module.exports = { phoneOrigin, registerPhoneWindow };
