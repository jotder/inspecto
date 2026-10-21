// node --test tools/seed-la-showcase.test.mjs  -  request building of the seed script against a MOCKED fetch (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, buildPlan, scaffold, INV } from './seed-la-showcase.mjs';

const OFF = { pending: false, alert: false, draft: false, coverage: false };
function mock(listItems = []) {
  const calls = []; let created = false;
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET';
    const u = url.replace('http://h/api/v1', '');
    if (u === '/spaces/s/inv/investigations' && method === 'POST') created = true;
    const auth = init.headers && init.headers.authorization;
    calls.push({ method, u, auth, body: init.body ? JSON.parse(init.body) : undefined });
    let data = {};
    if (u === '/auth/exchange') data = { accessToken: 'tok-' + JSON.parse(init.body).code };
    else if (u.startsWith('/spaces/s/inv/investigations?')) data = { items: created && !listItems.length ? [{ id: INV, headStep: 0 }] : listItems };
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
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.deepEqual(r.failed, []);
  assert.equal(m.calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const ops = m.calls.filter((c) => c.u === `/spaces/s/inv/investigations/${INV}/ops`);
  assert.equal(ops.length, 8);                                  // >= 8 steps
  assert.deepEqual(ops.map((c) => c.body.op), ['seed', 'expand', 'exclude', 'annotate', 'threshold', 'window', 'resolve', 'snapshot']);
  assert.ok(ops.every((c) => c.auth === 'Bearer tok-demo:fm.analyst'));
  assert.ok(ops.find((c) => c.body.op === 'exclude').body.reason);
  const members = m.calls.filter((c) => c.u.endsWith('/members') && c.u.includes('investigations'));
  assert.deepEqual(members.map((c) => c.body), [{ subject: 'ra.analyst', role: 'reviewer' }, { subject: 'fm.manager', role: 'reviewer' }, { subject: 'admin', role: 'lead' }]);   // admin is a lead: the only persona holding canShareInvestigation, so it can mint an embed link
  assert.ok(!members.some((c) => c.body.subject === 'demo.manager'));
  const assign = m.calls.find((c) => c.u === '/spaces/s/objects/CASE-7/assign');
  assert.deepEqual(assign.body, { assignee: 'fm.manager' });
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-7' });
  const expiring = m.calls.find((c) => c.body && c.body.expiresAt);
  assert.ok(Date.parse(expiring.body.expiresAt) > Date.now());
  assert.ok(m.calls.find((c) => c.u.endsWith('/standing-detection')));
  const watch = m.calls.find((c) => c.u === '/spaces/s/entity-lists' && c.body.entityType === 'msisdn');
  assert.deepEqual([watch.body.id, watch.body.purpose], ['telecom-watch-msisdn', 'watch']);
  assert.equal(watch.auth, 'Bearer tok-demo:fm.analyst');
  const watched = m.calls.find((c) => c.u === '/spaces/s/entity-lists/telecom-watch-msisdn/members');
  assert.deepEqual(watched.body.add, ['99979100001', '99979200001']);
  assert.ok(watched.body.reason);
});

test('re-run skips an Investigation that already has its steps and its Case', async () => {
  const m = mock([{ id: INV, headStep: 8, caseRef: 'CASE-7' }]);
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, stages: OFF });
  assert.equal(m.calls.filter((c) => c.u.endsWith('/ops')).length, 0);
  assert.equal(m.calls.filter((c) => c.u.includes('/cases/') || c.u.includes('/objects/')).length, 0);
  assert.ok(r.skipped.includes('investigation') && r.skipped.includes('case'));
});

test('a 409 on create counts as already there, not a failure', async () => {
  const m = mock();
  const f = async (url, init) => (url.endsWith('/entity-lists') ? { ok: false, status: 409, json: async () => ({ data: {} }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF });
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

import { stampIndexBuildOwner } from './seed-la-showcase.mjs';

test('scaffold stamps updatedBy = owner on the la.index.build Job only (else its commit-triggered runs are REJECTED)', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'seed-stamp-'));
  scaffold(root);
  const job = fs.readFileSync(path.join(root, 'la-showcase', 'config', 'jobs', 'la_index_build_job.toon'), 'utf8');
  assert.match(job, /^ {2}owner: admin$/m);
  assert.match(job, /^ {2}updatedBy: admin$/m);
  const other = 'job:\n  name: x\n  type: sql.template\n  owner: admin\n';
  assert.equal(stampIndexBuildOwner(other), other);
  const once = stampIndexBuildOwner('job:\n  name: x\n  type: la.index.build\n  owner: "ra"\n');
  assert.match(once, /updatedBy: ra\n$/);
  assert.equal(stampIndexBuildOwner(once), once);
});

test('the Investigation create waits out a 422 while the first ingest has not landed (LA-DEMO-POLISH-1 (7))', async () => {
  const m = mock(); let refusals = 2; const slept = [];
  const f = async (url, init) => (url.endsWith('/inv/investigations') && init.method === 'POST' && refusals-- > 0
    ? { ok: false, status: 422, json: async () => ({ data: { message: 'cannot read the columns' } }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async (ms) => { slept.push(ms); } });
  assert.equal(slept.length, 2);
  assert.ok(r.done.includes('investigation'));
  assert.deepEqual(r.failed, []);
});

test('a 422 that never clears fails after the bounded wait', async () => {
  const m = mock(); let posts = 0;
  const f = async (url, init) => (url.endsWith('/inv/investigations') && init.method === 'POST' && ++posts
    ? { ok: false, status: 422, json: async () => ({ data: {} }) } : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, stages: OFF, sleep: async () => {}, ingestWaitTries: 3 });
  assert.equal(posts, 3);
  assert.ok(r.failed[0].startsWith('investigation: HTTP 422'));
});

// ── the three pre-staging stages (pending four-eyes expand, fired Alert, Draft ready to promote) ─────────────────────────────

/** A stateful stub of just the routes the stages use. `opts.held` false = the expand RUNS (no threshold); `opts.alertAfter` = polls before the Alert appears. */
function staged(opts = {}) {
  const st = { pending: opts.pending || [], alerts: opts.alerts || [], drafts: opts.drafts || [], polls: 0, triggers: 0 };
  const m = mock([{ id: INV, headStep: 8, caseRef: 'CASE-7' }]);
  const reply = (status, data) => ({ ok: status < 300, status, json: async () => ({ data }) });
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET', u = url.replace('http://h/api/v1/spaces/s', ''), body = init.body ? JSON.parse(init.body) : undefined;
    const as = ((init.headers && init.headers.authorization) || '').replace('Bearer tok-demo:', '');
    if (opts.fail && opts.fail(method, u, as)) return reply(opts.failStatus || 500, {});
    if (u.startsWith('/inv/pending')) return reply(200, { items: as === 'fm.manager' ? st.pending.map((r) => ({ investigationId: INV, requestId: r })) : [] });
    if (u === `/inv/investigations/${INV}/ops` && body.op === 'expand') {
      m.calls.push({ method, u, as, body });
      if (opts.held === false) return reply(200, { status: 'ok' });
      if (st.pending.length) return reply(409, {});
      st.pending.push('p1'); return reply(200, { status: 'pending', pending: { id: 'p1' } });
    }
    if (u.startsWith('/alerts')) { st.polls++; if (st.polls > (opts.alertAfter ?? 1) && st.triggers) st.alerts.push({ rule: 'mule-pass-through', epochMillis: 7 }); return reply(200, st.alerts); }
    if (u === '/jobs/la_detect/trigger') { st.triggers++; return reply(202, { runId: 'r1' }); }
    const dr = `/inv/investigations/${INV}/drafts`;
    if (u === dr && method === 'GET') return reply(200, { items: st.drafts });
    if (u === dr && method === 'POST') { st.drafts.push({ draftId: 'd1', state: 'open', steps: 0, behind: 0 }); return reply(201, { draftId: 'd1' }); }
    if (u === `${dr}/d1/ops`) { st.drafts[0].steps = opts.badStep ? 2 : 1; return reply(200, {}); }
    if (u === `${dr}/d1/discard`) { st.drafts.length = 0; st.discarded = true; return reply(200, {}); }
    if (u === `${dr}/d1`) return reply(200, st.drafts[0]);
    if (u === '/inv/investigations/delivery-gap-case/coverage') return reply(200, opts.coverage || { collectors: { assessed: true, collectors: [{ collector: 'mule_transfers', coveredDays: 4, missingDays: ['2026-09-05'] }] } });
    if (u === '/inv/investigations?limit=200' && st.gapMade) { const r = await m.fetchFn(url, init); const d = await r.json(); return reply(200, { items: [...d.data.items, { id: 'delivery-gap-case' }] }); }
    if (u === '/inv/investigations' && body && body.id === 'delivery-gap-case') { st.gapMade = true; m.calls.push({ method, u, as, body }); return reply(201, {}); }
    if (u === '/inv/investigations/delivery-gap-case/ops') { m.calls.push({ method, u, as, body }); return reply(200, {}); }
    return m.fetchFn(url, init);
  };
  return { fetchFn, st, calls: m.calls };
}
const go = (s, extra = {}) => run({ fetchFn: s.fetchFn, base: 'http://h', space: 's', apply: true, sleep: async () => {}, ...extra, stages: { coverage: false, ...extra.stages } });

test('stage 1 creates ONE pending unbounded expand as fm.analyst and confirms it in fm.manager\'s inbox', async () => {
  const s = staged();
  const r = await go(s, { stages: { alert: false, draft: false } });
  assert.deepEqual(r.warnings, []);
  assert.ok(r.done.includes('pending-expand'));
  assert.equal(r.summary.pendingRequestId, 'p1');
  const expand = s.calls.find((c) => c.body && c.body.op === 'expand' && c.as);
  assert.deepEqual(expand.body, { op: 'expand', direction: 'either' });
  assert.equal('maxFanOut' in expand.body, false);
  assert.equal(expand.as, 'fm.analyst');
});

test('stage 1 is idempotent: a request already pending means no expand is sent', async () => {
  const s = staged({ pending: ['p9'] });
  const r = await go(s, { stages: { alert: false, draft: false } });
  assert.equal(s.calls.filter((c) => c.body && c.body.op === 'expand').length, 0);
  assert.equal(r.summary.pendingRequestId, 'p9');
  assert.ok(r.skipped.includes('pending-expand'));
});

test('stage 1 soft-fails when the expand RUNS instead of being held, and never fails the seed', async () => {
  const r = await go(staged({ held: false }), { stages: { alert: false, draft: false } });
  assert.match(r.warnings[0], /RAN instead of being held/);
  assert.deepEqual(r.failed, []);
  assert.equal(r.summary.pendingRequestId, undefined);
});

test('stage 2 triggers la_detect once as fm.manager and waits for the Alert', async () => {
  const s = staged({ alertAfter: 2 }); const slept = [];
  const r = await go(s, { stages: { pending: false, draft: false }, sleep: async (ms) => { slept.push(ms); } });
  assert.equal(s.st.triggers, 1);
  assert.ok(r.done.includes('alert'));
  assert.equal(r.summary.alertId, 'mule-pass-through@7');
  assert.ok(slept.length >= 2);
});

test('stage 2 is idempotent: an Alert for the rule already fired means no run', async () => {
  const s = staged({ alerts: [{ rule: 'mule-pass-through', epochMillis: 5 }] });
  const r = await go(s, { stages: { pending: false, draft: false } });
  assert.equal(s.st.triggers, 0);
  assert.equal(r.summary.alertId, 'mule-pass-through@5');
});

test('stage 2 ignores an Alert of another rule, and soft-fails after the bounded wait', async () => {
  const s = staged({ alerts: [{ rule: 'other', epochMillis: 1 }], alertAfter: 99 });
  const r = await go(s, { stages: { pending: false, draft: false }, alertWaitTries: 3 });
  assert.equal(s.st.triggers, 1);
  assert.equal(s.st.polls, 4);   // 1 before the trigger + exactly 3 bounded polls
  assert.match(r.warnings[0], /no Alert for mule-pass-through appeared after 3 polls/);
  assert.deepEqual(r.failed, []);
});

test('stage 2 soft-fails when the trigger is refused', async () => {
  const s = staged({ fail: (mth, u) => u === '/jobs/la_detect/trigger', failStatus: 403 });
  const r = await go(s, { stages: { pending: false, draft: false } });
  assert.match(r.warnings[0], /la_detect trigger answered HTTP 403/);
});

test('stage 3 forks one Draft as fm.analyst, adds one step, and checks it is open with one step and mainMoved 0', async () => {
  const s = staged();
  const r = await go(s, { stages: { pending: false, alert: false } });
  assert.deepEqual(r.warnings, []);
  assert.equal(r.summary.draftId, 'd1');
  assert.equal(s.st.drafts[0].steps, 1);
  assert.ok(r.done.includes('draft'));
});

test('stage 3 is idempotent: a live Draft with its step is left alone; an empty one is topped up, not re-forked', async () => {
  const full = staged({ drafts: [{ draftId: 'd1', state: 'open', steps: 1, behind: 0 }] });
  const r1 = await go(full, { stages: { pending: false, alert: false } });
  assert.ok(r1.skipped.includes('draft'));
  const empty = staged({ drafts: [{ draftId: 'd1', state: 'open', steps: 0, behind: 0 }] });
  const r2 = await go(empty, { stages: { pending: false, alert: false } });
  assert.equal(empty.st.drafts.length, 1);
  assert.equal(empty.st.drafts[0].steps, 1);
  assert.equal(r2.summary.draftId, 'd1');
});

test('stage 3 soft-fails and discards the Draft it made when the step is refused or the Draft reads wrong', async () => {
  const refused = staged({ fail: (mth, u) => u.endsWith('/d1/ops') });
  const r1 = await go(refused, { stages: { pending: false, alert: false } });
  assert.match(r1.warnings[0], /the Draft step answered HTTP 500/);
  assert.equal(refused.st.discarded, true);
  const odd = staged({ badStep: true });
  const r2 = await go(odd, { stages: { pending: false, alert: false } });
  assert.match(r2.warnings[0], /expected 1 and 0/);
  assert.equal(odd.st.discarded, true);
});

test('each stage is skipped by its flag, and none runs when the plan itself failed', async () => {
  const s = staged();
  await go(s, { stages: { pending: false, alert: false, draft: false } });
  assert.equal(s.st.triggers + s.st.drafts.length + s.st.pending.length, 0);
  const bad = staged({ fail: (mth, u) => mth === 'POST' && u === '/entity-lists' });
  const r = await run({ fetchFn: bad.fetchFn, base: 'http://h', space: 's', apply: true, sleep: async () => {} });
  assert.equal(bad.st.triggers + bad.st.drafts.length + bad.st.pending.length, 0);
  assert.ok(r.failed.length);
  assert.deepEqual(r.summary, {});
});

// ---- LA-DEMO-SEED-RACE: a seed run right after /health must wait for the Space, then be safe to re-run ----
const res = (status, data = {}) => ({ ok: status < 300, status, json: async () => ({ data }) });
/** A fake server whose Investigation store "loads" after `warm` reads: until then the list is empty, create says 409, by-id does not exist. */
function racy({ warm = 3, onDisk = true, cases = [] } = {}) {
  const calls = []; let reads = 0; let invCreated = false; const caseRows = [...cases];
  const fetchFn = async (url, init = {}) => {
    const method = init.method || 'GET'; const u = url.replace('http://h/api/v1', '');
    calls.push({ method, u, body: init.body ? JSON.parse(init.body) : undefined });
    if (u === '/auth/exchange') return res(200, { accessToken: 't' });
    const loaded = reads >= warm;
    if (u.startsWith('/spaces/s/inv/investigations?')) { reads++; return res(200, { items: (loaded && onDisk) || invCreated ? [{ id: INV, headStep: 0 }] : [] }); }
    if (u === '/spaces/s/inv/investigations' && method === 'POST') { if (onDisk && !loaded) return res(409); invCreated = true; return res(201); }
    if (u === '/spaces/s/objects?limit=200') return res(200, { items: caseRows });
    if (u === '/spaces/s/cases/from-entities') { caseRows.push({ id: 'CASE-9', title: 'Mule ring around MULE-HUB-01', type: 'CASE' }); return res(200, { case: { id: 'CASE-9' } }); }
    if (u === '/spaces/s/inv/entity-identities' && method === 'GET') return res(200, { items: [] });
    return res(200);
  };
  return { fetchFn, calls };
}
const noWait = { sleep: async () => {}, stages: OFF };

test('race: 409 on create with an unreadable Investigation waits until it is readable, and no op is sent before that', async () => {
  const m = racy({ warm: 4 });
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, ...noWait });
  assert.deepEqual(r.failed, []);
  const firstOp = m.calls.findIndex((c) => c.u.endsWith('/ops'));
  const lastBy404 = m.calls.map((c, i) => [c, i]).filter(([c]) => c.u.startsWith('/spaces/s/inv/investigations?')).pop()[1];
  assert.ok(firstOp > lastBy404, 'every read of the Investigation precedes the first op');
  assert.ok(r.skipped.includes('investigation'));
});

test('race: a created Investigation that never becomes readable fails loudly instead of 404ing every op', async () => {
  const fetchFn = async (url, init = {}) => {
    const u = url.replace('http://h/api/v1', '');
    if (u === '/auth/exchange') return res(200, { accessToken: 't' });
    if (u.startsWith('/spaces/s/inv/investigations?')) return res(200, { items: [] });
    if (u === '/spaces/s/inv/investigations' && init.method === 'POST') return res(201);
    return res(200);
  };
  const r = await run({ fetchFn, base: 'http://h', space: 's', apply: true, readyTries: 3, ...noWait });
  assert.match(r.failed[0], /not listed/);
  assert.equal(r.done.filter((d) => d.startsWith('op-')).length, 0);
});

test('race: a spurious 409 with nothing listed re-sends the create until it is a real create', async () => {
  let posts = 0; let made = false; const calls = [];
  const fetchFn = async (url, init = {}) => {
    const u = url.replace('http://h/api/v1', ''); const method = init.method || 'GET';
    calls.push({ method, u });
    if (u === '/auth/exchange') return res(200, { accessToken: 't' });
    if (u.startsWith('/spaces/s/inv/investigations?')) return res(200, { items: made ? [{ id: INV, headStep: 0 }] : [] });
    if (u === '/spaces/s/inv/investigations' && method === 'POST') { posts++; if (posts < 3) return res(409); made = true; return res(200); }
    if (u === '/spaces/s/objects?limit=200') return res(200, { items: [] });
    if (u === '/spaces/s/cases/from-entities') return res(200, { case: { id: 'CASE-3' } });
    if (u === '/spaces/s/inv/entity-identities' && method === 'GET') return res(200, { items: [] });
    return res(200);
  };
  const r = await run({ fetchFn, base: 'http://h', space: 's', apply: true, ...noWait });
  assert.deepEqual(r.failed, []);
  assert.ok(posts >= 3, 'the create was re-sent');
  const firstOp = calls.findIndex((c) => c.u.endsWith('/ops'));
  assert.ok(firstOp > calls.findIndex((c) => c.method === 'POST' && c.u === '/spaces/s/inv/investigations' ) && made, 'no op before the Investigation exists');
});

test('race: the list route failing at first (Space still loading) is retried, not fatal', async () => {
  let n = 0; const m = mock();
  const f = (url, init) => (url.includes('/inv/investigations?') && n++ < 2 ? Promise.resolve(res(503)) : m.fetchFn(url, init));
  const r = await run({ fetchFn: f, base: 'http://h', space: 's', apply: true, ...noWait });
  assert.deepEqual(r.failed, []);
  assert.ok(n >= 3);
});

test('rerun: an unlinked Case left by a half-finished run is reused, not duplicated', async () => {
  const m = racy({ warm: 0, cases: [{ id: 'CASE-1', title: 'Mule ring around MULE-HUB-01', type: 'CASE' }] });
  const r = await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, ...noWait });
  assert.equal(m.calls.filter((c) => c.u === '/spaces/s/cases/from-entities').length, 0);
  assert.deepEqual(m.calls.find((c) => c.method === 'PUT').body, { caseRef: 'CASE-1' });
  assert.ok(r.skipped.includes('case'));
});

test('rerun: two full applies against one server open exactly one Case', async () => {
  const m = racy({ warm: 0, onDisk: false });
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, ...noWait });
  await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, ...noWait });
  assert.equal(m.calls.filter((c) => c.u === '/spaces/s/cases/from-entities').length, 1);
});

test('coverage stage: a second Investigation with a window past the landed files, then a read-back that names the missing day', async () => {
  const s = staged();
  const r = await go(s, { stages: { pending: false, alert: false, draft: false, coverage: true } });
  assert.deepEqual(r.warnings, []);
  assert.ok(r.done.includes('coverage-investigation'));
  assert.match(r.summary.coverage, /mule_transfers: 4 covered, missing 2026-09-05/);
  const win = s.calls.find((c) => c.u === '/inv/investigations/delivery-gap-case/ops');
  assert.equal(win.body.window.to, '2026-09-06T00:00:00Z');
  const again = await go(s, { stages: { pending: false, alert: false, draft: false, coverage: true } });
  assert.ok(again.skipped.includes('coverage-investigation'));
});

test('coverage stage soft-fails when the Collector is not assessed or no day is missing', async () => {
  const a = await go(staged({ coverage: { collectors: { assessed: false } } }), { stages: { pending: false, alert: false, draft: false, coverage: true } });
  assert.match(a.warnings[0], /assessed=false/);
  const b = await go(staged({ coverage: { collectors: { assessed: true, collectors: [{ collector: 'c', coveredDays: 4, missingDays: [] }] } } }), { stages: { pending: false, alert: false, draft: false, coverage: true } });
  assert.match(b.warnings[0], /no missing day/);
});

test('Basic (exchange and Investigations answer 503): seeds only the Entity Lists, with no Authorization, and does not fail', async () => {
  const calls = [];
  const fetchFn = async (url, init = {}) => {
    const u = url.replace('http://h/api/v1', '');
    calls.push({ method: init.method || 'GET', u, auth: init.headers && init.headers.authorization });
    const status = u === '/auth/exchange' || u.startsWith('/spaces/s/inv/') ? 503 : 200;
    return { ok: status === 200, status, json: async () => (status === 200 ? { data: {} } : { error: { errorCode: 'CAPABILITY_UNAVAILABLE' } }) };
  };
  const r = await run({ fetchFn, base: 'http://h', space: 's', apply: true });
  assert.deepEqual(r.failed, []);
  assert.equal(calls.filter((c) => c.u === '/auth/exchange').length, 1);
  const space = calls.filter((c) => c.u.startsWith('/spaces/s/'));
  assert.ok(space.every((c) => c.auth === undefined));
  assert.deepEqual(space.filter((c) => !c.u.startsWith('/spaces/s/entity-lists')).map((c) => c.u), ['/spaces/s/inv/investigations?limit=200']);   // the one probe
  assert.deepEqual(r.done, ['list-smurfs', 'list-smurfs-members', 'list-smurfs-expiring', 'list-msisdn-watch', 'list-msisdn-watch-members']);
  assert.ok(r.unavailable.includes('investigation') && r.unavailable.includes('case'));
  assert.ok(r.warnings.some((w) => w.startsWith('Basic edition')));
});
