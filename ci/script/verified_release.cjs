const fs = require('node:fs');

// The release job runs only after every build/test dependency succeeded.
module.exports = async function promote({github, core, context, env = process.env, manifest = JSON.parse(fs.readFileSync('artifacts/windows/shared-release.json', 'utf8'))}) {
  const repo = context.repo;
  const candidate = env.CANDIDATE_SHA;
  const prNumber = Number(env.PR_NUMBER || 0);
  if (manifest.commit !== candidate) throw new Error('Artifact commit differs from tested candidate');
  if (!manifest.version.endsWith(`+${context.runNumber}`)) throw new Error('Artifact version differs from build run');
  const built = (await github.rest.git.getCommit({...repo, commit_sha: candidate})).data;
  const dispatch = async (number) => {
    await github.rest.actions.createWorkflowDispatch({...repo, workflow_id: 'shared-desktop.yml',
      ref: 'main', inputs: {pr_number: String(number)}});
    core.notice('Main changed: a fresh candidate build was scheduled; old artifacts will not be published.');
  };
  let target = candidate;
  if (prNumber) {
    const pr = (await github.rest.pulls.get({...repo, pull_number: prNumber})).data;
    if (pr.head.repo.full_name !== `${repo.owner}/${repo.repo}` || pr.user.login !== repo.owner || pr.draft || pr.base.ref !== 'main') {
      throw new Error('Automatic delivery is limited to owner-authored, same-repository, non-draft PRs to main');
    }
    if (pr.head.sha !== env.TESTED_HEAD) {
      core.notice('PR head changed; its new run owns delivery.');
      return;
    }
    if (pr.merged) {
      target = pr.merge_commit_sha;
    } else {
      if (pr.state !== 'open') return;
      const main = (await github.rest.git.getRef({...repo, ref: 'heads/main'})).data.object.sha;
      if (main !== env.TESTED_BASE) { await dispatch(prNumber); return; }
      const result = (await github.rest.pulls.merge({...repo, pull_number: prNumber,
        sha: env.TESTED_HEAD, merge_method: 'merge'})).data;
      if (!result.merged) throw new Error('GitHub did not merge the verified PR');
      target = result.sha;
    }
  }
  const merged = (await github.rest.git.getCommit({...repo, commit_sha: target})).data;
  if (merged.tree.sha !== built.tree.sha) {
    // A concurrent main update won the race with the merge API. Build actual main before release.
    await dispatch(0);
    return;
  }
  const main = (await github.rest.git.getRef({...repo, ref: 'heads/main'})).data.object.sha;
  if (main !== target) { await dispatch(0); return; }
  const releases = await github.paginate(github.rest.repos.listReleases, {...repo, per_page: 100});
  const tag = `shared-v${manifest.version}`;
  if (releases.some(r => !r.draft && r.tag_name === tag)) {
    core.notice('This verified version is already published.');
    return;
  }
  // Run numbers are monotonic within this workflow. Never publish an older completed run late.
  if (releases.some(r => !r.draft && r.tag_name.startsWith('shared-v') &&
      Number(r.tag_name.split('+').pop()) > context.runNumber)) {
    if (prNumber) await dispatch(0);
    core.notice('A newer version already exists; this run will not publish an older update.');
    return;
  }
  core.setOutput('publish', 'true');
  core.setOutput('target', target);
};
