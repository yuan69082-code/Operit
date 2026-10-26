const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { execFileSync } = require('node:child_process');
async function main() {
  const version = '45.0.0';
  const filename = `electron-v${version}-win32-x64.zip`;
  const base = `https://github.com/electron/electron/releases/download/v${version}/`;
  const sumsResponse = await fetch(base + 'SHASUMS256.txt');
  if (!sumsResponse.ok) throw new Error('Cannot download Electron checksums');
  const line = (await sumsResponse.text()).split('\n').find(row => row.trim().endsWith(filename));
  if (!line) throw new Error('Electron archive missing from checksums');
  const response = await fetch(base + filename);
  if (!response.ok) throw new Error('Cannot download Electron runtime');
  const archive = Buffer.from(await response.arrayBuffer());
  if (createHash('sha256').update(archive).digest('hex') !== line.trim().split(/\s+/)[0]) throw new Error('Electron checksum mismatch');
  const archivePath = path.resolve('electron-runtime.zip'), destination = path.resolve('package/electron');
  fs.writeFileSync(archivePath, archive); fs.mkdirSync(destination, { recursive: true });
  execFileSync('tar', ['-xf', archivePath, '-C', destination], { stdio: 'inherit' });
  fs.unlinkSync(archivePath);
  if (!fs.existsSync(path.join(destination, 'electron.exe'))) throw new Error('Electron executable missing');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
