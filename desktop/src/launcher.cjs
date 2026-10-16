const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createSharedServer } = require('./server.cjs');
const versionFile = path.join(__dirname, '../build-version.json');
if (fs.existsSync(versionFile)) process.env.OPERIT_RELEASE_VERSION = JSON.parse(fs.readFileSync(versionFile, 'utf8')).version;
const { server, store } = createSharedServer();
const port = Number(process.env.OPERIT_PORT || 8095);
server.on('error', error => { console.error('共享服务无法启动：', error.message); process.exitCode = 1; });
server.listen(port, '0.0.0.0', () => {
  const url = 'http://127.0.0.1:' + port + '/setup#token=' + store.state.token;
  console.log('Operit共享服务已启动。关闭此窗口会停止服务。');
  console.log('电脑地址：http://127.0.0.1:' + port + '/?shared=1');
  console.log('连接Token：' + store.state.token);
  if (process.platform === 'win32') {
    // cmd receives a fixed localhost URL, never a model-provided command.
    spawn('cmd.exe', ['/d', '/c', 'start', '', url], { windowsHide: true, stdio: 'ignore' });
  }
});
function stop() { server.close(); server.closeAllConnections(); }
process.on('SIGINT', stop); process.on('SIGTERM', stop);
