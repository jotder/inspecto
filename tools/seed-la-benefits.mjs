#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-benefits` Space (benefits and tax fraud Link Analysis showcase) over HTTP, as the right Demo
// Users: the Advanced tier's Investigation, members, an Entity List, a Case, a pending four-eyes expand and a Draft. They have no
// registry kind, so no Space Template can carry them. The Alert Rule (benefits_paid_to_the_deceased_after_notice) ships in the
// template itself. Self-contained sibling of tools/seed-la-card.mjs (which it does not modify).
//
//   node tools/seed-la-benefits.mjs --scaffold <spacesRoot>      offline, BEFORE the server starts: lay down the la-benefits Space folders
//   node tools/seed-la-benefits.mjs [--base URL] [--space id]    DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-benefits.mjs [...] --apply                perform the calls (safe to re-run: existing things are skipped)
//
// Stages after the plan (each on by default, skipped with its flag, idempotent, SOFT: a refusal prints a warning, exit code stays 0):
//   --no-pending   one PENDING four-eyes expand on paid-after-death (an unbounded expand by benefits.analyst; left undecided for benefits.manager)
//   --no-draft     one open Draft of paid-after-death forked by benefits.analyst, carrying one annotate step, left unpromoted
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Sends only `demo:<id>` codes, never a credential.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const INV = 'paid-after-death';
export const DATASET = 'benefits_links_dataset';
export const SPACE = 'la-benefits';
export const ENTITY_TYPE = 'public_entity';
const here = path.dirname(fileURLToPath(import.meta.url));
const gt = JSON.parse(fs.readFileSync(path.join(here, 'gen-la-benefits-data.ground-truth.json'), 'utf8'));
const story = gt.payments_to_the_deceased;
export const ACCOUNTS = story.accounts;                    // the 3 accounts the nine pensions are still paid into
export const CONTROLLER = story.controller;                // CIT-08276, the living person whose 3 claims are paid into the same accounts
export const DECEASED = story.deceased;                    // the 9 pensioners still paid months after their death notices
export const LOOKALIKE = story.lookalike;                  // CIT-05289, notified 40 days late, stopped 2 days after, overpayment recovered
const CASE_TITLE = `Pensions paid after death into accounts of ${CONTROLLER}`;
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** The seeded state as an ordered list of steps. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: ACCOUNTS, entityType: ENTITY_TYPE },
    // bounded, under four_eyes_fan_out_above (2000): each runs now. Account -> the 12 claims paid into it -> the 10 claimants
    { op: 'expand', direction: 'either', linkKinds: ['paid_into'], maxFanOut: 20 },
    { op: 'expand', direction: 'either', linkKinds: ['claims'], maxFanOut: 20 },
    { op: 'annotate', ids: [CONTROLLER], note: `${DECEASED.length} pensions are still paid into these ${ACCOUNTS.length} accounts months after the deaths were notified; the accounts also take this person's claims` },
    { op: 'threshold', min: 1 },
    { op: 'window', window: { from: '2025-10-01T00:00:00Z', to: '2026-10-01T00:00:00Z' } },
    { op: 'resolve' },
    { op: 'snapshot', label: 'showcase baseline' },
  ];
  return [
    { id: 'investigation', as: 'benefits.analyst', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: `Paid after death (${ACCOUNTS.length} accounts)`, purpose: `Showcase: ${DECEASED.length} pensions still paid after the death notices`, dataset: DATASET,
      sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts', entityType: ENTITY_TYPE } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'benefits.analyst', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['benefits.reviewer', 'reviewer'], ['benefits.manager', 'reviewer'], ['admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'benefits.analyst', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-deceased', as: 'benefits.analyst', method: 'POST', path: '/entity-lists', body: {
      id: 'deceased-still-paid', title: 'Deceased still paid', purpose: 'watch', entityType: ENTITY_TYPE, reason: 'on the death register and still paid months after the notice' } },
    { id: 'list-deceased-members', as: 'benefits.analyst', method: 'POST', path: '/entity-lists/deceased-still-paid/members', body: { add: DECEASED, reason: 'showcase payments to the deceased' } },
    { id: 'case', as: 'benefits.analyst', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: `entity:${ENTITY_TYPE}:${CONTROLLER}`, dataset: DATASET }] } },
    { id: 'case-assign', as: 'benefits.analyst', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'benefits.manager' } },
    { id: 'case-link', as: 'benefits.analyst', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
    { id: 'template', as: 'benefits.analyst', method: 'POST', path: `${base}/template`, body: { id: 'benefits-deceased-template', title: 'Paid after death (accounts + claims + claimants)' } },
  ];
}

/** The link Dataset is a Job output, so a fresh Space may have none yet and the Investigation create is refused: press Run on the link
 *  Job (as benefits.manager, who holds canOperateRuns) and wait for benefits_links_dataset to have rows, as seed-la-card.mjs does. */
async function prime(call, log, sleep, tries) {
  const has = async () => { const r = await call('benefits.analyst', 'GET', `/datasets/${DATASET}/rows?limit=1`); return r.status === 200 && r.data && (r.data.rows || []).length > 0; };
  if (await has()) return;
  const t = await call('benefits.manager', 'POST', '/jobs/benefits_links/trigger', {}); log(`  prime benefits_links: ${t.status}`);
  for (let i = 0; i < tries; i++) { if (await has()) return; await sleep(2000); }
  log(`  WARNING ${DATASET} still has no rows; the Investigation create will be refused`);
}

export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
// annotates the living claimant, who is in the Working Set after the second expand. The expands follow paid_into and claims only,
// so the claimant's address (a lives_at link further out) is not in it, and annotating an entity outside the Working Set is refused
export const DRAFT_STEP = { op: 'annotate', ids: [CONTROLLER], note: `widen the case: who else lives at ${CONTROLLER}'s address, and is any of them on the death register?` };

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, warnings, summary }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = SPACE, apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), ingestWaitTries = 40, readyTries = 60, stages = {} } = {}) {
  const want = { pending: true, draft: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(18)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  benefits.analyst   POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held for benefits.manager; skipped if one is pending)`);
    if (want.draft) log(`  benefits.analyst   POST /inv/investigations/${INV}/drafts, then .../ops ${JSON.stringify(DRAFT_STEP)}`);
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
  const listed = await poll(() => call('benefits.analyst', 'GET', '/inv/investigations?limit=200'), readyTries);
  if (listed.status !== 200) throw new Error(`cannot list Investigations as benefits.analyst (${listed.status}): is the Space '${space}' created from the la-benefits template?`);
  let existing = items(listed.data).find((i) => i.id === INV);
  if (!existing) await prime(call, log, sleep, readyTries);
  let headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };
  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replaceAll('@case', state.case || '@case'));
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
      // READABLE before any op: the LIST must show the id; a create answered 409 can be spurious for a few seconds after /health
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
    const inbox = async () => items(must(await call('benefits.manager', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox')).filter((i) => i.investigationId === INV);
    const mine = await inbox();
    if (mine.length) out.skipped.push('pending-expand');
    else {
      const d = must(await call('benefits.analyst', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND), 'the unbounded expand');
      if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?)');
      out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id;
    }
  } catch (e) { warn('pending', e); }
  if (want.draft) try {
    const dbase = `/inv/investigations/${INV}/drafts`;
    const live = items(must(await call('benefits.analyst', 'GET', dbase), 'Draft list')).find((d) => d.state === 'open' || d.state === 'hibernated');
    if (live && live.steps > 0) { out.skipped.push('draft'); out.summary.draftId = live.draftId; }
    else {
      const id = live ? live.draftId : must(await call('benefits.analyst', 'POST', dbase, { label: 'Draft: who else lives there' }), 'Draft fork').draftId;
      must(await call('benefits.analyst', 'POST', `${dbase}/${id}/ops`, DRAFT_STEP), 'the Draft step');
      out.done.push('draft'); out.summary.draftId = id;
    }
  } catch (e) { warn('draft', e); }
  return out;
}

/** Offline: lay the la-benefits Space folders (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
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
  const base = path.join(spacesRoot, SPACE);
  if (!fs.existsSync(base)) {
    for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
    copy(path.join(templatesDir, SPACE, 'config'), path.join(base, 'config'), SPACE);
    copy(path.join(templatesDir, SPACE, 'data'), path.join(base, 'data'), null);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Benefits and tax fraud\ndescription: Scaffolded from the ${SPACE} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(SPACE);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const root = opt('--scaffold');
    if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-la-benefits.mjs --scaffold <spacesRoot> (offline, before the server starts)'); process.exit(2); }
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
