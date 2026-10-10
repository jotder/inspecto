// node --test tools/seed-la-showcase.test.mjs  -  request building of the seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, INV } from './seed-la-showcase.mjs';

function mock(listItems = []) {
  const calls = [];
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET';
    const u = url.replace('http://h/api/v1', '');
    const auth = init.headers && init.headers.authorization;
    calls.push({ method, u, auth, body: init.body ? JSON.parse(init.body) : undefined });
    let data = {};
    if (u === '/auth/exchange') data = { accessToken: 'tok-' + JSON.parse(init.body).code };
    else if (u.startsWith('/spaces/s/inv/investigations?')) data = { items: listItems };
    else if (u === '/spaces/s/inv/entity-identities' && method === 'GET') data = { items: [] };
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
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true });
  assert.deepEqual(r.failed, []);
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.equal(ops.length, 8);                                  // >= 8 steps
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'exclude', 'annotate', 'threshold', 'window', 'resolve', 'snapshot']);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:fm.analyst'));
  assert.ok(ops.find((c) => c.body.op === 'exclude').body.reason);
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'ra.analyst', role: 'reviewer' }, { subject: 'fm.manager', role: 'reviewer' }, { subject: 'admin', role: 'reviewer' }]);
  assert.ok(!members.some((c) => c.body.subject === 'demo.manager'));
  const assign = m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign');
  assert.deepEqual(assign.body, { assignee: 'fm.manager' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  const expiring = m.calls.find((c) => c.body && c.body.expiresAt);
  assert.ok(Date.parse(expiring.body.expiresAt) > Date.now());
  assert.ok(m.calls.find((c) => c.u.endsWith('/standing-detection')));
});

test('re-run skips an Investigation that already has its steps and its Case', async () => {
  const m = mock([{ id: INV, headStep: 8, caseRef: 'CASE-7' }]);
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true });
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops')).length, 0);
  assert.equal(m.calls.filter((c) => c.u.includes('/cases/') || c.u.includes('/objects/')).length, 0);
  assert.ok(r.skipped.includes('investigation') && r.skipped.includes('case'));
});

test('a 409 on create counts as already there, not a failure', async () => {
  const m = mock();
  const f = async (url, init) => (url.endsWith('/entity-lists') ? { ok: false, status: 409, json: async () => ({ data: {} }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true });
  assert.ok(r.skipped.includes('list-smurfs'));
  assert.deepEqual(r.failed, []);
});

test('scaffold lays down both Spaces once and is idempotent', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-showcase-'));
  assert.deepEqual(scaffold(root), ['_templates', 'la-showcase', 'la-showcase-off']);
  assert.ok(fs.existsSync(path.join(root, 'la-showcase', 'config', 'demo-users.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-showcase', 'data', 'inbox', 'mule_transfers', 'TRANSFERS_20260901.csv')));
  assert.ok(fs.existsSync(path.join(root, 'la-showcase-off', 'config', 'modules.toon')));
  assert.deepEqual(scaffold(root), []);
});

test('plan never grants demo.manager anything', () => {
  assert.ok(!JSON.stringify(buildPlan()).includes('demo.manager'));
});
