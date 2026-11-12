// Tests for tools/product-documents.mjs (PRODUCT-ASSEMBLY-1 PA-10d, D-PA36). Run: node --test tools/product-documents.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { GENERATED_FILES, GENERATED_SLOTS, MARK, productDocuments, refuseHandWritten } from './product-documents.mjs';

const docs = productDocuments('.');
const products = readdirSync('products').filter((f) => f.endsWith('.toon')).map((f) => f.slice(0, -5));

test('every Product gets its six generated documents (the demo and video scripts too), each marked generated and committed as generated', () => {
    for (const id of products) for (const slot of GENERATED_FILES) {
        const path = `docs/okf/product/product/${id}/${slot}.md`;
        assert.ok(docs[path]?.includes(MARK), `${path} is generated`);
        assert.equal(readFileSync(path, 'utf8'), docs[path], `${path} is current (node tools/render-product-docs.mjs)`);
    }
    assert.equal(Object.keys(docs).length, products.length * GENERATED_FILES.length);
    assert.deepEqual(GENERATED_FILES.filter((f) => !GENERATED_SLOTS.includes(f)), ['brochure-demo-script', 'brochure-video-script']);
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
test("the Telco demo script names its Apps, its Demo Users and every seeded Alert; its video script walks the Apps", () => {
    const d = docs['docs/okf/product/product/telco/brochure-demo-script.md'];
    for (const app of ['Revenue Assurance', 'Fraud Management', 'Case Management', 'Link Analysis']) assert.match(d, new RegExp(`### \\d\\. \\[${app}\\]`));
    // the Demo Users of the presenter text, each a row
    for (const [user, display] of [['admin', 'Demo Admin'], ['ra.analyst', 'Demo RA Analyst'], ['fm.analyst', 'Demo FM Analyst'], ['fm.manager', 'Demo FM Manager'], ['viewer', 'Demo Viewer']])
        assert.match(d, new RegExp(`\\| \`${user.replace('.', '\\.')}\` \\| ${display} \\|`));
    // every Alert Rule the composed Space's templates seed, read independently of the generator
    const seeded = ['telco-ra', 'telco-fraud', 'telco-bypass'].flatMap((t) => readdirSync(`spaces/_templates/${t}/config/registry/alert-rules`).map((f) => f.slice(0, -5)));
    assert.ok(seeded.length >= 19, `${seeded.length} seeded Alert Rules`);
    for (const a of seeded) assert.match(d, new RegExp(`\\| \`${a}\` \\| [A-Z]+ \\|`), `${a} in the seeded Alerts table`);
    assert.match(d, /\| `fraud_irsf` \| CRITICAL \| \[Telecom fraud\]/);
    assert.match(d, /Space `telco` is pre-installed/);
    assert.match(d, /## Reset\n\nStop the server, delete/);
    const v = docs['docs/okf/product/product/telco/brochure-video-script.md'];
    assert.match(v, /\| 1 \| Problem \|/);
    for (const app of ['Revenue Assurance', 'Fraud Management', 'Case Management', 'Link Analysis']) assert.match(v, new RegExp(`\\| \\d \\| ${app} \\| ${app}: \\*`));
    assert.match(v, new RegExp(`what the demo Space's Alert Rules raised \\(${seeded.length} Alert Rules`));
    assert.doesNotMatch(d, /new Rules/, 'the presenter text is copied; bare "Rule" is banned (GLOSSARY)');
    // an Alert Rule the demo's seed adds over the API (not in a template) is listed too: Inspecto LA's mule-pass-through
    assert.match(docs['docs/okf/product/product/la/brochure-demo-script.md'], /\| `mule-pass-through` \| CRITICAL \| the seed \| Added by the demo's seed \(`tools\/seed-la-showcase\.mjs`\)\. \|/);
    assert.match(v, /\| Edition \| Editions: Standard · Advanced \| Inspecto Telco ships in the Standard and Advanced Editions\. \|/);
    // a Product with no demo Space says so instead of naming one
    assert.match(docs['docs/okf/product/product/inspecto/brochure-demo-script.md'], /Inspecto has no demo Space/);
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
        writeFileSync(join(dir, 'brochure-demo-script.md'), `${MARK}x.toon -->\n`);
        refuseHandWritten(dir, 'x');
        writeFileSync(join(dir, 'brochure-video-script.md'), '# a hand-written video script\n');
        assert.throws(() => refuseHandWritten(dir, 'x'), /brochure slot of a Product hub is generated/);
        rmSync(join(dir, 'brochure-video-script.md'));
        writeFileSync(join(dir, 'brochure-launch-video.md'), `${MARK}x.toon -->\n`);
        assert.throws(() => refuseHandWritten(dir, 'x'), /brochure slot of a Product hub is generated/);
        rmSync(join(dir, 'brochure-launch-video.md'));
        writeFileSync(join(dir, 'features.md'), '# hand-written datasheet\n');
        assert.throws(() => refuseHandWritten(dir, 'x'), /features slot of a Product hub is generated/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
