// Tests for tools/assemble.mjs — PRODUCT-ASSEMBLY-1 PA-2. Run: node --test tools/assemble.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble, diffLocks, productLock, PLAN_SCHEMA, shipOf } from './assemble.mjs';
import { gatherModel } from './products.mjs';
import { gather } from './check-offerings.mjs';
import { classpath } from './offering-classpath.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const g = gather(repo);
const model = (plant) => { const m = gatherModel(repo); const exists = m.exists; const c = structuredClone({ ...m, exists: undefined }); c.exists = exists; plant?.(c); return c; };
const plan = (opts, plant) => assemble(model(plant), g, opts);

test('Inspecto LA x Advanced x demo: the LA host, the demo classpath, only the la-* templates, la-showcase seeded', () => {
    const p = plan({ product: 'la', edition: 'advanced', distribution: 'demo' });
    assert.equal(p.schema, PLAN_SCHEMA);
    assert.equal(p.host, 'la-app');
    assert.equal(p.demo, true);
    assert.deepEqual(p.modules, classpath('Advanced', { demo: true, g }), 'the module set is the Edition\'s demo classpath, unchanged');
    assert.ok(p.templates.length === 20 && p.templates.every((t) => t.startsWith('la-')));
    assert.equal(p.seedSpace, 'la-showcase');
    assert.equal(p.seedTool, 'tools/seed-la-showcase.mjs');
    assert.equal(p.demoText, 'products/la.demo-build.txt');
    assert.deepEqual(p.editionProperties, { edition: 'Advanced', variant: 'demo', product: 'la', apps: 'link-analysis' });
});

test('the console x Standard x production: gamma host, every template, no seed, Add-ons the edition offers', () => {
    const p = plan({ product: 'inspecto', edition: 'standard' });
    assert.equal(p.host, 'gamma');
    assert.equal(p.demo, false);
    assert.equal(p.seedSpace, null);
    assert.deepEqual([...p.templates].sort(), [...model().templates].sort());
    assert.deepEqual(p.modules, classpath('Standard', { g }));
    assert.ok(p.addons.includes('reconciliation') && !p.addons.includes('aiAssist') === false, 'Standard offers aiAssist too');
    assert.ok(!plan({ product: 'inspecto', edition: 'basic' }).addons.includes('reconciliation'), 'Basic does not offer Reconciliation');
});

test('an optional App whose Add-ons the edition lacks is left out with a note, and refused when asked for by name', () => {
    const plant = (m) => { m.offerings = structuredClone(m.offerings); m.apps['link-analysis'].requires.addons.push('reconciliation'); m.products.la.apps.required = []; m.products.la.apps.optional = ['link-analysis']; m.products.la.shape = 'platform'; m.products.la.addons = ['linkAnalysis']; m.products.la.demoSpace = undefined; m.products.la.templates = ['la-card']; m.apps['link-analysis'].templates = m.apps['link-analysis'].templates.filter((t) => t !== 'la-card'); };
    // the console carries the same App; drop it there so the plant stays a single Product's concern
    const both = (m) => { plant(m); m.products.inspecto.apps.optional = []; m.products.inspecto.templates.push(...m.apps['link-analysis'].templates, 'la-card'); m.products.inspecto.templates = [...new Set(m.products.inspecto.templates)]; };
    const p = plan({ product: 'la', edition: 'basic' }, both);
    assert.deepEqual(p.apps, []);
    assert.match(p.notes.join(), /optional App 'link-analysis' left out: edition 'basic' lacks Add-on\(s\) reconciliation/);
    assert.throws(() => plan({ product: 'la', edition: 'basic', apps: ['link-analysis'] }, both), /App 'link-analysis' needs Add-on\(s\) reconciliation/);
});

test('PA-3: the descriptor carries each App\'s modules for THIS edition and its nav in drawing order', () => {
    const adv = plan({ product: 'la', edition: 'advanced', distribution: 'demo' }).descriptor;
    assert.equal(adv.schema, 'inspecto.product/1');
    assert.deepEqual([adv.id, adv.title, adv.shape, adv.host, adv.brand.name, adv.edition, adv.distribution],
        ['la', 'Inspecto LA', 'domain', 'la-app', 'Inspecto LA', 'Advanced', 'demo']);
    const [la] = adv.apps;
    assert.deepEqual(la.modules, ['la-storage', 'la-core', 'la-api', 'geo-link', 'la-store-pg'], 'Advanced adds the Postgres Investigation store');
    assert.deepEqual(la.nav.map((n) => [n.id, n.path, n.feature ?? null]), [
        ['linkAnalysis', 'link-analysis', null], ['data', 'data', null], ['geoMap', 'geo-map', 'geoMap'],
        ['entities', 'entities', 'entityPages'], ['entityLists', 'entity-lists', null], ['alerts', 'alerts', 'investigations'],
    ]);
    assert.ok(la.nav.every((n) => !('capability' in n)), 'an absent field is left out, not written as null');
    const basic = plan({ product: 'la', edition: 'basic' }).descriptor.apps[0];
    assert.deepEqual(basic.modules, ['la-storage', 'la-core', 'la-api', 'geo-link'], 'Basic has no Postgres store');
    const console_ = plan({ product: 'inspecto', edition: 'standard' }).descriptor;
    assert.deepEqual([console_.shape, console_.host, console_.apps.map((a) => a.id)], ['platform', 'gamma', ['link-analysis']]);
});

test('PA-5: Inspecto Telco is la-app built with -c telco, four Apps, and one Space composed from RA and FM', () => {
    for (const edition of ['standard', 'advanced']) {
        const p = plan({ product: 'telco', edition });
        assert.deepEqual([p.host, p.hostConfiguration, p.industry], ['la-app', 'telco', 'telecom']);
        assert.deepEqual(p.apps.map((a) => a.id), ['telco-ra', 'case-management', 'telco-fraud', 'link-analysis']);
        assert.deepEqual(p.templates, ['telco-ra', 'telco-fraud', 'telco-bypass'], 'the parts ship as templates of their own too');
        assert.deepEqual(p.space, {
            id: 'telco',
            title: 'Inspecto Telco',
            industry: 'telecom',
            parts: [
                { app: 'telco-ra', appTitle: 'Revenue Assurance', template: 'telco-ra' },
                { app: 'telco-fraud', appTitle: 'Fraud Management', template: 'telco-fraud' },
                { app: 'telco-fraud', appTitle: 'Fraud Management', template: 'telco-bypass' },
            ],
        });
        assert.equal(p.editionProperties.product, 'telco');
        assert.deepEqual(p.descriptor.apps.map((a) => a.id), p.apps.map((a) => a.id));
        // PA-5b: a page two Apps declare is drawn once, under the first App in Product order.
        const navOf = (id) => p.descriptor.apps.find((a) => a.id === id).nav.map((n) => n.path);
        assert.deepEqual(navOf('case-management'), ['alerts', 'incidents', 'cases']);
        assert.ok(!navOf('link-analysis').includes('alerts'), "Link Analysis' Alerts gives way to Case Management's");
        assert.deepEqual(navOf('telco-ra'), ['reconciliation', 'kpi-reports']);
        assert.deepEqual(navOf('telco-fraud'), ['risk-scores', 'anomaly-models']);
    }
    assert.ok(plan({ product: 'la', edition: 'standard' }).descriptor.apps[0].nav.some((n) => n.path === 'alerts'), 'Inspecto LA keeps its Alerts');
    assert.equal(plan({ product: 'la', edition: 'basic' }).hostConfiguration, null, 'Inspecto LA stays the default build of la-app');
    assert.equal(plan({ product: 'la', edition: 'basic' }).space, null);
    assert.throws(() => plan({ product: 'telco', edition: 'basic' }), /Product 'telco' does not come in edition 'basic'/);
});

test('PA-8: a container or air-gap build names its image and chart; a zip build names none', () => {
    const telco = plan({ product: 'telco', edition: 'standard', target: 'container' });
    assert.deepEqual(telco.ship, {
        image: { repository: 'inspecto-telco', tag: `${telco.platformVersion}-standard` },
        chart: { name: 'inspecto-telco', version: telco.platformVersion, appVersion: `${telco.platformVersion}-standard` },
    });
    assert.equal(plan({ product: 'inspecto', edition: 'basic', target: 'airgap' }).ship.image.repository, 'inspecto', 'the Platform product keeps the plain name');
    assert.equal(plan({ product: 'la', edition: 'basic', distribution: 'trial', target: 'airgap' }).ship.image.tag.endsWith('-basic-trial'), true);
    assert.equal(plan({ product: 'la', edition: 'basic' }).ship, null);
    assert.equal(plan({ product: 'la', edition: 'basic', target: 'linux' }).ship, null);
});

test('PA-8: a Product id that cannot be an image name is refused', () => {
    assert.throws(() => shipOf('Acme Platform', 'standard', 'production', '4.0.0'), /does not make an image name/);
});

const REFUSED = {
    'an unknown Product': [{ product: 'nope', edition: 'basic' }, /unknown Product 'nope'/],
    'an edition the Product does not list': [{ product: 'la', edition: 'enterprise' }, /does not come in edition 'enterprise'/],
    'a demo on Basic': [{ product: 'la', edition: 'basic', distribution: 'demo' }, /demo distribution exists for advanced and preview only/],
    // PA-10c (D-PA35): a pilot is a customer build only.
    'a pilot outside a customer build': [{ product: 'la', edition: 'advanced', distribution: 'pilot' }, /'pilot' distribution is a customer build: .*customer-build.mjs/],
    'an unknown target': [{ product: 'la', edition: 'advanced', target: 'saas' }, /target 'saas' is not one of windows, linux, container, airgap/],
    'a demo container': [{ product: 'telco', edition: 'advanced', distribution: 'demo', target: 'container' }, /demo distribution is not built for the 'container' target/],
    'a demo air-gapped bundle': [{ product: 'la', edition: 'advanced', distribution: 'demo', target: 'airgap' }, /demo distribution is not built for the 'airgap' target/],
    'an App the Product does not have': [{ product: 'la', edition: 'advanced', apps: ['telco-ra'] }, /App 'telco-ra' is not one of Product 'la''s Apps/],
};
for (const [name, [opts, re]] of Object.entries(REFUSED)) test(`REFUSED: ${name}`, () => assert.throws(() => plan(opts), re));

test('REFUSED: definitions that fail the Product guard', () => {
    assert.throws(() => plan({ product: 'la', edition: 'advanced' }, (m) => m.products.la.templates.push('ghost')), /do not pass tools\/check-products\.mjs/);
});

test('the Product lock records versions and content hashes; the diff names each change', () => {
    const tree = (p) => `tree-of-${p}`;
    const a = productLock(model(), g, 'la', { tree });
    assert.equal(a.product, 'la');
    assert.deepEqual(a.apps, [{ id: 'link-analysis', version: '1.0.0', required: true }]);
    assert.equal(a.templates.length, 20);
    assert.deepEqual(Object.keys(a.editions), ['basic', 'standard', 'advanced', 'preview']);
    assert.deepEqual(diffLocks(a, a), []);
    const b = structuredClone(a);
    b.platformVersion = '4.1.0';
    b.apps[0].version = '1.1.0';
    b.templates[0].tree = 'changed';
    b.templates.push({ id: 'la-new', tree: 'x' });
    b.editions.basic = b.editions.basic.filter((j) => j !== 'inspecto-connectors.jar');
    const d = diffLocks(a, b);
    assert.ok(d.includes('Platform 4.0.0-SNAPSHOT -> 4.1.0'));
    assert.ok(d.includes("App 'link-analysis' changed 1.0.0 -> 1.1.0"));
    assert.ok(d.includes(`template '${a.templates[0].id}' changed`));
    assert.ok(d.includes("template 'la-new' added"));
    assert.ok(d.includes('basic: inspecto-connectors.jar removed from the classpath'));
});

test('the real lock reads git tree hashes for every template', () => {
    const l = productLock(model(), g, 'la');
    assert.ok(l.templates.every((t) => /^[0-9a-f]{40}$/.test(t.tree)), 'every template has a git tree hash');
});
