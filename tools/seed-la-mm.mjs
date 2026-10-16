#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-mobile-money` Space (vertical A of docs/superpower/la-multi-vertical-showcase-plan.md) over
// HTTP, as the right Demo Users. Investigations, members, Entity Lists, identity facts, a Case and a bound Alert Rule have no
// registry kind, so no Space Template can carry them; this script is their home. Self-contained: it shares no code with
// tools/seed-la-showcase.mjs (each showcase vertical owns its seed, smoke and runbook).
//
//   node tools/seed-la-mm.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the Space folders
//   node tools/seed-la-mm.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-mm.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Two optional stages (each on by default, skipped with its flag, idempotent, SOFT: a refusal prints a warning, exit stays 0):
//   --no-pending   one PENDING four-eyes expand on mm-structuring-case (an unbounded expand by mm.analyst; left for mm.manager)
//   --no-alert     one run of the la_detect Job (as mm.manager, who holds canOperateRuns) until the mm-structuring Alert exists
//
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Sends only `demo:<id>` codes, never a credential.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'mm-structuring-case';
export const DATASET = 'mm_links_dataset';
export const RULE = 'mm-structuring';
export const WINDOW = { from: '2026-10-01T00:00:00Z', to: '2026-10-15T00:00:00Z' };   // the 14 landed days
const CASE_TITLE = 'Structuring through HUB-01';
const here = path.dirname(fileURLToPath(import.meta.url));
const structIds = Array.from({ length: 25 }, (_, i) => `STRUCT-${String(i + 1).padStart(2, '0')}`);
const farmIds = Array.from({ length: 9 }, (_, i) => `FARM-${i + 1}`);

/** The seeded state as an ordered list of steps. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: ['HUB-01', 'RELAY-1', 'RELAY-2', 'STRUCT-01'], entityType: 'wallet' },
    { op: 'expand', direction: 'either', maxFanOut: 5 },   // bounded, under four_eyes_fan_out_above (100): runs now; an UNBOUNDED expand always goes pending
    { op: 'exclude', ids: ['RELAY-2'], reason: 'second relay repeats the RELAY-1 route' },
    { op: 'annotate', ids: ['HUB-01'], note: 'hub: 25 wallets just under the 5 000 tier limit in, 98% out within minutes' },
    { op: 'threshold', min: 1 },
    { op: 'window', window: WINDOW },
    { op: 'resolve' },
    { op: 'snapshot', label: 'showcase baseline' },
  ];
  return [
    { id: 'investigation', as: 'mm.analyst', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: 'Mule hub HUB-01', purpose: 'Showcase: agent-assisted structuring and the mule chain', dataset: DATASET,
      sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts' } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'mm.analyst', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['mm.reviewer', 'reviewer'], ['mm.manager', 'reviewer'], ['admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'mm.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-struct', as: 'mm.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'struct-wallets', title: 'STRUCT wallets', purpose: 'watch', entityType: 'wallet', reason: 'showcase structuring story' } },
    { id: 'list-struct-members', as: 'mm.analyst', method: 'POST', path: '/entity-lists/struct-wallets/members', body: {
      add: structIds, addRanges: [{ prefix: 'STRUCT-' }], reason: 'showcase structuring story' } },
    { id: 'list-struct-expiring', as: 'mm.analyst', method: 'POST', path: '/entity-lists/struct-wallets/members', body: {
      add: ['HUB-01'], expiresAt: '@+30d', reason: '30 day hold on the mule hub' } },
    { id: 'list-farm', as: 'mm.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'farm-wallets', title: 'Device farm wallets', purpose: 'watch', entityType: 'wallet', reason: 'showcase device farm' } },
    { id: 'list-farm-members', as: 'mm.analyst', method: 'POST', path: '/entity-lists/farm-wallets/members', body: {
      add: farmIds, reason: 'one handset, nine national ids (showcase)' } },
    { id: 'identity', as: 'mm.analyst', method: 'POST', path: '/inv/entity-identities', body: {
      a: 'wallet:FARM-1', b: 'wallet:FARM-2', reason: 'same handset DEV-FARM-01 (showcase)' } },
    // A Case opened from an entity of a classified Dataset needs canRevealLinkEntities, which only `admin` holds in this Space
    // (mm.analyst deliberately does not: the masking beat). admin is also the Investigation's lead, so it may link the Case.
    { id: 'case', as: 'admin', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: 'entity:wallet:HUB-01', dataset: DATASET }] } },
    { id: 'case-assign', as: 'admin', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'mm.manager' } },
    { id: 'case-link', as: 'admin', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
    { id: 'template', as: 'mm.analyst', method: 'POST', path: `${base}/template`, body: { id: 'mm-mule-chain-template', title: 'Mule chain (hub + relays + cash-out agent)' } },
    { id: 'alert-rule', as: 'mm.analyst', method: 'POST', path: `${base}/alert-rules`, body: {
      name: RULE, severity: 'CRITICAL',
      valueMeasure: { name: 'structuring', valueCol: 'amount', timeCol: 'ts', from: '2026-10-01', to: '2026-10-15', min: 4750, max: 5000, minLegs: 10, minPayers: 5 } } },
    { id: 'standing', as: 'mm.analyst', method: 'POST', path: `${base}/standing-detection`, body: { rule: RULE } },
  ];
}

export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, warnings, summary }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-mobile-money', apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), readyTries = 60, alertWaitTries = 20, stages = {} } = {}) {
  const want = { pending: true, alert: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(11)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  mm.analyst  POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held as a pending four-eyes request)`);
    if (want.alert) log(`  mm.manager  POST /jobs/la_detect/trigger  (skipped if an Alert for ${RULE} already exists)`);
    return out;
  }
  const tokens = {};
  const api = `${base}/api/v1`;
  const token = async (user) => {
    if (tokens[user]) return tokens[user];
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build and does the Space define ${user}?`);
    return (tokens[user] = unwrap(await r.json()).accessToken);
  };
  const call = async (user, method, p, body) => {
    const r = await fetchFn(`${api}/spaces/${space}${p}`, { method, headers: { authorization: `Bearer ${await token(user)}`, 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    let data = null;
    try { data = unwrap(await r.json()); } catch { /* empty body */ }
    return { status: r.status, data };
  };
  const items = (d) => (Array.isArray(d) ? d : (d && d.items) || []);
  const must = (r, what, ok = [200, 201, 202]) => { if (!ok.includes(r.status)) throw new Error(`${what} answered HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`); return r.data; };

  // Right after /health first answers the Investigation store may not be loaded: poll the LIST until it answers 200.
  let listed = { status: 0, data: null };
  for (let i = 0; i < readyTries && listed.status !== 200; i++) {
    try { listed = await call('mm.analyst', 'GET', '/inv/investigations?limit=200'); } catch (e) { listed = { status: 0, data: String(e.message || e) }; }
    if (listed.status !== 200) await sleep(1000);
  }
  if (listed.status !== 200) throw new Error(`cannot list Investigations as mm.analyst (${listed.status}): is the Space '${space}' created from the la-mobile-money template?`);
  const existing = items(listed.data).find((i) => i.id === INV);
  const headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };

  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replace('"@+30d"', JSON.stringify(new Date(Date.now() + 30 * 864e5).toISOString())).replace(/@case/g, state.case || '@case'));
    const p = s.path.replace('@case', state.case || '@case');
    if (p.includes('@case')) { out.failed.push(`${s.id}: no Case id`); continue; }
    if (s.id === 'identity') {
      const have = await call(s.as, 'GET', '/inv/entity-identities');
      if (have.status === 200 && JSON.stringify(have.data).includes('FARM-1')) { out.skipped.push(s.id); continue; }
    }
    let r = await call(s.as, s.method, p, body);
    // Right after boot the Dataset's first ingest / mm_links run may not have landed yet: the create answers 422 "cannot read the columns".
    for (let i = 1; s.id === 'investigation' && r.status === 422 && i < 60; i++) {
      log(`  ${s.id}: 422, waiting for the first mm_links run (${i}/59; it runs every 10 minutes, trigger it as mm.manager to skip the wait)`);
      await sleep(2000);
      r = await call(s.as, s.method, p, body);
    }
    if (s.id === 'case' && r.status < 300) state.case = r.data.case.id;
    if (r.status === 409) out.skipped.push(s.id);
    else if (r.status < 300) out.done.push(s.id);
    else out.failed.push(`${s.id}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
    log(`  ${s.id}: ${r.status}`);
    if (s.id === 'investigation' && r.status >= 300 && r.status !== 409) break;   // nothing else can work
  }
  if (out.failed.length) { out.warnings.push('optional stages skipped: the plan above failed'); return out; }
  const warn = (stage, e) => { const m = `${stage}: ${e.message || e}; the stage was skipped and left no half-made state`; out.warnings.push(m); log(`  WARNING ${m}`); };
  if (want.pending) try { await stagePending(call, out, log, must); } catch (e) { warn('pending', e); }
  if (want.alert) try { await stageAlert(call, out, log, sleep, alertWaitTries, items, must); } catch (e) { warn('alert', e); }
  return out;
}

/** ONE pending four-eyes expand. Idempotent: the approver inbox (as mm.manager) is read first. */
async function stagePending(call, out, log, must) {
  const inbox = async () => (must(await call('mm.manager', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox').items || []).filter((i) => i.investigationId === INV);
  let mine = await inbox();
  if (mine.length) { out.skipped.push('pending-expand'); out.summary.pendingRequestId = mine[0].requestId; return; }
  const d = must(await call('mm.analyst', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND), 'the unbounded expand');
  if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?)');
  mine = await inbox();
  if (mine.length !== 1) throw new Error(`mm.manager's inbox shows ${mine.length} pending request(s) on ${INV}, expected exactly one`);
  out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id; log(`  pending-expand: ${d.pending.id}`);
}

const firedAlert = (alerts) => alerts.find((a) => a.rule === RULE || String(a.rule).endsWith(`/${RULE}`));

/** ONE la_detect run; waits (bounded) for the Alert. Idempotent: an existing Alert for the rule means no run. */
async function stageAlert(call, out, log, sleep, tries, items, must) {
  const read = async () => firedAlert(items(must(await call('mm.analyst', 'GET', '/alerts?limit=200'), 'Alerts list')));
  const id = (x) => `${x.rule}@${x.epochMillis}`;
  let a = await read();
  if (a) { out.skipped.push('alert'); out.summary.alertId = id(a); return; }
  const t = await call('mm.manager', 'POST', '/jobs/la_detect/trigger');
  if (t.status !== 409) must(t, 'la_detect trigger');   // 409 = already running: just wait for it
  for (let i = 0; i < tries && !a; i++) { await sleep(3000); a = await read(); }
  if (!a) throw new Error(`no Alert for ${RULE} appeared after ${tries} polls of the la_detect run (is standing detection enabled on the rule?)`);
  out.done.push('alert'); out.summary.alertId = id(a); log(`  alert: ${id(a)}`);
}

/** The server's TemplateSeedGate re-stamps a la.index.build Job to its applier; a file copy does not, and the run authority then
 *  REJECTS the build ("last editor (none recorded)"). Stamp `updatedBy` = the named owner, as a save would. */
export function stampIndexBuildOwner(text) {
  const owner = /^ {2}owner: *"?([^"\r\n]+?)"?\s*$/m.exec(text);
  if (!/^ {2}type: *la\.index\.build\b/m.test(text) || !owner || /^ {2}updatedBy:/m.test(text)) return text;
  return `${text.replace(/\s*$/, '')}\n  updatedBy: ${owner[1]}\n`;
}

/** Offline: lay the Space folder (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
export function scaffold(spacesRoot, templatesDir = path.join(here, '..', 'spaces', '_templates'), id = 'la-mobile-money') {
  const made = [];
  const copy = (src, dst, sid) => {
    fs.mkdirSync(dst, { recursive: true });
    for (const e of fs.readdirSync(src, { withFileTypes: true })) {
      const s = path.join(src, e.name), d = path.join(dst, e.name);
      if (e.isDirectory()) copy(s, d, sid);
      else if (sid && e.name.endsWith('.toon')) fs.writeFileSync(d, stampIndexBuildOwner(fs.readFileSync(s, 'utf8').replaceAll('${SPACE}', sid)));
      else fs.copyFileSync(s, d);
    }
  };
  fs.mkdirSync(spacesRoot, { recursive: true });
  if (!fs.existsSync(path.join(spacesRoot, '_templates'))) { copy(templatesDir, path.join(spacesRoot, '_templates'), null); made.push('_templates'); }
  const base = path.join(spacesRoot, id);
  if (!fs.existsSync(base)) {
    for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
    copy(path.join(templatesDir, id, 'config'), path.join(base, 'config'), id);
    copy(path.join(templatesDir, id, 'data'), path.join(base, 'data'), null);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Link Analysis mobile money\ndescription: Scaffolded from the ${id} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(id);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const root = opt('--scaffold');
    if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-la-mm.mjs --scaffold <spacesRoot> (offline, before the server starts)'); process.exit(2); }
    const made = scaffold(root);
    console.log(made.length ? `scaffolded: ${made.join(', ')}` : 'nothing to do: the Space already exists');
  } else {
    const stages = { pending: !args.includes('--no-pending'), alert: !args.includes('--no-alert') };
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-mobile-money'), apply: args.includes('--apply'), log: console.log, stages })
      .then((r) => {
        if (args.includes('--apply')) {
          console.log(`created: ${r.done.join(', ') || '-'}\nalready there: ${r.skipped.join(', ') || '-'}\nfailed: ${r.failed.join('; ') || '-'}`);
          console.log(`warnings: ${r.warnings.join('; ') || '-'}\nstaged: pending request ${r.summary.pendingRequestId || '-'} | Alert ${r.summary.alertId || '-'}`);
        }
        process.exit(r.failed.length ? 1 : 0);
      })
      .catch((e) => { console.error(e.message); process.exit(1); });
  }
}
