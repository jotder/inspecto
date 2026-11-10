// Tests for tools/render-product-hosts.mjs - PRODUCT-ASSEMBLY-1 PA-4. Run: node --test tools/render-product-hosts.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generatedProduct, hostFile, render, renderAll } from './render-product-hosts.mjs';
import { appNav, gatherModel } from './products.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

test('every committed host file is what the definitions generate (the CI --check)', () => {
    for (const { file, text } of renderAll(repo)) {
        assert.equal(readFileSync(join(repo, file), 'utf8').replace(/\r\n/g, '\n'), text, `${file} is stale - run: node tools/render-product-hosts.mjs`);
    }
});

test("each Product lands in its host project's sourceRoot; a configured build gets its own file (PA-5)", () => {
    const files = Object.fromEntries(renderAll(repo).map((r) => [r.product, r.file.replace(/\\/g, '/')]));
    assert.equal(files.la, 'inspecto-ui/projects/la-app/src/app/product.generated.ts', 'Inspecto LA is the default build of la-app');
    assert.equal(files.telco, 'inspecto-ui/projects/la-app/src/app/product.telco.generated.ts', 'Inspecto Telco is la-app built with -c telco');
    assert.equal(files.inspecto, 'inspecto-ui/src/app/product.generated.ts');
    assert.throws(() => hostFile({}, { id: 'x', host: 'nope' }), /host 'nope' is not an Angular project/);
});

test('PA-5: the Telco host data names its brand and pitch, and draws no area yet for an App with no nav', () => {
    const m = gatherModel(repo);
    const g = generatedProduct(m, m.products.telco);
    assert.equal(g.brand.name, 'Inspecto Telco');
    assert.ok(g.brand.signInPitch.title && g.brand.signInPitch.body);
    assert.deepEqual([...new Set(g.nav.map((n) => n.app))], ['link-analysis'], 'RA, FM and CM declare their areas with their pages (PA-5b)');
});

test('the LA host data carries the brand, the pitch and the App nav in drawing order', () => {
    const m = gatherModel(repo);
    const g = generatedProduct(m, m.products.la);
    assert.equal(g.brand.name, 'Inspecto LA');
    assert.equal(g.brand.signInPitch.title, 'Follow every link, one case at a time.');
    assert.deepEqual(g.nav.map((n) => n.id), appNav(m.apps['link-analysis']).map((n) => n.id));
    assert.ok(g.nav.every((n) => n.app === 'link-analysis'));
    assert.deepEqual(g.nav.find((n) => n.id === 'geoMap').feature, 'geoMap');
    assert.equal(generatedProduct(m, m.products.inspecto).brand.signInPitch, undefined, 'the console keeps its own sign-in pitch');
});

test('strings are written the way Prettier writes them', () => {
    const t = render('products/x.toon', { id: 'x', title: "Ann's", brand: { name: 'a\\b' }, nav: [] });
    assert.match(t, /title: "Ann's",/, 'a single quote inside switches to double quotes');
    assert.match(t, /name: 'a\\\\b',/, 'a backslash is escaped');
    assert.match(t, /nav: \[\],/);
});
