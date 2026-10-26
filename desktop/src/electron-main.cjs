const { app, BrowserWindow, Menu, dialog, shell, session } = require('electron');
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const { createSharedServer } = require('./server.cjs');
const { isInternal, mayOpenExternal } = require('./window-policy.cjs');
let window, service;
const port = Number(process.env.OPERIT_PORT || 8095);
const origin = 'http://127.0.0.1:' + port;
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
  app.whenReady().then(async () => {
    // Refuse an existing browser-host process before opening its data store.
    await new Promise((resolve, reject) => {
      const probe = net.createServer();
      probe.once('error', () => reject(new Error('共享服务端口已被占用，请先关闭已有的 Operit 服务窗口。')));
      probe.listen(port, '0.0.0.0', () => probe.close(resolve));
    });
    const versionFile = path.join(__dirname, '../build-version.json');
    if (fs.existsSync(versionFile)) process.env.OPERIT_RELEASE_VERSION = JSON.parse(fs.readFileSync(versionFile, 'utf8')).version;
    service = createSharedServer();
    await new Promise((resolve, reject) => { service.server.once('error', reject); service.server.listen(port, '0.0.0.0', resolve); });
    session.defaultSession.setPermissionRequestHandler(async (contents, permission, callback) => {
      if (!isInternal(contents.getURL(), origin) || permission !== 'media') return callback(false);
      const answer = await dialog.showMessageBox(window, { type: 'question', title: '麦克风权限', message: '允许当前 Operit 页面使用麦克风吗？', buttons: ['允许', '取消'], defaultId: 1, cancelId: 1 });
      callback(answer.response === 0);
    });
    window = new BrowserWindow({ width: 1180, height: 820, minWidth: 760, minHeight: 560, title: 'Vio Life · Operit', backgroundColor: '#faf8f5', webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true } });
    window.webContents.setWindowOpenHandler(({ url }) => { if (mayOpenExternal(url)) shell.openExternal(url); return { action: 'deny' }; });
    window.webContents.on('will-navigate', (event, url) => {
      if (!isInternal(url, origin)) { event.preventDefault(); if (mayOpenExternal(url)) shell.openExternal(url); }
    });
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      { label: 'Operit', submenu: [
        { label: '聊天', click: () => window.loadURL(origin + '/?shared=1') },
        { label: '模型与连接设置', click: () => window.loadURL(origin + '/setup') },
        { label: '项目与磁盘同步', click: () => window.loadURL(origin + '/workspace') },
        { type: 'separator' }, { role: 'quit', label: '退出并停止共享服务' }
      ] },
      { label: '编辑', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
      { label: '显示', submenu: [{ role: 'reload', label: '刷新' }, { role: 'resetZoom', label: '实际大小' }, { role: 'zoomIn', label: '放大' }, { role: 'zoomOut', label: '缩小' }, { role: 'toggleDevTools', label: '页面检查器' }] }
    ]));
    await window.loadURL(origin + '/setup#token=' + encodeURIComponent(service.store.state.token));
  }).catch(error => { dialog.showErrorBox('Operit 未能启动', error.message); app.quit(); });
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', () => { if (service) { service.server.closeAllConnections(); service.server.close(); } });
}
