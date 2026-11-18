// Fixture test for tools/check-product-docs.mjs. Run: node --test tools/check-product-docs.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkArchitectureNames, checkHub, SLOTS } from './check-product-docs.mjs';

const hub = (rows = SLOTS.map((s) => [s, 'missing']), item = 'module.acquire') =>
    `---\nitem: ${item}\n---\n\n| Slot | Status | Document |\n|---|---|---|\n${rows.map(([s, t]) => `| ${s} | ${t} | x |`).join('\n')}\n`;
const map = 'id: module.acquire\ndocs: docs/okf/product/module/acquire/index.md\n';

test('a complete hub passes and reports its slots', () => {
    const r = checkHub('module', 'acquire', hub(), map);
    assert.deepEqual(r.findings, []);
    assert.equal(r.slots.brochure, 'missing');
});
test('a wrong item id is refused', () => {
    assert.match(checkHub('module', 'acquire', hub(undefined, 'module.etl'), map).findings.join(), /item 'module.etl'/);
});
test('a missing Product Map Item is refused', () => {
    assert.match(checkHub('module', 'acquire', hub(), undefined).findings.join(), /no product-map/);
});
test('product-map docs: must point at the hub', () => {
    assert.match(checkHub('module', 'acquire', hub(), 'id: module.acquire\n').findings.join(), /docs: must be/);
});
test('a dropped slot is refused', () => {
    assert.match(checkHub('module', 'acquire', hub(SLOTS.slice(1).map((s) => [s, 'missing'])), map).findings.join(), /slot rows/);
});
test('architecture-<hub>.md alone or with a subject page passes; no architecture page passes', () => {
    assert.deepEqual(checkArchitectureNames('module.engine', ['index.md', 'architecture-engine.md', 'architecture-stage1.md', 'spec-engine.md', 'feature-db.md']), []);
    assert.deepEqual(checkArchitectureNames('module.engine', ['index.md', 'spec-engine.md']), []);
});
test('a bare slot file name is refused; the generated feature-db.md is not', () => {
    const f = checkArchitectureNames('module.la-core', ['index.md', 'user-guide.md', 'feature-db.md', 'tests-inventory.md']).join();
    assert.match(f, /user-guide\.md — a bare slot name; name it user-guide-la-core\.md/);
    assert.doesNotMatch(f, /feature-db|tests-inventory/);
    assert.match(checkArchitectureNames('module.engine', ['architecture.md']).join(), /name it architecture-engine\.md/);
});
test('an overview or change-log architecture page is refused', () => {
    assert.match(checkArchitectureNames('module.graph', ['architecture-graph.md', 'architecture-overview.md']).join(), /'overview' is not a subject/);
    assert.match(checkArchitectureNames('layer.product', ['architecture-product.md', 'architecture-change-log.md']).join(), /'change-log'/);
});
test('topic pages without architecture-<hub>.md are refused', () => {
    assert.match(checkArchitectureNames('module.elt', ['architecture-parsing-ingest.md']).join(), /no architecture-elt\.md/);
});
test('an unknown status is refused', () => {
    assert.match(checkHub('module', 'acquire', hub(SLOTS.map((s) => [s, 'done'])), map).findings.join(), /unknown status 'done'/);
});
