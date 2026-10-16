// node --test tools/seed-la-claims.test.mjs  -  request building of the claims seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, INV, RING, TWINS } from './seed-la-claims.mjs';

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
    else if (u === '/spaces/s/inv/entity-identities' && method === 'GET') data = { items: [] };
    else if (u === '/spaces/s/cases/from-entities') data = { case: { id: 'CASE-7' } };
    return { ok: true, status: 200, json: async () => ({ data }) };
  };
  return { fetchFn, calls };
}

test('the ring and the twin pair are the ones the generator plants', () => {
  assert.equal(RING.length, 12);
  assert.equal(new Set(RING).size, 12);
  assert.ok(RING.includes(TWINS[0]) && !RING.includes(TWINS[1]));
});

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
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'annotate', 'threshold', 'window', 'resolve', 'snapshot']);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:siu.analyst'));
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'partner.reviewer', role: 'reviewer' }, { subject: 'siu.manager', role: 'reviewer' }, { subject: 'admin', role: 'lead' }]);
  assert.ok(!members.some((c) => c.body.subject === 'outsider'), 'the outsider is not a member');
  const ring = m.calls.find((c) => c.u === '/spaces/s/entity-lists/ring-claimants/members');
  assert.deepEqual(ring.body.add, RING);
  const identity = m.calls.find((c) => c.u === '/spaces/s/inv/entity-identities' && c.method === 'POST');
  assert.deepEqual([identity.body.a, identity.body.b], TWINS.map((t) => `party:${t}`));
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign').body, { assignee: 'siu.manager' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  assert.ok(!m.calls.find((c) => c.u.endsWith('/alert-rules')), 'no Alert Rule: no value Measure fits claims');
  const refs = m.calls.filter((c) => c.u.endsWith('/references'));
  assert.equal(refs.length, 3);
  assert.ok(refs.every((c) => c.body.system === 'police-records' && /^https:\/\/records\.example\//.test(c.body.url) && c.auth === 'Bearer tok-demo:siu.analyst'));
});

test('re-run skips an Investigation that already has its steps and its Case', async () => {
  const m = mock([{ id: INV, headStep: 7, caseRef: 'CASE-7' }]);
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops')).length, 0);
  assert.equal(m.calls.filter((c) => c.u.includes('/cases/') || c.u.includes('/objects/')).length, 0);
  assert.ok(r.skipped.includes('investigation') && r.skipped.includes('case'));
});

test('an existing identity group (ids come back masked) means the identity is not asserted twice', async () => {
  const m = mock();
  const f = async (url, init = {}) => (url.endsWith('/inv/entity-identities') && (init.method || 'GET') === 'GET'
    ? { ok: true, status: 200, json: async () => ({ data: { groups: [{ id: 'masked:1', members: ['masked:1', 'masked:2'] }] } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.ok(r.skipped.includes('identity'));
  assert.ok(!m.calls.some((c) => c.u.endsWith('/inv/entity-identities') && c.method === 'POST'));
});

test('a 409 on create counts as already there, not a failure', async () => {
  const m = mock();
  const f = async (url, init) => (url.endsWith('/entity-lists') ? { ok: false, status: 409, json: async () => ({ data: {} }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.ok(r.skipped.includes('list-ring'));
  assert.deepEqual(r.failed, []);
});

test('every plan step names a persona the template defines', () => {
  const users = fs.readFileSync(new URL('../spaces/_templates/la-claims/config/demo-users.toon', import.meta.url), 'utf8');
  for (const s of buildPlan()) assert.ok(users.includes(`  ${s.as},`), `${s.as} is not a Demo User of the la-claims template`);
});

test('scaffold lays down the Space once, stamps the index-build owner, and is idempotent', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-claims-'));
  assert.deepEqual(scaffold(root), ['_templates', 'la-claims']);
  assert.ok(fs.existsSync(path.join(root, 'la-claims', 'config', 'link-analysis.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-claims', 'data', 'inbox', 'claim')));
  assert.match(fs.readFileSync(path.join(root, 'la-claims', 'config', 'jobs', 'la_index_build_job.toon'), 'utf8'), /updatedBy: admin/);
  assert.deepEqual(scaffold(root), []);
});
