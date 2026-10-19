// Fixture test for tools/check-docs-touched.mjs. Run: node --test tools/check-docs-touched.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { analyse, itemOf, likelySlots, parseLog } from './check-docs-touched.mjs';

const dirs = [['features/ops-ui', 'module/ops-ui'], ['features/ops', 'module/ops'], ['platform/engine', 'module/engine']];
const ui = new Set(['ops', 'engine', 'risk']);
const commit = (sha, ...files) => ({ sha, subject: `s${sha}`, files: files.map(([path, ...added]) => ({ path, added })) });

test('maps main code to its item, longest module dir first; ignores tests and unmapped files', () => {
    assert.equal(itemOf('features/ops-ui/src/main/java/A.java', dirs, ui), 'module/ops-ui');
    assert.equal(itemOf('features/ops/src/main/java/A.java', dirs, ui), 'module/ops');
    assert.equal(itemOf('features/ops/src/test/java/ATest.java', dirs, ui), null);
    assert.equal(itemOf('inspecto-ui/src/app/inspecto/risk/risk.component.ts', dirs, ui), 'module/risk');
    assert.equal(itemOf('inspecto-ui/src/app/inspecto/risk/risk.component.spec.ts', dirs, ui), null);
    assert.equal(itemOf('inspecto-ui/src/app/inspecto/grid/grid.ts', dirs, ui), null);
    assert.equal(itemOf('README.md', dirs, ui), null);
});

test('slot heuristics', () => {
    assert.deepEqual([...likelySlots('x/src/main/java/A.java', ['api.post("/v1/ops/x", h);'])], ['api']);
    assert.deepEqual([...likelySlots('x/src/main/java/A.java', ['int n = Integer.getInteger("ops.max", 5);'])].sort(), ['admin-guide', 'spec']);
    assert.deepEqual([...likelySlots('x/src/main/java/a/spi/Foo.java', ['public interface Foo {'])].sort(), ['api', 'spec']);
    assert.deepEqual([...likelySlots('inspecto-ui/src/app/inspecto/risk/r.ts', ['x'])], ['user-guide']);
    assert.deepEqual([...likelySlots('x/src/main/java/A.java', ['int i = 1;'])], []);
});

test('warns only for items whose hub got no hand-written change', () => {
    const ws = analyse([
        commit('a1', ['features/ops/src/main/java/A.java', 'api.get("/v1/ops", h);']),
        commit('b2', ['platform/engine/src/main/java/E.java', 'x'], ['docs/okf/product/module/engine/spec-x.md', 'y']),
        commit('c3', ['features/ops/src/main/java/B.java', 'System.getProperty("ops.dir")'], ['docs/okf/product/module/ops/index.md', 'gen'],
            ['docs/okf/product/module/ops/feature-db.md', 'gen']),
    ], dirs, ui);
    assert.deepEqual(ws, [{ item: 'module/ops', hub: 'docs/okf/product/module/ops/', commits: [{ sha: 'a1', subject: 'sa1' }, { sha: 'c3', subject: 'sc3' }],
        slots: ['admin-guide', 'api', 'spec'] }]);
});

test('parses git log -p output', () => {
    const log = '\x1eabcdef0123456789\x1ffeat: x\n\ndiff --git a/f/src/main/A.java b/f/src/main/A.java\n--- a/f\n+++ b/f\n@@ -0,0 +1 @@\n+added line\n-removed\n';
    assert.deepEqual(parseLog(log), [{ sha: 'abcdef012', subject: 'feat: x', files: [{ path: 'f/src/main/A.java', added: ['added line'] }] }]);
});
