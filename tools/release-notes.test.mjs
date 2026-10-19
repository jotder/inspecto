// Fixture test for tools/release-notes.mjs. Run: node --test tools/release-notes.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CROSS, attribute, index, parseLog, parseSubject, render } from './release-notes.mjs';

const idx = () => index({
    items: [
        { id: 'module.reconciliation', title: 'Reconciliation module' }, { id: 'addon.reconciliation', title: 'Reconciliation' },
        { id: 'addon.linkAnalysis', title: 'Link Analysis' }, { id: 'module.ops', title: 'Ops' }, { id: 'layer.la', title: 'LA' },
        { id: 'layer.product', title: 'Product' }, ...['a', 'b', 'c', 'd', 'e', 'f'].map((x) => ({ id: `module.${x}`, title: x })),
    ],
    moduleDirs: { 'features/inspecto-recon': 'reconciliation', 'features/inspecto-ops': 'ops', ...Object.fromEntries(['a', 'b', 'c', 'd', 'e', 'f'].map((x) => [`m/${x}`, x])) },
});
const log = (...cs) => cs.map(([subject, files = [], parents = 'p1', body = '']) => `\x1eh${subject.length}abcdefgh\x1f${parents}\x1f${subject}\x1f${body}\x1f\n\n${files.join('\n')}\n`).join('');
const run = (...cs) => attribute(parseLog(log(...cs)), idx());

test('a subject parses into type, scope, breaking and text', () => {
    assert.deepEqual(parseSubject('feat(reconciliation)!: x'), { type: 'feat', scope: 'reconciliation', breaking: true, text: 'x' });
    assert.equal(parseSubject('fix: y', 'BREAKING CHANGE: z').breaking, true);
    assert.equal(parseSubject('Merge lane').type, null);
});

test('a scope naming an item attributes by scope; module.* wins a name clash; id, kebab and alias forms match', () => {
    const { entries } = run(['feat(reconciliation): a', ['features/inspecto-ops/X.java']], ['fix(module.ops): b'], ['feat(link-analysis): c'], ['feat(recon): d']);
    assert.deepEqual(entries.map((e) => [e.items, e.by]), [[['module.reconciliation'], 'scope'], [['module.ops'], 'scope'], [['addon.linkAnalysis'], 'scope'], [['module.reconciliation'], 'scope']]);
});

test('an unknown scope falls back to module dirs, hub docs and product-map files', () => {
    const { entries } = run(['feat(okf): a', ['features/inspecto-recon/src/A.java', 'docs/okf/product/addon/link-analysis/spec.md', 'product-map/layer/la.toon', 'inspecto-ui/src/x.ts', 'README.md']]);
    assert.deepEqual(entries[0].items, ['addon.linkAnalysis', 'layer.la', 'layer.product', 'module.reconciliation']);
    assert.equal(entries[0].by, 'files');
    assert.deepEqual(run(['chore: x', ['README.md']]).entries[0].by, 'none');
});

test('merges and pure regenerations are skipped; a wide sweep is cross-cutting', () => {
    const r = run(['Merge lane x', ['m/a/x']], ['feat: m', ['m/a/x'], 'p1 p2'], ['docs(okf): regen', ['docs/okf/product/module/ops/index.md', 'docs/superpower/plan.md']],
        ['refactor: sweep', ['a', 'b', 'c', 'd', 'e', 'f'].map((x) => `m/${x}/F.java`)]);
    assert.deepEqual(r.skipped, { merge: 2, generated: 1 });
    assert.deepEqual(r.entries.map((e) => e.items), [[CROSS]]);
});

test('render groups by item then type, writes one file per item and reports coverage', () => {
    const i = idx();
    const { files, coverage } = render(attribute(parseLog(log(['feat(ops)!: big', []], ['fix(ops): small'], ['perf(recon): fast'], ['docs: nowhere', ['README.md']], ['chore(ops): hidden'])), i), i, { from: 'v1', to: 'v2' });
    assert.deepEqual(Object.keys(files).sort(), ['RELEASE-NOTES.md', 'module.ops.md', 'module.reconciliation.md']);
    const w = files['RELEASE-NOTES.md'];
    assert.ok(w.indexOf('## Ops (`module.ops`)') < w.indexOf('### BREAKING') && w.indexOf('### BREAKING') < w.indexOf('### Fixes'));
    assert.match(w, /## \(unattributed\)[\s\S]*nowhere/);
    assert.doesNotMatch(w, /hidden/);
    assert.deepEqual(coverage, { considered: 5, attributed: 4, wide: 0, pct: 80 });
    assert.match(render(attribute(parseLog(log(['feat(ops): a'], ['feat(recon): b'])), i), i, { only: 'module.ops' }).files['RELEASE-NOTES.md'], /^(?![\s\S]*Reconciliation)/);
});

test('check-commit-scope warns only for feat/fix/perf without an item or general scope', async () => {
    const { checkMessage } = await import('./check-commit-scope.mjs');
    const i = idx();
    assert.equal(checkMessage('feat(reconciliation): a', i), null);
    assert.equal(checkMessage('fix(ci): a', i), null);
    assert.equal(checkMessage('chore(whatever): a', i), null);
    assert.equal(checkMessage('# comment\nperf(module.ops): a', i), null);
    assert.match(checkMessage('feat(okf): a', i), /'okf' is not a Product Map Item/);
    assert.match(checkMessage('fix: a', i), /has no scope/);
});
