// node --test tools/seed-la-audit.test.mjs  -  request building of the la-audit seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, stampIndexBuildOwner, INV } from './seed-la-audit.mjs';

const OFF = { pending: false, draft: false, review: false };
function mock() {
  const calls = []; let created = false;
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET';
    const u = url.replace('http://h/api/v1', '');
    if (u === '/spaces/s/inv/investigations' && method === 'POST') created = true;
    calls.push({ method, u, auth: init.headers && init.headers.authorization, body: init.body ? JSON.parse(init.body) : undefined });
    let data = {};
    if (u === '/auth/exchange') data = { accessToken: 'tok-' + JSON.parse(init.body).code };
    else if (u.startsWith('/spaces/s/inv/investigations?')) data = { items: created ? [{ id: INV, headStep: 0 }] : [] };
    else if (u === '/spaces/s/cases/from-entities') data = { case: { id: 'CASE-9' } };
    return { ok: true, status: 200, json: async () => ({ data }) };
  };
  return { fetchFn, calls };
}

test('dry run sends nothing and lists every step', async () => {
  const m = mock(); const lines = [];
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: false, log: (l) => lines.push(l) });
  assert.equal(m.calls.length, 0);
  assert.ok(lines.length > 15);
});

test('apply acts as the audit personas, in order, and never as a reviewer', async () => {
  const m = mock();
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async () => {}, readyTries: 2 });
  assert.deepEqual(r.failed, []);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'annotate', 'window', 'resolve', 'snapshot']);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:audit.lead'));
  assert.equal(ops[0].body.entityType, 'party');
  assert.ok(ops[1].body.maxFanOut < 30, 'the seeded expand stays under four_eyes_fan_out_above');
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'audit.committee', role: 'reviewer' }, { subject: 'audit.manager', role: 'reviewer' }, { subject: 'audit.admin', role: 'lead' }]);
  assert.ok(!members.some((c) => c.body.subject === 'audit.outsider'), 'the outsider is not a member');
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/objects/CASE-9/assign').body, { assignee: 'audit.manager' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-9' });
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/entity-lists/shell-vendors/members').body.add, ['V-131', 'V-132', 'V-133', 'V-134', 'V-135']);
});

test('every plan step names an audit Demo User', () => {
  for (const s of buildPlan()) assert.match(s.as, /^audit\./, s.id);
});

test('scaffold lays down the Space once and stamps the index build owner', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-audit-'));
  try {
    assert.deepEqual(scaffold(root), ['_templates', 'la-audit']);
    assert.deepEqual(scaffold(root), []);
    const job = fs.readFileSync(path.join(root, 'la-audit', 'config', 'jobs', 'la_index_build_job.toon'), 'utf8');
    assert.match(job, /updatedBy: audit\.admin/);
    assert.ok(fs.existsSync(path.join(root, 'la-audit', 'data', 'inbox', 'ap_invoice')));
    assert.equal(stampIndexBuildOwner('job:\n  type: sql.template\n'), 'job:\n  type: sql.template\n');
  } finally { fs.rmSync(root, { recursive: true, force: true }); }
});
