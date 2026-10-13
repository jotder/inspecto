#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-showcase` Space (LA-DEMO-SEED-1: DR-S1, S4, S5) over HTTP, as the right Demo Users.
// Investigations, members, Entity Lists, identity facts, a Case and a bound Alert Rule have no registry kind, so no Space
// Template can carry them; this script is the documented home. See docs/okf/capabilities/spaces/spaces.md (la-showcase).
//
//   node tools/seed-la-showcase.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the Space folders
//   node tools/seed-la-showcase.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-showcase.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Three optional stages pre-stage the demo scenes after the plan (each on by default, each skipped with its flag, each
// idempotent, each SOFT: a refusal prints a warning and leaves the Space as it was, the exit code stays 0):
//   --no-pending   one PENDING four-eyes expand on mule-hub-case (an unbounded expand by fm.analyst; left undecided for fm.manager)
//   --no-alert     one run of the la_detect Job (as fm.manager, who holds canOperateRuns) until the mule-pass-through Alert exists
//   --no-draft     one open Draft of mule-hub-case forked by fm.analyst, carrying one annotate step, left unpromoted
// (the Draft carries the label 'Draft: widen the ring'; it is still found again as fm.analyst's one live Draft)
//
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Never sends a credential: the Demo User exchange
// takes a `demo:<id>` code and no password.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'mule-hub-case';
export const DATASET = 'mule_transfers_dataset';
const CASE_TITLE = 'Mule ring around MULE-HUB-01';
const here = path.dirname(fileURLToPath(import.meta.url));

/** The seeded state as an ordered list of steps. `skip(state)` lets a step opt out when the thing already exists. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: ['MULE-HUB-01', 'RELAY-01', 'RELAY-02', 'SMURF-01'], entityType: 'account' },
    { op: 'expand', direction: 'either', maxFanOut: 5 },   // bounded, under four_eyes_fan_out_above (2000): runs now; an UNBOUNDED expand (no maxFanOut) always goes pending
    { op: 'exclude', ids: ['RELAY-02'], reason: 'second relay repeats the RELAY-01 route' },
    { op: 'annotate', ids: ['MULE-HUB-01'], note: 'hub: 12 sub-1000 cash deposits in, 98% out within hours' },
    { op: 'threshold', min: 1 },
    { op: 'window', window: { from: '2026-09-01T00:00:00Z', to: '2026-09-04T00:00:00Z' } },
    { op: 'resolve' },
    { op: 'snapshot', label: 'showcase baseline' },
  ];
  const plan = [
    { id: 'investigation', as: 'fm.analyst', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: 'Mule hub MULE-HUB-01', purpose: 'Showcase: layering ring around MULE-HUB-01', dataset: DATASET,
      sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT', linkKindCol: 'CHANNEL', timeCol: 'BOOKED_AT' } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'fm.analyst', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['ra.analyst', 'reviewer'], ['fm.manager', 'reviewer'], ['admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'fm.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-smurfs', as: 'fm.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'smurf-accounts', title: 'SMURF accounts', purpose: 'watch', entityType: 'account', reason: 'showcase structuring ring' } },
    { id: 'list-smurfs-members', as: 'fm.analyst', method: 'POST', path: '/entity-lists/smurf-accounts/members', body: {
      add: Array.from({ length: 12 }, (_, i) => `SMURF-${String(i + 1).padStart(2, '0')}`),
      addRanges: [{ prefix: 'SMURF-' }], reason: 'showcase structuring ring' } },
    { id: 'list-smurfs-expiring', as: 'fm.analyst', method: 'POST', path: '/entity-lists/smurf-accounts/members', body: {
      add: ['TILL-06'], expiresAt: '@+30d', reason: '30 day hold on the cash-out till' } },
    // A watch list of the telecom profile's Entity Type (msisdn), so the node dialog's "Add to watch list" has a target on
    // the telecom graph; it holds the Wangiri suspect and IRSF-01 only, leaving the rest of the ring to be added live.
    { id: 'list-msisdn-watch', as: 'fm.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'telecom-watch-msisdn', title: 'Telecom numbers under watch', purpose: 'watch', entityType: 'msisdn', reason: 'showcase telecom fraud ring' } },
    { id: 'list-msisdn-watch-members', as: 'fm.analyst', method: 'POST', path: '/entity-lists/telecom-watch-msisdn/members', body: {
      add: ['99979100001', '99979200001'], reason: 'Wangiri suspect and IRSF-01 (showcase)' } },
    { id: 'identity', as: 'fm.analyst', method: 'POST', path: '/inv/entity-identities', body: {
      a: 'account:MULE-HUB-01', b: 'account:ACC-1006', reason: 'same KYC document (showcase)' } },
    { id: 'case', as: 'fm.analyst', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: 'entity:account:MULE-HUB-01', dataset: DATASET }] } },
    { id: 'case-assign', as: 'fm.analyst', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'fm.manager' } },
    { id: 'case-link', as: 'fm.analyst', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
    { id: 'template', as: 'fm.analyst', method: 'POST', path: `${base}/template`, body: { id: 'mule-ring-template', title: 'Mule ring (hub + relays)' } },
    { id: 'alert-rule', as: 'fm.analyst', method: 'POST', path: `${base}/alert-rules`, body: {
      name: 'mule-pass-through', severity: 'CRITICAL',
      valueMeasure: { name: 'passThrough', valueCol: 'AMOUNT', timeCol: 'BOOKED_AT', from: '2026-09-01', to: '2026-09-04' } } },
    { id: 'standing', as: 'fm.analyst', method: 'POST', path: `${base}/standing-detection`, body: { rule: 'mule-pass-through' } },
  ];
  return plan;
}

export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
export const DRAFT_STEP = { op: 'annotate', ids: ['RELAY-01'], note: 'widen the ring: check what RELAY-01 pays on to' };
const RULE = 'mule-pass-through';
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, lines }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-showcase', apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), ingestWaitTries = 30, alertWaitTries = 20, readyTries = 60, stages = {} } = {}) {
  const want = { pending: true, alert: true, draft: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(11)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  fm.analyst  POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held as a pending four-eyes request; skipped if fm.manager already sees one)`);
    if (want.alert) log(`  fm.manager  POST /jobs/la_detect/trigger  (skipped if an Alert for ${RULE} already exists)`);
    if (want.draft) log(`  fm.analyst  POST /inv/investigations/${INV}/drafts, then .../ops ${JSON.stringify(DRAFT_STEP)}  (skipped if fm.analyst has a live Draft)`);
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

  // What already exists decides what is skipped (ops are not idempotent: each POST appends a step).
  // READINESS (LA-DEMO-SEED-RACE, 2026-10-13): right after /health first answers, the Space's Investigation store may not be loaded:
  // the LIST can answer 200 with no items while the create answers 409 (it is on disk) and every op 404s. So the list is polled until
  // it answers 200 (not merely once), and every Investigation is re-read by id before a write is trusted (see `readable`).
  const sleepRetry = async (fn, tries) => { let last; for (let i = 0; i < tries; i++) { try { last = await fn(); } catch (e) { last = { status: 0, data: String(e.message || e) }; } if (last.status === 200) return last; await sleep(1000); } return last; };
  const listed = await sleepRetry(() => call('fm.analyst', 'GET', '/inv/investigations?limit=200'), readyTries);
  if (listed.status !== 200) throw new Error(`cannot list Investigations as fm.analyst (${listed.status}): is the Space '${space}' created from the la-showcase template?`);
  // There is NO by-id GET for an Investigation (only the list and the /log, /ops, ... sub-routes), so "readable" = the LIST shows the id.
  const readable = async () => {
    for (let i = 0; i < readyTries; i++) {
      let r; try { r = await call('fm.analyst', 'GET', '/inv/investigations?limit=200'); } catch (e) { r = { status: 0, data: String(e.message || e) }; }
      const item = r.status === 200 ? (r.data.items || []).find((x) => x.id === INV) : null;
      if (item) return { status: 200, data: item };
      await sleep(1000);
    }
    return { status: 404, data: {} };
  };
  let existing = (listed.data.items || []).find((i) => i.id === INV);
  let headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };
  const adopt = (inv) => { existing = inv; headStep = inv.headStep || 0; if (inv.caseRef) { state.case = inv.caseRef; state.caseExisted = true; } };
  const findCase = async () => {   // an UNLINKED Case a half-finished earlier run made: reuse it instead of opening a second
    const r = await call('fm.analyst', 'GET', '/objects?limit=200');
    const rows = r.status === 200 ? (Array.isArray(r.data) ? r.data : (r.data && r.data.items) || []) : [];
    return rows.find((o) => o && o.title === CASE_TITLE && (!o.type || String(o.type).toUpperCase() === 'CASE'));
  };

  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replace('"@+30d"', JSON.stringify(new Date(Date.now() + 30 * 864e5).toISOString())).replace(/@case/g, state.case || '@case'));
    const p = s.path.replace('@case', state.case || '@case');
    if (p.includes('@case')) { out.failed.push(`${s.id}: no Case id`); continue; }
    if (s.id === 'identity') {
      const have = await call(s.as, 'GET', '/inv/entity-identities');
      if (have.status === 200 && JSON.stringify(have.data).includes('ACC-1006')) { out.skipped.push(s.id); continue; }
    }
    if (s.id === 'case' && !state.case) { const c = await findCase(); if (c) { state.case = c.id; out.skipped.push('case'); log(`  case: reusing ${c.id}`); continue; } }
    let r = await call(s.as, s.method, p, body);
    // Right after boot the Dataset's first ingest may not have landed yet: the create answers 422 "cannot read the
    // columns" (LA-DEMO-POLISH-1 (7)). Wait for it instead of failing the whole seed.
    for (let i = 1; s.id === 'investigation' && r.status === 422 && i < ingestWaitTries; i++) {
      log(`  ${s.id}: 422, waiting for the first ingest of ${DATASET} (${i}/${ingestWaitTries - 1})`);
      await sleep(2000);
      r = await call(s.as, s.method, p, body);
    }
    if (s.id === 'investigation' && (r.status < 300 || r.status === 409)) {
      const rd = await readable();   // created or "already there": either way it must be READABLE before any op is sent
      if (rd.status !== 200) { out.failed.push(`investigation: ${r.status === 409 ? 'exists (409)' : 'created'} but not listed (HTTP ${rd.status}) after ${readyTries} tries`); log(`  ${s.id}: not listed`); break; }
      if (r.status === 409) { adopt(rd.data); if (!out.skipped.includes(s.id)) out.skipped.push(s.id); log(`  ${s.id}: 409, now readable (head step ${headStep})`); continue; }
    }
    if (s.id === 'case' && r.status < 300) state.case = r.data.case.id;
    if (r.status === 409) out.skipped.push(s.id);                 // already exists (list, template, rule, member, pending)
    else if (r.status < 300) out.done.push(s.id);
    else out.failed.push(`${s.id}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
    log(`  ${s.id}: ${r.status}`);
    if (s.id === 'investigation' && r.status >= 300 && r.status !== 409) break;   // nothing else can work
  }
  if (out.failed.length) { out.warnings.push('optional stages skipped: the plan above failed'); return out; }
  const warn = (stage, e) => { const m = `${stage}: ${e.message || e}; the stage was skipped and left no half-made state`; out.warnings.push(m); log(`  WARNING ${m}`); };
  const items = (d) => (Array.isArray(d) ? d : (d && d.items) || []);
  if (want.pending) try { await stagePending(call, out, log); } catch (e) { warn('pending', e); }
  if (want.alert) try { await stageAlert(call, out, log, sleep, alertWaitTries, items); } catch (e) { warn('alert', e); }
  if (want.draft) try { await stageDraft(call, out, log); } catch (e) { warn('draft', e); }
  return out;
}

const must = (r, what, ok = [200, 201, 202]) => { if (!ok.includes(r.status)) throw new Error(`${what} answered HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`); return r.data; };

/** ONE pending four-eyes expand. Idempotent: the approver inbox (as fm.manager) is read first. */
async function stagePending(call, out, log) {
  const inbox = async () => (must(await call('fm.manager', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox').items || []).filter((i) => i.investigationId === INV);
  let mine = await inbox();
  if (mine.length) { out.skipped.push('pending-expand'); out.summary.pendingRequestId = mine[0].requestId; return; }
  const r = await call('fm.analyst', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND);
  if (r.status === 409) { mine = await inbox(); if (mine.length) { out.skipped.push('pending-expand'); out.summary.pendingRequestId = mine[0].requestId; return; } }
  const d = must(r, 'the unbounded expand');
  if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?) and added a step to the log');
  mine = await inbox();
  if (mine.length !== 1 || mine[0].requestId !== d.pending.id) throw new Error(`fm.manager's inbox shows ${mine.length} pending request(s) on ${INV}, expected exactly the one just made (${d.pending.id})`);
  out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id; log(`  pending-expand: ${d.pending.id}`);
}

const firedAlert = (alerts) => alerts.find((a) => a.rule === RULE || String(a.rule).endsWith(`/${RULE}`));

/** ONE la_detect run; waits (bounded) for the Alert. Idempotent: an existing Alert for the rule means no run. */
async function stageAlert(call, out, log, sleep, tries, items) {
  const read = async () => firedAlert(items(must(await call('fm.analyst', 'GET', '/alerts?limit=200'), 'Alerts list')));
  const id = (x) => `${x.rule}@${x.epochMillis}`;
  let a = await read();
  if (a) { out.skipped.push('alert'); out.summary.alertId = id(a); return; }
  const t = await call('fm.manager', 'POST', '/jobs/la_detect/trigger');
  if (t.status !== 409) must(t, 'la_detect trigger');   // 409 = already running: just wait for it
  for (let i = 0; i < tries && !a; i++) { await sleep(3000); a = await read(); }
  if (!a) throw new Error(`no Alert for ${RULE} appeared after ${tries} polls of the la_detect run (is standing detection enabled on the rule?)`);
  out.done.push('alert'); out.summary.alertId = id(a); log(`  alert: ${id(a)}`);
}

/** ONE open Draft with one step. Idempotent: fm.analyst's live Draft (open or hibernated) is reused, and only topped up when it is empty. */
async function stageDraft(call, out, log) {
  const base = `/inv/investigations/${INV}/drafts`;
  const live = (must(await call('fm.analyst', 'GET', base), 'Draft list').items || []).find((d) => d.state === 'open' || d.state === 'hibernated');
  if (live && live.steps > 0) { out.skipped.push('draft'); out.summary.draftId = live.draftId; return; }
  const created = !live;
  const id = live ? live.draftId : must(await call('fm.analyst', 'POST', base, { label: 'Draft: widen the ring' }), 'Draft fork').draftId;
  try {
    must(await call('fm.analyst', 'POST', `${base}/${id}/ops`, DRAFT_STEP), 'the Draft step');
    const d = must(await call('fm.analyst', 'GET', `${base}/${id}`), 'Draft read');
    if (d.steps !== 1 || d.behind !== 0) throw new Error(`the Draft reads steps=${d.steps} behind=${d.behind}, expected 1 and 0`);
  } catch (e) {
    if (created) await call('fm.analyst', 'POST', `${base}/${id}/discard`, {});   // no half-made Draft left behind
    throw e;
  }
  out.done.push('draft'); out.summary.draftId = id; log(`  draft: ${id}`);
}

/** SpaceManager's TemplateSeedGate re-stamps a la.index.build Job to its applier; a file copy does not, and the run authority then
 *  REJECTS every commit-triggered build ("last editor (none recorded)"). Stamp `updatedBy` = the named owner, as a save would. */
export function stampIndexBuildOwner(text) {
  const owner = /^ {2}owner: *"?([^"\r\n]+?)"?\s*$/m.exec(text);
  if (!/^ {2}type: *la\.index\.build\b/m.test(text) || !owner || /^ {2}updatedBy:/m.test(text)) return text;
  return `${text.replace(/\s*$/, '')}\n  updatedBy: ${owner[1]}\n`;
}

/** Offline: lay the showcase Space folders (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
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
  for (const id of ['la-showcase', 'la-showcase-off']) {
    const base = path.join(spacesRoot, id);
    if (fs.existsSync(base)) continue;
    for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
    if (fs.existsSync(path.join(templatesDir, id, 'config'))) copy(path.join(templatesDir, id, 'config'), path.join(base, 'config'), id);
    if (fs.existsSync(path.join(templatesDir, id, 'data'))) copy(path.join(templatesDir, id, 'data'), path.join(base, 'data'), null);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: ${id === 'la-showcase' ? 'Link Analysis showcase' : 'Link Analysis showcase (feature off)'}\ndescription: Scaffolded from the ${id} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(id);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const made = scaffold(opt('--scaffold'));
    console.log(made.length ? `scaffolded: ${made.join(', ')}` : 'nothing to do: the Spaces already exist');
  } else {
    const stages = { pending: !args.includes('--no-pending'), alert: !args.includes('--no-alert'), draft: !args.includes('--no-draft') };
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), apply: args.includes('--apply'), log: console.log, stages })
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
