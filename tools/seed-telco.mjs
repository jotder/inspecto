#!/usr/bin/env node
// Seeds the Inspecto Telco demo Space `telco` (PRODUCT-ASSEMBLY-1 PA-5d; plan docs/superpower/2026-11-10-product-assembly-plan.md
// §11): the Composed Space Template of RA, FM and bypass with its one usage feed (PA-5c), its samples ingested and every App's
// Jobs run, so each App's area opens on its planted findings. The seed contract of package-demo-bake.ps1:
//
//   node tools/seed-telco.mjs --scaffold <spacesRoot>             offline, BEFORE the server starts: compose the template exactly as
//                                                                  the bundle does and lay down spaces/telco with its samples in the
//                                                                  inboxes and the Demo Users
//   node tools/seed-telco.mjs [--base URL] [--space telco]        DRY RUN: print every call it would make, change nothing
//   node tools/seed-telco.mjs [...] --apply                       perform them: run every Pipeline, every App's Jobs for each day of
//                                                                  the corpus (July 2026), then the Alert sweep; safe to re-run
//
// Needs the Demo Auth build (serve-demo, loopback; Advanced or Preview). Synthetic data only: the samples of the committed
// telco-ra, telco-fraud and telco-bypass templates, converted by tools/telecom-usage.mjs. Never sends a credential: the Demo
// User exchange takes a `demo:<id>` code and no password.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gather } from './check-offerings.mjs';
import { composeSpace } from './compose-space.mjs';
import { gatherModel } from './products.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.join(here, '..');
export const SPACE = 'telco';

/** The Demo Users (built-in roles): one per App's desk, a manager who works Cases and Alerts, and the administrator. */
export const ROSTER = `users[5]{id,displayName,title,roles}:
  admin,Demo Admin,Platform administrator,admin;super
  ra.analyst,Demo RA Analyst,Revenue Assurance analyst,operations
  fm.analyst,Demo FM Analyst,Fraud analyst,operations
  fm.manager,Demo FM Manager,Fraud manager,power
  viewer,Demo Viewer,Non-privileged viewer,business
`;

/** The corpus days: RA's records span 2026-07-01..03, FM's 07-01 and 07-02. */
export const DAYS = ['2026-07-01', '2026-07-02', '2026-07-03'];
const window = (day) => {
    const next = new Date(Date.parse(`${day}T00:00:00Z`) + 86400000).toISOString().slice(0, 10);
    return { window_start: `${day} 00:00:00`, window_end: `${next} 00:00:00`, day };
};

/** FM's detection Jobs (one per typology), in name order. */
export const FM_JOBS = ['fraud_adjustment', 'fraud_data_bypass', 'fraud_dealer', 'fraud_identity', 'fraud_internal', 'fraud_irsf',
    'fraud_premium_rate', 'fraud_recharge', 'fraud_reversal', 'fraud_roaming', 'fraud_sim_swap', 'fraud_simbox', 'fraud_voucher', 'fraud_wangiri'];
/** Bypass's nightly chain, in its cron order. */
export const BYPASS_JOBS = ['tb_features', 'tb_factors', 'tb_risk_score', 'tb_findings', 'tb_cluster_members', 'tb_clusters'];
/** The Alert Rules whose Alert must exist once the seed is done: one per App, so every App's area opens on a finding. */
export const EXPECTED_RULES = { 'Revenue Assurance': 'ra_leakage_found', 'Fraud Management': 'fraud_irsf', 'SIM-box bypass': 'tb_box_suspected' };

/** The ordered calls: Pipelines (ingest), then each App's Jobs, then the Alert sweep. */
export function buildPlan(feeds) {
    const plan = feeds.map((f) => ({ id: `ingest-${f}`, as: 'ra.analyst', kind: 'pipeline', method: 'POST', path: `/runs/${f}/trigger` }));
    const job = (id, name, params, as) => ({ id, as, kind: 'job', name, method: 'POST', path: `/jobs/${name}/trigger`, body: params ? { params } : undefined });
    // RA: completeness per day, then the controls; the findings chain also runs on signal, and is run here so the order is fixed.
    for (const day of DAYS) plan.push(job(`ra_xdr_completeness-${day}`, 'ra_xdr_completeness', { day }, 'ra.analyst'));
    for (const j of ['ra_xdr_lost', 'ra_rated_vs_billed', 'ra_rerating', 'ra_rollforward', 'ra_settlement', 'ra_leakage', 'ra_data_quality'])
        plan.push(job(j, j, null, 'ra.analyst'));
    for (const day of DAYS) for (const j of FM_JOBS) plan.push(job(`${j}-${day}`, j, window(day), 'fm.analyst'));
    plan.push(job('tf_anomaly_score', 'tf_anomaly_score', null, 'fm.analyst'));
    for (const day of DAYS) for (const j of BYPASS_JOBS) plan.push(job(`${j}-${day}`, j, window(day), 'fm.analyst'));
    plan.push({ id: 'alerts', as: 'fm.manager', kind: 'sweep', method: 'POST', path: '/alerts/evaluate' });
    return plan;
}

// ── --scaffold ──

function copyTree(src, dst) {
    fs.mkdirSync(dst, { recursive: true });
    for (const e of fs.readdirSync(src, { withFileTypes: true })) {
        const s = path.join(src, e.name), d = path.join(dst, e.name);
        if (e.isDirectory()) copyTree(s, d); else fs.copyFileSync(s, d);
    }
}

/**
 * Compose the Telco Space Template as the bundle does (tools/assemble.mjs + tools/compose-space.mjs over copies of the committed
 * part templates) and lay it down as `<spacesRoot>/telco`: config and data as created from the template, each sample feed
 * also in `data/inbox/<feed>/` (so --apply only triggers the Pipelines), the Demo Users, a space.toon. Returns the feeds, or
 * [] when the Space already exists.
 */
export function scaffold(spacesRoot, { repoRoot = repo } = {}) {
    const base = path.join(spacesRoot, SPACE);
    if (fs.existsSync(base)) return [];
    const plan = assemble(gatherModel(repoRoot), gather(repoRoot), { product: 'telco', edition: 'advanced', distribution: 'demo' });
    if (plan.space?.id !== SPACE) throw new Error(`the Telco Product composes '${plan.space?.id}', not '${SPACE}'`);
    const stage = fs.mkdtempSync(path.join(os.tmpdir(), 'seed-telco-'));
    try {
        for (const p of plan.space.parts) copyTree(path.join(repoRoot, 'spaces', '_templates', p.template), path.join(stage, p.template));
        composeSpace(plan.space, stage);
        const composed = path.join(stage, SPACE);
        for (const sub of ['config', 'data', 'audit', 'duckdb']) fs.mkdirSync(path.join(base, sub), { recursive: true });
        copyTree(path.join(composed, 'config'), path.join(base, 'config'));
        copyTree(path.join(composed, 'data'), path.join(base, 'data'));
    } finally {
        fs.rmSync(stage, { recursive: true, force: true });
    }
    // The demo corpus is fixed to July 2026, so a feed watching for one file a day (gap detection) would raise an Alert for
    // every day since then (262 on 2026-11-11, from FM's payments and subscriber_events), and one more each day the bundle
    // ages. In the demo Space only, the watch is REMOVED; the templates keep it. Removed, not `enabled: false`: the
    // file-template check ignores that switch (GAP-DETECTION-ENABLED-IGNORED-1).
    for (const dir of fs.readdirSync(path.join(base, 'config'))) {
        const f = path.join(base, 'config', dir, `${dir}_pipeline.toon`);
        if (!fs.existsSync(f)) continue;
        const lines = fs.readFileSync(f, 'utf8').split('\n');
        const at = lines.findIndex((l) => /^  gap_detection:\r?$/.test(l));
        if (at < 0) continue;
        let end = at + 1;
        while (end < lines.length && /^ {4,}\S/.test(lines[end])) end++;
        lines.splice(at, end - at);
        // A `collector:` left with nothing under it goes too (an empty key is not an empty map).
        if (at > 0 && /^collector:\r?$/.test(lines[at - 1]) && !/^ {2,}\S/.test(lines[at] ?? '')) lines.splice(at - 1, 1);
        fs.writeFileSync(f, lines.join('\n'));
    }
    const samples = path.join(base, 'data', 'samples');
    const feeds = fs.readdirSync(samples).sort();
    for (const f of feeds) copyTree(path.join(samples, f), path.join(base, 'data', 'inbox', f));
    fs.writeFileSync(path.join(base, 'config', 'demo-users.toon'), ROSTER);
    fs.writeFileSync(path.join(base, 'space.toon'), `display_name: Inspecto Telco\ndescription: The Inspecto Telco demo - Revenue Assurance, Fraud Management and SIM-box bypass over one usage feed, seeded\ncreated_at: ${new Date().toISOString()}\n`);
    return feeds;
}

// ── --apply ──

const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);
const finished = (s) => s && !['RUNNING', 'QUEUED', 'PENDING'].includes(String(s).toUpperCase());

/** Runs (or, with apply=false, only prints) the plan. Returns { done, failed, alerts, lines }. */
export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = SPACE, apply = false, log = () => {},
    sleep = (ms) => new Promise((r) => setTimeout(r, ms)), pollTries = 600, feeds = null } = {}) {
    const out = { done: [], failed: [], alerts: {} };
    const api = `${base}/api/v1`;
    const spaced = `${api}/spaces/${space}`;
    const tokens = {};
    const token = async (user) => {
        if (tokens[user]) return tokens[user];
        const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
        if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build (Advanced or Preview) and does the Space define ${user}?`);
        return (tokens[user] = unwrap(await r.json()).accessToken);
    };
    const call = async (user, method, p, body) => {
        const t = await token(user);
        const r = await fetchFn(`${spaced}${p}`, { method, headers: { authorization: `Bearer ${t}`, 'content-type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body) });
        let data = null;
        try { data = unwrap(await r.json()); } catch { /* empty body */ }
        return { status: r.status, data };
    };
    if (!feeds) {
        if (!apply) feeds = ['<every feed with a sample>'];
        else {
            const p = await call('ra.analyst', 'GET', '/pipelines');
            if (p.status !== 200) throw new Error(`GET /pipelines answered ${p.status}`);
            const list = Array.isArray(p.data) ? p.data : (p.data?.items ?? p.data?.pipelines ?? []);
            feeds = list.map((x) => (typeof x === 'string' ? x : x.name ?? x.id)).filter(Boolean).sort();
        }
    }
    const plan = buildPlan(feeds);
    if (!apply) {
        log(`DRY RUN (no request is sent; add --apply). Space '${space}' at ${base}`);
        for (const s of plan) log(`  ${s.as.padEnd(11)} ${s.method} ${s.path}${s.body ? `  ${JSON.stringify(s.body)}` : ''}`);
        log(`  then GET /alerts: one Alert each of ${Object.values(EXPECTED_RULES).join(', ')}`);
        return out;
    }
    for (const s of plan) {
        // A Job the server is already running (a cron or on_signal fire: RA's findings chain runs on job.run.completed) answers
        // 409, or accepts the fire and records it SKIPPED ("previous run still in flight"): wait, then fire it again.
        let r, st = null;
        for (let attempt = 0; attempt < 30; attempt++) {
            if (attempt) await sleep(1000);
            r = await call(s.as, s.method, s.path, s.body);
            if (r.status === 409 && s.kind !== 'sweep') continue;
            if (r.status >= 300 || s.kind === 'sweep') break;
            const poll = s.kind === 'pipeline' ? `/runs/runs/${r.data.runId}` : `/jobs/runs/${r.data.runId}`;
            st = null;
            for (let i = 0; i < pollTries && !finished(st?.status); i++) {
                await sleep(i === 0 ? 100 : 500);
                const g = await call(s.as, 'GET', poll);
                if (g.status === 200) st = g.data;
            }
            if (!(s.kind === 'job' && String(st?.status).toUpperCase() === 'SKIPPED')) break;
        }
        if (r.status >= 300) { out.failed.push(`${s.id}: ${r.status} ${JSON.stringify(r.data)}`); continue; }
        if (s.kind === 'sweep') { out.done.push(s.id); continue; }
        // SUCCESS only: a Pipeline run that throws is recorded FAILED with failed = 0, so "finished, no failed file" is not enough.
        const ok = String(st?.status).toUpperCase() === 'SUCCESS' && !(s.kind === 'pipeline' && st.failed > 0);
        if (ok) { out.done.push(s.id); log(`  ${s.id}: ${st.status}`); }
        else out.failed.push(`${s.id}: ${st ? `${st.status} ${st.message ?? ''}` : 'no finished run'}`);
    }
    const a = await call('fm.manager', 'GET', '/alerts?limit=500');
    const alerts = Array.isArray(a.data) ? a.data : (a.data?.items ?? []);
    for (const [app, rule] of Object.entries(EXPECTED_RULES)) {
        out.alerts[app] = alerts.filter((x) => x.rule === rule).length;
        if (!out.alerts[app]) out.failed.push(`no Alert of ${rule} (${app}) after the sweep`);
    }
    return out;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const args = process.argv.slice(2);
    const opt = (n, d) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : d; };
    if (args.includes('--scaffold')) {
        const root = opt('--scaffold');
        if (!root || root.startsWith('--')) { console.error('--scaffold needs the spaces root: node tools/seed-telco.mjs --scaffold <spacesRoot>'); process.exit(2); }
        const feeds = scaffold(root);
        console.log(feeds.length ? `scaffolded: ${SPACE} (inboxes: ${feeds.join(', ')})` : `nothing to do: ${SPACE} already exists`);
    } else {
        run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', SPACE), apply: args.includes('--apply'), log: console.log })
            .then((r) => {
                if (args.includes('--apply')) {
                    console.log(`done: ${r.done.length} call(s)\nAlerts: ${Object.entries(r.alerts).map(([k, v]) => `${k} ${v}`).join(', ')}\nfailed: ${r.failed.join('; ') || '-'}`);
                }
                process.exit(r.failed.length ? 1 : 0);
            })
            .catch((e) => { console.error(e.message); process.exit(1); });
    }
}
