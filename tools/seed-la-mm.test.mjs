// node --test tools/seed-la-mm.test.mjs  -  request building of the mobile-money seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, stampIndexBuildOwner, INV } from './seed-la-mm.mjs';

const OFF = { pending: false, alert: false };
function mock(listItems = []) {
  const calls = []; let created = false;
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET';
    const u = url.replace('http://h/api/v1', '');
    if (u === '/spaces/s/inv/investigations' && method === 'POST') created = true;
    calls.push({ method, u, auth: init.headers && init.headers.authorization, body: init.body ? JSON.parse(init.body) : undefined });
    let data = {};
    if (u === '/auth/exchange') data = { accessToken: 'tok-' + JSON.parse(init.body).code };
    else if (u.startsWith('/spaces/s/inv/investigations?')) data = { items: created && !listItems.length ? [{ id: INV, headStep: 0 }] : listItems };
    else if (u === '/spaces/s/inv/entity-identities' && method === 'GET') data = { items: [] };
    else if (u.startsWith('/spaces/s/datasets/') && u.includes('/rows')) data = { rows: [{ src: 'x' }] };   // the link Job has run
    else if (u === '/spaces/s/cases/from-entities') data = { case: { id: 'CASE-7' } };
    return { ok: true, status: 200, json: async () => ({ data }) };
  };
  return { fetchFn, calls };
}

test('dry run sends nothing and lists every step', async () => {
  const m = mock(); const lines = [];
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: false, log: (l) => lines.push(l) });
  assert.equal(m.calls.length, 0);
  assert.ok(lines.length > 20);
});

test('apply signs in once per persona and acts as the right persona', async () => {
  const m = mock();
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.deepEqual(r.failed, []);
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 2);   // mm.analyst, and admin for the Case (needs reveal)
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'exclude', 'annotate', 'threshold', 'window', 'resolve', 'snapshot']);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:mm.analyst'));
  assert.ok(ops.find((c) => c.body.op === 'exclude').body.reason);
  assert.ok(ops.find((c) => c.body.op === 'expand').body.maxFanOut <= 100, 'the bounded expand stays under the four-eyes threshold');
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'mm.reviewer', role: 'reviewer' }, { subject: 'mm.manager', role: 'reviewer' }, { subject: 'admin', role: 'lead' }]);
  assert.ok(!members.some((c) => c.body.subject === 'mm.outsider'), 'the outsider is not a member');
  const assign = m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign');
  assert.deepEqual(assign.body, { assignee: 'mm.manager' });
  assert.equal(assign.auth, 'Bearer tok-demo:admin');
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  assert.ok(Date.parse(m.calls.find((c) => c.body && c.body.expiresAt).body.expiresAt) > Date.now());
  assert.ok(m.calls.find((c) => c.u.endsWith('/standing-detection')));
});

test('the Alert Rule watches the structuring Measure inside the top-5% band under the 5 000 tier limit', () => {
  const rule = buildPlan().find((s) => s.id === 'alert-rule').body;
  assert.equal(rule.valueMeasure.name, 'structuring');
  assert.equal(rule.valueMeasure.max, 5000);
  assert.equal(rule.valueMeasure.min, 4750);
  assert.ok(rule.valueMeasure.minPayers <= 25 && rule.valueMeasure.minLegs <= 25);
  assert.equal(rule.valueMeasure.valueCol, 'amount');
});

test('an existing Investigation is skipped, not recreated', async () => {
  const m = mock([{ id: INV, headStep: 8, caseRef: 'CASE-7' }]);
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.ok(r.skipped.includes('investigation'));
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops')).length, 0);
});

test('scaffold lays the Space and the template gallery, and stamps the index owner', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-mm-'));
  assert.deepEqual(scaffold(root), ['_templates', 'la-mobile-money']);
  assert.ok(fs.existsSync(path.join(root, 'la-mobile-money', 'config', 'link-analysis.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-mobile-money', 'data', 'inbox', 'wallet_txn', 'WALLET_TXN_20261001.csv')));
  const job = fs.readFileSync(path.join(root, 'la-mobile-money', 'config', 'jobs', 'la_index_build_job.toon'), 'utf8');
  assert.match(job, /updatedBy: admin/);
  assert.deepEqual(scaffold(root), []);
  assert.equal(stampIndexBuildOwner('job:\n  type: sql.template\n'), 'job:\n  type: sql.template\n');
});

test('a fresh Space (no link rows yet) presses Run on mm_links as mm.manager before the create', async () => {
  const m = mock(); let n = 0;
  const f = async (url, init = {}) => (url.includes('/datasets/mm_links_dataset/rows') && n++ < 2
    ? { ok: true, status: 200, json: async () => ({ data: { rows: [] } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async () => {} });
  assert.deepEqual(r.failed, []);
  const i = m.calls.findIndex((c) => c.u === '/spaces/s/jobs/mm_links/trigger' && c.method === 'POST');
  assert.ok(i >= 0 && m.calls[i].auth === 'Bearer tok-demo:mm.manager');
  assert.ok(i < m.calls.findIndex((c) => c.u === '/spaces/s/inv/investigations' && c.method === 'POST'));
});

test('a re-run matches the handset identity on its reason (ids come back masked) and does not assert it twice', async () => {
  const m = mock([{ id: INV, headStep: 8, caseRef: 'CASE-7' }]);
  const reason = buildPlan().find((s) => s.id === 'identity').body.reason;
  const f = async (url, init = {}) => (url.endsWith('/inv/entity-identities') && (init.method || 'GET') === 'GET'
    ? { ok: true, status: 200, json: async () => ({ data: { groups: [{ id: 'tok-1', members: ['tok-a', 'tok-b'], assertions: [{ seq: 7, a: 'tok-a', b: 'tok-b', reason }] }] } }) }
    : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.ok(r.skipped.includes('identity'));
  assert.ok(!m.calls.some((c) => c.u.endsWith('/inv/entity-identities') && c.method === 'POST'));
});
