#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-showcase` Space (LA-DEMO-SEED-1: DR-S1, S4, S5) over HTTP, as the right Demo Users.
// Investigations, members, Entity Lists, identity facts, a Case and a bound Alert Rule have no registry kind, so no Space
// Template can carry them; this script is the documented home. See docs/okf/capabilities/spaces/spaces.md (la-showcase).
//
//   node tools/seed-la-showcase.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the Space folders
//   node tools/seed-la-showcase.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-showcase.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Never sends a credential: the Demo User exchange
// takes a `demo:<id>` code and no password.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'mule-hub-case';
export const DATASET = 'mule_transfers_dataset';
const here = path.dirname(fileURLToPath(import.meta.url));

/** The seeded state as an ordered list of steps. `skip(state)` lets a step opt out when the thing already exists. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: ['MULE-HUB-01', 'RELAY-01', 'RELAY-02', 'SMURF-01'], entityType: 'account' },
    { op: 'expand', direction: 'either', maxFanOut: 5 },   // <= four_eyes_fan_out_above (5): runs now; an unbounded expand goes pending
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
    ...[['ra.analyst', 'reviewer'], ['fm.manager', 'reviewer'], ['admin', 'reviewer']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'fm.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-smurfs', as: 'fm.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'smurf-accounts', title: 'SMURF accounts', purpose: 'watch', entityType: 'account', reason: 'showcase structuring ring' } },
    { id: 'list-smurfs-members', as: 'fm.analyst', method: 'POST', path: '/entity-lists/smurf-accounts/members', body: {
      add: Array.from({ length: 12 }, (_, i) => `SMURF-${String(i + 1).padStart(2, '0')}`),
      addRanges: [{ prefix: 'SMURF-' }], reason: 'showcase structuring ring' } },
    { id: 'list-smurfs-expiring', as: 'fm.analyst', method: 'POST', path: '/entity-lists/smurf-accounts/members', body: {
      add: ['TILL-06'], expiresAt: '@+30d', reason: '30 day hold on the cash-out till' } },
    { id: 'identity', as: 'fm.analyst', method: 'POST', path: '/inv/entity-identities', body: {
      a: 'account:MULE-HUB-01', b: 'account:ACC-1006', reason: 'same KYC document (showcase)' } },
    { id: 'case', as: 'fm.analyst', method: 'POST', path: '/cases/from-entities', body: {
      title: 'Mule ring around MULE-HUB-01', entities: [{ id: 'entity:account:MULE-HUB-01', dataset: DATASET }] } },
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

const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, lines }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-showcase', apply = false, log = () => {} } = {}) {
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [] };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(11)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
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
  const listed = await call('fm.analyst', 'GET', '/inv/investigations?limit=200');
  if (listed.status !== 200) throw new Error(`cannot list Investigations as fm.analyst (${listed.status}): is the Space '${space}' created from the la-showcase template?`);
  const existing = (listed.data.items || []).find((i) => i.id === INV);
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
      if (have.status === 200 && JSON.stringify(have.data).includes('ACC-1006')) { out.skipped.push(s.id); continue; }
    }
    const r = await call(s.as, s.method, p, body);
    if (s.id === 'case' && r.status < 300) state.case = r.data.case.id;
    if (r.status === 409) out.skipped.push(s.id);                 // already exists (list, template, rule, member, pending)
    else if (r.status < 300) out.done.push(s.id);
    else out.failed.push(`${s.id}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
    log(`  ${s.id}: ${r.status}`);
    if (s.id === 'investigation' && r.status >= 300 && r.status !== 409) break;   // nothing else can work
  }
  return out;
}

/** Offline: lay the showcase Space folders (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
export function scaffold(spacesRoot, templatesDir = path.join(here, '..', 'spaces', '_templates')) {
  const made = [];
  const copy = (src, dst, id) => {
    fs.mkdirSync(dst, { recursive: true });
    for (const e of fs.readdirSync(src, { withFileTypes: true })) {
      const s = path.join(src, e.name), d = path.join(dst, e.name);
      if (e.isDirectory()) copy(s, d, id);
      else if (id && e.name.endsWith('.toon')) fs.writeFileSync(d, fs.readFileSync(s, 'utf8').replaceAll('${SPACE}', id));
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
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), apply: args.includes('--apply'), log: console.log })
      .then((r) => { if (args.includes('--apply')) console.log(`created: ${r.done.join(', ') || '-'}\nalready there: ${r.skipped.join(', ') || '-'}\nfailed: ${r.failed.join('; ') || '-'}`); process.exit(r.failed.length ? 1 : 0); })
      .catch((e) => { console.error(e.message); process.exit(1); });
  }
}
