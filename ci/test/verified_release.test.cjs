const test = require('node:test');
const assert = require('node:assert/strict');
const promote = require('../script/verified_release.cjs');
function fixture(options = {}) {
  const calls = [], outputs = {};
  let merged = Boolean(options.alreadyMerged);
  const pr = {head: {sha: options.head || 'head', repo: {full_name: options.repo || 'owner/repo'}},
    user: {login: 'owner'}, base: {ref: 'main'}, draft: Boolean(options.draft),
    state: 'open', merged, merge_commit_sha: 'merged'};
  return {calls, outputs, args: {
    context: {repo: {owner: 'owner', repo: 'repo'}, runNumber: 60},
    env: {CANDIDATE_SHA: 'candidate', TESTED_HEAD: 'head', TESTED_BASE: 'base', PR_NUMBER: '36'},
    manifest: {commit: 'candidate', version: '1.12.2-pc.1+60'},
    core: {setOutput: (k,v) => outputs[k] = v, notice: () => {}},
    github: {
      paginate: async () => options.releases || [],
      rest: {
        git: {
          getCommit: async ({commit_sha}) => ({data: {tree: {sha: commit_sha === 'merged' && options.treeChanged ? 'different' : 'tree'}}}),
          getRef: async () => ({data: {object: {sha: merged ? 'merged' : options.main || 'base'}}})
        },
        pulls: {
          get: async () => ({data: pr}),
          merge: async (data) => {calls.push(['merge', data]); if (options.mergeFails) throw Error('blocked'); merged = true; return {data: {merged: true, sha: 'merged'}};}
        },
        actions: {createWorkflowDispatch: async data => calls.push(['dispatch', data])},
        repos: {listReleases: () => {}}
      }
    }
  }};
}
test('verified candidate merges with head lease and publishes same artifacts', async () => {
  const f=fixture(); await promote(f.args);
  assert.equal(f.calls[0][1].sha, 'head');
  assert.equal(f.outputs.publish, 'true'); assert.equal(f.outputs.target, 'merged');
});
test('main advances: automatically build new candidate without merging old one', async () => {
  const f=fixture({main:'new-main'}); await promote(f.args);
  assert.deepEqual(f.calls.map(c=>c[0]), ['dispatch']);
  assert.equal(f.calls[0][1].inputs.pr_number,'36'); assert.equal(f.outputs.publish,undefined);
});
test('merge race produces different tree: rebuild actual main', async () => {
  const f=fixture({treeChanged:true}); await promote(f.args);
  assert.deepEqual(f.calls.map(c=>c[0]), ['merge','dispatch']);
  assert.equal(f.calls[1][1].inputs.pr_number,'0'); assert.equal(f.outputs.publish,undefined);
});
test('new PR head cannot use stale artifacts', async () => {
  const f=fixture({head:'new-head'}); await promote(f.args);
  assert.equal(f.calls.length,0); assert.equal(f.outputs.publish,undefined);
});
test('foreign and draft PRs never auto merge', async () => {
  for (const o of [{repo:'other/repo'},{draft:true}]) {
    const f=fixture(o); await assert.rejects(promote(f.args)); assert.equal(f.calls.length,0);
  }
});
test('wrong artifact identity stops before merge', async () => {
  const f=fixture(); f.args.manifest.commit='wrong';
  await assert.rejects(promote(f.args)); assert.equal(f.calls.length,0);
});
test('failed merge does not authorize publication', async () => {
  const f=fixture({mergeFails:true}); await assert.rejects(promote(f.args)); assert.equal(f.outputs.publish,undefined);
});
test('rerun after successful merge can finish publishing', async () => {
  const f=fixture({alreadyMerged:true}); await promote(f.args);
  assert.equal(f.calls.length,0); assert.equal(f.outputs.publish,'true');
});
test('already published version is idempotent', async () => {
  const f=fixture({alreadyMerged:true,releases:[{draft:false,tag_name:'shared-v1.12.2-pc.1+60'}]});
  await promote(f.args); assert.equal(f.outputs.publish,undefined);
});
test('older version schedules fresh main build instead of shipping outdated version code', async () => {
  const f=fixture({releases:[{draft:false,tag_name:'shared-v1.12.2-pc.1+61'}]});
  await promote(f.args); assert.equal(f.outputs.publish,undefined);
  assert.equal(f.calls.at(-1)[0],'dispatch');
});
