// Unit test for the pure assertion helpers of tools/la-demo-smoke.mjs, plus a stub-server run. node --test tools/la-demo-smoke.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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
  const ok = await run({ fetchFn: stub(false) });
  assert.ok(ok.every((r) => r.ok), JSON.stringify(ok.filter((r) => !r.ok)));
  assert.ok(ok.some((r) => r.id.includes(String(EXPECTED_LA_ROUTES))));
  const bad = await run({ fetchFn: stub(true) });
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
  assert.ok(expand.body.maxFanOut <= 5);
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
