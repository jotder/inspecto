// node --test tools/seed-la-ownership.test.mjs  -  request building of the ownership and sanctions seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, INV, LISTED, CUSTOMER, MATCHED, PATH_PARTIES, LOOKALIKES } from './seed-la-ownership.mjs';

const OFF = { pending: false, draft: false };
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
    else if (u.startsWith('/spaces/s/datasets/') && u.includes('/rows')) data = { rows: [{ src: 'x' }] };   // the link Job has run
    else if (u === '/spaces/s/cases/from-entities') data = { case: { id: 'CASE-7' } };
    return { ok: true, status: 200, json: async () => ({ data }) };
  };
  return { fetchFn, calls };
}

test('the seeded ids are the ones the generator planted, and no look-alike is seeded', () => {
  assert.equal(LISTED, 'SL-0137');
  assert.equal(CUSTOMER, 'CUS-00528');
  assert.equal(MATCHED, 'PER-03904');
  assert.deepEqual(PATH_PARTIES, ['CO-04313', 'CO-04431', 'CUS-00528', 'PER-03904']);
  assert.deepEqual(LOOKALIKES, ['CO-04242', 'CUS-00642', 'PER-02226'], 'the near-namesake, its company and its customer');
  const plan = JSON.stringify(buildPlan());
  for (const l of LOOKALIKES) assert.ok(!plan.includes(l), `the look-alike ${l} is in no step`);
});

test('dry run sends nothing and lists every step', async () => {
  const m = mock(); const lines = [];
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: false, log: (l) => lines.push(l) });
  assert.equal(m.calls.length, 0);
  assert.ok(lines.length > 16);
});

test('apply signs in once per persona and acts as the right persona', async () => {
  const m = mock();
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.deepEqual(r.failed, []);
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'annotate', 'window', 'snapshot']);
  assert.deepEqual(ops[0].body.ids, ['SL-0137', 'CUS-00528']);
  assert.ok(ops[1].body.maxFanOut <= 2000, 'the first expand is bounded under four_eyes_fan_out_above, so it runs');
  assert.deepEqual(ops[1].body.linkKinds, ['name_match', 'owns', 'customer_is'], 'ownership, customers and list matches; no payments');
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:ownership.analyst'));
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'ownership.reviewer', role: 'reviewer' }, { subject: 'ownership.manager', role: 'reviewer' }, { subject: 'admin', role: 'lead' }]);
  assert.ok(!members.some((c) => c.body.subject === 'ownership.outsider'), 'the outsider is not a member');
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/entity-lists/sanctions-path-parties/members').body.add, PATH_PARTIES);
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/cases/from-entities').body.entities, [{ id: 'entity:party:CUS-00528', dataset: 'ownership_links_dataset' }]);
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign').body, { assignee: 'ownership.manager' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  assert.ok(!m.calls.find((c) => c.u.endsWith('/alert-rules')), 'the Alert Rule ships in the template, not in the seed');
});

test('re-run skips an Investigation that already has its steps and its Case', async () => {
  const m = mock([{ id: INV, headStep: 5, caseRef: 'CASE-7' }]);
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops')).length, 0);
  assert.equal(m.calls.filter((c) => c.u.includes('/cases/') || c.u.includes('/objects/')).length, 0);
  assert.ok(r.skipped.includes('investigation') && r.skipped.includes('case'));
});

test('a 409 on create counts as already there, not a failure', async () => {
  const m = mock();
  const f = async (url, init) => (url.endsWith('/entity-lists') ? { ok: false, status: 409, json: async () => ({ data: {} }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.ok(r.skipped.includes('list-path'));
  assert.deepEqual(r.failed, []);
});

test('the pending expand is unbounded and the Draft step annotates the matched person, a Working Set node', async () => {
  const m = mock(); const held = [];
  const f = async (url, init = {}) => {
    const u = url.replace('http://h/api/v1', '');
    if (u.startsWith('/spaces/s/inv/pending')) return { ok: true, status: 200, json: async () => ({ data: { items: [] } }) };
    if (u === `/spaces/s/inv/investigations/${INV}/ops` && JSON.parse(init.body).maxFanOut === undefined && JSON.parse(init.body).op === 'expand') {
      held.push(JSON.parse(init.body));
      return { ok: true, status: 202, json: async () => ({ data: { status: 'pending', pending: { id: 'P-1' } } }) };
    }
    if (u === `/spaces/s/inv/investigations/${INV}/drafts` && (init.method || 'GET') === 'GET') return { ok: true, status: 200, json: async () => ({ data: { items: [] } }) };
    if (u === `/spaces/s/inv/investigations/${INV}/drafts`) return { ok: true, status: 201, json: async () => ({ data: { draftId: 'D-1' } }) };
    return m.fetchFn(url, init);
  };
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true });
  assert.ok(r.done.includes('pending-expand') && r.done.includes('draft'), JSON.stringify(r));
  assert.equal(r.summary.pendingRequestId, 'P-1');
  assert.deepEqual(held, [{ op: 'expand', direction: 'either' }], 'one expand, unbounded, so above four_eyes_fan_out_above');
  const step = m.calls.find((c) => c.u.endsWith('/drafts/D-1/ops')).body;
  // one hop from a seed: the matched person (from SL-0137) or the customer's company (from CUS-00528). The 50 % owner, CO-04431,
  // is two hops from either seed, so annotating it is refused (422 not in the Working Set)
  assert.deepEqual(step.ids, ['PER-03904']);
  assert.ok(!step.ids.includes('CO-04431'));
});

test('a pending expand already in the inbox is not requested twice', async () => {
  const m = mock();
  const f = async (url, init = {}) => (url.includes('/inv/pending')
    ? { ok: true, status: 200, json: async () => ({ data: { items: [{ investigationId: INV, id: 'P-0' }] } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: { draft: false } });
  assert.ok(r.skipped.includes('pending-expand'));
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops') && c.body.op === 'expand' && c.body.maxFanOut === undefined).length, 0);
});

test('every plan step names a persona the template defines, and the outsider is on the roster', () => {
  const users = fs.readFileSync(new URL('../spaces/_templates/la-ownership/config/demo-users.toon', import.meta.url), 'utf8');
  for (const s of buildPlan()) assert.ok(users.includes(`  ${s.as},`), `${s.as} is not a Demo User of the la-ownership template`);
  for (const u of ['ownership.analyst', 'ownership.manager', 'ownership.reviewer', 'ownership.outsider', 'admin']) assert.ok(users.includes(`  ${u},`), u);
});

test('the shared admin reads exactly as in la-showcase, la-utility and la-card (a roster conflict refuses sign-in)', () => {
  const line = (f) => fs.readFileSync(new URL(f, import.meta.url), 'utf8').split('\n').find((l) => l.startsWith('  admin,'));
  for (const other of ['la-showcase', 'la-utility', 'la-card'])
    assert.equal(line('../spaces/_templates/la-ownership/config/demo-users.toon'), line(`../spaces/_templates/${other}/config/demo-users.toon`), other);
});

test('scaffold lays down the Space once and is idempotent', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-ownership-'));
  assert.deepEqual(scaffold(root), ['_templates', 'la-ownership']);
  assert.ok(fs.existsSync(path.join(root, 'la-ownership', 'config', 'demo-users.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-ownership', 'config', 'registry', 'alert-rules', 'ownership_path_to_listed_person.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-ownership', 'data', 'inbox', 'sanctions_list')));
  assert.deepEqual(scaffold(root), []);
});

test('a fresh Space (no link rows yet) presses Run on ownership_links as ownership.manager before the create', async () => {
  const m = mock(); let n = 0;
  const f = async (url, init = {}) => (url.includes('/datasets/ownership_links_dataset/rows') && n++ < 2
    ? { ok: true, status: 200, json: async () => ({ data: { rows: [] } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async () => {} });
  assert.deepEqual(r.failed, []);
  const i = m.calls.findIndex((c) => c.u === '/spaces/s/jobs/ownership_links/trigger' && c.method === 'POST');
  assert.ok(i >= 0 && m.calls[i].auth === 'Bearer tok-demo:ownership.manager');
  assert.ok(i < m.calls.findIndex((c) => c.u === '/spaces/s/inv/investigations' && c.method === 'POST'));
});
