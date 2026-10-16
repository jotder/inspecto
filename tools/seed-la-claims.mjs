#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-claims` Space (insurance-claims Link Analysis showcase) over HTTP, as the right Demo Users.
// Investigations, members, Entity Lists, identity facts, a Case and a bound Alert Rule have no registry kind, so no Space Template
// can carry them; this script is their home. Self-contained sibling of tools/seed-la-showcase.mjs (which it does not modify).
//
//   node tools/seed-la-claims.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the la-claims Space folders
//   node tools/seed-la-claims.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-claims.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Stages after the plan (each on by default, skipped with its flag, idempotent, SOFT: a refusal prints a warning, exit code stays 0):
//   --no-pending   one PENDING four-eyes expand on ring-case (an unbounded expand by siu.analyst; left undecided for siu.manager)
//   --no-alert     one run of the la_detect Job (as siu.manager, who holds canOperateRuns) until the ring-billing Alert exists
//   --no-draft     one open Draft of ring-case forked by siu.analyst, carrying one annotate step, left unpromoted
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Sends only `demo:<id>` codes, never a credential.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stampIndexBuildOwner } from './seed-la-showcase.mjs';

export const INV = 'ring-case';
export const DATASET = 'claims_links_dataset';
export const SPACE = 'la-claims';
export const RING = ['CL-0029', 'CL-0061', 'CL-0111', 'CL-0134', 'CL-0173', 'CL-0193', 'CL-0206', 'CL-0223', 'CL-0247', 'CL-0276', 'CL-0332', 'CL-0377'];
export const REPEAT_PASSENGER = 'CL-0134';
export const TWINS = ['CL-0332', 'CL-0141'];                 // one person under two records (name one letter apart, same date of birth and phone)
export const PROVIDERS = ['GAR-03', 'CLI-04', 'LAW-02'];     // the ring's garage, clinic and lawyer
export const RULE = 'ring-billing';
const CASE_TITLE = 'Staged-accident ring around LAW-02';
const here = path.dirname(fileURLToPath(import.meta.url));
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** The seeded state as an ordered list of steps. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: [REPEAT_PASSENGER, 'LAW-02'], entityType: 'party' },
    { op: 'expand', direction: 'either', maxFanOut: 40 },   // bounded, under four_eyes_fan_out_above (2000): runs now; an UNBOUNDED expand always goes pending
    { op: 'annotate', ids: [REPEAT_PASSENGER], note: 'passenger on six claims with six different drivers in under seven months' },
    { op: 'threshold', min: 1 },
    { op: 'window', window: { from: '2025-04-01T00:00:00Z', to: '2026-10-01T00:00:00Z' } },
    { op: 'resolve' },
    { op: 'snapshot', label: 'showcase baseline' },
  ];
  return [
    { id: 'investigation', as: 'siu.analyst', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: 'Staged-accident ring (LAW-02)', purpose: 'Showcase: one garage, one clinic, one lawyer, twelve strangers', dataset: DATASET,
      sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts', entityType: 'party' } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'siu.analyst', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['partner.reviewer', 'reviewer'], ['siu.manager', 'reviewer'], ['admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'siu.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    // police-report PDFs (invented URLs on the reserved example domain) as EXTERNAL REFERENCES: never fetched, never trusted, never in the Dossier manifest
    ...[['PR-REQ-0001', 'Request to the police records office: reports for the nine ring accidents (none on file, by design)'],
        ['PR-REQ-0002', 'Request: reports for the eleven accidents at the Derby junction, 02:00-03:59'],
        ['PR-NOTE-0003', 'Identity note: CL-0332 and CL-0141 share a date of birth and phone (E.164 versus national spelling)']].map(([id, label]) => (
      { id: `reference-${id}`, as: 'siu.analyst', method: 'POST', path: `${base}/references`, body: {
        system: 'police-records', type: id.startsWith('PR-REQ') ? 'report-request' : 'note', id, url: `https://records.example/la-claims/${id}.pdf`, label } })),
    { id: 'list-ring', as: 'siu.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'ring-claimants', title: 'Ring claimants', purpose: 'watch', entityType: 'party', reason: 'showcase staged-accident ring' } },
    { id: 'list-ring-members', as: 'siu.analyst', method: 'POST', path: '/entity-lists/ring-claimants/members', body: { add: RING, reason: 'showcase staged-accident ring' } },
    { id: 'list-providers', as: 'siu.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'ring-providers', title: 'Ring providers', purpose: 'watch', entityType: 'party', reason: 'garage, clinic and lawyer behind the ring' } },
    { id: 'list-providers-members', as: 'siu.analyst', method: 'POST', path: '/entity-lists/ring-providers/members', body: { add: PROVIDERS, reason: 'showcase ring providers' } },
    { id: 'identity', as: 'siu.analyst', method: 'POST', path: '/inv/entity-identities', body: {
      a: `party:${TWINS[0]}`, b: `party:${TWINS[1]}`, reason: 'same date of birth and phone, name one letter apart (showcase)' } },
    { id: 'case', as: 'siu.analyst', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: 'entity:party:LAW-02', dataset: DATASET }] } },
    { id: 'case-assign', as: 'siu.analyst', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'siu.manager' } },
    { id: 'case-link', as: 'siu.analyst', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
    { id: 'template', as: 'siu.analyst', method: 'POST', path: `${base}/template`, body: { id: 'claims-ring-template', title: 'Staged-accident ring (lawyer + clinic + garage)' } },
    { id: 'alert-rule', as: 'siu.analyst', method: 'POST', path: `${base}/alert-rules`, body: {
      name: RULE, severity: 'HIGH', valueMeasure: { name: 'ringBilling', valueCol: 'amount', timeCol: 'ts', from: '2025-04-01', to: '2026-10-01' } } },
    { id: 'standing', as: 'siu.analyst', method: 'POST', path: `${base}/standing-detection`, body: { rule: RULE } },
  ];
}

export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
export const DRAFT_STEP = { op: 'annotate', ids: ['CLI-04'], note: 'widen the ring: who else does LAW-02 refer to this clinic?' };

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, warnings, summary }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = SPACE, apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), ingestWaitTries = 40, alertWaitTries = 20, readyTries = 60, stages = {} } = {}) {
  const want = { pending: true, alert: true, draft: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(16)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  siu.analyst      POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held for siu.manager; skipped if one is pending)`);
    if (want.alert) log(`  siu.manager      POST /jobs/la_detect/trigger  (skipped if an Alert for ${RULE} already exists)`);
    if (want.draft) log(`  siu.analyst      POST /inv/investigations/${INV}/drafts, then .../ops ${JSON.stringify(DRAFT_STEP)}`);
    return out;
  }
  const tokens = {};
  const api = `${base}/api/v1`;
  const spaced = `${api}/spaces/${space}`;
  const token = async (user) => {
    if (tokens[user]) return tokens[user];
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build and does the Space define ${user}?`);
    return (tokens[user] = unwrap(await r.json()).accessToken);
  };
  const call = async (user, method, p, body) => {
    const r = await fetchFn(`${spaced}${p}`, { method, headers: { authorization: `Bearer ${await token(user)}`, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    let data = null;
    try { data = unwrap(await r.json()); } catch { /* empty body */ }
    return { status: r.status, data };
  };
  const items = (d) => (Array.isArray(d) ? d : (d && d.items) || []);
  // READINESS: right after /health first answers, the Investigation store may not be loaded; poll the LIST until it answers 200.
  const poll = async (fn, tries) => { let last; for (let i = 0; i < tries; i++) { try { last = await fn(); } catch (e) { last = { status: 0, data: String(e.message || e) }; } if (last.status === 200) return last; await sleep(1000); } return last; };
  const listed = await poll(() => call('siu.analyst', 'GET', '/inv/investigations?limit=200'), readyTries);
  if (listed.status !== 200) throw new Error(`cannot list Investigations as siu.analyst (${listed.status}): is the Space '${space}' created from the la-claims template?`);
  let existing = items(listed.data).find((i) => i.id === INV);
  let headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };
  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replace(/@case/g, state.case || '@case'));
    const p = s.path.replace('@case', state.case || '@case');
    if (p.includes('@case')) { out.failed.push(`${s.id}: no Case id`); continue; }
    if (s.id === 'identity') {
      const have = await call(s.as, 'GET', '/inv/entity-identities');
      if (have.status === 200 && JSON.stringify(have.data).includes(TWINS[1])) { out.skipped.push(s.id); continue; }
    }
    let r = await call(s.as, s.method, p, body);
    // right after boot the link Dataset's first build may not have landed: the create answers 422 "cannot read the columns"
    for (let i = 1; s.id === 'investigation' && r.status === 422 && i < ingestWaitTries; i++) {
      log(`  ${s.id}: 422, waiting for the first build of ${DATASET} (${i}/${ingestWaitTries - 1})`);
      await sleep(3000);
      r = await call(s.as, s.method, p, body);
    }
    if (s.id === 'case' && r.status < 300) state.case = r.data.case.id;
    if (r.status === 409) out.skipped.push(s.id);
    else if (r.status < 300) out.done.push(s.id);
    else out.failed.push(`${s.id}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
    log(`  ${s.id}: ${r.status}`);
    if (s.id === 'investigation' && r.status >= 300 && r.status !== 409) break;
  }
  if (out.failed.length) { out.warnings.push('optional stages skipped: the plan above failed'); return out; }
  const warn = (stage, e) => { const m = `${stage}: ${e.message || e}; the stage was skipped and left no half-made state`; out.warnings.push(m); log(`  WARNING ${m}`); };
  const must = (r, what) => { if (![200, 201, 202].includes(r.status)) throw new Error(`${what} answered HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`); return r.data; };
  if (want.pending) try {
    const inbox = async () => items(must(await call('siu.manager', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox')).filter((i) => i.investigationId === INV);
    const mine = await inbox();
    if (mine.length) out.skipped.push('pending-expand');
    else {
      const d = must(await call('siu.analyst', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND), 'the unbounded expand');
      if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?)');
      out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id;
    }
  } catch (e) { warn('pending', e); }
  if (want.alert) try {
    const read = async () => items(must(await call('siu.analyst', 'GET', '/alerts?limit=200'), 'Alerts list')).find((a) => a.rule === RULE || String(a.rule).endsWith(`/${RULE}`));
    let a = await read();
    if (a) out.skipped.push('alert');
    else {
      const t = await call('siu.manager', 'POST', '/jobs/la_detect/trigger');
      if (t.status !== 409) must(t, 'la_detect trigger');
      for (let i = 0; i < alertWaitTries && !a; i++) { await sleep(3000); a = await read(); }
      if (!a) throw new Error(`no Alert for ${RULE} appeared after ${alertWaitTries} polls (is standing detection enabled on the rule?)`);
      out.done.push('alert');
    }
    out.summary.alertId = `${a.rule}@${a.epochMillis}`;
  } catch (e) { warn('alert', e); }
  if (want.draft) try {
    const dbase = `/inv/investigations/${INV}/drafts`;
    const live = items(must(await call('siu.analyst', 'GET', dbase), 'Draft list')).find((d) => d.state === 'open' || d.state === 'hibernated');
    if (live && live.steps > 0) { out.skipped.push('draft'); out.summary.draftId = live.draftId; }
    else {
      const id = live ? live.draftId : must(await call('siu.analyst', 'POST', dbase, { label: 'Draft: widen the ring' }), 'Draft fork').draftId;
      must(await call('siu.analyst', 'POST', `${dbase}/${id}/ops`, DRAFT_STEP), 'the Draft step');
      out.done.push('draft'); out.summary.draftId = id;
    }
  } catch (e) { warn('draft', e); }
  return out;
}

/** Offline: lay the la-claims Space folders (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
export function scaffold(spacesRoot, templatesDir = path.join(here, '..', 'spaces', '_templates')) {
  const made = [];
  const copy = (src, dst, id) => {
    fs.mkdirSync(dst, { recursive: true });
    for (const e of fs.readdirSync(src, { withFileTypes: true })) {
      const s = path.join(src, e.name), d = path.join(dst, e.name);
      if (e.isDirectory()) copy(s, d, id);
      else if (id && e.name.endsWith('.toon')) fs.writeFileSync(d, stampIndexBuildOwner(fs.readFileSync(s, 'utf8').replaceAll('${SPACE}', id)));
      else fs.copyFileSync(s, d);
    }
  };
  fs.mkdirSync(spacesRoot, { recursive: true });
  if (!fs.existsSync(path.join(spacesRoot, '_templates'))) { copy(templatesDir, path.join(spacesRoot, '_templates'), null); made.push('_templates'); }
  const base = path.join(spacesRoot, SPACE);
  if (!fs.existsSync(base)) {
    for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
    copy(path.join(templatesDir, SPACE, 'config'), path.join(base, 'config'), SPACE);
    copy(path.join(templatesDir, SPACE, 'data'), path.join(base, 'data'), null);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Link Analysis claims showcase\ndescription: Scaffolded from the ${SPACE} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(SPACE);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const root = opt('--scaffold');
    if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-la-claims.mjs --scaffold <spacesRoot> (offline, before the server starts)'); process.exit(2); }
    const made = scaffold(root);
    console.log(made.length ? `scaffolded: ${made.join(', ')}` : 'nothing to do: the Space already exists');
  } else {
    const stages = { pending: !args.includes('--no-pending'), alert: !args.includes('--no-alert'), draft: !args.includes('--no-draft') };
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', SPACE), apply: args.includes('--apply'), log: console.log, stages })
      .then((r) => {
        if (args.includes('--apply')) {
          console.log(`created: ${r.done.join(', ') || '-'}\nalready there: ${r.skipped.join(', ') || '-'}\nfailed: ${r.failed.join('; ') || '-'}`);
          console.log(`warnings: ${r.warnings.join('; ') || '-'}\nstaged: pending request ${r.summary.pendingRequestId || '-'} | Alert ${r.summary.alertId || '-'} | Draft ${r.summary.draftId || '-'}`);
        }
        process.exit(r.failed.length ? 1 : 0);
      })
      .catch((e) => { console.error(e.message); process.exit(1); });
  }
}
