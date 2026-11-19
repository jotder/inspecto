// Tests for tools/fill-doc-headers.mjs. Run: node --test tools/fill-doc-headers.test.mjs
//
// The `code` derivation is proven for every Product Map kind (and the kinds that link none leave it out); existing
// values are never changed; the body stays byte-identical, CRLF included; pages without the base header and generated
// pages are left alone; and a second run changes nothing — both on the pure planner and through the CLI in a throwaway
// git repository.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { copyFileSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { applyFields, codeFor, handbookAudiences, hubOf, missingFields, planFill } from './fill-doc-headers.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const CTX = {
    manifests: { ops: { dir: 'features/inspecto-ops' }, 'la-core': { dir: 'la/inspecto-la-core' }, 'la-api': { dir: 'la/inspecto-la-api' } },
    offerings: {
        basic: { addons: { linkAnalysis: { modules: ['la-core'] } } },
        standard: { addons: { linkAnalysis: { modules: ['la-api', 'la-core'] }, incidents: { modules: ['ops'] } } },
    },
    apps: { fraud: { templates: ['telco-fraud', 'telco-bypass'] } },
    industryPacks: { telecom: ['platform/x/META-INF/inspecto/industry-packs/telecom'] },
    isTracked: (p) => p !== 'spaces/_templates/telco-bypass',
};

test('code: derived from what the Product Map links, per kind; layers and unknown links leave it out', () => {
    assert.deepEqual(codeFor({ kind: 'module', module: 'ops' }, CTX), ['features/inspecto-ops']);
    assert.deepEqual(codeFor({ kind: 'module', module: 'nope' }, CTX), []);
    assert.deepEqual(codeFor({ kind: 'addon', offeringAddon: 'linkAnalysis' }, CTX), ['la/inspecto-la-api', 'la/inspecto-la-core']);
    assert.deepEqual(codeFor({ kind: 'addon', offeringAddon: 'planned' }, CTX), []);
    assert.deepEqual(codeFor({ kind: 'solution-pack', template: 'aml' }, CTX), ['spaces/_templates/aml']);
    assert.deepEqual(codeFor({ kind: 'solution-pack', industryPack: 'telecom' }, CTX), ['platform/x/META-INF/inspecto/industry-packs/telecom']);
    // an untracked template is dropped, never written
    assert.deepEqual(codeFor({ kind: 'app', app: 'fraud' }, CTX), ['apps/fraud', 'spaces/_templates/telco-fraud']);
    assert.deepEqual(codeFor({ kind: 'edition', offering: 'basic' }, CTX), ['offerings/basic.toon']);
    assert.deepEqual(codeFor({ kind: 'product', product: 'inspecto' }, CTX), ['products/inspecto.toon']);
    assert.deepEqual(codeFor({ kind: 'industry', industry: 'telecom' }, CTX), ['industries/telecom']);
    assert.deepEqual(codeFor({ kind: 'layer' }, CTX), []);
});

test('hubOf: only a page directly in a hub folder', () => {
    assert.deepEqual(hubOf('docs/okf/product/module/ops/spec-ops.md'), { kind: 'module', name: 'ops' });
    assert.equal(hubOf('docs/okf/product/module/ops/sub/spec.md'), null);
    assert.equal(hubOf('docs/BACKLOG.md'), null);
});

test('handbook audiences: every page entry with file + audience, at any depth', () => {
    const a = handbookAudiences('chapters:\n  start:\n    pages:\n      one:\n        file: a.md\n        audience: external\n      two:\n        file: b.md\n        audience: internal\n');
    assert.equal(a.get('a.md'), 'external');
    assert.equal(a.get('b.md'), 'internal');
    assert.equal(a.size, 2);
});

test('missing fields: existing values — even empty ones — are kept; only absent keys are added', () => {
    const page = { item: 'module.ops', audience: 'internal', status: 'current', lastEdit: '2026-11-01', code: ['features/inspecto-ops'] };
    assert.deepEqual(missingFields({ item: 'module.other', audience: 'external', status: 'history', code: [] }, page),
        { verified: '2026-11-01 last-edit' });
    assert.deepEqual(missingFields({}, { ...page, code: [], lastEdit: null }), { item: 'module.ops', audience: 'internal', status: 'current' });
});

test('apply: keys go just before the closing ---, the body is byte-identical (LF and CRLF)', () => {
    const body = '# T\n\n---\n\nbody with a rule above\n';
    const lf = '---\ntype: Spec\ntitle: T\ndescription: d\n---\n' + body;
    const out = applyFields(lf, { item: 'module.ops', code: ['a', 'b'] });
    assert.equal(out, '---\ntype: Spec\ntitle: T\ndescription: d\nitem: module.ops\ncode: [a, b]\n---\n' + body);
    const crlf = lf.replaceAll('\n', '\r\n');
    const outCrlf = applyFields(crlf, { code: ['a'] });
    assert.ok(outCrlf.endsWith(body.replaceAll('\n', '\r\n')));
    assert.ok(outCrlf.includes('description: d\r\ncode: a\r\n---\r\n'));
    assert.equal(applyFields(lf, {}), lf);
});

const PAGES = {
    'docs/okf/product/module/ops/spec-ops.md': '---\ntype: Spec\ntitle: Ops\ndescription: d\naudience: external\n---\n# Ops\n',
    'docs/okf/product/module/ops/plan-ops.md': '---\ntype: Plan\ntitle: P\ndescription: d\n---\n# P\n',
    'docs/okf/product/module/ops/architecture-ops.md': '# No header — lane 2 owns it\n',
    'docs/okf/product/module/ops/index.md': '---\ntype: Index\ntitle: I\ndescription: d\n---\n',
    'docs/okf/product/module/ops/api-routes.md': '---\ntype: Api\ntitle: A\ndescription: d\n---\n> **Generated** by `tools/render-api-routes.mjs`\n',
    'docs/okf/product/layer/platform/spec-x.md': '---\ntype: Spec\ntitle: X\ndescription: d\n---\n# X\n',
};
const plan = (pages, hub) => planFill({
    paths: Object.keys(pages), read: (p) => pages[p], hub,
    items: { 'module.ops': { kind: 'module', module: 'ops' }, 'layer.platform': { kind: 'layer' } },
    audiences: new Map([['docs/okf/product/module/ops/plan-ops.md', 'external']]),
    lastEdit: () => '2026-11-02', ctx: CTX,
});

test('plan: fills only base-header, hand-written pages; plan-* gets status plan; handbook says the audience', () => {
    const r = plan(PAGES);
    assert.deepEqual(r.changes.map((c) => c.path).sort(), [
        'docs/okf/product/layer/platform/spec-x.md', 'docs/okf/product/module/ops/plan-ops.md', 'docs/okf/product/module/ops/spec-ops.md']);
    const by = Object.fromEntries(r.changes.map((c) => [c.path, c.add]));
    assert.deepEqual(by['docs/okf/product/module/ops/plan-ops.md'],
        { item: 'module.ops', audience: 'external', status: 'plan', verified: '2026-11-02 last-edit', code: ['features/inspecto-ops'] });
    assert.equal(by['docs/okf/product/module/ops/spec-ops.md'].audience, undefined, 'existing audience kept');
    assert.equal(by['docs/okf/product/layer/platform/spec-x.md'].code, undefined, 'a layer gets no code');
    assert.deepEqual([...r.noCode], ['layer.platform']);
    assert.deepEqual(plan(PAGES, 'module/ops').changes.length, 2, '--hub narrows to one hub');
});

test('idempotent: a second plan over the filled pages changes nothing', () => {
    const filled = { ...PAGES };
    for (const c of plan(PAGES).changes) filled[c.path] = c.text;
    assert.deepEqual(plan(filled).changes, []);
});

test('CLI: --write fills a real repo, bodies untouched, a second run is a no-op', () => {
    const dir = mkdtempSync(join(tmpdir(), 'fill-doc-headers-'));
    try {
        const w = (p, s) => { mkdirSync(dirname(join(dir, p)), { recursive: true }); writeFileSync(join(dir, p), s); };
        mkdirSync(join(dir, 'tools'));
        for (const f of ['fill-doc-headers.mjs', 'docs-health.mjs', 'toon-subset.mjs', 'bundle-modules.mjs']) copyFileSync(join(HERE, f), join(dir, 'tools', f));
        w('features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon', 'id: ops\nofferingRole: optional\n');
        w('offerings/basic.toon', 'id: basic\nmodules[1]: ops\n');
        w('product-map/module/ops.toon', 'id: module.ops\nkind: module\ntitle: Ops\nmodule: ops\n');
        w('docs/okf/product/layer/product/handbook.toon', 'chapters:\n  c:\n    pages:\n      p:\n        file: docs/okf/product/module/ops/spec-ops.md\n        audience: external\n');
        const page = 'docs/okf/product/module/ops/spec-ops.md';
        const body = '# Ops\n\nThe body.\n';
        w(page, '---\ntype: Spec\ntitle: Ops\ndescription: d\n---\n' + body);
        w('docs/okf/product/module/ops/architecture-ops.md', '# headerless\n');
        const git = (...a) => execFileSync('git', a, { cwd: dir, encoding: 'utf8' });
        git('init', '-q'); git('config', 'user.email', 't@t'); git('config', 'user.name', 't'); git('config', 'core.autocrlf', 'false');
        git('add', '.'); git('commit', '-qm', 'init', '--date', '2026-01-02T00:00:00');
        const run = (...a) => spawnSync(process.execPath, ['tools/fill-doc-headers.mjs', ...a], { cwd: dir, encoding: 'utf8' });
        const dry = run();
        assert.equal(dry.status, 0, dry.stderr);
        assert.match(dry.stdout, /1 page\(s\) would gain fields/);
        assert.equal(readFileSync(join(dir, page), 'utf8').includes('item:'), false, 'dry run writes nothing');
        assert.equal(run('--write').status, 0);
        const date = git('log', '-1', '--format=%cs').trim();
        assert.equal(readFileSync(join(dir, page), 'utf8'),
            `---\ntype: Spec\ntitle: Ops\ndescription: d\nitem: module.ops\naudience: external\nstatus: current\nverified: ${date} last-edit\ncode: features/inspecto-ops\n---\n` + body);
        assert.equal(readFileSync(join(dir, 'docs/okf/product/module/ops/architecture-ops.md'), 'utf8'), '# headerless\n');
        assert.match(run('--write').stdout, /0 page\(s\) filled/);
        // a page staged but not committed yet is dated today, so the printed fix leaves nothing missing
        w('docs/okf/product/module/ops/spec-new.md', '---\ntype: Spec\ntitle: N\ndescription: d\n---\n# N\n');
        git('add', '.');
        assert.equal(run('--write').status, 0);
        assert.match(readFileSync(join(dir, 'docs/okf/product/module/ops/spec-new.md'), 'utf8'), /\nverified: \d{4}-\d{2}-\d{2} last-edit\n/);
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
