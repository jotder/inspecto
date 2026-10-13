// Unit test for the pure assertion helpers of tools/la-demo-smoke.mjs, plus a stub-server run. node --test tools/la-demo-smoke.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { moduleRoutePatterns } from './lib/module-routes.mjs';
import { expectStatus, countLaRoutes, tamperManifest, rateLimitVerdict, poolVerdict, buildProbes, run, EXPECTED_LA_ROUTES,
  TELECOM_DATASETS, TELECOM_LINK_COLUMNS, TELECOM_ALERT_RULES, TELECOM_HUB, TELECOM_PACKS, telecomRegistryVerdict, triggerVerdict,
  expandVerdict, patternVerdict, telecomAlertsVerdict, runTelecom, resolvePresenter, denialReason } from './la-demo-smoke.mjs';

test('expectStatus checks the status and, when asked, the error code', () => {
  assert.equal(expectStatus({ status: 404, body: { error: { errorCode: 'MODULE_DISABLED' } } }, { status: 404, code: 'MODULE_DISABLED' }), null);
  assert.match(expectStatus({ status: 404, body: { error: { errorCode: 'NOT_FOUND' } } }, { status: 404, code: 'MODULE_DISABLED' }), /MODULE_DISABLED/);
  assert.match(expectStatus({ status: 200, body: null }, 401), /401/);
  assert.equal(expectStatus({ status: 401, body: null }, 401), null);
});

test('countLaRoutes counts /inv and /geo rows only; a non-inventory is -1', () => {
  const routes = [{ pattern: '/inv/index' }, { pattern: '/geo/projection' }, { pattern: '/cases' }, { pattern: '/invoice' }];
  assert.equal(countLaRoutes({ routes }), 2);
  assert.equal(countLaRoutes({}), -1);
});

test('every route the LA manifests declare is counted by countLaRoutes (the 82-vs-81 mismatch was the /public embed route)', () => {
  const declared = [...moduleRoutePatterns('la/inspecto-la-api'), ...moduleRoutePatterns('la/inspecto-geo-link')];
  assert.equal(declared.length, EXPECTED_LA_ROUTES);
  const routes = declared.map((d) => ({ pattern: d.split(' ')[1] }));
  assert.equal(countLaRoutes({ routes }), EXPECTED_LA_ROUTES);
  assert.ok(routes.some((r) => r.pattern.startsWith('/public/')), 'the anonymous embed route is among them');
});

test('tamperManifest changes every 64-hex hash and keeps the rest', () => {
  const h = 'a'.repeat(64);
  const t = tamperManifest({ root: h, entries: [{ path: 'log.jsonl', sha256: h }], n: 3 });
  assert.notEqual(t.root, h);
  assert.notEqual(t.entries[0].sha256, h);
  assert.equal(t.entries[0].path, 'log.jsonl');
  assert.equal(t.n, 3);
});

test('tamperManifest flips the server form sha256:<64hex> and keeps the prefix', () => {
  const h = 'sha256:' + 'a'.repeat(64);
  const t = tamperManifest({ root: h, artefacts: [{ sha256: h }], content: { entities: h } });
  assert.equal(t.root, 'sha256:0' + 'a'.repeat(63));
  assert.notEqual(t.artefacts[0].sha256, h);
  assert.notEqual(t.content.entities, h);
});

test('tamperManifest refuses a manifest with no hash to tamper', () => {
  assert.throws(() => tamperManifest({ root: 'not-a-hash', n: 3 }), /no hash field/);
});

test('rateLimitVerdict needs a 429 and a bounded pass-through', () => {
  assert.equal(rateLimitVerdict([...Array(20).fill(200), ...Array(5).fill(429)]), null);
  assert.match(rateLimitVerdict(Array(25).fill(200)), /no 429/);
  assert.match(rateLimitVerdict([...Array(30).fill(200), 429]), /got through/);
});

test('poolVerdict needs at least one 503', () => {
  assert.equal(poolVerdict([...Array(18).fill(200), 503]), null);
  assert.match(poolVerdict(Array(19).fill(200)), /no 503/);
});

test('the probe table covers 401, 403, 404, 404 MODULE_DISABLED and 422', () => {
  const wants = buildProbes().map((p) => (typeof p.want === 'number' ? p.want : `${p.want.status}:${p.want.code}`));
  for (const w of [401, 403, 404, '404:MODULE_DISABLED', 422]) assert.ok(wants.includes(w), String(w));
});

test('run() against a stub server passes every probe, and fails a wrong status', async () => {
  const hash = 'b'.repeat(64);
  const stub = (wrong401) => async (url, init = {}) => {
    const u = new URL(url); const p = u.pathname.replace(/^\/api\/v1(\/spaces\/[^/]+)?/, ''); const space = (u.pathname.match(/spaces\/([^/]+)/) || [])[1];
    const who = (init.headers || {}).authorization;
    const res = (status, body) => ({ ok: status < 400, status, json: async () => body });
    if (p === '/auth/exchange') return res(200, { data: { accessToken: JSON.parse(init.body).code } });
    if (!who) return res(wrong401 ? 200 : 401, {});
    if (who === 'Bearer demo:ra.analyst') return res(403, {});
    if (who === 'Bearer demo:demo.manager') return res(404, {});
    if (space === 'la-showcase-off') return res(404, { error: { errorCode: 'MODULE_DISABLED' } });
    if (p === '/inv/investigations' && init.method === 'POST') return res(422, {});
    if (p.endsWith('/log')) return res(200, { data: {} });
    if (p === '/audit/route-inventory') return res(200, { data: { routes: Array.from({ length: EXPECTED_LA_ROUTES }, (_, i) => ({ pattern: `/inv/x${i}` })) } });
    if (p.endsWith('/dossier')) return res(200, { data: { manifest: { root: hash } } });
    if (p.endsWith('/dossier/verify')) return res(200, { data: { verified: JSON.parse(init.body).manifest.root === hash } });
    return res(500, {});
  };
  const ok = await run({ fetchFn: withNewer(stub(false)) });
  assert.ok(ok.every((r) => r.ok), JSON.stringify(ok.filter((r) => !r.ok)));
  assert.ok(ok.some((r) => r.id.includes(String(EXPECTED_LA_ROUTES))));
  const bad = await run({ fetchFn: withNewer(stub(true)) });
  assert.ok(bad.some((r) => !r.ok && r.id === '401 no token'));
});

// ---- telecom group -------------------------------------------------------------------------------------------------

const fullCols = TELECOM_LINK_COLUMNS.map((name) => ({ name }));
const dsList = (cols = fullCols) => TELECOM_DATASETS.map((name) => ({ name, content: { columns: name === 'telecom_links_dataset' ? cols : [] } }));

test('telecomRegistryVerdict catches a missing Dataset and an incomplete telecom_links_dataset', () => {
  assert.equal(telecomRegistryVerdict(dsList()), null);
  assert.match(telecomRegistryVerdict(dsList().filter((d) => d.name !== 'hlr_eir_dataset')), /hlr_eir_dataset/);
  assert.match(telecomRegistryVerdict(dsList([{ name: 'a_msisdn' }, { name: 'b_msisdn' }, { name: 'via' }])), /link_kind, first_seen, last_seen, events/);
});

test('triggerVerdict passes 2xx and names canOperateRuns on 403', () => {
  assert.equal(triggerVerdict({ status: 202, body: {} }), null);
  assert.match(triggerVerdict({ status: 403, body: { error: { errorCode: 'FORBIDDEN' } } }), /canOperateRuns.*FORBIDDEN/);
});

test('expandVerdict: entities admitted, hub flagged when reached, held is a FAIL', () => {
  const step = { status: 200, data: { delta: { admitted: ['msisdn:99979100002'] } } };
  const ws = (flag) => ({ status: 200, data: { rows: [{ entityId: `msisdn:${TELECOM_HUB}`, highConnectivity: flag }] } });
  assert.equal(expandVerdict(step, ws(true)), null);
  assert.equal(expandVerdict(step, { status: 200, data: { rows: [] } }), null);
  assert.match(expandVerdict(step, ws(false)), /not flagged/);
  assert.match(expandVerdict({ status: 200, data: { delta: { admitted: [] } } }, ws(true)), /no entity/);
  assert.match(expandVerdict({ status: 202, data: { pending: true } }, null), /held/);
});

test('patternVerdict needs >= 1 match and no refusal', () => {
  assert.equal(patternVerdict({ status: 200, data: { matches: [{}] } }), null);
  assert.match(patternVerdict({ status: 200, data: { matches: [] } }), /0 matches/);
  assert.match(patternVerdict({ status: 200, data: { matches: [], refusal: { reason: 'x' } } }), /refused/);
  assert.match(patternVerdict({ status: 404, body: {} }), /404/);
});

test('telecomAlertsVerdict needs all three rules and no MSISDN', () => {
  const a = TELECOM_ALERT_RULES.map((rule) => ({ rule, value: 3, message: `${rule}: 3 lines` }));
  assert.equal(telecomAlertsVerdict(a), null);
  assert.match(telecomAlertsVerdict(a.slice(1)), /telecom_wangiri_lines/);
  assert.match(telecomAlertsVerdict([...a.slice(1), { rule: TELECOM_ALERT_RULES[0], message: 'line 99979100001' }]), /MSISDN/);
});

test('TELECOM_PACKS mirrors the template pattern packs', () => {
  const dir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'spaces', '_templates', 'la-showcase', 'config', 'registry', 'pattern-packs');
  for (const [pack, stages] of Object.entries(TELECOM_PACKS)) {
    const rows = fs.readFileSync(path.join(dir, `${pack}.toon`), 'utf8').split(/\r?\n/).filter((l) => /^\s+fan-(in|out),/.test(l))
      .map((l) => l.trim().split(','));
    assert.equal(rows.length, stages.length, pack);
    rows.forEach(([shape, minBranches, edgeKind, , windowHours, afterPrevious, maxGapHours, , , , closesToStart], i) => {
      const want = { shape, minBranches: +minBranches, edgeKind };
      if (+windowHours > 0) want.windowHours = +windowHours;
      if (afterPrevious === 'true') { want.afterPrevious = true; if (+maxGapHours > 0) want.maxGapHours = +maxGapHours; }
      if (closesToStart === 'true') want.closesToStart = true;
      assert.deepEqual(stages[i], want, `${pack} stage ${i}`);
    });
  }
});

/** A fake telecom Space: `faults` switches individual defects on. */
const telecomCall = (faults = {}) => {
  const calls = [];
  let rowsPolls = 0;
  const call = async (user, method, p, body) => {
    calls.push({ user, method, p, body });
    const r = (status, data) => ({ status, body: { data }, data });
    if (p === '/components/dataset') return r(200, dsList(faults.cols));
    if (p.startsWith('/jobs/')) return faults.forbidden ? { status: 403, body: { error: { errorCode: 'FORBIDDEN', message: 'canOperateRuns' } }, data: null } : r(202, { runId: 'x' });
    if (p.startsWith('/datasets/')) return r(200, { rows: faults.noRows || rowsPolls++ % 2 === 0 ? [] : [{ n: 1 }] });
    if (p === '/inv/investigations') return r(201, { id: body.id });
    if (p.endsWith('/ops')) return body.op === 'seed' ? r(200, {}) : r(200, { delta: { admitted: ['msisdn:99979100002', `msisdn:${TELECOM_HUB}`] } });
    if (p.includes('/working-set')) return r(200, { rows: [{ entityId: `msisdn:${TELECOM_HUB}`, highConnectivity: !faults.unflagged }] });
    if (p === '/inv/pattern/branching') return r(200, { matches: faults.noMatch ? [] : [{ layers: [] }] });
    if (p.startsWith('/alerts')) return r(200, TELECOM_ALERT_RULES.map((rule) => ({ rule, value: 2 })));
    return r(500, null);
  };
  return { call, calls };
};
const runT = async (faults, extra = {}) => {
  const results = [];
  const { call, calls } = telecomCall(faults);
  let t = 0;
  await runTelecom({ call, record: (id, reason) => results.push({ id, ok: !reason, reason }),
    skip: (id, reason) => results.push({ id, ok: true, skipped: true, reason }), ...extra, timeoutMs: 10, pollMs: 1,
    sleep: async () => { t += 5; }, now: () => t });
  return { results, calls };
};

test('runTelecom passes every probe on a healthy Space and polls rows', async () => {
  const { results, calls } = await runT({});
  assert.ok(results.length >= 11, String(results.length));
  assert.ok(results.every((r) => r.ok), JSON.stringify(results.filter((r) => !r.ok)));
  const expand = calls.find((c) => c.body && c.body.op === 'expand');
  assert.ok(expand.body.maxFanOut <= 2000);
  const create = calls.find((c) => c.p === '/inv/investigations');
  assert.equal(create.body.eventsCol, 'events');
  assert.equal(calls.filter((c) => c.p === '/inv/pattern/branching').length, 3);
});

test('runTelecom FAILs each telecom defect', async () => {
  const failed = async (f) => (await runT(f)).results.filter((r) => !r.ok).map((r) => r.id + ' ' + r.reason).join('\n');
  assert.match(await failed({ cols: [{ name: 'a_msisdn' }] }), /lacks columns/);
  assert.match(await failed({ forbidden: true }), /fm\.manager lacks canOperateRuns/);
  assert.match(await failed({ noRows: true }), /no rows after/);
  assert.match(await failed({ unflagged: true }), /not flagged/);
  assert.match(await failed({ noMatch: true }), /pattern pack telecom-.* 0 matches/);
});

test('the dry run lists the telecom group as skipped without --telecom', async () => {
  const lines = [];
  await run({ dryRun: true, log: (l) => lines.push(l) });
  assert.ok(lines.some((l) => /telecom.*SKIPPED/.test(l)));
});

test('--telecom defaults the presenter to fm.manager; an explicit --presenter wins', async () => {
  assert.equal(resolvePresenter(null, true), 'fm.manager');
  assert.equal(resolvePresenter(null, false), 'fm.analyst');
  assert.equal(resolvePresenter('fm.analyst', true), 'fm.analyst');
  const { calls } = await runT({});
  assert.ok(calls.every((c) => c.user === 'fm.manager'));
  const explicit = await runT({}, { presenter: 'fm.analyst' });
  assert.ok(explicit.calls.every((c) => c.user === 'fm.analyst'));
  const lines = [];
  await run({ dryRun: true, telecom: true, log: (l) => lines.push(l) });
  assert.ok(lines.some((l) => /telecom \(fm\.manager\)/.test(l)), lines.join('\n'));
});

test('a 403 trigger FAILs naming persona + canOperateRuns and SKIPs the dependent rows/Alerts probes', async () => {
  assert.equal(denialReason({ status: 202 }, 'x'), null);
  const { results } = await runT({ forbidden: true }, { presenter: 'fm.analyst' });
  const fails = results.filter((r) => !r.ok);
  assert.equal(fails.filter((r) => /triggers Job/.test(r.id)).length, 3);
  assert.ok(fails.every((r) => !/has rows|no rows|did not produce rows/.test(r.id + r.reason)), JSON.stringify(fails));
  assert.ok(fails.filter((r) => /triggers Job/.test(r.id)).every((r) => /fm\.analyst lacks canOperateRuns/.test(r.reason)));
  const skipped = results.filter((r) => r.skipped);
  assert.equal(skipped.length, 4);
  assert.ok(skipped.every((r) => /trigger denied: fm\.analyst lacks canOperateRuns/.test(r.reason)));
  assert.ok(skipped.some((r) => /Alerts/.test(r.id)));
});

// ---- index burst, duplicate build, replay-vs-drift ------------------------------------------------------------------

import os from 'node:os';
import { indexBurstBodies, indexQueueVerdict, duplicateBuildVerdict, poolInlineOnly, driftDay, driftCsv, driftFileName, waitGone } from './la-demo-smoke.mjs';

test('indexBurstBodies: 16 DISTINCT mappings (a repeat would be a 409, not a queue slot)', () => {
  const bodies = indexBurstBodies();
  assert.equal(bodies.length, 16);
  assert.equal(new Set(bodies.map((b) => JSON.stringify(b))).size, 16);
  assert.ok(bodies.every((b) => b.dataset === 'mule_transfers_dataset' && b.sourceCol && b.targetCol));
});

test('indexQueueVerdict needs an accepted build, a 503, and nothing else', () => {
  assert.equal(indexQueueVerdict([...Array(5).fill(202), ...Array(11).fill(503)]), null);
  assert.match(indexQueueVerdict(Array(16).fill(202)), /no 503/);
  assert.match(indexQueueVerdict(Array(16).fill(503)), /no build accepted/);
  assert.match(indexQueueVerdict([202, 503, 429]), /unexpected statuses 429/);
});

test('duplicateBuildVerdict: the repeat of a live mapping is a 409', () => {
  assert.equal(duplicateBuildVerdict({ status: 202 }, { status: 409 }), null);
  assert.match(duplicateBuildVerdict({ status: 202 }, { status: 202 }), /expected 409, got 202/);
  assert.match(duplicateBuildVerdict({ status: 429, body: null }, { status: 409 }), /first build not accepted/);
});

test('poolInlineOnly: all-inline runs are a skip, a 429 or a 503 is not', () => {
  assert.equal(poolInlineOnly(Array(19).fill(200)), true);
  assert.equal(poolInlineOnly([200, 429]), false);
  assert.equal(poolInlineOnly([200, 503]), false);
  assert.equal(poolInlineOnly([]), false);
  assert.equal(poolInlineOnly(Array(19).fill(202)), false);   // a 202 reached the pool
});

test('driftCsv: header kept, 40 MULE-HUB-01 rows on the drift day, every id unique; driftDay is far-future and per second', () => {
  const header = 'TRANSFER_ID,PAYER_ACCOUNT,PAYEE_ACCOUNT,CHANNEL,AMOUNT,CURRENCY,PAYER_COUNTRY,PAYEE_COUNTRY,BOOKED_DATE,BOOKED_AT';
  const day = driftDay(1_700_000_000_000);
  assert.match(day, /^20(27|28|29|30|31|32|33|34|35)\d{4}$/);
  assert.notEqual(driftDay(1_700_000_001_000), day);
  const lines = driftCsv(header, day).trim().split('\n');
  assert.equal(lines[0], header);
  assert.equal(lines.length, 41);
  assert.ok(lines.slice(1).every((l) => l.split(',').length === 10 && l.split(',')[1] === 'MULE-HUB-01'));
  assert.equal(new Set(lines.slice(1).map((l) => l.split(',')[0])).size, 40);
  assert.equal(driftFileName(day), `TRANSFERS_${day}.csv`);
});

test('waitGone: true once the file is gone, false at the deadline', async () => {
  const f = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'wg-')), 'x.csv');
  fs.writeFileSync(f, 'x');
  let t = 0;
  assert.equal(await waitGone(f, { timeoutMs: 60000, pollMs: 3000, now: () => t, sleep: async (ms) => { t += ms; if (t >= 9000) fs.rmSync(f); } }), true);
  fs.writeFileSync(f, 'x'); t = 0;
  assert.equal(await waitGone(f, { timeoutMs: 6000, pollMs: 3000, now: () => t, sleep: async (ms) => { t += ms; } }), false);
});

/** A stub Space for the --staged/--burst path. `drift` = whether the re-read reports divergence; `queue` = builds accepted before 503. */
function burstStub({ drift = true, queue = 5 } = {}) {
  let n = 0, bucket = 20;
  const dupSeen = new Set();
  return async (url, init = {}) => {
    const u = new URL(url); const p = u.pathname.replace(/^\/api\/v1(\/spaces\/[^/]+)?/, ''); const space = (u.pathname.match(/spaces\/([^/]+)/) || [])[1];
    const who = (init.headers || {}).authorization;
    const res = (status, body) => ({ ok: status < 400, status, json: async () => body });
    if (p === '/auth/exchange') return res(200, { data: { accessToken: JSON.parse(init.body).code } });
    if (!who) return res(401, {});
    if (who === 'Bearer demo:ra.analyst') return res(403, {});
    if (who === 'Bearer demo:demo.manager') return res(404, {});
    if (space === 'la-showcase-off') return res(404, { error: { errorCode: 'MODULE_DISABLED' } });
    if (p === '/inv/investigations' && init.method === 'POST') return res(422, {});
    if (p.endsWith('/log')) return res(200, { data: {} });
    if (p === '/audit/route-inventory') return res(200, { data: { routes: Array.from({ length: EXPECTED_LA_ROUTES }, (_, i) => ({ pattern: `/inv/x${i}` })) } });
    if (p.endsWith('/dossier')) return res(200, { data: { manifest: { root: 'b'.repeat(64) } } });
    if (p.endsWith('/dossier/verify')) return res(200, { data: { verified: JSON.parse(init.body).manifest.root === 'b'.repeat(64) } });
    if (p.endsWith('/replay')) return res(200, { data: JSON.parse(init.body).reread ? { diverged: drift } : { workingSet: { hash: 'h0' } } });
    if (p === '/inv/graph/runs') return res(200, { data: { status: 'COMPLETED', cached: false } });
    if (p === '/inv/index/builds') {
      if (dupSeen.has(init.body)) return res(409, {});
      dupSeen.add(init.body);
      return res(!init.body.includes('AMOUNT') && ++n > queue ? 503 : 202, {});   // the burst fills the queue; by the repeat pair it has drained
    }
    if (p === '/inv/projection') return res(--bucket >= 0 ? 403 : 429, {});
    return res(500, {});
  };
}
const fastSleep = async () => {};
const HEADER = 'TRANSFER_ID,PAYER_ACCOUNT,PAYEE_ACCOUNT,CHANNEL,AMOUNT,CURRENCY,PAYER_COUNTRY,PAYEE_COUNTRY,BOOKED_DATE,BOOKED_AT';
const dirs = () => {
  const staged = fs.mkdtempSync(path.join(os.tmpdir(), 'st-')); const inbox = fs.mkdtempSync(path.join(os.tmpdir(), 'ib-'));
  fs.writeFileSync(path.join(staged, 'TRANSFERS_20260904.csv'), `${HEADER}\nTX-1,A,B,card,1,EUR,FR,NL,2026-09-04,2026-09-04 00:00:00\n`);
  return { staged, inbox };
};
const ingest = (inbox) => async () => { for (const f of fs.readdirSync(inbox)) fs.rmSync(path.join(inbox, f)); };   // the Space ingests on the first poll

test('run() --staged: writes a drift file, waits for the Space to ingest it, then hash unchanged and the re-read drifts', async () => {
  const { staged, inbox } = dirs();
  let landed = null;
  const rs = await run({ fetchFn: withNewer(burstStub()), staged, inbox, sleep: async () => { landed ??= fs.readdirSync(inbox); await ingest(inbox)(); } });
  assert.ok(rs.every((r) => r.ok), JSON.stringify(rs.filter((r) => !r.ok)));
  assert.ok(rs.some((r) => r.id === 'Re-read reports drift after a file lands' && r.ok));
  assert.equal(landed.length, 1);
  assert.match(landed[0], /^TRANSFERS_20\d{6}\.csv$/);
});

test('run() --staged FAILs a file that is never ingested, and a landed file that does not drift the re-read', async () => {
  const a = dirs(); let t = 0;
  const stuck = await run({ fetchFn: withNewer(burstStub()), staged: a.staged, inbox: a.inbox, landTimeoutMs: 6000, now: () => (t += 3000), sleep: fastSleep });
  assert.ok(stuck.some((r) => !r.ok && /not ingested within 6000 ms/.test(r.reason)), JSON.stringify(stuck));
  const b = dirs();
  const noDrift = await run({ fetchFn: withNewer(burstStub({ drift: false })), staged: b.staged, inbox: b.inbox, sleep: ingest(b.inbox) });
  assert.ok(noDrift.some((r) => !r.ok && r.id === 'Re-read reports drift after a file lands'));
});

test('run() --burst: inline Graph Runs are SKIPPED; the 6th index build is a 503, a repeat a 409, the bucket a 429', async () => {
  const waits = [];
  const rs = await run({ fetchFn: withNewer(burstStub()), burst: true, sleep: async (ms) => { waits.push(ms); } });
  assert.ok(rs.every((r) => r.ok), JSON.stringify(rs.filter((r) => !r.ok)));
  assert.ok(rs.some((r) => r.skipped && /19 concurrent Graph Runs/.test(r.id)));
  for (const id of ['16 concurrent index builds -> a 503 (queue full)', 'a second live build of the same mapping -> 409', '>20 expensive POSTs -> 429'])
    assert.ok(rs.some((r) => r.id === id && r.ok && !r.skipped), id);
  assert.deepEqual(waits, [16 * 3000, 2 * 3000]);   // each capacity probe waits for its tokens in the shared bucket
});

test('run() --burst FAILs a queue that never fills', async () => {
  const wide = await run({ fetchFn: withNewer(burstStub({ queue: 99 })), burst: true, sleep: fastSleep });
  assert.ok(wide.some((r) => !r.ok && /index builds/.test(r.id) && /no 503/.test(r.reason)), JSON.stringify(wide.filter((r) => !r.ok)));
});

// ---- newer-behaviour probes ---------------------------------------------------------------------------------------------
import { emptyDatasetVerdict, readOnlyVerdict, settingsPutBody, maxAgeVerdict, settingsRoundtripVerdict, publicViewVerdict, uniform404Verdict,
  bootstrapVerdict, mangleToken, runNewer } from './la-demo-smoke.mjs';

/** Wraps a stub fetch with a healthy Space for the newer probes; `f` switches defects on. */
function withNewer(inner, f = {}) {
  let settings = { projectionNodeCap: 5000, maskingMode: 'mask', index: { maxAgeHours: 24 } };
  const live = new Set();
  const res = (status, body, headers = {}) => ({ ok: status < 400, status, json: async () => body, headers: { get: (k) => headers[k.toLowerCase()] ?? null } });
  return async (url, init = {}) => {
    const u = new URL(url); const p = u.pathname.replace(/^\/api\/v1(\/spaces\/[^/]+)?/, ''); const who = (init.headers || {}).authorization;
    const body = init.body && !p.startsWith('/auth') ? JSON.parse(init.body) : null;
    if (p === '/bootstrap') return res(200, { data: { auth: f.noMock ? {} : { mock: true, ...(f.attached0 && { spacesAttached: 0 }) } } });
    if (p.startsWith('/datasets/telecom_links_dataset/rows')) return res(f.rowsMissing ? 404 : 200, { data: { rows: f.rowsExist ? [{ a: 1 }] : [] } });
    if (p === '/inv/projection' && init.method === 'POST' && body.dataset === 'telecom_links_dataset')
      return res(f.wrong409 ? 500 : 409, { error: { message: f.leak ? 'has no data yet: IO Error: read_parquet C:/data/x' : 'telecom_links_dataset has no data yet' } });
    if (p.endsWith('/ops') && who === 'Bearer demo:ra.analyst') return res(f.writeAllowed ? 200 : 403, { error: { message: 'missing capability canInvestigate' } });
    if (p.endsWith('/log') && who === 'Bearer demo:ra.analyst') return res(200, { data: {} });
    if (p === '/settings/link-analysis') {
      if (!init.method || init.method === 'GET') return res(200, { data: { ...settings, maskingModeInForce: 'mask', hubThresholdInForce: 1 } });
      if (body.index && (body.index.maxAgeHours === 0 || body.index.maxAgeHours === 8761)) return res(f.range422 ? 200 : 422, { error: { message: 'index.maxAgeHours must be 1..8760' } });
      if (f.settingsDrift) settings = { ...settings, projectionNodeCap: 1 }; else settings = body;
      return res(200, { data: settings });
    }
    if (p.endsWith('/embeds') && init.method === 'POST') {
      if (f.embedOn) { live.add('t'); return res(200, { data: { token: 'tok-abcdefABCDEF', url: '/public/investigations/tok-abcdefABCDEF', grantId: 'g1' } }); }
      return res(503, { error: { errorCode: 'CAPABILITY_UNAVAILABLE' } });
    }
    if (p.endsWith('/revoke')) { live.delete('t'); return res(200, { data: {} }); }
    if (p.startsWith('/public/investigations/') && !u.pathname.includes('/spaces/')) {
      const tok = p.split('/').pop();
      if (tok === 'tok-abcdefABCDEF' && live.has('t')) return res(200, { data: {} }, f.badHeaders ? {} : { 'content-security-policy': "frame-ancestors 'none'", 'x-frame-options': 'DENY', 'cache-control': 'no-store' });
      return res(404, { error: { errorCode: 'NOT_FOUND', message: tok === 'tok-abcdefABCDEF' && f.oracle ? 'revoked' : 'not found' } });
    }
    return inner(url, init);
  };
}
const runN = async (f, extra = {}) => {
  const out = [];
  const fx = withNewer(async () => ({ ok: false, status: 500, json: async () => ({}), headers: { get: () => null } }), f);
  const pack = async (r) => { const b = await r.json().catch(() => null); return { status: r.status, body: b, data: b && 'data' in b ? b.data : b, headers: r.headers }; };
  const call = (user, method, p, body) => fx(`http://x/api/v1/spaces/s${p}`, { method, headers: user ? { authorization: `Bearer demo:${user}` } : {}, body: body === undefined ? undefined : JSON.stringify(body) }).then(pack);
  await runNewer({ ...extra, call, pub: (p) => fx(`http://x/api/v1${p}`, { method: 'GET' }).then(pack), boot: () => fx('http://x/api/v1/bootstrap', { method: 'GET' }).then(pack),
    record: (id, reason) => out.push({ id, ok: !reason, reason }), skip: (id, reason) => out.push({ id, ok: true, skipped: true, reason }) });
  return out;
};
const failsOf = (rs) => rs.filter((r) => !r.ok).map((r) => `${r.id}: ${r.reason}`).join('\n');

test('newer probes: every one passes on a healthy dark Space; the embed group is the dark answers', async () => {
  const rs = await runN({});
  assert.equal(failsOf(rs), '');
  for (const id of [/empty-dataset/, /read-only-persona/, /replace-with-itself/, /maxAgeHours 0/, /maxAgeHours 8761/, /embed \(dark\): mint/, /uniform 404/, /signin-picker: \/bootstrap/])
    assert.ok(rs.some((r) => id.test(r.id) && r.ok && !r.skipped), String(id));
  assert.ok(rs.some((r) => r.skipped && /spacesAttached/.test(r.id)));
});

test('empty-dataset: FAILs a leak or a wrong status; SKIPs when the Dataset has rows or is unreadable', async () => {
  assert.match(failsOf(await runN({ leak: true })), /empty-dataset.*leaks/);
  assert.match(failsOf(await runN({ wrong409: true })), /empty-dataset.*expected HTTP 409/);
  for (const f of [{ rowsExist: true }, { rowsMissing: true }]) {
    const rs = await runN(f);
    assert.equal(failsOf(rs), '');
    assert.ok(rs.some((r) => r.skipped && /empty-dataset/.test(r.id)));
  }
  assert.match(emptyDatasetVerdict({ status: 409, body: { error: { message: 'has no data yet at /tmp/x' } } }), /\/tmp/);
});

test('read-only-persona FAILs a persona that can write', async () => {
  assert.match(failsOf(await runN({ writeAllowed: true })), /read-only-persona.*expected HTTP 403/);
  assert.match(readOnlyVerdict({ status: 403, body: { error: { message: 'nope' } } }, { status: 200 }), /missing capability/);
});

test('settings-roundtrip FAILs a drifting document and an accepted out-of-range maxAgeHours', async () => {
  assert.match(failsOf(await runN({ settingsDrift: true })), /replace-with-itself.*changed/);
  assert.match(failsOf(await runN({ range422: true })), /maxAgeHours 0.*expected HTTP 422/);
  assert.deepEqual(settingsPutBody({ a: 1, aInForce: 2 }), { a: 1 });
  assert.match(maxAgeVerdict({ status: 422, body: { error: { message: 'bad' } } }), /1\.\.8760/);
  assert.match(settingsRoundtripVerdict({ status: 200, data: 1 }, { status: 422, body: null }, { status: 200, data: 1 }), /PUT same document/);
});

test('--embed: mint, headers, mangled == revoked 404, revoke pass; each defect FAILs; an enabled server without --embed SKIPs the dark pair', async () => {
  const ok = await runN({ embedOn: true }, { embed: true });
  assert.equal(failsOf(ok), '');
  assert.equal(ok.filter((r) => /^embed:/.test(r.id)).length, 3);
  assert.match(failsOf(await runN({ embedOn: true, badHeaders: true }, { embed: true })), /frame-ancestors/);
  assert.match(failsOf(await runN({ embedOn: true, oracle: true }, { embed: true })), /404 bodies differ/);
  assert.match(failsOf(await runN({}, { embed: true })), /embed: admin mints a link/);
  const enabledNoFlag = await runN({ embedOn: true });
  assert.ok(enabledNoFlag.some((r) => r.skipped && /embed \(dark\)/.test(r.id) && /pass --embed/.test(r.reason)));
  assert.match(publicViewVerdict({ status: 200, headers: { 'content-security-policy': "frame-ancestors 'none'", 'x-frame-options': 'SAMEORIGIN', 'cache-control': 'no-store' } }), /DENY/);
  assert.notEqual(mangleToken('abcA'), 'abcA');
  assert.match(uniform404Verdict({ status: 404, body: 1 }, { status: 200, body: 1 }), /two 404s/);
  const nf = (id, message = 'not found') => ({ status: 404, body: { error: { code: 'NOT_FOUND', message, correlationId: id } } });
  assert.equal(uniform404Verdict(nf('aaa'), nf('bbb')), null);   // only the per-request correlationId differs
  assert.match(uniform404Verdict(nf('aaa'), nf('bbb', 'token revoked')), /404 bodies differ/);   // a real oracle still fails
});

test('signin-picker FAILs a non-demo build or spacesAttached 0', async () => {
  assert.match(failsOf(await runN({ noMock: true })), /auth\.mock/);
  assert.match(failsOf(await runN({ attached0: true })), /spacesAttached is 0/);
  assert.equal(bootstrapVerdict({ status: 200, data: { auth: { mock: true, spacesAttached: 2 } } }), null);
});

test('the dry run lists the newer probes', async () => {
  const lines = []; await run({ dryRun: true, embed: true, log: (l) => lines.push(l) });
  for (const w of [/has no data yet/, /ra\.analyst/, /maxAgeHours/, /public GET/, /\/bootstrap/]) assert.ok(lines.some((l) => w.test(l)), String(w));
});
