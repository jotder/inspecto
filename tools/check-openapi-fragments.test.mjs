// Negative fixtures for tools/check-openapi-fragments.mjs (+ the split/merge round trip on the REAL document).
// Run: node --test tools/check-openapi-fragments.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { FRAGMENT, MANIFEST, mergeFragments, pathsOf, readManifests, routeShape, splitDocument } from './openapi-fragments.mjs';
import { parseJson } from './openapi-json.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const GUARD = join(REPO, 'tools', 'check-openapi-fragments.mjs');
const OPS = 'features/inspecto-ops';
const SCORING = 'features/inspecto-scoring';

/** A fixture tree: the real document, every fragment and every manifest, then optionally mutated. */
function fixture(edit = () => {}) {
    const root = mkdtempSync(join(tmpdir(), 'openapi-fragments-'));
    const copy = (rel) => { mkdirSync(dirname(join(root, rel)), { recursive: true }); cpSync(join(REPO, rel), join(root, rel)); };
    copy('docs/api/openapi-v1.json');
    for (const m of readManifests(REPO)) { copy(join(m.dir, MANIFEST)); try { copy(join(m.dir, FRAGMENT)); } catch { /* none */ } }
    edit((rel, fn) => {
        const f = join(root, rel);
        const before = readFileSync(f, 'utf8');
        const after = fn(before);
        assert.notEqual(after, before, `fixture anchor missing from ${rel}`);
        writeFileSync(f, after);
    });
    return root;
}
const run = (root) => spawnSync(process.execPath, [GUARD], { encoding: 'utf8', env: { ...process.env, OPENAPI_FRAGMENTS_ROOT: root } });

test('the real tree is green', () => {
    const r = run(REPO);
    assert.equal(r.status, 0, r.stderr);
});

test('merge(split(document)) is byte-identical, for the real document, whatever the assignment', () => {
    const doc = readFileSync(join(REPO, 'docs/api/openapi-v1.json'), 'utf8');
    const shapes = new Map();
    for (const m of readManifests(REPO)) for (const r of m.routes) shapes.set(r.shape, m.id);
    for (const owner of [(k) => shapes.get(routeShape(k)) ?? null, (k) => (k.length % 3 ? 'a' : k.length % 2 ? 'b' : null)]) {
        const { core, modules } = splitDocument(doc, owner);
        const merged = mergeFragments(core, [...modules].reverse().map(([name, text]) => ({ name, text })));
        assert.equal(merged, doc);
        assert.equal(parseJson(merged).get('paths').size, pathsOf(doc).entries.length);
    }
});

test('a hand-edited document goes red', () => {
    const root = fixture((edit) => edit('docs/api/openapi-v1.json', (t) => t.replace('"summary" : ', '"summary" : "x" , "_": ')));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /not the merge of the fragments/);
});

test('a path in two fragments goes red', () => {
    const ops = join(REPO, OPS, FRAGMENT);
    const first = pathsOf(readFileSync(ops, 'utf8')).entries[0];
    const root = fixture((edit) => edit(join(SCORING, FRAGMENT), (t) => t.replace('  "paths" : {\n', `  "paths" : {\n${first.text},\n`)));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /path in two fragments|not declared by provides\.routes/);
});

test('a path the manifest does not declare goes red', () => {
    const root = fixture((edit) => edit(join(SCORING, FRAGMENT), (t) => t.replace('"/risk-scores/preview"', '"/risk-scores/other"')));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /not declared by provides\.routes of scoring/);
});

test('a module that declares routes but ships no fragment goes red', () => {
    const root = fixture((edit) => edit(join(SCORING, FRAGMENT), () => '{\n  "paths" : {\n  }\n}\n'));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /has no path in the fragment/);
});

test('a fragment with a member other than "paths" goes red', () => {
    const root = fixture((edit) => edit(join(SCORING, FRAGMENT), (t) => t.replace('{\n  "paths" : {', '{\n  "components" : { },\n  "paths" : {')));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /must be exactly "paths"/);
});

test('mergeFragments refuses a path that two fragments both carry', () => {
    const doc = readFileSync(join(REPO, 'docs/api/openapi-v1.json'), 'utf8');
    const { core, modules } = splitDocument(doc, (k) => (k === '/health' ? 'a' : null));
    const a = modules.get('a');
    assert.throws(() => mergeFragments(core, [{ name: 'a', text: a }, { name: 'b', text: a }]), /path in two fragments/);
});
