#!/usr/bin/env node
// Scripted probes of the `la-audit` Space (vertical B of docs/superpower/la-multi-vertical-showcase-plan.md), run against a SEEDED
// demo and ASSERTED: the Job outputs carry every planted fact (the ground truths pinned offline by LaAuditGoldenTest), the Dossier
// seals, verifies and FAILS verification after one byte is changed, and the personas hold. Needs the Demo Auth build (serve-demo,
// loopback) and the la-audit Space seeded by tools/seed-la-audit.mjs. Sends only `demo:<id>` codes. Self-contained.
//
//   node tools/la-audit-smoke.mjs [--base http://127.0.0.1:8096] [--space la-audit] [--timeout 180] [--dry-run]
//
// It TRIGGERS the link Jobs (as audit.manager, who holds canOperateRuns) and waits for rows, then reads the Datasets as audit.admin
// (who may reveal). Exit 1 on any failed probe.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeClient } from './lib/la-client.mjs';

export const INV = 'vendor-v117-workpaper';
const JOBS = [['ap_links', 'ap_links_dataset'], ['ap_approval_links', 'ap_approval_links_dataset'], ['ap_org_links', 'ap_org_links_dataset'],
  ['gl_links', 'gl_links_dataset'], ['ap_duplicate_payments', 'ap_duplicate_payments_dataset']];
const LIMIT = 10000;
const num = (x) => Number(x);

/** Rows of a /datasets/{id}/rows body as objects (the body may carry arrays plus a column list, or objects). */
export function asObjects(data) {
  const cols = (data.columns || []).map((c) => (typeof c === 'string' ? c : c.name));
  return (data.rows || []).map((r) => (Array.isArray(r) ? Object.fromEntries(cols.map((c, i) => [c, r[i]])) : r));
}

/** The planted facts over the ap_links rows. Returns [[name, reason|null], ...]; null = holds. */
export function apChecks(rows) {
  const by = (k) => rows.filter((r) => r.kind === k);
  const approved = by('approved');
  const acct = by('shares_account');
  const addr = by('shares_address');
  const has = (list, a, b) => list.some((r) => r.src === a && r.dst === b);
  const pv = new Set(Array.from({ length: 20 }, (_, i) => `V-${String(i + 21).padStart(3, '0')}`));
  const p = approved.filter((r) => pv.has(r.dst));
  const e7 = p.filter((r) => r.src === 'E-07').length;
  const band = approved.filter((r) => num(r.amount) >= LIMIT * 0.97 && num(r.amount) < LIMIT);
  const v117 = approved.filter((r) => r.dst === 'V-117');
  const shell = ['V-131', 'V-132', 'V-133', 'V-134', 'V-135'];
  const ok = (cond, why) => (cond ? null : why);
  return [
    ['approved links present (one per invoice)', ok(approved.length >= 1700, `${approved.length} approved rows`)],
    ['1 conflict of interest: V-117 shares account and address with E-31', ok(has(acct, 'E-31', 'V-117') && has(addr, 'E-31', 'V-117'), 'shares_account or shares_address E-31/V-117 missing')],
    ['1 V-117: 14 invoices, all approved by E-15', ok(v117.length === 14 && v117.every((r) => r.src === 'E-15'), `${v117.length} rows, approvers ${[...new Set(v117.map((r) => r.src))]}`)],
    ['2 shell cluster: 10 shares_account pairs among V-131..V-135', ok(acct.filter((r) => shell.includes(r.src) && shell.includes(r.dst)).length === 10, 'not 10 pairs')],
    ['2 the sixth vendor V-136 has no shares_account link', ok(!acct.some((r) => r.src === 'V-136' || r.dst === 'V-136'), 'V-136 is linked')],
    ['3 threshold splitting: 30 approved invoices at 97-100 % of the limit, all V-099 by E-23', ok(band.length === 30 && band.every((r) => r.dst === 'V-099' && r.src === 'E-23'), `${band.length} in band`)],
    ['4 single point of control: E-07 signs 70 % of the 20 vendors', ok(p.length > 0 && Math.abs(e7 / p.length - 0.7) <= 0.02, `${e7}/${p.length}`)],
    ['4 org chart: 59 reports_to links, manager to report', ok(by('reports_to').length === 59 && has(by('reports_to'), 'E-15', 'E-31'), `${by('reports_to').length} rows`)],
    ['placeholders: no link names PENDING or a blank key', ok(!rows.some((r) => r.src === 'PENDING' || r.dst === 'PENDING' || r.via === 'PENDING' || r.src === '' || r.dst === ''), 'a placeholder leaked into ap_links')],
    ['7 benign: the CFO E-01 approved 40, the utilities vendor V-001 is paid 100 times', ok(approved.filter((r) => r.src === 'E-01').length === 40 && by('paid_to').filter((r) => r.src === 'V-001').length === 100, 'benign counts differ')],
    ['event-day column is filled on every row (data-trust strip)', ok(rows.every((r) => r.event_date), 'a row has no event_date')],
  ];
}

/** The journal facts over the gl_links rows. */
export function glChecks(rows) {
  const ring = rows.filter((r) => num(r.amount) === 2400000);
  const journals = new Set(rows.map((r) => String(r.via).split('/')[0]));
  const legs = new Set(ring.map((r) => `${r.src}>${r.dst}`));
  return [
    ['5 intercompany round trip: 12 legs of 2 400 000 over LE-05..LE-08', ok2(ring.length === 12 && ['LE-05>LE-06', 'LE-06>LE-07', 'LE-07>LE-08', 'LE-08>LE-05'].every((l) => legs.has(l)), `${ring.length} legs`)],
    ['40 journals', ok2(journals.size === 40, `${journals.size} journals`)],
  ];
}
const ok2 = (cond, why) => (cond ? null : why);

/** Flip the first hex digit of every sha256 field, as a one-byte alteration of a sealed workpaper. */
export function tamperManifest(manifest) {
  const flip = (s) => s.replace(/^(sha256:)?([0-9a-f])/, (_, p = '', c) => p + (c === '0' ? '1' : '0'));
  const walk = (v) => {
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, typeof x === 'string' && /^(sha256:)?[0-9a-f]{64}$/.test(x) ? flip(x) : walk(x)]));
    return v;
  };
  const out = walk(manifest);
  if (JSON.stringify(out) === JSON.stringify(manifest)) throw new Error('tamperManifest: no hash field found to tamper');
  return out;
}

export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-audit', timeoutMs = 180000, pollMs = 3000, log = console.log,
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), now = () => Date.now() } = {}) {
  const client = makeClient({ fetchFn, base, space });
  const call = async (u, m, p, b) => { const r = await client(u, m, p, b); return { status: r.status, data: r.data, body: r.body }; };
  const results = [];
  const record = (name, reason) => { results.push({ name, reason }); log(`${reason ? 'FAIL' : 'PASS'}  ${name}${reason ? `: ${reason}` : ''}`); };

  for (const [job, dataset] of JOBS) {
    const t = await call('audit.manager', 'POST', `/jobs/${job}/trigger`, {});
    record(`audit.manager triggers ${job}`, t.status === 200 || t.status === 202 || t.status === 409 ? null : `HTTP ${t.status}`);
    const deadline = now() + timeoutMs;
    let rows = { status: 0 };
    for (;;) {
      rows = await call('audit.admin', 'GET', `/datasets/${dataset}/rows?limit=5000`);
      if (rows.status === 200 && rows.data && (rows.data.rows || []).length) break;
      if (now() >= deadline) break;
      await sleep(pollMs);
    }
    const got = rows.status === 200 && rows.data ? asObjects(rows.data) : [];
    record(`${dataset} has rows`, got.length ? null : `no rows (HTTP ${rows.status})`);
    if (dataset === 'ap_links_dataset' && got.length) for (const [n, r] of apChecks(got)) record(n, r);
    if (dataset === 'gl_links_dataset' && got.length) for (const [n, r] of glChecks(got)) record(n, r);
    if (dataset === 'ap_duplicate_payments_dataset') record('6 duplicate payments: 13 pairs', got.length === 13 ? null : `${got.length} rows`);
  }

  const base2 = `/inv/investigations/${INV}`;
  const dossier = await call('audit.lead', 'GET', `${base2}/dossier`);
  if (dossier.status !== 200 || !dossier.data.manifest) record('workpaper: the Dossier builds', `no manifest (HTTP ${dossier.status}); is the Space seeded?`);
  else {
    const clean = await call('audit.lead', 'POST', `${base2}/dossier/verify`, { manifest: dossier.data.manifest });
    record('workpaper: the untouched Dossier verifies', clean.status === 200 && clean.data.verified === true ? null : `HTTP ${clean.status} verified=${clean.data && clean.data.verified}`);
    const bad = await call('audit.lead', 'POST', `${base2}/dossier/verify`, { manifest: tamperManifest(dossier.data.manifest) });
    record('workpaper: one altered hash FAILS verification', (bad.status === 200 && bad.data.verified === false) || bad.status === 422 ? null : `HTTP ${bad.status} verified=${bad.data && bad.data.verified}`);
  }
  const write = await call('audit.committee', 'POST', `${base2}/ops`, { op: 'expand', direction: 'either', maxFanOut: 5 });
  const read = await call('audit.committee', 'GET', `${base2}/log`);
  record('audit.committee is read-only (write refused, read allowed)', write.status === 403 && read.status === 200 ? null : `write ${write.status}, read ${read.status}`);
  const outsider = await call('audit.outsider', 'GET', `${base2}/log`);
  record('audit.outsider is not a member (404)', outsider.status === 404 ? null : `HTTP ${outsider.status}`);
  const inbox = await call('audit.manager', 'GET', '/inv/pending?status=pending&limit=200');
  record('audit.manager sees the pending four-eyes expand', inbox.status === 200 && (inbox.data.items || []).some((i) => i.investigationId === INV) ? null : `HTTP ${inbox.status}`);
  const run1 = await call('audit.lead', 'POST', '/inv/graph/runs', { investigationId: INV, algorithm: 'articulationPoints', params: {} });
  record('Run on server accepts articulationPoints', [200, 202].includes(run1.status) ? null : `HTTP ${run1.status}`);
  return results;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--dry-run')) {
    console.log('DRY RUN: would trigger', JOBS.map((j) => j[0]).join(', '), '- read their Datasets as audit.admin, pin the planted facts, build/verify/tamper the Dossier of', INV);
    process.exit(0);
  }
  run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-audit'), timeoutMs: Number(opt('--timeout', '180')) * 1000 })
    .then((r) => { const f = r.filter((x) => x.reason); console.log(`\n${r.length - f.length}/${r.length} PASS`); process.exit(f.length ? 1 : 0); })
    .catch((e) => { console.error(e.message); process.exit(1); });
}
