#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-police` Space (police Link Analysis showcase, Advanced tier) over HTTP, as the right Demo Users.
// Investigations, members, Entity Lists and a Case have no registry kind, so no Space Template can carry them; this script is their home.
// Sibling of tools/seed-la-claims.mjs. The Alert Rule (police_repeat_seller_serial_matches) ships in the template, not here.
//
//   node tools/seed-la-police.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the la-police Space folders
//   node tools/seed-la-police.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-police.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Stages after the plan (each on by default, skipped with its flag, idempotent, SOFT: a refusal prints a warning, exit code stays 0):
//   --no-pending   one PENDING four-eyes expand on burglary-series (an unbounded expand by police.analyst; left undecided for police.sio)
//   --no-draft     one open Draft of burglary-series forked by police.analyst, carrying one annotate step, left unpromoted
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Sends only `demo:<id>` codes, never a credential.
// The ids below are the generator's ground truth (tools/la-police-ground-truth.json); tools/seed-la-police.test.mjs pins them to it.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { stampIndexBuildOwner } from './seed-la-showcase.mjs';

export const INV = 'burglary-series';
export const DATASET = 'police_links_dataset';
export const SPACE = 'la-police';
export const TYPE = 'police_entity';
export const SELLERS = ['P-00476', 'P-04874', 'P-00785'];   // sold stolen items from the series to DLR-04 and DLR-09
export const PLATE = 'ZX 9117 X';                           // kept by P-05167 at the first seller's address; seen near 11 of the 18 scenes
export const KEEPER = 'P-05167';
const CASE_TITLE = 'Burglary series, Leeds North and East, Feb-Apr 2026';
const here = path.dirname(fileURLToPath(import.meta.url));
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** The seeded state as an ordered list of steps. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: [PLATE, SELLERS[0]], entityType: TYPE },
    { op: 'expand', direction: 'either', maxFanOut: 40 },   // bounded, under four_eyes_fan_out_above (2000): runs now; an UNBOUNDED expand always goes pending
    { op: 'annotate', ids: [PLATE], note: 'read by cameras within 2 km and 30 minutes of 11 of the 18 burglaries; kept by a seller\'s partner' },
    { op: 'window', window: { from: '2026-02-01T00:00:00Z', to: '2026-06-01T00:00:00Z' } },
    { op: 'snapshot', label: 'showcase baseline' },
  ];
  return [
    { id: 'investigation', as: 'police.analyst', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: 'Burglary series (ZX 9117 X)', purpose: 'Showcase: stolen serials at two dealers and one vehicle near the scenes', dataset: DATASET,
      sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts', entityType: TYPE } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'police.analyst', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['police.reviewer', 'reviewer'], ['police.sio', 'reviewer'], ['admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'police.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-sellers', as: 'police.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'series-sellers', title: 'Burglary series sellers', purpose: 'watch', entityType: TYPE, reason: 'sold stolen items from the series to two dealers' } },
    { id: 'list-sellers-members', as: 'police.analyst', method: 'POST', path: '/entity-lists/series-sellers/members', body: { add: SELLERS, reason: 'showcase burglary series' } },
    { id: 'case', as: 'police.analyst', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: `entity:${TYPE}:${PLATE}`, dataset: DATASET }] } },
    { id: 'case-assign', as: 'police.analyst', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'police.sio' } },
    { id: 'case-link', as: 'police.analyst', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
  ];
}

export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
export const DRAFT_STEP = { op: 'annotate', ids: ['DLR-09'], note: 'widen the series: who else sells stolen items to DLR-09?' };   // in the Working Set after the first expand

/** The link Dataset is a Job output, so a fresh Space has none until the 5-minute cron and the Investigation create is refused: press Run on
 *  the link Job (as police.sio, who holds canOperateRuns) and wait for police_links_dataset to have rows. */
async function prime(call, log, sleep, tries) {
  const has = async () => { const r = await call('police.analyst', 'GET', `/datasets/${DATASET}/rows?limit=1`); return r.status === 200 && r.data && (r.data.rows || []).length > 0; };
  if (await has()) return;
  const t = await call('police.sio', 'POST', '/jobs/police_links/trigger', {}); log(`  prime police_links: ${t.status}`);
  for (let i = 0; i < tries; i++) { if (await has()) return; await sleep(2000); }
  log(`  WARNING ${DATASET} still has no rows; the Investigation create will be refused`);
}

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, warnings, summary }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = SPACE, apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), ingestWaitTries = 40, readyTries = 60, stages = {} } = {}) {
  const want = { pending: true, draft: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(16)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  police.analyst   POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held for police.sio; skipped if one is pending)`);
    if (want.draft) log(`  police.analyst   POST /inv/investigations/${INV}/drafts, then .../ops ${JSON.stringify(DRAFT_STEP)}`);
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
  const listed = await poll(() => call('police.analyst', 'GET', '/inv/investigations?limit=200'), readyTries);
  if (listed.status !== 200) throw new Error(`cannot list Investigations as police.analyst (${listed.status}): is the Space '${space}' created from the la-police template?`);
  const existing = items(listed.data).find((i) => i.id === INV);
  if (!existing) await prime(call, log, sleep, readyTries);
  let headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };
  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replace(/@case/g, state.case || '@case'));
    const p = s.path.replace('@case', state.case || '@case');
    if (p.includes('@case')) { out.failed.push(`${s.id}: no Case id`); continue; }
    if (s.id === 'case' && !state.case) {   // an UNLINKED Case a half-finished earlier run made: reuse it instead of opening a second
      const c = await call(s.as, 'GET', '/objects?limit=200');
      const hit = c.status === 200 ? items(c.data).find((o) => o && o.title === CASE_TITLE) : null;
      if (hit) { state.case = hit.id; out.skipped.push('case'); log(`  case: reusing ${hit.id}`); continue; }
    }
    let r = await call(s.as, s.method, p, body);
    // right after boot the link Dataset's first build may not have landed: the create answers 422 "cannot read the columns"
    for (let i = 1; s.id === 'investigation' && r.status === 422 && i < ingestWaitTries; i++) {
      log(`  ${s.id}: 422, waiting for the first build of ${DATASET} (${i}/${ingestWaitTries - 1})`);
      await sleep(3000);
      r = await call(s.as, s.method, p, body);
    }
    if (s.id === 'investigation' && (r.status < 300 || r.status === 409)) {
      // READABLE before any op: the LIST must show the id. A create answered 409 can be spurious for a few seconds after /health, so
      // while the id is not listed the create is RE-SENT every third try; a 2xx there is a real create.
      let found = null;
      for (let i = 0; i < readyTries && !found; i++) {
        const l = await call(s.as, 'GET', '/inv/investigations?limit=200').catch(() => ({ status: 0 }));
        found = l.status === 200 ? items(l.data).find((x) => x.id === INV) : null;
        if (found) break;
        if (i % 3 === 2) await call(s.as, s.method, p, body).catch(() => null);
        await sleep(1000);
      }
      if (!found) { out.failed.push(`investigation: answered ${r.status} but never listed after ${readyTries} tries`); log('  investigation: not listed'); break; }
      headStep = found.headStep || 0;
      if (r.status === 409) { out.skipped.push(s.id); log('  investigation: 409, now readable'); continue; }
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
    const inbox = async () => items(must(await call('police.sio', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox')).filter((i) => i.investigationId === INV);
    if ((await inbox()).length) out.skipped.push('pending-expand');
    else {
      const d = must(await call('police.analyst', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND), 'the unbounded expand');
      if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?)');
      out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id;
    }
  } catch (e) { warn('pending', e); }
  if (want.draft) try {
    const dbase = `/inv/investigations/${INV}/drafts`;
    const live = items(must(await call('police.analyst', 'GET', dbase), 'Draft list')).find((d) => d.state === 'open' || d.state === 'hibernated');
    if (live && live.steps > 0) { out.skipped.push('draft'); out.summary.draftId = live.draftId; }
    else {
      const id = live ? live.draftId : must(await call('police.analyst', 'POST', dbase, { label: 'Draft: widen the series' }), 'Draft fork').draftId;
      must(await call('police.analyst', 'POST', `${dbase}/${id}/ops`, DRAFT_STEP), 'the Draft step');
      out.done.push('draft'); out.summary.draftId = id;
    }
  } catch (e) { warn('draft', e); }
  return out;
}

/** Offline: lay the la-police Space folders (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
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
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Police\ndescription: Scaffolded from the ${SPACE} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(SPACE);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const root = opt('--scaffold');
    if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-la-police.mjs --scaffold <spacesRoot> (offline, before the server starts)'); process.exit(2); }
    const made = scaffold(root);
    console.log(made.length ? `scaffolded: ${made.join(', ')}` : 'nothing to do: the Space already exists');
  } else {
    const stages = { pending: !args.includes('--no-pending'), draft: !args.includes('--no-draft') };
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', SPACE), apply: args.includes('--apply'), log: console.log, stages })
      .then((r) => {
        if (args.includes('--apply')) {
          console.log(`created: ${r.done.join(', ') || '-'}\nalready there: ${r.skipped.join(', ') || '-'}\nfailed: ${r.failed.join('; ') || '-'}`);
          console.log(`warnings: ${r.warnings.join('; ') || '-'}\nstaged: pending request ${r.summary.pendingRequestId || '-'} | Draft ${r.summary.draftId || '-'}`);
        }
        process.exit(r.failed.length ? 1 : 0);
      })
      .catch((e) => { console.error(e.message); process.exit(1); });
  }
}
