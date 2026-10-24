// node trigger-jobs.mjs <base> <space> <demoUser> <jobId> [jobId...]  - run each job once, as a Demo User who may operate Runs.
const [base, space, user, ...jobs] = process.argv.slice(2);
const ex = await fetch(`${base}/api/v1/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
if (!ex.ok) throw new Error(`sign-in as ${user} refused (${ex.status})`);
const t = (await ex.json()).data.accessToken;
for (const j of jobs) {
  const r = await fetch(`${base}/api/v1/spaces/${space}/jobs/${j}/trigger`, { method: 'POST', headers: { authorization: 'Bearer ' + t } });
  console.log(`  trigger ${space}/${j}: ${r.status}`);
}
