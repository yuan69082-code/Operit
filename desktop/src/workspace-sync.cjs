const fs = require('node:fs');
const path = require('node:path');
const { randomUUID, createHash } = require('node:crypto');
const hash = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const ignored = new Set(['.git', 'node_modules', '.gradle', 'build']);
function validateFiles(files) {
  if (!files || Array.isArray(files) || typeof files !== 'object') throw new Error('文件集合必须是对象');
  for (const [name, content] of Object.entries(files)) {
    if (!name || name.length > 300 || name.includes('\\') || name.includes(':') || name.includes('\0') || name.split('/').some(p => !p || p === '.' || p === '..' || ignored.has(p))) throw new Error('无效项目路径：' + name);
    if (typeof content !== 'string' || Buffer.byteLength(content) > 2 * 1024 * 1024) throw new Error('仅支持不超过2MB的文本文件：' + name);
    const parts = name.split('/');
    for (let i = 1; i < parts.length; i++) if (Object.hasOwn(files, parts.slice(0, i).join('/'))) throw new Error('文件与目录重名：' + name);
  }
  if (Object.keys(files).length > 2000 || Buffer.byteLength(JSON.stringify(files)) > 24 * 1024 * 1024) throw new Error('项目超过同步容量');
}
function scan(root) {
  const files = {};
  function visit(directory, relative) {
    for (const name of fs.readdirSync(directory).sort()) {
      if (ignored.has(name)) continue;
      const absolute = path.join(directory, name), key = relative ? relative + '/' + name : name;
      const stat = fs.lstatSync(absolute);
      if (stat.isSymbolicLink()) throw new Error('同步目录包含符号链接，请移除链接后再同步：' + key);
      if (stat.isDirectory()) visit(absolute, key);
      else if (stat.isFile()) {
        if (stat.size > 2 * 1024 * 1024) throw new Error('文件过大：' + key);
        const bytes = fs.readFileSync(absolute);
        const text = bytes.toString('utf8');
        if (bytes.includes(0) || !Buffer.from(text).equals(bytes)) throw new Error('同步仅支持UTF-8文本：' + key);
        files[key] = text;
      }
    }
  }
  visit(root, ''); validateFiles(files); return files;
}
function merge(base, app, disk, choices = {}) {
  validateFiles(app);
  const merged = {}, conflicts = [], changes = [];
  for (const name of [...new Set([...Object.keys(base), ...Object.keys(app), ...Object.keys(disk)])].sort()) {
    const b = base[name], a = app[name], d = disk[name];
    let value;
    if (a === d) value = a;
    else if (a === b) value = d;
    else if (d === b) value = a;
    else {
      conflicts.push({ path: name, app: a ?? null, disk: d ?? null, base: b ?? null });
      if (choices[name] === 'app') value = a;
      else if (choices[name] === 'disk') value = d;
      else continue;
    }
    if (value !== undefined) merged[name] = value;
    if (value !== d) changes.push({ path: name, action: value === undefined ? 'delete' : d === undefined ? 'create' : 'update' });
  }
  validateFiles(merged);
  return { files: merged, conflicts, changes };
}
class WorkspaceSync {
  constructor(directory) { this.directory = path.join(directory, 'workspace-sync'); fs.mkdirSync(this.directory, { recursive: true }); }
  stateFile(id) { if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('无效项目ID'); return path.join(this.directory, id + '.json'); }
  read(id) { return JSON.parse(fs.readFileSync(this.stateFile(id), 'utf8')); }
  save(state) { const target = this.stateFile(state.id); fs.writeFileSync(target + '.tmp', JSON.stringify(state), { mode: 0o600 }); fs.renameSync(target + '.tmp', target); }
  list() { return fs.readdirSync(this.directory).filter(name => name.endsWith('.json')).map(name => this.read(name.slice(0, -5))).map(({ id, root, revision }) => ({ id, root, revision })); }
  register(root) {
    if (typeof root !== 'string' || !path.isAbsolute(root)) throw new Error('请输入绝对目录');
    if (fs.lstatSync(root).isSymbolicLink()) throw new Error('不能绑定符号链接');
    root = fs.realpathSync(root);
    const exists = this.list().find(item => item.root === root);
    if (exists) return this.open(exists.id);
    const state = { id: randomUUID(), root, revision: 1, files: scan(root) }; this.save(state); return state;
  }
  open(id) { const state = this.read(id); return { ...state, disk: scan(state.root) }; }
  plan(id, app, choices) {
    const state = this.read(id), disk = scan(state.root);
    return { ...merge(state.files, app, disk, choices), revision: state.revision, fingerprint: hash(disk) };
  }
  apply(id, app, choices, revision, fingerprint) {
    const state = this.read(id), disk = scan(state.root);
    if (state.revision !== revision || hash(disk) !== fingerprint) throw new Error('目录或同步版本已变化，请重新预览；未写入文件');
    const plan = merge(state.files, app, disk, choices);
    if (plan.conflicts.some(item => !['app', 'disk'].includes(choices?.[item.path]))) throw new Error('请逐项解决冲突');
    const completed = [];
    try {
      for (const change of plan.changes) {
        const destination = path.join(state.root, change.path);
        if (change.action === 'delete') fs.unlinkSync(destination);
        else {
          fs.mkdirSync(path.dirname(destination), { recursive: true });
          const temporary = destination + '.operit-' + randomUUID() + '.tmp';
          fs.writeFileSync(temporary, plan.files[change.path], 'utf8'); fs.renameSync(temporary, destination);
        }
        completed.push(change.path);
      }
    } catch (error) { throw new Error('同步中断，已写入：' + completed.join(', ') + '；请重新预览。原因：' + error.message); }
    state.files = plan.files; state.revision++; this.save(state); return { ...state, changes: plan.changes };
  }
}
module.exports = { WorkspaceSync, merge, validateFiles, scan };
