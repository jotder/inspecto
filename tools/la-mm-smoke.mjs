#!/usr/bin/env node
// Scripted API-level smoke of the `la-mobile-money` showcase (vertical A of docs/superpower/la-multi-vertical-showcase-plan.md).
// Needs the Demo Auth build (serve-demo, loopback), the Space created from the la-mobile-money template, the mm_links Job run once,
// and tools/seed-la-mm.mjs --apply. Sends only `demo:<id>` codes. Self-contained: shares nothing with la-demo-smoke.mjs.
//
//   node tools/la-mm-smoke.mjs [--base http://127.0.0.1:8096] [--space la-mobile-money] [--dry-run]
//
// It pins each planted story's ground truth against the LIVE Space (entities are masked under masking `typed`, so it asserts
// counts, ratios and shapes, never an id). It MUTATES the Space a little: it creates two `smoke-mm-<ts>` Investigations.
//   1 structuring   value Measure `structuring`: ONE entity, 25 legs from 25 payers in the 4 750 <= amount < 5 000 band
//   2 mule chain    passThrough row at ratio 0.98; shortest path hub -> cash-out agent = 3 hops (4 nodes); max flow = 25 (unit capacity per
//                   transaction; the amount-weighted flow is the passThrough inbound/outbound)
//   4 loop          Find cycles on a LOOP-1 neighbourhood: exactly one cycle of 4 nodes
//   masking         every entity answered to mm.analyst is a `masked:` token
//   four-eyes       an unbounded expand is HELD (status pending), a bounded one runs
//   roles           mm.reviewer reads (200) and cannot write (403); mm.outsider is not a member (404)
//   7 alert         the mm-structuring Alert exists (seed stage or la_detect), naming a count, never a wallet
// Exit 1 on any failed probe.
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'mm-structuring-case';
export const RULE = 'mm-structuring';
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

// ---- pure verdicts (unit-tested in la-mm-smoke.test.mjs); each returns null when it holds, else the reason ---------------------
export function structuringVerdict(m) {
  const e = m && m.entities ? m.entities : [];
  if (m.count !== 1 || e.length !== 1) return `expected exactly 1 entity, got ${m && m.count}`;
  if (e[0].legs !== 25 || e[0].payers !== 25) return `expected 25 legs from 25 payers, got ${e[0].legs} from ${e[0].payers}`;
  return null;
}
export function passThroughVerdict(m) {
  const rows = m && m.entities ? m.entities : [];
  return rows.some((r) => r.ratio >= 0.975 && r.ratio <= 0.985) ? null : `no entity at pass-through 0.98 among ${rows.map((r) => r.ratio.toFixed(3)).join(', ')}`;
}
export const allMasked = (ids) => (ids.length && ids.every((i) => String(i).startsWith('masked:')) ? null : `unmasked entity in ${JSON.stringify(ids.slice(0, 3))}`);
export function pathVerdict(run, hops) {
  const n = run && run.result && run.result.selection ? run.result.selection.nodeIds.length : -1;
  return n === hops + 1 ? null : `expected ${hops} hops (${hops + 1} nodes), got ${n} nodes`;
}
export const flowVerdict = (run, value) => (run && run.result && run.result.value === value ? null : `expected max flow ${value}, got ${run && run.result && run.result.value}`);
export function cycleVerdict(run) {
  const s = run && run.result && run.result.selections ? run.result.selections : null;
  if (!s) return 'no selections in the run result';
  if (s.length !== 1) return `expected exactly 1 cycle, got ${s.length}`;
  return new Set(s[0].nodeIds).size === 4 ? null : `expected a cycle of 4 nodes, got ${s[0].nodeIds.length}`;
}
export function alertVerdict(alerts) {
  const a = alerts.find((x) => x.rule === RULE || String(x.rule).endsWith(`/${RULE}`));
  if (!a) return `no Alert for ${RULE}`;
  return /\b(HUB|STRUCT|W)-/.test(JSON.stringify(a)) ? 'the Alert names a wallet (it must be aggregate-only)' : null;
}

export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-mobile-money', dryRun = false, log = console.log } = {}) {
  const results = [];
  const record = (name, why) => { results.push({ name, ok: !why, why }); log(`${why ? 'FAIL' : 'PASS'}  ${name}${why ? `  -  ${why}` : ''}`); };
  if (dryRun) { log(`DRY RUN: would probe ${base} space ${space} (structuring, passThrough, masking, roles, four-eyes, shortest path, max flow, cycles, Alert)`); return results; }
  const api = `${base}/api/v1`;
  const tokens = {};
  const token = async (u) => tokens[u] || (tokens[u] = await (async () => {
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${u}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${u} refused (${r.status}); is this the Demo Auth build?`);
    return unwrap(await r.json()).accessToken;
  })());
  const call = async (u, method, p, body) => {
    const r = await fetchFn(`${api}/spaces/${space}${p}`, { method, headers: { authorization: `Bearer ${await token(u)}`, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    let data = null;
    try { data = unwrap(await r.json()); } catch { /* empty body */ }
    return { status: r.status, data };
  };
  const step = async (name, fn) => { try { record(name, await fn()); } catch (e) { record(name, String(e.message || e)); } };
  const ok = (r, what) => { if (r.status >= 300) throw new Error(`${what}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`); return r.data; };

  const Q = 'dataset=mm_links_dataset&sourceCol=src&targetCol=dst&linkKindCol=kind&valueCol=amount&timeCol=ts&from=2026-10-01&to=2026-10-15';
  const measure = async (extra) => ok(await call('mm.analyst', 'GET', `/inv/value-measures?${Q}&${extra}`), `value measure ${extra}`);

  await step('1 structuring Measure: one entity, 25 legs, 25 payers in the top-5% band', async () => structuringVerdict(await measure('name=structuring&min=4750&max=5000')));
  await step('2 passThrough Measure: an entity at 0.98', async () => passThroughVerdict(await measure('name=passThrough')));
  await step('masking typed: every Measure entity is a masked token', async () => allMasked((await measure('name=passThrough')).entities.map((e) => e.entity)));
  await step('roles: mm.reviewer reads (200) and cannot write (403); mm.outsider is not a member (404)', async () => {
    const read = await call('mm.reviewer', 'GET', `/inv/investigations/${INV}/working-set`);
    const write = await call('mm.reviewer', 'POST', `/inv/investigations/${INV}/ops`, { op: 'annotate', ids: ['x'], note: 'x' });
    const outsider = await call('mm.outsider', 'GET', `/inv/investigations/${INV}/working-set`);
    if (read.status !== 200) return `reviewer read answered ${read.status}`;
    if (write.status !== 403) return `reviewer write answered ${write.status}, expected 403`;
    return outsider.status === 404 ? null : `outsider answered ${outsider.status}, expected 404`;
  });
  await step(`7 Alert ${RULE} exists and is aggregate-only`, async () => {
    const d = ok(await call('mm.analyst', 'GET', '/alerts?limit=200'), 'Alerts list');
    return alertVerdict(Array.isArray(d) ? d : d.items || []);
  });

  const ts = Date.now();
  const create = async (id) => ok(await call('mm.analyst', 'POST', '/inv/investigations', { id, title: id, purpose: 'smoke', dataset: 'mm_links_dataset',
    sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts' }), `create ${id}`);
  const op = async (id, body) => ok(await call('mm.analyst', 'POST', `/inv/investigations/${id}/ops`, body), `op ${body.op}`);
  const graph = async (id, algorithm, params) => ok(await call('mm.analyst', 'POST', '/inv/graph/runs', { investigationId: id, algorithm, params }), `graph run ${algorithm}`);
  const expand3 = async (id) => { for (let i = 0; i < 3; i++) await op(id, { op: 'expand', direction: 'either', maxFanOut: 100 }); };

  const chain = `smoke-mm-chain-${ts}`;
  let hub, agent;
  await step('setup: seed HUB-01 and AG-39 and expand three times (a bounded expand runs, under the four-eyes threshold)', async () => {
    await create(chain);
    hub = (await op(chain, { op: 'seed', ids: ['HUB-01'], entityType: 'wallet' })).delta.admitted[0];
    agent = (await op(chain, { op: 'seed', ids: ['AG-39'], entityType: 'wallet' })).delta.admitted[0];
    await expand3(chain);
    return null;
  });
  await step('2 shortest path hub -> cash-out agent = 3 hops', async () => pathVerdict(await graph(chain, 'shortestPath', { from: hub, to: agent }), 3));
  await step('2 maximum flow hub -> cash-out agent = 25 (unit capacity per transaction)', async () => flowVerdict(await graph(chain, 'maxFlow', { from: hub, to: agent }), 25));
  await step('four-eyes: an unbounded expand is held (pending), not run', async () => {
    const d = await op(chain, { op: 'expand', direction: 'either' });
    return d.status === 'pending' ? null : `an unbounded expand answered status ${d.status}`;
  });
  const loop = `smoke-mm-loop-${ts}`;
  await step('4 Find cycles around LOOP-1: exactly one cycle of 4 nodes', async () => {
    await create(loop);
    await op(loop, { op: 'seed', ids: ['LOOP-1'], entityType: 'wallet' });
    await expand3(loop);
    return cycleVerdict(await graph(loop, 'findCycles', { limit: 50, maxLen: 8 }));
  });

  const failed = results.filter((r) => !r.ok);
  log(`\n${results.length - failed.length}/${results.length} passed`);
  return results;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-mobile-money'), dryRun: args.includes('--dry-run') })
    .then((r) => process.exit(r.some((x) => !x.ok) ? 1 : 0))
    .catch((e) => { console.error(e.message); process.exit(1); });
}
