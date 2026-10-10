#!/usr/bin/env node
// LA-DEMO-GUARDS-1 / DR-T5 + DR-T7: the Link Analysis demo's scripted probes, run against a SEEDED demo and ASSERTED
// (docs/superpower/la-demo-readiness-plan.md walkthrough steps 0, 2, 7). Needs the Demo Auth build (serve-demo, loopback)
// and the `la-showcase` + `la-showcase-off` Spaces seeded by tools/seed-la-showcase.mjs. Sends only `demo:<id>` codes.
//
//   node tools/la-demo-smoke.mjs [--base http://127.0.0.1:8096] [--space la-showcase] [--off-space la-showcase-off]
//                                [--staged <dir with TRANSFERS_20260904.csv> --inbox <Space inbox/mule_transfers dir>]
//                                [--burst] [--dry-run]
//
// Always run: 401, 403, 404 (non-member), 404 MODULE_DISABLED, 422, tamper verify fails, route inventory = 75.
// With --staged/--inbox: Replay hash unchanged after a file lands in the bound Dataset (it COPIES the staged file; the
// step is skipped otherwise). With --burst: >20 expensive POSTs -> 429, 19 concurrent Graph Runs -> a 503 (MUTATES the
// Space: it starts runs - use a disposable demo). --dry-run prints the probes and sends nothing. Exit 1 on any failed probe.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'mule-hub-case';
export const EXPECTED_LA_ROUTES = 75;   // = docs/okf/backend/modules/link-analysis.md (count marker la-routes)
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

// ---- pure assertion helpers (unit-tested in la-demo-smoke.test.mjs) ----------------------------------------------

/** `want` = a status number, or {status, code} (code = body error.errorCode). Returns null when it holds, else the reason. */
export function expectStatus(res, want) {
  const w = typeof want === 'number' ? { status: want } : want;
  if (res.status !== w.status) return `expected HTTP ${w.status}, got ${res.status}`;
  if (w.code) {
    const got = res.body && res.body.error && res.body.error.errorCode;
    if (got !== w.code) return `expected errorCode ${w.code}, got ${got}`;
  }
  return null;
}

/** The inventory's /inv + /geo route count, or -1 when the body is not an inventory. */
export function countLaRoutes(inventory) {
  const rows = inventory && Array.isArray(inventory.routes) ? inventory.routes : null;
  if (!rows) return -1;
  return rows.filter((r) => /^\/?(api\/v1\/)?(inv|geo)(\/|$)/.test(String(r.pattern))).length;
}

/** A manifest that no longer matches the store: every string hash/root field (the server writes `sha256:<64hex>`; a
 *  bare 64-hex is accepted too) gets its first hex digit flipped. Throws if nothing changed - a no-op tamper would make
 *  the verify probe pass vacuously. */
export function tamperManifest(manifest) {
  const flip = (s) => s.replace(/^(sha256:)?([0-9a-f])/, (_, p = '', c) => p + (c === '0' ? '1' : '0'));
  const walk = (v) => {
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k,
      typeof x === 'string' && /^(sha256:)?[0-9a-f]{64}$/.test(x) ? flip(x) : walk(x)]));
    return v;
  };
  const out = walk(manifest);
  if (JSON.stringify(out) === JSON.stringify(manifest)) throw new Error('tamperManifest: no hash field found to tamper');
  return out;
}

/** Burst verdicts: 429 present and the allowed requests did not exceed the bucket (20) by more than the refill slack. */
export function rateLimitVerdict(statuses, capacity = 20, slack = 3) {
  const limited = statuses.filter((s) => s === 429).length;
  const through = statuses.length - limited;
  if (limited === 0) return `no 429 in ${statuses.length} requests`;
  if (through > capacity + slack) return `${through} requests got through a bucket of ${capacity}`;
  return null;
}

/** 19 concurrent runs against pool 2 + queue 16 = 18 slots: at least one must be refused with 503. */
export function poolVerdict(statuses, slots = 18) {
  const refused = statuses.filter((s) => s === 503).length;
  if (refused === 0) return `no 503 in ${statuses.length} concurrent runs (${slots} slots)`;
  return null;
}

// ---- the probes ---------------------------------------------------------------------------------------------------

/** The scripted probes as data: { id, as, method, path, body?, space?, want } (the ones that need state are in run()). */
export function buildProbes({ space = 'la-showcase', offSpace = 'la-showcase-off' } = {}) {
  const inv = `/inv/investigations/${INV}`;
  return [
    { id: '401 no token', as: null, method: 'GET', path: '/inv/investigations', want: 401 },
    { id: '403 ra.analyst on identities', as: 'ra.analyst', method: 'GET', path: '/inv/entity-identities', want: 403 },
    { id: '404 non-member (demo.manager)', as: 'demo.manager', method: 'GET', path: `${inv}/log`, want: 404 },
    { id: '404 MODULE_DISABLED on the switched-off Space', as: 'fm.analyst', method: 'GET', path: '/inv/investigations', space: offSpace,
      want: { status: 404, code: 'MODULE_DISABLED' } },
    { id: '422 bad Investigation create', as: 'fm.analyst', method: 'POST', path: '/inv/investigations', body: {}, want: 422 },
    { id: 'member reads the log (200)', as: 'fm.analyst', method: 'GET', path: `${inv}/log`, want: 200 },
  ].map((p) => ({ space, ...p }));
}

export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-showcase', offSpace = 'la-showcase-off',
  inbox = null, staged = null, burst = false, dryRun = false, log = () => {} } = {}) {
  const results = [];
  const api = `${base}/api/v1`;
  const record = (id, reason) => { results.push({ id, ok: !reason, reason }); log(`${reason ? 'FAIL' : 'ok  '}  ${id}${reason ? '  - ' + reason : ''}`); };
  const probes = buildProbes({ space, offSpace });
  if (dryRun) {
    for (const p of probes) log(`  ${(p.as || '(no token)').padEnd(13)} ${p.method} /spaces/${p.space}${p.path}  -> ${JSON.stringify(p.want)}`);
    log(`  admin         GET /spaces/${space}/audit/route-inventory  -> ${EXPECTED_LA_ROUTES} /inv + /geo routes`);
    log(`  fm.analyst    GET dossier, tamper the manifest, POST dossier/verify  -> verified:false`);
    log(`  fm.analyst    replay hash before/after copying a staged file  ${inbox && staged ? '' : '(SKIPPED: no --staged/--inbox)'}`);
    log(`  burst: 25 POST /inv/projection -> 429; 19 concurrent graph runs -> 503  ${burst ? '' : '(SKIPPED: no --burst)'}`);
    return results;
  }

  const tokens = {};
  const token = async (user) => {
    if (tokens[user]) return tokens[user];
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build?`);
    return (tokens[user] = unwrap(await r.json()).accessToken);
  };
  const call = async (user, method, p, body, sp = space) => {
    const headers = { 'content-type': 'application/json' };
    if (user) headers.authorization = `Bearer ${await token(user)}`;
    const r = await fetchFn(`${api}/spaces/${sp}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    let parsed = null;
    try { parsed = await r.json(); } catch { /* empty body */ }
    return { status: r.status, body: parsed, data: unwrap(parsed) };
  };

  for (const p of probes) {
    const res = await call(p.as, p.method, p.path, p.body, p.space);
    record(p.id, expectStatus(res, p.want));
  }

  const inventory = await call('admin', 'GET', '/audit/route-inventory');
  const n = inventory.status === 200 ? countLaRoutes(inventory.data) : -1;
  record(`route inventory lists ${EXPECTED_LA_ROUTES} /inv + /geo routes`, n === EXPECTED_LA_ROUTES ? null : `got ${n} (HTTP ${inventory.status})`);

  const dossier = await call('fm.analyst', 'GET', `/inv/investigations/${INV}/dossier`);
  if (dossier.status !== 200 || !dossier.data.manifest) record('tamper verify fails', `no dossier manifest (HTTP ${dossier.status})`);
  else {
    const clean = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/dossier/verify`, { manifest: dossier.data.manifest });
    record('untouched manifest verifies', clean.status === 200 && clean.data.verified === true ? null : `HTTP ${clean.status} verified=${clean.data && clean.data.verified}`);
    const bad = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/dossier/verify`, { manifest: tamperManifest(dossier.data.manifest) });
    record('tamper verify fails', (bad.status === 200 && bad.data.verified === false) || bad.status === 422 ? null : `HTTP ${bad.status} verified=${bad.data && bad.data.verified}`);
  }

  if (inbox && staged) {
    const hashOf = async () => { const r = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/replay`, {}); return r.status === 200 ? r.data.workingSet.hash : `HTTP ${r.status}`; };
    const before = await hashOf();
    const file = fs.readdirSync(staged).find((f) => /20260904/.test(f));
    if (!file) record('replay hash unchanged after a file lands', `no *20260904* file in ${staged}`);
    else {
      fs.copyFileSync(path.join(staged, file), path.join(inbox, file));
      await new Promise((r) => setTimeout(r, 5000));   // let the Space ingest it
      const after = await hashOf();
      const reread = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/replay`, { reread: true });
      record('replay hash unchanged after a file lands', before === after ? null : `${before} -> ${after}`);
      record('Re-read reports drift after a file lands', reread.status === 200 && reread.data.diverged === true ? null : `HTTP ${reread.status} diverged=${reread.data && reread.data.diverged}`);
    }
  } else log('skip  replay-vs-drift (no --staged/--inbox)');

  if (burst) {
    const seq = [];
    for (let i = 0; i < 25; i++) seq.push((await call('fm.analyst', 'POST', '/inv/projection',
      { dataset: 'mule_transfers_dataset', sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' })).status);
    record('>20 expensive POSTs -> 429', rateLimitVerdict(seq));
    const runs = await Promise.all(Array.from({ length: 19 }, () => call('fm.analyst', 'POST', '/inv/graph/runs', { investigationId: INV, algorithm: 'pageRank' })));
    record('19 concurrent Graph Runs -> a 503', poolVerdict(runs.map((r) => r.status)));
  } else log('skip  burst limits (no --burst)');

  return results;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), offSpace: opt('--off-space', 'la-showcase-off'),
    inbox: opt('--inbox', null), staged: opt('--staged', null), burst: args.includes('--burst'), dryRun: args.includes('--dry-run'), log: console.log })
    .then((rs) => { const bad = rs.filter((r) => !r.ok); console.log(`${rs.length - bad.length}/${rs.length} probes passed`); process.exitCode = bad.length ? 1 : 0; })
    .catch((e) => { console.error(e.message); process.exitCode = 2; });
}
