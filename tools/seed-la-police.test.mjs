// node --test tools/seed-la-police.test.mjs  -  request building of the police seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, INV, SELLERS, PLATE, KEEPER, TYPE, PENDING_EXPAND } from './seed-la-police.mjs';

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
    else if (u.startsWith('/spaces/s/inv/pending')) data = { items: [] };
    else if (u === `/spaces/s/inv/investigations/${INV}/ops` && JSON.stringify(init.body) === JSON.stringify(JSON.stringify(PENDING_EXPAND)))
      data = { status: 'pending', pending: { id: 'PR-1' } };
    else if (u === `/spaces/s/inv/investigations/${INV}/drafts` && method === 'GET') data = { items: [] };
    else if (u === `/spaces/s/inv/investigations/${INV}/drafts` && method === 'POST') data = { draftId: 'D-1' };
    return { ok: true, status: 200, json: async () => ({ data }) };
  };
  return { fetchFn, calls };
}

test('the sellers, the plate and its keeper are the ones the generator plants', () => {
  const t = JSON.parse(fs.readFileSync(new URL('./la-police-ground-truth.json', import.meta.url), 'utf8')).burglary_series;
  assert.deepEqual(SELLERS, t.sellers);
  assert.equal(PLATE, t.plate);
  assert.equal(KEEPER, t.keeper);
});

test('dry run sends nothing and lists every step', async () => {
  const m = mock(); const lines = [];
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: false, log: (l) => lines.push(l) });
  assert.equal(m.calls.length, 0);
  assert.ok(lines.length > 12);
});

test('apply signs in once per persona and acts as the right persona', async () => {
  const m = mock();
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.deepEqual(r.failed, []);
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const create = m.calls.find((c) => c.u === '/spaces/s/inv/investigations' && c.method === 'POST');
  assert.equal(create.body.dataset, 'police_links_dataset');
  assert.equal(create.body.entityType, TYPE);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'annotate', 'window', 'snapshot']);
  assert.deepEqual(ops[0].body.ids, [PLATE, SELLERS[0]]);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:police.analyst'));
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'police.reviewer', role: 'reviewer' }, { subject: 'police.sio', role: 'reviewer' }, { subject: 'admin', role: 'lead' }]);
  assert.ok(!members.some((c) => c.body.subject === 'police.outsider'), 'the outsider is not a member');
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/entity-lists/series-sellers/members').body.add, SELLERS);
  assert.deepEqual(m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign').body, { assignee: 'police.sio' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  assert.ok(!m.calls.find((c) => c.u.endsWith('/alert-rules')), 'the Alert Rule ships in the template, not in the seed');
});

test('the pending expand is unbounded, sent by the analyst and left for the SIO', async () => {
  const m = mock();
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: { pending: true, draft: true } });
  assert.deepEqual(r.warnings, []);
  assert.equal(r.summary.pendingRequestId, 'PR-1');
  assert.equal(r.summary.draftId, 'D-1');
  assert.ok(m.calls.some((c) => c.u.startsWith('/spaces/s/inv/pending') && c.auth === 'Bearer tok-demo:police.sio'));
  const held = m.calls.find((c) => c.u.endsWith('/ops') && c.body && c.body.op === 'expand' && c.body.maxFanOut === undefined);
  assert.equal(held.auth, 'Bearer tok-demo:police.analyst');
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
  assert.ok(r.skipped.includes('list-sellers'));
  assert.deepEqual(r.failed, []);
});

test('every plan step names a persona the template defines, with the Entity Type the link Dataset carries', () => {
  const users = fs.readFileSync(new URL('../spaces/_templates/la-police/config/demo-users.toon', import.meta.url), 'utf8');
  for (const s of buildPlan()) assert.ok(users.includes(`  ${s.as},`), `${s.as} is not a Demo User of the la-police template`);
  for (const u of ['police.sio', 'police.reviewer', 'police.outsider']) assert.ok(users.includes(`  ${u},`), u);
  const la = fs.readFileSync(new URL('../spaces/_templates/la-police/config/link-analysis.toon', import.meta.url), 'utf8');
  assert.ok(la.includes(`id: ${TYPE}`) && la.includes('four_eyes_fan_out_above:'));
});

test('scaffold lays down the Space once and is idempotent', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'la-police-'));
  assert.deepEqual(scaffold(root), ['_templates', 'la-police']);
  assert.ok(fs.existsSync(path.join(root, 'la-police', 'config', 'demo-users.toon')));
  assert.ok(fs.existsSync(path.join(root, 'la-police', 'data', 'inbox', 'dealer_sale')));
  assert.deepEqual(scaffold(root), []);
});

test('a fresh Space (no link rows yet) presses Run on police_links as police.sio before the create', async () => {
  const m = mock(); let n = 0;
  const f = async (url, init = {}) => (url.includes('/datasets/police_links_dataset/rows') && n++ < 2
    ? { ok: true, status: 200, json: async () => ({ data: { rows: [] } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async () => {} });
  assert.deepEqual(r.failed, []);
  const i = m.calls.findIndex((c) => c.u === '/spaces/s/jobs/police_links/trigger' && c.method === 'POST');
  assert.ok(i >= 0 && m.calls[i].auth === 'Bearer tok-demo:police.sio');
  assert.ok(i < m.calls.findIndex((c) => c.u === '/spaces/s/inv/investigations' && c.method === 'POST'));
});
