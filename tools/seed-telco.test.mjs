// node --test tools/seed-telco.test.mjs - the Inspecto Telco demo seed (PRODUCT-ASSEMBLY-1 PA-5d): the scaffold against the
// committed templates, and the --apply calls against a MOCKED server. The live proof is the demo bake (package.ps1 -Product
// telco -Edition Advanced -Distribution demo), recorded in the plan's §11.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BYPASS_JOBS, DAYS, EXPECTED_RULES, FM_JOBS, ROSTER, SPACE, buildPlan, run, scaffold } from './seed-telco.mjs';

function mock({ jobStatus = 'SUCCESS', pipelineRun = { status: 'SUCCESS', failed: 0 }, alerts = Object.values(EXPECTED_RULES).map((rule) => ({ rule })), busyOnce = null, skippedOnce = null } = {}) {
    const calls = [];
    let n = 0, busy = busyOnce, skipRun = null;
    const fetchFn = async (url, init = {}) => {
        const method = init.method || 'GET';
        const u = url.replace('http://h/api/v1', '');
        calls.push({ method, u, auth: init.headers?.authorization, body: init.body ? JSON.parse(init.body) : undefined });
        let status = 200, data = {};
        if (u === '/auth/exchange') data = { accessToken: `tok-${JSON.parse(init.body).code}` };
        else if (u === '/spaces/s/pipelines') data = [{ name: 'telecom_usage' }, { name: 'tariff' }];
        else if (busy && u === `/spaces/s/jobs/${busy}/trigger`) { busy = null; status = 409; }
        else if (u.endsWith('/trigger')) {
            status = 202; data = { runId: `r${++n}` };
            if (skippedOnce && u === `/spaces/s/jobs/${skippedOnce}/trigger`) { skipRun = data.runId; skippedOnce = null; }
        }
        else if (skipRun && u === `/spaces/s/jobs/runs/${skipRun}`) { skipRun = null; data = { status: 'SKIPPED', message: 'previous run still in flight' }; }
        else if (u.startsWith('/spaces/s/runs/runs/')) data = pipelineRun;
        else if (u.startsWith('/spaces/s/jobs/runs/')) data = { status: jobStatus, message: jobStatus === 'SUCCESS' ? '' : 'boom' };
        else if (u.startsWith('/spaces/s/alerts?')) data = alerts;
        return { ok: status < 300, status, json: async () => ({ data }) };
    };
    return { fetchFn, calls };
}
const opts = (m) => ({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: true, sleep: async () => {} });

test('the plan ingests every feed, then runs each App\'s Jobs for each corpus day, then sweeps the Alert Rules', () => {
    const plan = buildPlan(['telecom_usage', 'tariff']);
    assert.deepEqual(plan.slice(0, 2).map((s) => s.path), ['/runs/telecom_usage/trigger', '/runs/tariff/trigger']);
    assert.equal(plan.at(-1).path, '/alerts/evaluate');
    const jobs = plan.filter((s) => s.kind === 'job');
    assert.equal(jobs.filter((s) => s.name === 'ra_xdr_completeness').length, DAYS.length, 'RA completeness once per day');
    for (const j of [...FM_JOBS, ...BYPASS_JOBS])
        assert.deepEqual(jobs.filter((s) => s.name === j).map((s) => s.body.params.day), DAYS, `${j}: every corpus day`);
    assert.deepEqual(jobs.find((s) => s.id === 'fraud_irsf-2026-07-02').body.params,
        { window_start: '2026-07-02 00:00:00', window_end: '2026-07-03 00:00:00', day: '2026-07-02' });
    const ra = jobs.findIndex((s) => s.name === 'ra_leakage'), fm = jobs.findIndex((s) => s.name === 'fraud_adjustment'), tb = jobs.findIndex((s) => s.name === 'tb_findings');
    assert.ok(ra < fm && fm < tb, 'RA, then FM, then bypass');
    assert.ok(BYPASS_JOBS.indexOf('tb_risk_score') < BYPASS_JOBS.indexOf('tb_findings'), 'findings read the day\'s risk scores');
});

test('dry run sends nothing and prints every call', async () => {
    const m = mock(); const lines = [];
    await run({ fetchFn: m.fetchFn, base: 'http://h', space: 's', apply: false, log: (l) => lines.push(l) });
    assert.equal(m.calls.length, 0);
    assert.ok(lines.length > 60);
});

test('apply signs in once per Demo User, waits on each run, and checks an Alert per App', async () => {
    const m = mock();
    const r = await run(opts(m));
    assert.deepEqual(r.failed, []);
    assert.deepEqual(r.alerts, Object.fromEntries(Object.keys(EXPECTED_RULES).map((k) => [k, 1])));
    const signIns = m.calls.filter((c) => c.u === '/auth/exchange').map((c) => c.body.code).sort();
    assert.deepEqual(signIns, ['demo:fm.analyst', 'demo:fm.manager', 'demo:ra.analyst']);
    assert.ok(m.calls.filter((c) => c.u !== '/auth/exchange').every((c) => c.auth?.startsWith('Bearer tok-demo:')), 'every Space call is signed');
    assert.ok(m.calls.every((c) => !JSON.stringify(c.body ?? {}).includes('password')), 'no credential is sent');
    assert.equal(m.calls.filter((c) => c.u.startsWith('/spaces/s/jobs/runs/')).length, buildPlan(['a', 'b']).filter((s) => s.kind === 'job').length);
});

test('a busy or skipped Job is fired again, a failed run and a missing App Alert fail the seed', async () => {
    const busy = mock({ busyOnce: 'ra_leakage' });
    assert.deepEqual((await run(opts(busy))).failed, []);
    assert.equal(busy.calls.filter((c) => c.u === '/spaces/s/jobs/ra_leakage/trigger').length, 2, '409 (already running): retried');
    const skipped = mock({ skippedOnce: 'ra_data_quality' });
    assert.deepEqual((await run(opts(skipped))).failed, []);
    assert.equal(skipped.calls.filter((c) => c.u === '/spaces/s/jobs/ra_data_quality/trigger').length, 2, 'SKIPPED (its on_signal run in flight): fired again');
    // A Pipeline run that throws is recorded FAILED with failed = 0 (CollectorService): it must fail the seed too.
    const crashed = await run(opts(mock({ pipelineRun: { status: 'FAILED', failed: 0, message: 'boom' } })));
    assert.ok(crashed.failed.some((f) => f.startsWith('ingest-telecom_usage: FAILED boom')), crashed.failed.join('\n'));
    assert.ok(!crashed.done.includes('ingest-telecom_usage'));
    const partial = await run(opts(mock({ pipelineRun: { status: 'SUCCESS', failed: 1 } })));
    assert.ok(partial.failed.some((f) => f.startsWith('ingest-tariff')), 'a run with a failed file fails the seed');
    const failing = await run(opts(mock({ jobStatus: 'FAILED' })));
    assert.ok(failing.failed.some((f) => f.startsWith('ra_xdr_lost: FAILED boom')));
    const quiet = await run(opts(mock({ alerts: [{ rule: 'fraud_irsf' }] })));
    assert.deepEqual(quiet.failed.filter((f) => f.startsWith('no Alert')), ['no Alert of ra_leakage_found (Revenue Assurance) after the sweep', 'no Alert of tb_box_suspected (SIM-box bypass) after the sweep']);
});

test('scaffold lays down the composed Space with its samples in the inboxes and the Demo Users, once', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'seed-telco-'));
    try {
        const feeds = scaffold(root);
        const base = path.join(root, SPACE);
        assert.ok(feeds.includes('telecom_usage') && !feeds.includes('cdr') && !feeds.includes('switch_xdr'), `one usage feed: ${feeds}`);
        assert.ok(fs.existsSync(path.join(base, 'config/telecom_usage/telecom_usage_pipeline.toon')));
        assert.ok(!fs.existsSync(path.join(base, 'config/cdr')), 'FM\'s own usage Pipeline is swapped out');
        for (const f of feeds)
            assert.deepEqual(fs.readdirSync(path.join(base, 'data/inbox', f)), fs.readdirSync(path.join(base, 'data/samples', f)), `${f}: every sample is in its inbox`);
        assert.equal(fs.readFileSync(path.join(base, 'config/demo-users.toon'), 'utf8'), ROSTER);
        for (const f of ['payments', 'subscriber_events']) {
            const p = fs.readFileSync(path.join(base, 'config', f, `${f}_pipeline.toon`), 'utf8');
            assert.doesNotMatch(p, /gap_detection|file_template/, `${f}: no daily-file watch over a fixed-date corpus`);
            assert.doesNotMatch(p, /^collector:\s*$(?![\s\S]*^  \S)/m, `${f}: no empty collector key is left`);
            assert.match(p, /^processing:\r?$/m, `${f}: the rest of the Pipeline stays`);
        }
        const tpl = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'spaces/_templates/telco-fraud/config/payments/payments_pipeline.toon'), 'utf8');
        assert.match(tpl, /^    enabled: true$/m, 'the template keeps its watch');
        for (const sub of ['audit', 'duckdb']) assert.ok(fs.statSync(path.join(base, sub)).isDirectory());
        assert.match(fs.readFileSync(path.join(base, 'space.toon'), 'utf8'), /^display_name: Inspecto Telco$/m);
        assert.ok(!fs.existsSync(path.join(base, 'template.toon')), 'a Space, not a template');
        assert.deepEqual(scaffold(root), [], 'an existing Space is left alone');
        assert.deepEqual(fs.readdirSync(root), [SPACE], 'nothing else is written to the spaces root');
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('the roster names each Demo User the plan signs in as, with built-in roles only', () => {
    const ids = [...ROSTER.matchAll(/^  ([a-z.]+),/gm)].map((m) => m[1]);
    for (const s of buildPlan(['x'])) assert.ok(ids.includes(s.as), `${s.as} is a Demo User`);
    assert.ok(ids.includes('admin'));
});
