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

test('PA-5b: the Telco host data names its brand, tagline and Apps, and draws a page two Apps declare once', () => {
    const m = gatherModel(repo);
    const g = generatedProduct(m, m.products.telco);
    assert.equal(g.brand.name, 'Inspecto Telco');
    assert.ok(g.brand.tagline && g.brand.signInPitch.title && g.brand.signInPitch.body);
    assert.deepEqual(g.apps, [
        { id: 'telco-ra', title: 'Revenue Assurance' },
        { id: 'case-management', title: 'Case Management' },
        { id: 'telco-fraud', title: 'Fraud Management' },
        { id: 'link-analysis', title: 'Link Analysis' },
    ]);
    assert.deepEqual(g.nav.map((n) => `${n.app}/${n.path}`), [
        'telco-ra/reconciliation', 'telco-ra/kpi-reports',
        'case-management/alerts', 'case-management/incidents', 'case-management/cases',
        'telco-fraud/risk-scores', 'telco-fraud/anomaly-models',
        'link-analysis/link-analysis', 'link-analysis/data', 'link-analysis/geo-map', 'link-analysis/entities', 'link-analysis/entity-lists',
    ], "Case Management's Alerts wins: Link Analysis' Alerts is the same page");
    const la = generatedProduct(m, m.products.la);
    assert.ok(la.nav.some((n) => n.app === 'link-analysis' && n.path === 'alerts'), 'Inspecto LA keeps its Alerts');
    assert.deepEqual(la.apps, [{ id: 'link-analysis', title: 'Link Analysis' }]);
});

test('Prettier layout: a long string breaks after its colon only under a key at least 7 wide', () => {
    const long = 'x'.repeat(130);
    const t = render('products/x.toon', { id: 'x', title: 'X', brand: { name: 'X', tagline: long }, apps: [], nav: [{ app: 'a', id: 'b', path: 'p', label: 'L', blurb: long }] });
    assert.match(t, new RegExp(`tagline:\\n {12}'${long}',`), 'tagline is 7 wide: the string moves to the next line');
    assert.match(t, new RegExp(`blurb: '${long}',`), 'blurb is 5 wide: it stays on the key line');
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
    const t = render('products/x.toon', { id: 'x', title: "Ann's", brand: { name: 'a\\b' }, apps: [], nav: [] });
    assert.match(t, /title: "Ann's",/, 'a single quote inside switches to double quotes');
    assert.match(t, /name: 'a\\\\b',/, 'a backslash is escaped');
    assert.match(t, /nav: \[\],/);
});
