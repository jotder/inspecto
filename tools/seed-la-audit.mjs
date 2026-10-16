#!/usr/bin/env node
// Seeds the RUNTIME state of the `la-audit` Space (vertical B of docs/superpower/la-multi-vertical-showcase-plan.md) over HTTP, as the
// right Demo Users: Investigations, members, an Entity List, a Case, a bound Alert Rule. None has a registry kind, so no Space
// Template can carry them. Self-contained (its own plan); only the tiny sign-in client in tools/lib/la-client.mjs is shared.
//
//   node tools/seed-la-audit.mjs --scaffold <spacesRoot>        offline, BEFORE the server starts: lay down the la-audit Space folders
//   node tools/seed-la-audit.mjs [--base URL] [--space id]      DRY RUN: print every call it would make, change nothing
//   node tools/seed-la-audit.mjs [...] --apply                  perform the calls (safe to re-run: existing things are skipped)
//
// Optional stages, each on by default, skipped with its flag, idempotent and SOFT (a refusal prints a warning, exit code stays 0):
//   --no-pending   one PENDING four-eyes expand (the CFO's 40 vendors, unbounded, over four_eyes_fan_out_above) left for audit.manager
//   --no-draft     one open Draft of the workpaper Investigation: the auditor's what-if over the approval limit
// Needs the Demo Auth build (serve-demo, loopback). Synthetic data only. Never sends a credential: the Demo User exchange takes a
// `demo:<id>` code and no password.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeClient } from './lib/la-client.mjs';

export const INV = 'vendor-v117-workpaper';
export const DATASET = 'ap_links_dataset';
const CASE_TITLE = 'Vendor V-117 conflict of interest';
const here = path.dirname(fileURLToPath(import.meta.url));
export const PENDING_EXPAND = { op: 'expand', direction: 'either' };   // no maxFanOut = unbounded: always above four_eyes_fan_out_above, so held
export const DRAFT_STEP = { op: 'annotate', ids: ['V-099'], note: 'what-if: an approval limit of 9 000 would have stopped all 30 invoices' };

/** The seeded state as an ordered list of steps. */
export function buildPlan() {
  const base = `/inv/investigations/${INV}`;
  const ops = [
    { op: 'seed', ids: ['V-117', 'E-31'], entityType: 'party' },
    { op: 'expand', direction: 'either', maxFanOut: 20 },   // bounded, under four_eyes_fan_out_above (30): runs now
    { op: 'annotate', ids: ['E-31'], note: 'shares bank account BA-9031 and address AD-9031 with V-117; manager E-15 approved all 14 invoices' },
    { op: 'window', window: { from: '2025-10-01T00:00:00Z', to: '2026-11-01T00:00:00Z' } },
    { op: 'resolve' },
    { op: 'snapshot', label: 'workpaper baseline' },
  ];
  return [
    { id: 'investigation', as: 'audit.lead', method: 'POST', path: '/inv/investigations', body: {
      id: INV, title: 'Vendor V-117 workpaper', purpose: 'Showcase: audit workpaper for the conflict-of-interest vendor', dataset: DATASET,
      sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts' } },
    ...ops.map((body, i) => ({ id: `op-${i + 1}-${body.op}`, as: 'audit.lead', method: 'POST', path: `${base}/ops`, body, opIndex: i })),
    ...[['audit.committee', 'reviewer'], ['audit.manager', 'reviewer'], ['audit.admin', 'lead']].map(([subject, role]) => (
      { id: `member-${subject}`, as: 'audit.lead', method: 'POST', path: `${base}/members`, body: { subject, role } })),
    { id: 'list-shells', as: 'audit.lead', method: 'POST', path: '/entity-lists', body: {
      id: 'shell-vendors', title: 'Shell cluster vendors', purpose: 'watch', entityType: 'party', reason: 'showcase shell cluster on one bank account' } },
    { id: 'list-shells-members', as: 'audit.lead', method: 'POST', path: '/entity-lists/shell-vendors/members', body: {
      add: ['V-131', 'V-132', 'V-133', 'V-134', 'V-135'], reason: 'five vendors paid to BA-7701 (showcase)' } },
    { id: 'case', as: 'audit.lead', method: 'POST', path: '/cases/from-entities', body: {
      title: CASE_TITLE, entities: [{ id: 'entity:party:V-117', dataset: DATASET }] } },
    { id: 'case-assign', as: 'audit.lead', method: 'POST', path: '/objects/@case/assign', body: { assignee: 'audit.manager' } },
    { id: 'case-link', as: 'audit.lead', method: 'PUT', path: `${base}/case`, body: { caseRef: '@case' } },
    { id: 'alert-rule', as: 'audit.lead', method: 'POST', path: `${base}/alert-rules`, body: {
      name: 'ap-approved-value', severity: 'HIGH',
      valueMeasure: { name: 'approvedValue', valueCol: 'amount', timeCol: 'ts', from: '2025-10-01', to: '2026-10-01' } } },
  ];
}

const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Runs (or, with apply=false, only prints) the plan. Returns { done, skipped, failed, warnings, summary }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-audit', apply = false, log = () => {},
  sleep = (ms) => new Promise((r) => setTimeout(r, ms)), readyTries = 60, stages = {} } = {}) {
  const want = { pending: true, draft: true, ...stages };
  const plan = buildPlan();
  const out = { done: [], skipped: [], failed: [], warnings: [], summary: {} };
  if (!apply) {
    log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
    for (const s of plan) log(`  ${s.as.padEnd(15)} ${s.method} ${s.path}  ${JSON.stringify(s.body)}`);
    if (want.pending) log(`  audit.lead      POST /inv/investigations/${INV}/ops  ${JSON.stringify(PENDING_EXPAND)}  (held as a pending four-eyes request for audit.manager)`);
    if (want.draft) log(`  audit.lead      POST /inv/investigations/${INV}/drafts, then .../ops ${JSON.stringify(DRAFT_STEP)}`);
    return out;
  }
  const client = makeClient({ fetchFn, base, space });
  const call = async (user, method, p, body) => { const r = await client(user, method, p, body); return { status: r.status, data: r.data }; };
  const items = (d) => (Array.isArray(d) ? d : (d && d.items) || []);
  // READINESS: right after /health first answers the Investigation store may not be loaded; poll the LIST until it answers 200.
  let listed = { status: 0 };
  for (let i = 0; i < readyTries; i++) {
    try { listed = await call('audit.lead', 'GET', '/inv/investigations?limit=200'); } catch (e) { listed = { status: 0, data: String(e.message || e) }; }
    if (listed.status === 200) break;
    await sleep(1000);
  }
  if (listed.status !== 200) throw new Error(`cannot list Investigations as audit.lead (${listed.status}): is the Space '${space}' created from the la-audit template?`);
  const existing = items(listed.data).find((i) => i.id === INV);
  const headStep = existing ? existing.headStep || 0 : 0;
  const state = { case: existing && existing.caseRef ? existing.caseRef : null, caseExisted: !!(existing && existing.caseRef) };
  for (const s of plan) {
    if (s.id === 'investigation' && existing) { out.skipped.push(s.id); continue; }
    if (s.opIndex !== undefined && s.opIndex < headStep) { out.skipped.push(s.id); continue; }
    if (['case', 'case-assign', 'case-link'].includes(s.id) && state.caseExisted) { out.skipped.push(s.id); continue; }
    const body = JSON.parse(JSON.stringify(s.body).replace(/@case/g, state.case || '@case'));
    const p = s.path.replace('@case', state.case || '@case');
    if (p.includes('@case')) { out.failed.push(`${s.id}: no Case id`); continue; }
    const r = await call(s.as, s.method, p, body);
    if (s.id === 'case' && r.status < 300) state.case = r.data.case.id;
    if (r.status === 409) out.skipped.push(s.id);
    else if (r.status < 300) out.done.push(s.id);
    else out.failed.push(`${s.id}: HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
    log(`  ${s.id}: ${r.status}`);
    if (s.id === 'investigation' && r.status >= 300 && r.status !== 409) break;   // nothing else can work
  }
  if (out.failed.length) { out.warnings.push('optional stages skipped: the plan above failed'); return out; }
  const warn = (stage, e) => { const m = `${stage}: ${e.message || e}; the stage was skipped and left no half-made state`; out.warnings.push(m); log(`  WARNING ${m}`); };
  if (want.pending) try { await stagePending(call, out, log); } catch (e) { warn('pending', e); }
  if (want.draft) try { await stageDraft(call, out, log); } catch (e) { warn('draft', e); }
  return out;
}

const must = (r, what) => { if (![200, 201, 202].includes(r.status)) throw new Error(`${what} answered HTTP ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`); return r.data; };

/** ONE pending four-eyes expand. Idempotent: the approver inbox (as audit.manager) is read first. */
async function stagePending(call, out, log) {
  const inbox = async () => (must(await call('audit.manager', 'GET', '/inv/pending?status=pending&limit=200'), 'approval inbox').items || []).filter((i) => i.investigationId === INV);
  let mine = await inbox();
  if (mine.length) { out.skipped.push('pending-expand'); out.summary.pendingRequestId = mine[0].requestId; return; }
  const d = must(await call('audit.lead', 'POST', `/inv/investigations/${INV}/ops`, PENDING_EXPAND), 'the unbounded expand');
  if (d.status !== 'pending') throw new Error('the unbounded expand RAN instead of being held (is four_eyes_fan_out_above set in link-analysis.toon?)');
  out.done.push('pending-expand'); out.summary.pendingRequestId = d.pending.id; log(`  pending-expand: ${d.pending.id}`);
}

/** ONE open Draft with one step; idempotent (audit.lead's live Draft is reused and only topped up when empty). */
async function stageDraft(call, out, log) {
  const base = `/inv/investigations/${INV}/drafts`;
  const live = (must(await call('audit.lead', 'GET', base), 'Draft list').items || []).find((d) => d.state === 'open' || d.state === 'hibernated');
  if (live && live.steps > 0) { out.skipped.push('draft'); out.summary.draftId = live.draftId; return; }
  const created = !live;
  const id = live ? live.draftId : must(await call('audit.lead', 'POST', base, { label: 'Draft: approval limit what-if' }), 'Draft fork').draftId;
  try {
    must(await call('audit.lead', 'POST', `${base}/${id}/ops`, DRAFT_STEP), 'the Draft step');
  } catch (e) {
    if (created) await call('audit.lead', 'POST', `${base}/${id}/discard`, {});
    throw e;
  }
  out.done.push('draft'); out.summary.draftId = id; log(`  draft: ${id}`);
}

/** A file copy does not stamp a la.index.build Job's `updatedBy`; the run authority then rejects commit-triggered builds. Stamp it as a save would. */
export function stampIndexBuildOwner(text) {
  const owner = /^ {2}owner: *"?([^"\r\n]+?)"?\s*$/m.exec(text);
  if (!/^ {2}type: *la\.index\.build\b/m.test(text) || !owner || /^ {2}updatedBy:/m.test(text)) return text;
  return `${text.replace(/\s*$/, '')}\n  updatedBy: ${owner[1]}\n`;
}

/** Offline: lay the la-audit Space folder (and the template gallery) under a spaces root, as SpaceManager.createFromTemplate does. */
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
  const id = 'la-audit';
  const base = path.join(spacesRoot, id);
  if (!fs.existsSync(base)) {
    for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
    copy(path.join(templatesDir, id, 'config'), path.join(base, 'config'), id);
    copy(path.join(templatesDir, id, 'data'), path.join(base, 'data'), null);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Link Analysis audit showcase\ndescription: Scaffolded from the ${id} Space Template\ncreated_at: ${new Date().toISOString()}\n`);
    made.push(id);
  }
  return made;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
  if (args.includes('--scaffold')) {
    const root = opt('--scaffold');
    if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-la-audit.mjs --scaffold <spacesRoot> (offline, before the server starts)'); process.exit(2); }
    const made = scaffold(root);
    console.log(made.length ? `scaffolded: ${made.join(', ')}` : 'nothing to do: the Space already exists');
  } else {
    const stages = { pending: !args.includes('--no-pending'), draft: !args.includes('--no-draft') };
    run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-audit'), apply: args.includes('--apply'), log: console.log, stages })
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
