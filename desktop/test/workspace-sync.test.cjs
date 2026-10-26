const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { WorkspaceSync, merge, validateFiles } = require('../src/workspace-sync.cjs');
test('independent application and disk edits both survive', () => {
  assert.deepEqual(merge({a:'old',b:'old'}, {a:'app',b:'old'}, {a:'old',b:'disk'}).files, {a:'app',b:'disk'});
});
test('delete versus edit requires an explicit conflict choice', () => {
  assert.equal(merge({a:'old'}, {}, {a:'changed'}).conflicts.length, 1);
  assert.deepEqual(merge({a:'old'}, {}, {a:'changed'}, {a:'disk'}).files, {a:'changed'});
  assert.deepEqual(merge({a:'old'}, {}, {a:'changed'}, {a:'app'}).files, {});
});
test('path traversal and file-directory collisions are rejected', () => {
  for (const files of [{'../outside':'x'},{'/absolute':'x'},{a:'x','a/b':'y'}]) assert.throws(()=>validateFiles(files));
});
test('stale preview cannot overwrite a later disk change; successful sync persists', () => {
  const temp=fs.mkdtempSync(path.join(os.tmpdir(),'operit-sync-'));
  try {
    const root=path.join(temp,'project');fs.mkdirSync(root);fs.writeFileSync(path.join(root,'a'),'base');
    const sync=new WorkspaceSync(path.join(temp,'data')),state=sync.register(root);
    const plan=sync.plan(state.id,{a:'app'},{});fs.writeFileSync(path.join(root,'a'),'disk');
    assert.throws(()=>sync.apply(state.id,{a:'app'},{},plan.revision,plan.fingerprint),/变化/);
    assert.equal(fs.readFileSync(path.join(root,'a'),'utf8'),'disk');
    const next=sync.plan(state.id,{a:'app'},{a:'app'});
    sync.apply(state.id,{a:'app'},{a:'app'},next.revision,next.fingerprint);
    assert.equal(new WorkspaceSync(path.join(temp,'data')).open(state.id).files.a,'app');
    assert.equal(fs.readFileSync(path.join(root,'a'),'utf8'),'app');
  } finally {fs.rmSync(temp,{recursive:true,force:true});}
});
