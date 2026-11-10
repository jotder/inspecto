// node --test tools/inspecto-app.test.mjs - the App authoring kit (PRODUCT-ASSEMBLY-1 PA-6a, D-PA18 / D-PA19).
// The boot half of `test --bundle` is proven against a real bundle in the plan's §11; here its HTTP half runs on a mock.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { STARTER, goldenConformance, packageApp, scaffold, scaffoldSpace, staticConformance, validate, zip } from './inspecto-app.mjs';
import { assemble } from './assemble.mjs';
import { gather } from './check-offerings.mjs';
import { gatherModel } from './products.mjs';

const repo = join(import.meta.dirname, '..');
const withApp = (fn, id = 'acme-limits') => {
    const dir = mkdtempSync(join(tmpdir(), 'inspecto-app-'));
    try { const root = join(dir, id); scaffold(id, root, { title: 'Acme Limits' }); return fn(root, dir); }
    finally { rmSync(dir, { recursive: true, force: true }); }
};

test('new scaffolds an App that validates and passes static conformance on every Edition, as created', () => withApp((root) => {
    assert.deepEqual(validate(root), []);
    const r = staticConformance(root);
    assert.deepEqual(r.findings, []);
    assert.deepEqual(r.editions, ['basic', 'standard', 'advanced', 'preview']);
    assert.ok(existsSync(join(root, 'templates/acme-limits/data/samples/acme_limits_events/ACME_LIMITS_EVENTS_20260701.csv')));
    assert.throws(() => scaffold('acme-limits', root), /not empty/);
    assert.throws(() => scaffold('Acme', join(root, 'x')), /lower-case/);
}));

test('the starter plants exactly its findings: the accounts over the threshold, never the one AT it', () => {
    const sums = new Map();
    for (const [a, v] of STARTER.events) sums.set(a, (sums.get(a) ?? 0) + v);
    assert.deepEqual([...sums].filter(([, v]) => v > STARTER.threshold).map(([a]) => a).sort(), STARTER.planted);
    assert.equal(sums.get('A-104'), STARTER.threshold, 'a look-alike exactly at the threshold stays silent');
});

test('the assembler carries the partner App and stages its template from its folder', () => withApp((root) => {
    const model = gatherModel(repo, { appRoots: [root] });
    const p = model.products.inspecto;
    p.apps.optional = [...p.apps.optional, 'acme-limits'];
    const plan = assemble(model, gather(repo), { product: 'inspecto', edition: 'standard', apps: ['acme-limits'] });
    assert.ok(plan.templates.includes('acme-limits'));
    assert.deepEqual(plan.partnerTemplates, [{ id: 'acme-limits', dir: join(root, 'templates', 'acme-limits') }]);
    assert.ok(plan.descriptor.apps.some((a) => a.id === 'acme-limits' && a.nav.length === 1));
    assert.ok(!plan.partnerTemplates.some((t) => t.id === 'telco-ra'), 'a first-party template stays a git-staged one');
}));

test('validate names what does not hold: a reused id or template, a broken conformance file, missing golden data', () => {
    const dir = mkdtempSync(join(tmpdir(), 'inspecto-app-'));
    try {
        const dup = join(dir, 'dup');
        scaffold('link-analysis', dup);
        assert.ok(validate(dup).some((f) => /App id 'link-analysis' is already a first-party App/.test(f)));
        // An App of its own id whose template is named like a first-party template (telco-ra): the TEMPLATE rule refuses it.
        const tpl = join(dir, 'tpl');
        scaffold('acme-tpl', tpl);
        renameSync(join(tpl, 'templates', 'acme-tpl'), join(tpl, 'templates', 'telco-ra'));
        for (const [f, from, to] of [['app.toon', 'templates[1]: acme-tpl', 'templates[1]: telco-ra'], ['conformance.toon', 'template: acme-tpl', 'template: telco-ra']])
            writeFileSync(join(tpl, f), readFileSync(join(tpl, f), 'utf8').replace(from, to));
        const tf = validate(tpl);
        assert.ok(tf.some((f) => /template 'telco-ra' of App 'acme-tpl' is already a first-party template/.test(f)), tf.join('\n'));
        assert.ok(!tf.some((f) => /App id .* is already/.test(f)), 'the App id itself is new');
        // Two partner Apps shipping one template name: the second is refused, not silently overwritten.
        const two = gatherModel(repo, { appRoots: [join(dir, 'dup2a'), join(dir, 'dup2b')].map((r, i) => { scaffold(`acme-${i}`, r); renameSync(join(r, 'templates', `acme-${i}`), join(r, 'templates', 'shared-tpl')); return r; }) });
        assert.ok(two.collisions.some((f) => /template 'shared-tpl' is already shipped by the partner App 'acme-0'/.test(f)), two.collisions.join('\n'));

        const app = join(dir, 'acme');
        scaffold('acme', app);
        const cf = join(app, 'conformance.toon');
        writeFileSync(cf, 'template: nope\nday: July\njobs[1]: ghost\nexpectations:\n  ghost_findings:\n    key: account_id\n');
        const f = validate(app).join('\n');
        for (const re of [/template 'nope' is not one of the App's templates/, /day 'July' is not a YYYY-MM-DD date/,
            /Job 'ghost' has no config\/jobs\/ghost_job.toon/, /'ghost_findings' names no Dataset/, /'ghost_findings' has no expected\[N\] list/])
            assert.match(f, re);
        rmSync(cf);
        assert.ok(validate(app).some((x) => /no conformance.toon/.test(x)));
        writeFileSync(cf, 'template: acme\nday: 2026-07-01\njobs[0]:\nexpectations:\n  acme_findings:\n    key: account_id\n    expected[0]:\n');
        const feedDir = join(app, 'templates', 'acme', 'data', 'samples', 'acme_events');
        for (const f of readdirSync(feedDir)) rmSync(join(feedDir, f));
        assert.ok(validate(app).some((x) => /an empty feed folder/.test(x)), 'an empty feed folder is no golden data');
        rmSync(join(app, 'templates', 'acme', 'data'), { recursive: true });
        writeFileSync(cf, 'template: acme\nday: 2026-07-01\njobs[0]:\nexpectations:\n  acme_findings:\n    key: account_id\n    expected[0]:\n');
        assert.ok(validate(app).some((x) => /no data\/samples/.test(x)));
        assert.ok(staticConformance(app).findings.length > 0, 'static conformance refuses what does not validate');
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('golden conformance triggers every Pipeline and the listed Jobs for the corpus day, then holds the keys exactly', async () => {
    const calls = [];
    let n = 0;
    const mock = (rows, jobStatus = 'SUCCESS') => async (url, init = {}) => {
        const u = url.replace('http://h/api/v1/spaces/s', '');
        calls.push({ method: init.method, u, body: init.body ? JSON.parse(init.body) : undefined });
        let status = 200, data = {};
        if (u === '/pipelines') data = [{ name: 'acme_events' }];
        else if (u.endsWith('/trigger')) { status = 202; data = { runId: `r${++n}` }; }
        else if (u.startsWith('/runs/runs/')) data = { status: 'SUCCESS', failed: 0 };
        else if (u.startsWith('/jobs/runs/')) data = { status: jobStatus, message: 'x' };
        else if (u.startsWith('/datasets/acme_findings/rows')) data = { columns: [{ name: 'account_id' }, { name: 'total' }], rows };
        return { status, json: async () => ({ data }) };
    };
    const conformance = { day: '2026-07-01', jobs: ['acme_detect'], expectations: { acme_findings: { key: 'account_id', expected: ['A-001', 'A-002'] } } };
    const opts = (fetchFn) => ({ fetchFn, base: 'http://h', space: 's', conformance, sleep: async () => {} });
    assert.deepEqual(await goldenConformance(opts(mock([['A-002', 1], ['A-001', 2]]))), []);
    assert.deepEqual(calls.find((c) => c.u === '/jobs/acme_detect/trigger').body,
        { params: { window_start: '2026-07-01 00:00:00', window_end: '2026-07-02 00:00:00', day: '2026-07-01' } });
    assert.ok(calls.some((c) => c.u === '/runs/acme_events/trigger'), 'every Pipeline is triggered');
    const extra = await goldenConformance(opts(mock([{ account_id: 'A-001' }, { account_id: 'A-002' }, { account_id: 'A-104' }])));
    assert.match(extra.join(), /holds A-001, A-002, A-104; conformance expects exactly A-001, A-002/);
    assert.match((await goldenConformance(opts(mock([], 'FAILED')))).join(), /Job acme_detect: FAILED/);
    const signIn = async () => ({ status: 401, json: async () => ({}) });
    assert.match((await goldenConformance(opts(signIn))).join(), /asks for sign-in \(401\)/);
});

test('scaffoldSpace lays a Space down as created from the template, samples in the inboxes', () => withApp((root, dir) => {
    const feeds = scaffoldSpace(join(dir, 'spaces'), 'kit-acme-limits', join(root, 'templates', 'acme-limits'));
    assert.deepEqual(feeds, ['acme_limits_events']);
    for (const p of ['config/jobs/acme_limits_detect_job.toon', 'data/inbox/acme_limits_events/ACME_LIMITS_EVENTS_20260701.csv', 'space.toon', 'audit', 'duckdb'])
        assert.ok(existsSync(join(dir, 'spaces', 'kit-acme-limits', p)), p);
}));

test('package writes a deterministic zip with a SHA-256 manifest, and refuses an App that does not validate', () => withApp((root, dir) => {
    const a = packageApp(root, join(dir, 'a'));
    const b = packageApp(root, join(dir, 'b'));
    assert.match(a.file, /acme-limits-0\.1\.0\.zip$/);
    assert.deepEqual(readFileSync(a.file), readFileSync(b.file), 'the same App packages to the same bytes');
    const buf = readFileSync(a.file);
    const names = [];
    for (let i = 0; i + 4 <= buf.length; ) {
        if (buf.readUInt32LE(i) !== 0x04034b50) break;
        const size = buf.readUInt32LE(i + 18), nl = buf.readUInt16LE(i + 26);
        const name = buf.subarray(i + 30, i + 30 + nl).toString();
        names.push(name);
        if (name.endsWith('MANIFEST.sha256')) assert.match(inflateRawSync(buf.subarray(i + 30 + nl, i + 30 + nl + size)).toString(), /^[0-9a-f]{64} {2}acme-limits\/app\.toon$/m);
        i += 30 + nl + size;
    }
    assert.ok(names.includes('acme-limits/app.toon') && names.includes('acme-limits/conformance.toon'));
    // A partner folder is usually a git repository: its .git (and node_modules, dist/) never ship.
    for (const f of ['.git/HEAD', '.git/hooks/pre-commit', 'node_modules/x/index.js', 'dist/old.txt']) {
        mkdirSync(dirname(join(root, f)), { recursive: true });
        writeFileSync(join(root, f), 'x');
    }
    const g = readFileSync(packageApp(root, join(dir, 'g')).file).toString('latin1');
    for (const bad of ['.git/', 'node_modules/', 'dist/old.txt']) assert.ok(!g.includes(`acme-limits/${bad}`), `${bad} is left out`);
    rmSync(join(root, 'conformance.toon'));
    assert.throws(() => packageApp(root, join(dir, 'c')), /does not validate/);
    assert.equal(zip([]).readUInt32LE(0), 0x06054b50, 'an empty archive is just the end record');
}));
