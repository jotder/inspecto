// DR-T7: burst scripts for the Link Analysis resource limits, against a running Demo Auth host:
//   node tools/la-demo-burst.mjs [--base http://127.0.0.1:8096] [--space la-showcase] [--user fm.analyst]
// The expensive-route bucket is per subject (20 tokens, +1 per 3 s) and SHARED by projection, Graph Runs and Index builds, so
// the capacity probes wait for tokens first and the 429 probe goes last.
//   19 concurrent Graph Runs   -> a 503 (pool 2 running + 16 waiting = 18 slots; the 19th is REJECTED). SKIPPED when every run
//                                 finished inline: a SYNC algorithm under its node ceiling never reaches the pool.
//   16 concurrent index builds -> a 503 STORE_BUSY (1 running + 4 queued)
//   25 sequential projections  -> a 429 once the bucket runs dry
// The same probes also run inside la-demo-smoke.mjs --burst; this is the standalone form for a limits-only check.
import { fileURLToPath } from 'node:url';
import { makeClient } from './lib/la-client.mjs';
import { rateLimitVerdict, poolVerdict, poolInlineOnly, indexBurstBodies, indexQueueVerdict, INV } from './la-demo-smoke.mjs';

export async function runBurst({ fetchFn = fetch, base, space, user = 'fm.analyst', sleep = (ms) => new Promise((r) => setTimeout(r, ms)), log = () => {} } = {}) {
  const call = makeClient({ fetchFn, base, space });
  const results = [];
  const record = (id, reason, skipped = false) => { results.push({ id, ok: !reason, skipped, reason }); log(`${reason ? 'FAIL' : skipped ? 'SKIP' : 'PASS'}  ${id}${reason ? '  - ' + reason : ''}`); };
  const refill = (tokens) => sleep(tokens * 3000);

  // distinct params per run: an identical request is a cache hit (200) and never reaches the pool
  const runs = await Promise.all(Array.from({ length: 19 }, (_, i) => call(user, 'POST', '/inv/graph/runs',
    { investigationId: INV, algorithm: 'pageRank', params: { iterations: 60 + i } })));
  const runStatuses = runs.map((r) => r.status);
  if (poolInlineOnly(runStatuses)) record('19 concurrent Graph Runs -> a 503', null, true);
  else record('19 concurrent Graph Runs -> a 503', poolVerdict(runStatuses));

  const bodies = indexBurstBodies();
  await refill(bodies.length);
  const starts = await Promise.all(bodies.map((b) => call(user, 'POST', '/inv/index/builds', b)));
  record(`${bodies.length} concurrent index builds -> a 503 (queue full)`, indexQueueVerdict(starts.map((r) => r.status)));

  await refill(20);
  const seq = [];
  for (let i = 0; i < 25; i++) seq.push((await call(user, 'POST', '/inv/projection',
    { dataset: 'mule_transfers_dataset', sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' })).status);
  record('>20 expensive POSTs -> 429', rateLimitVerdict(seq));
  return results;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  runBurst({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), user: opt('--user', 'fm.analyst'), log: console.log })
    .then((rs) => { const bad = rs.filter((r) => !r.ok); console.log(`${rs.length - bad.length}/${rs.length} burst probes passed`); process.exitCode = bad.length ? 1 : 0; })
    .catch((e) => { console.error(e.message); process.exitCode = 2; });
}
