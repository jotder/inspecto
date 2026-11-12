// Tests for tools/product-documents.mjs (PRODUCT-ASSEMBLY-1 PA-10d, D-PA36). Run: node --test tools/product-documents.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { GENERATED_SLOTS, MARK, productDocuments, refuseHandWritten } from './product-documents.mjs';

const docs = productDocuments('.');
const products = readdirSync('products').filter((f) => f.endsWith('.toon')).map((f) => f.slice(0, -5));

test('every Product gets its four generated documents, each marked generated and committed as generated', () => {
    for (const id of products) for (const slot of GENERATED_SLOTS) {
        const path = `docs/okf/product/product/${id}/${slot}.md`;
        assert.ok(docs[path]?.includes(MARK), `${path} is generated`);
        assert.equal(readFileSync(path, 'utf8'), docs[path], `${path} is current (node tools/render-product-docs.mjs)`);
    }
    assert.equal(Object.keys(docs).length, products.length * GENERATED_SLOTS.length);
});
test('every link in a generated document resolves to a file', () => {
    for (const [path, text] of Object.entries(docs)) {
        for (const [, target] of text.matchAll(/\]\(([^)#]+)\)/g)) assert.ok(existsSync(join(dirname(path), target)), `${path} -> ${target}`);
    }
});
test('the Telco brochure and datasheet are composed from its definition and its Apps', () => {
    const b = docs['docs/okf/product/product/telco/brochure.md'];
    assert.match(b, /\[Revenue Assurance\]\(\.\.\/\.\.\/app\/telco-ra\/index\.md\)\*\* \(required\)/);
    assert.match(b, /\[Fraud Management\]\(\.\.\/\.\.\/app\/telco-fraud\/index\.md\)\*\* \(optional\)/);
    assert.match(b, /Editions: \[Standard\].*\[Advanced\]/);
    const f = docs['docs/okf/product/product/telco/features.md'];
    assert.match(f, /\| \[Standard\]\(\.\.\/\.\.\/edition\/standard\/index\.md\) \| yes \| yes \| yes \| yes \|/);
    assert.match(f, /composed into one Space Template, `telco`/);
    assert.match(docs['docs/okf/product/product/telco/user-guide.md'], /## Link Analysis\n\n(- .*\n)*- \[[^\]]+\]\(\.\.\/\.\.\/module\/la-core\//);
    assert.match(docs['docs/okf/product/product/inspecto/user-guide.md'], /## The console/);
    // a Domain product's own templates (Inspecto LA's showcases) are listed too, with their hubs' guides
    assert.match(docs['docs/okf/product/product/la/features.md'], /## Space Templates it ships\n\n.*\(\.\.\/\.\.\/solution-pack\/la-showcase\/index\.md\)/);
    assert.match(docs['docs/okf/product/product/la/admin-guide.md'], /## The Space Templates it ships\n\n(- .*\n)*- \[[^\]]+\]\(\.\.\/\.\.\/solution-pack\/la-/);
});
test('a hand-written file in a generated slot of a Product hub is refused', () => {
    const dir = mkdtempSync(join(tmpdir(), 'pd-'));
    try {
        writeFileSync(join(dir, 'brochure.md'), `${MARK}x.toon -->\n`);
        writeFileSync(join(dir, 'plan.md'), '# a plan\n');
        refuseHandWritten(dir, 'x');
        writeFileSync(join(dir, 'user-guide-extra.md'), '# hand-written\n');
        assert.throws(() => refuseHandWritten(dir, 'x'), /user-guide slot of a Product hub is generated/);
        rmSync(join(dir, 'user-guide-extra.md'));
        writeFileSync(join(dir, 'features.md'), '# hand-written datasheet\n');
        assert.throws(() => refuseHandWritten(dir, 'x'), /features slot of a Product hub is generated/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
