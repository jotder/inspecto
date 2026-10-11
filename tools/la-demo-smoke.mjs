#!/usr/bin/env node
// LA-DEMO-GUARDS-1 / DR-T5 + DR-T7: the Link Analysis demo's scripted probes, run against a SEEDED demo and ASSERTED
// (docs/superpower/la-demo-readiness-plan.md walkthrough steps 0, 2, 7). Needs the Demo Auth build (serve-demo, loopback)
// and the `la-showcase` + `la-showcase-off` Spaces seeded by tools/seed-la-showcase.mjs. Sends only `demo:<id>` codes.
//
//   node tools/la-demo-smoke.mjs [--base http://127.0.0.1:8096] [--space la-showcase] [--off-space la-showcase-off]
//                                [--staged <dir with TRANSFERS_20260904.csv> --inbox <Space inbox/mule_transfers dir>]
//                                [--burst] [--telecom [--presenter fm.manager] [--four-eyes 2000] [--telecom-timeout 180]] [--dry-run]
//
// With --telecom (MUTATES: triggers the telecom Jobs, creates a smoke-telecom-<ts> Investigation): telecom Datasets + the
// telecom_links_dataset link columns; the presenter triggers telecom_links / _msisdn_indicators / _typology_counts and their
// Datasets get rows (polled); seed 99979100001 + expand (maxFanOut <= four-eyes) admits entities, hub 99970000100 flagged;
// the three telecom pattern packs match >= 1; the three telecom typology Alerts are present with counts only.
//
// Always run: 401, 403, 404 (non-member), 404 MODULE_DISABLED, 422, tamper verify fails, route inventory = EXPECTED_LA_ROUTES (derived from the LA module.toon files).
// With --staged/--inbox: Replay hash unchanged after a file lands in the bound Dataset (it WRITES a 40-row MULE-HUB-01 file named for a
// far-future day - the shipped staged files touch no seeded entity, so they cannot drift a re-read - and polls the inbox until
// the Space ingests it, ~one poll; the step is skipped otherwise). With --burst: >20 expensive POSTs -> 429, 19 concurrent Graph Runs -> a 503, 16 concurrent index builds -> a 503 (queue full) and a duplicate build -> 409 (MUTATES the
// Space: it starts runs - use a disposable demo). --dry-run prints the probes and sends nothing. Exit 1 on any failed probe.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { laRoutes } from './lib/module-routes.mjs';

export const INV = 'mule-hub-case';
export const EXPECTED_LA_ROUTES = laRoutes();   // derived exactly like check-doc-counts' la-routes marker
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

/** Distinct edge mappings over the mule Dataset (the live-build guard is per Dataset + mapping, so none of these is a 409):
 *  kindCol x timeCol x attribute columns = 16. */
export function indexBurstBodies(dataset = 'mule_transfers_dataset') {
  const out = [];
  for (const kindCol of [null, 'CHANNEL']) for (const timeCol of [null, 'BOOKED_AT'])
    for (const attrCols of [[], ['CHANNEL'], ['BOOKED_AT'], ['CHANNEL', 'BOOKED_AT']])
      out.push({ dataset, sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT', ...(kindCol && { kindCol }), ...(timeCol && { timeCol }), attrCols });
  return out;
}

/** A day (yyyyMMdd) for the drift file: unique per run, years after the seeded days so it never collides with a landed file. */
export function driftDay(ms) {
  return new Date(Date.UTC(2027, 0, 1) + (Math.floor(ms / 1000) % 3000) * 86400000).toISOString().slice(0, 10).replace(/-/g, '');
}
export const driftFileName = (day) => `TRANSFERS_${day}.csv`;
/** 40 transfers MULE-HUB-01 -> ACC-SMOKE-DRIFT: heavy enough to displace a fan-out-capped neighbourhood read (a 1-row file does not). */
export function driftCsv(header, day) {
  const iso = `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6)}`;
  const cols = header.split(',');
  const row = (i) => cols.map((c) => ({ TRANSFER_ID: `TX-DRIFT-${day}-${i}`, PAYER_ACCOUNT: 'MULE-HUB-01', PAYEE_ACCOUNT: 'ACC-SMOKE-DRIFT', CHANNEL: 'card',
    AMOUNT: '10.00', CURRENCY: 'EUR', PAYER_COUNTRY: 'FR', PAYEE_COUNTRY: 'NL', BOOKED_DATE: iso, BOOKED_AT: `${iso} 10:00:${String(i % 60).padStart(2, '0')}` }[c] ?? '')).join(',');
  return [header, ...Array.from({ length: 40 }, (_, i) => row(i + 1))].join('\n') + '\n';
}
/** Resolves true once `file` no longer exists (the Space ingested it), false at the deadline. */
export async function waitGone(file, { timeoutMs, pollMs, sleep, now }) {
  const deadline = now() + timeoutMs;
  while (fs.existsSync(file)) { if (now() >= deadline) return false; await sleep(pollMs); }
  return true;
}

/** Index build burst (1 worker + 4 queued = 5 slots): every answer is 202/409/503, some build is accepted and at least one
 *  start is refused 503 STORE_BUSY (queue full). A build that finishes quickly frees a slot, so the burst must be wider than 5. */
export function indexQueueVerdict(statuses, slots = 5) {
  const odd = statuses.filter((s) => ![202, 409, 503].includes(s));
  if (odd.length) return `unexpected statuses ${[...new Set(odd)].join(', ')} in ${statuses.length} concurrent builds`;
  if (!statuses.includes(202)) return `no build accepted in ${statuses.length} starts`;
  if (!statuses.includes(503)) return `no 503 in ${statuses.length} concurrent builds (${slots} slots)`;
  return null;
}

/** The same mapping started twice while the first is live: the second is a 409 (one live build per Dataset + mapping). */
export function duplicateBuildVerdict(first, dup) {
  if (first.status !== 202) return `first build not accepted: ${excerpt(first)}`;
  return dup.status === 409 ? null : `second start of the same mapping: expected 409, got ${dup.status} (the first build may have finished already)`;
}

/** True when every concurrent run was answered inline (200/202): nothing was queued, so no 503 can happen at this graph size. */
export function poolInlineOnly(statuses) {
  return statuses.length > 0 && statuses.every((s) => s === 200 || s === 202);
}

// ---- telecom probe group (--telecom) ------------------------------------------------------------------------------

export const TELECOM_DATASETS = ['telecom_links_dataset', 'telecom_msisdn_indicators_dataset', 'telecom_typology_counts_dataset',
  'crm_kyc_dataset', 'hlr_eir_dataset'];
export const TELECOM_LINK_COLUMNS = ['a_msisdn', 'b_msisdn', 'link_kind', 'first_seen', 'last_seen', 'events'];
/** Job name -> the Dataset whose rows prove it ran (the Job's sink). Triggered in this order (counts reads indicators). */
export const TELECOM_JOBS = [['telecom_links', 'telecom_links_dataset'], ['telecom_msisdn_indicators', 'telecom_msisdn_indicators_dataset'],
  ['telecom_typology_counts', 'telecom_typology_counts_dataset']];
export const TELECOM_ALERT_RULES = ['telecom_wangiri_lines', 'telecom_irsf_lines', 'telecom_simbox_lines'];
export const TELECOM_SEED = '99979100001';
export const TELECOM_HUB = '99970000100';
/** The la-showcase telecom pattern packs' stages (= spaces/_templates/la-showcase/config/registry/pattern-packs/telecom-*.toon,
 *  mapped as pattern-packs.ts stagesOf maps them; the unit test keeps the two in step). */
export const TELECOM_PACKS = {
  'telecom-forwarding-chain': [{ shape: 'fan-out', minBranches: 1, edgeKind: 'forwarding' },
    { shape: 'fan-out', minBranches: 1, edgeKind: 'forwarding', afterPrevious: true, maxGapHours: 48 }],
  'telecom-payment-ring': [{ shape: 'fan-out', minBranches: 2, edgeKind: 'shared_payment' },
    { shape: 'fan-in', minBranches: 2, edgeKind: 'shared_payment', closesToStart: true }],
  'telecom-subscription-ring': [{ shape: 'fan-in', minBranches: 5, edgeKind: 'shared_identity', windowHours: 168 }],
};

/** A short body excerpt for a FAIL line. */
export const excerpt = (res) => `HTTP ${res.status} ${JSON.stringify(res.body ?? null).slice(0, 200)}`;

/** Null when every telecom Dataset is listed and telecom_links_dataset declares every link column, else the reason. */
export function telecomRegistryVerdict(list) {
  const items = Array.isArray(list) ? list : (list && Array.isArray(list.items) ? list.items : null);
  if (!items) return 'Dataset list is not an array';
  const byName = Object.fromEntries(items.map((d) => [d.name ?? d.id, d]));
  const missing = TELECOM_DATASETS.filter((n) => !byName[n]);
  if (missing.length) return `missing Datasets: ${missing.join(', ')}`;
  const cols = ((byName.telecom_links_dataset.content || byName.telecom_links_dataset).columns || [])
    .map((c) => (typeof c === 'string' ? c : c && c.name));
  const absent = TELECOM_LINK_COLUMNS.filter((c) => !cols.includes(c));
  return absent.length ? `telecom_links_dataset lacks columns: ${absent.join(', ')} (declares ${cols.join(', ') || 'none'})` : null;
}

/** A Job trigger is accepted (2xx); a 403 names the missing capability. */
export function triggerVerdict(res) {
  if (res.status >= 200 && res.status < 300) return null;
  return res.status === 403 ? `403 (canOperateRuns?) ${excerpt(res)}` : excerpt(res);
}

/** The telecom presenter: an explicit --presenter wins; else fm.manager (only fm.manager may press Run in la-showcase,
 *  docs/archived-documents/plans-archive/la-telecom-demo-data.md §7). Non-telecom runs keep fm.analyst. */
export function resolvePresenter(presenter, telecom) {
  return presenter || (telecom ? 'fm.manager' : 'fm.analyst');
}

/** A 403 on a Job trigger names the persona and the capability it lacks; null for anything else. */
export function denialReason(res, persona) {
  if (res.status !== 403) return null;
  return `${persona} lacks canOperateRuns (403 on Job trigger; run as fm.manager or pass --presenter <persona with canOperateRuns>): ${excerpt(res)}`;
}

/** Expand verdict: entities admitted and, when the hub is reached, flagged highConnectivity. `ws` = working-set?of=entities. */
export function expandVerdict(step, ws, hub = TELECOM_HUB) {
  if (step.status === 202 || (step.data && (step.data.pending || step.data.held))) return `expand held for four-eyes: ${excerpt(step)}`;
  if (step.status !== 200) return `expand ${excerpt(step)}`;
  const admitted = (step.data && step.data.delta && step.data.delta.admitted) || [];
  if (!admitted.length) return `expand admitted no entity: ${excerpt(step)}`;
  if (!ws || ws.status !== 200) return `working set ${ws ? excerpt(ws) : 'missing'}`;
  const row = ((ws.data && ws.data.rows) || []).find((r) => String(r.entityId).endsWith(hub));
  if (row && row.highConnectivity !== true) return `hub ${hub} reached but not flagged highConnectivity`;
  return null;
}

/** ≥1 match and no refusal. */
export function patternVerdict(res) {
  if (res.status !== 200) return excerpt(res);
  if (res.data && res.data.refusal) return `refused: ${JSON.stringify(res.data.refusal).slice(0, 160)}`;
  const n = (res.data && Array.isArray(res.data.matches)) ? res.data.matches.length : 0;
  return n >= 1 ? null : `0 matches: ${excerpt(res)}`;
}

/** The three telecom Alerts are present and carry counts only (no MSISDN anywhere in them). */
export function telecomAlertsVerdict(alerts) {
  const list = Array.isArray(alerts) ? alerts : [];
  const missing = TELECOM_ALERT_RULES.filter((r) => !list.some((a) => a.rule === r));
  if (missing.length) return `missing Alerts: ${missing.join(', ')} (have ${[...new Set(list.map((a) => a.rule))].join(', ') || 'none'})`;
  const leak = list.filter((a) => TELECOM_ALERT_RULES.includes(a.rule) && /\b\d{11}\b/.test(JSON.stringify(a)));
  return leak.length ? `Alert carries an MSISDN: ${JSON.stringify(leak[0]).slice(0, 200)}` : null;
}

/** The telecom group (mutates: triggers Jobs, creates an Investigation). `call(user, method, path, body)` as in run(). */
export async function runTelecom({ call, record, skip = () => {}, presenter = 'fm.manager', fourEyes = 2000, timeoutMs = 180000, pollMs = 3000,
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), now = () => Date.now() }) {
  const ds = await call(presenter, 'GET', '/components/dataset');
  record('telecom Datasets listed, telecom_links_dataset columns complete', ds.status === 200 ? telecomRegistryVerdict(ds.data) : excerpt(ds));

  const waitRows = async (dataset) => {
    const deadline = now() + timeoutMs;
    let last;
    for (;;) {
      last = await call(presenter, 'GET', `/datasets/${dataset}/rows?limit=1`);
      if (last.status === 200 && last.data && Array.isArray(last.data.rows) && last.data.rows.length) return null;
      if (now() >= deadline) return `no rows after ${timeoutMs} ms: ${excerpt(last)}`;
      await sleep(pollMs);
    }
  };
  let countsRan = false;
  let countsDenied = null;
  for (const [job, dataset] of TELECOM_JOBS) {
    const t = await call(presenter, 'POST', `/jobs/${job}/trigger`, {});
    const denied = denialReason(t, presenter);
    const tv = denied || triggerVerdict(t);
    record(`${presenter} triggers Job ${job}`, tv);
    if (denied) {
      skip(`${dataset} has rows after ${job}`, `trigger denied: ${presenter} lacks canOperateRuns`);
      if (job === 'telecom_typology_counts') countsDenied = presenter;
    }
    if (tv) continue;
    const rv = await waitRows(dataset);
    record(`${dataset} has rows after ${job}`, rv);
    if (job === 'telecom_typology_counts' && !rv) countsRan = true;
  }

  const id = `smoke-telecom-${now()}`;
  const created = await call(presenter, 'POST', '/inv/investigations', { id, title: 'smoke telecom', purpose: 'la-demo-smoke telecom probe',
    dataset: 'telecom_links_dataset', sourceCol: 'a_msisdn', targetCol: 'b_msisdn', linkKindCol: 'link_kind', timeCol: 'last_seen', eventsCol: 'events' });
  if (created.status >= 300) record(`telecom Investigation seeds ${TELECOM_SEED} and expands`, `create ${excerpt(created)}`);
  else {
    const base = `/inv/investigations/${id}`;
    const seed = await call(presenter, 'POST', `${base}/ops`, { op: 'seed', ids: [TELECOM_SEED], entityType: 'msisdn' });
    if (seed.status !== 200) record(`telecom Investigation seeds ${TELECOM_SEED} and expands`, `seed ${excerpt(seed)}`);
    else {
      const step = await call(presenter, 'POST', `${base}/ops`, { op: 'expand', direction: 'either', maxFanOut: fourEyes });
      const ws = step.status === 200 ? await call(presenter, 'GET', `${base}/working-set?of=entities`) : null;
      record(`telecom Investigation seeds ${TELECOM_SEED} and expands (hub ${TELECOM_HUB} flagged if reached)`, expandVerdict(step, ws));
    }
  }

  for (const [pack, stages] of Object.entries(TELECOM_PACKS)) {
    const r = await call(presenter, 'POST', '/inv/pattern/branching', { dataset: 'telecom_links_dataset', sourceCol: 'a_msisdn',
      targetCol: 'b_msisdn', linkKindCol: 'link_kind', timeCol: 'first_seen', limit: 200, stages });
    record(`pattern pack ${pack} matches >= 1`, patternVerdict(r));
  }

  if (countsDenied) skip('telecom typology Alerts present (counts only)', `trigger denied: ${countsDenied} lacks canOperateRuns`);
  else if (!countsRan) record('telecom typology Alerts present (counts only)', 'telecom_typology_counts did not produce rows');
  else {
    const deadline = now() + timeoutMs;
    let verdict;
    for (;;) {
      const a = await call(presenter, 'GET', '/alerts?limit=500');
      verdict = a.status === 200 ? telecomAlertsVerdict(a.data) : excerpt(a);
      if (!verdict || now() >= deadline) break;
      await sleep(pollMs);
    }
    record('telecom typology Alerts present (counts only)', verdict);
  }
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
  inbox = null, staged = null, burst = false, telecom = false, presenter = null, fourEyes = 2000, telecomTimeoutMs = 180000,
  dryRun = false, log = () => {}, landTimeoutMs = 150000, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), now = () => Date.now() } = {}) {
  const results = [];
  const api = `${base}/api/v1`;
  const record = (id, reason) => { results.push({ id, ok: !reason, reason }); log(`${reason ? 'FAIL' : 'PASS'}  ${id}${reason ? '  - ' + reason : ''}`); };
  const skip = (id, reason) => { results.push({ id, ok: true, skipped: true, reason }); log(`SKIPPED  ${id}  - ${reason}`); };
  presenter = resolvePresenter(presenter, telecom);
  const probes = buildProbes({ space, offSpace });
  if (dryRun) {
    for (const p of probes) log(`  ${(p.as || '(no token)').padEnd(13)} ${p.method} /spaces/${p.space}${p.path}  -> ${JSON.stringify(p.want)}`);
    log(`  admin         GET /spaces/${space}/audit/route-inventory  -> ${EXPECTED_LA_ROUTES} /inv + /geo routes`);
    log(`  fm.analyst    GET dossier, tamper the manifest, POST dossier/verify  -> verified:false`);
    log(`  fm.analyst    replay hash before/after copying a staged file  ${inbox && staged ? '' : '(SKIPPED: no --staged/--inbox)'}`);
    log(`  burst: 25 POST /inv/projection -> 429; 19 concurrent graph runs -> 503; 16 concurrent index builds -> 503; duplicate build -> 409  ${burst ? '' : '(SKIPPED: no --burst)'}`);
    log(`  telecom (${presenter}): Datasets + link columns; trigger ${TELECOM_JOBS.map(([j]) => j).join(', ')} and wait for rows; `
      + `Investigation seed ${TELECOM_SEED} + expand maxFanOut ${fourEyes}; ${Object.keys(TELECOM_PACKS).join(', ')} >= 1 match; `
      + `Alerts ${TELECOM_ALERT_RULES.join(', ')}  ${telecom ? '' : '(SKIPPED: no --telecom)'}`);
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
      // The shipped staged files touch none of the seeded Investigation's entities, so they cannot drift a re-read: land a file
      // that adds many MULE-HUB-01 links instead (header taken from the staged file), then wait for the Space to ingest it.
      const header = fs.readFileSync(path.join(staged, file), 'utf8').split(/\r?\n/)[0];
      const day = driftDay(now());
      fs.writeFileSync(path.join(inbox, driftFileName(day)), driftCsv(header, day));
      const gone = await waitGone(path.join(inbox, driftFileName(day)), { timeoutMs: landTimeoutMs, pollMs: 3000, sleep, now });
      if (!gone) record('replay hash unchanged after a file lands', `${driftFileName(day)} was not ingested within ${landTimeoutMs} ms`);
      else {
        await sleep(3000);   // pipeline.commit and the Dataset are visible a moment after the file leaves the inbox
        const after = await hashOf();
        const reread = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/replay`, { reread: true });
        record('replay hash unchanged after a file lands', before === after ? null : `${before} -> ${after}`);
        record('Re-read reports drift after a file lands', reread.status === 200 && reread.data.diverged === true ? null : `HTTP ${reread.status} diverged=${reread.data && reread.data.diverged}`);
      }
    }
  } else log('skip  replay-vs-drift (no --staged/--inbox)');

  if (burst) {
    // The expensive-route bucket is per subject and SHARED by projection, Graph Runs and Index builds (20 tokens, +1 per 3 s), so
    // each capacity probe waits for its tokens first; the 429 probe goes last (it only needs a bucket that runs dry).
    const refill = (tokens) => sleep(tokens * 3000);
    // distinct params per run: an identical request is answered from the result cache (200 cached:true) and never reaches the pool
    const runs = await Promise.all(Array.from({ length: 19 }, (_, i) => call('fm.analyst', 'POST', '/inv/graph/runs',
      { investigationId: INV, algorithm: 'pageRank', params: { iterations: 60 + i } })));
    const runStatuses = runs.map((r) => r.status);
    if (poolInlineOnly(runStatuses)) skip('19 concurrent Graph Runs -> a 503', 'every run finished inline (a SYNC algorithm under its node ceiling): the pool is unreachable on a graph this small');
    else record('19 concurrent Graph Runs -> a 503', poolVerdict(runStatuses));
    const bodies = indexBurstBodies();
    await refill(bodies.length);
    const starts = await Promise.all(bodies.map((b) => call('fm.analyst', 'POST', '/inv/index/builds', b)));
    record(`${bodies.length} concurrent index builds -> a 503 (queue full)`, indexQueueVerdict(starts.map((r) => r.status)));
    await refill(2);
    const dupBody = { ...bodies[0], attrCols: ['AMOUNT'] };   // outside the 16 burst mappings, so the first of the pair cannot itself be a 409
    const a = await call('fm.analyst', 'POST', '/inv/index/builds', dupBody);
    const b = await call('fm.analyst', 'POST', '/inv/index/builds', dupBody);
    record('a second live build of the same mapping -> 409', duplicateBuildVerdict(a, b));
    const seq = [];
    for (let i = 0; i < 25; i++) seq.push((await call('fm.analyst', 'POST', '/inv/projection',
      { dataset: 'mule_transfers_dataset', sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' })).status);
    record('>20 expensive POSTs -> 429', rateLimitVerdict(seq));
  } else log('skip  burst limits (no --burst)');

  if (telecom) await runTelecom({ call, record, skip, presenter, fourEyes, timeoutMs: telecomTimeoutMs });
  else log('skip  telecom (no --telecom)');

  return results;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), offSpace: opt('--off-space', 'la-showcase-off'),
    inbox: opt('--inbox', null), staged: opt('--staged', null), burst: args.includes('--burst'),
    telecom: args.includes('--telecom'), presenter: opt('--presenter', null), fourEyes: Number(opt('--four-eyes', 2000)),
    telecomTimeoutMs: Number(opt('--telecom-timeout', 180)) * 1000, dryRun: args.includes('--dry-run'), log: console.log })
    .then((rs) => { const bad = rs.filter((r) => !r.ok); console.log(`${rs.length - bad.length}/${rs.length} probes passed`); process.exitCode = bad.length ? 1 : 0; })
    .catch((e) => { console.error(e.message); process.exitCode = 2; });
}
