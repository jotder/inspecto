// Tests for tools/products.mjs checkModel (the rules of tools/check-products.mjs) — PRODUCT-ASSEMBLY-1 PA-1.
// Run: node --test tools/check-products.test.mjs
// The repo's own definitions must be GREEN; each case below plants ONE violation in a copy of them and must turn RED.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkModel, gatherModel, productTemplates, satisfies, builtAddons } from './products.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const fresh = () => structuredClone({ ...gatherModel(repo), exists: undefined });
/** A copy of the repo model with `plant` applied; `exists` is re-attached (structuredClone drops functions). */
const model = (plant) => { const real = gatherModel(repo); const m = fresh(); m.exists = real.exists; plant?.(m); return m; };
const red = (plant, re) => {
    const f = checkModel(model(plant));
    assert.ok(f.some((x) => re.test(x)), `expected a finding matching ${re}, got:\n  ${f.join('\n  ') || '(none)'}`);
};

test('the repo definitions are GREEN', () => assert.deepEqual(checkModel(model()), []));

test('today: the two Products partition the templates as the bundles ship them', () => {
    const m = model();
    const la = productTemplates(m.products.la, ['link-analysis'], m.apps);
    const console_ = productTemplates(m.products.inspecto, ['link-analysis'], m.apps);
    assert.ok(la.every((t) => t.startsWith('la-')), 'Inspecto LA stages only la-* templates');
    assert.deepEqual([...console_].sort(), [...m.templates].sort(), 'the console stages every template, as before');
});

test('satisfies() reads version ranges', () => {
    assert.ok(satisfies('4.0.0-SNAPSHOT', '>=4.0 <5'));
    assert.ok(!satisfies('5.1.0', '>=4.0 <5'));
    assert.ok(satisfies('4.2.1', '=4.2.1'));
    assert.ok(!satisfies('3.9.9', '>=4'));
    assert.throws(() => satisfies('4.0.0', '~4'), /unreadable version range token/);
});

test('builtAddons() resolves includes (Standard inherits Basic\'s linkAnalysis)', () => {
    const m = model();
    assert.ok(builtAddons('standard', m.offerings).has('linkAnalysis'));
    assert.ok(!builtAddons('basic', m.offerings).has('reconciliation'));
});

const RED = {
    'an unknown template': [(m) => m.products.inspecto.templates.push('no-such-template'), /template 'no-such-template' is not under spaces\/_templates/],
    'a template staged twice in one Product': [(m) => m.products.inspecto.templates.push('la-card'), /template 'la-card' is staged twice/],
    'a template no Product ships': [(m) => m.templates.push('orphan-template'), /orphan-template: no Product or App ships this template/],
    'a required App whose Add-on an edition lacks': [(m) => m.apps['link-analysis'].requires.addons.push('reconciliation'), /edition 'basic' lacks Add-on\(s\) reconciliation that required App 'link-analysis' needs/],
    'an App requiring an Add-on no Offering names': [(m) => m.apps['link-analysis'].requires.addons.push('ghost'), /requires Add-on 'ghost', which no Offering names/],
    'an Industry-specific App in a Product with no Industry': [(m) => m.apps['link-analysis'].industries.push('telecom'), /serves telecom, so the Product must name its industry/],
    'an App for another Industry': [(m) => { m.apps['link-analysis'].industries.push('telecom'); m.products.la.industry = 'mobile-money'; }, /serves telecom, not the Product's Industry 'mobile-money'/],
    'an Industry whose pack does not exist': [(m) => { m.industries.telecom.pack = 'nope'; }, /pack 'nope' is not an Industry pack/],
    'a Platform product with no Add-ons': [(m) => { m.products.inspecto.addons = []; }, /a Platform product names its Add-ons/],
    'a Domain product with no required App': [(m) => { m.products.la.apps.required = []; }, /needs at least one required App/],
    'a planned distribution': [(m) => m.products.la.distributions.push('pilot'), /distribution 'pilot' is not built yet \(PA-10/],
    'a demo distribution with no demo-capable edition': [(m) => { m.products.la.editions = ['basic', 'standard']; }, /offers the demo distribution but none of advanced, preview/],
    'a demoSpace outside the Product': [(m) => { m.products.la.demoSpace = 'telco-ra'; }, /demoSpace 'telco-ra' is not one of the Product's templates/],
    'a demoSpace with no presenter text': [(m) => { const e = m.exists; m.exists = (p) => p !== 'products/la.demo-build.txt' && e(p); }, /demoSpace 'la-showcase' has no presenter text products\/la\.demo-build\.txt/],
    'a demoSpace with no seed tool': [(m) => { m.products.la.demoSpace = 'la-showcase-off'; }, /demoSpace 'la-showcase-off' has no seed tool tools\/seed-la-showcase-off\.mjs/],
    'an unknown UI host': [(m) => { m.products.la.host = 'la-ap'; }, /host 'la-ap' is not an Angular project/],
    'an App whose Platform range excludes this version': [(m) => { m.apps['link-analysis'].platform = '>=5.0 <6'; }, /does not admit this Platform version/],
    'an unknown edition': [(m) => m.products.la.editions.push('enterprise'), /edition 'enterprise' has no offerings\/enterprise\.toon/],
    'an id that differs from its file name': [(m) => { m.products.la.id = 'lax'; }, /id 'lax' does not equal its file name 'la'/],
    // One per remaining rule of checkModel (review of 2026-11-10: every rule is locked in by a RED case).
    'an Industry id that differs from its folder': [(m) => { m.industries.telecom.id = 'tel'; }, /industries\/telecom\/industry\.toon: id 'tel' does not equal its folder name 'telecom'/],
    'an Industry with no title': [(m) => { delete m.industries.telecom.title; }, /industries\/telecom\/industry\.toon: no title/],
    'an App id that differs from its folder': [(m) => { m.apps['link-analysis'].id = 'la'; }, /apps\/link-analysis\/app\.toon: id 'la' does not equal its folder name/],
    'an App with no version': [(m) => { delete m.apps['link-analysis'].version; }, /apps\/link-analysis\/app\.toon: no version/],
    'an unreadable App version range': [(m) => { m.apps['link-analysis'].platform = '~4'; }, /unreadable version range token '~4'/],
    'an App naming an Industry with no definition': [(m) => { m.apps['link-analysis'].industries.push('retail'); m.products.la.industry = 'retail'; }, /apps\/link-analysis\/app\.toon: Industry 'retail' has no industries\/retail\/industry\.toon/],
    'an App template that does not exist': [(m) => m.apps['link-analysis'].templates.push('la-ghost'), /apps\/link-analysis\/app\.toon: template 'la-ghost' is not under spaces\/_templates/],
    'an App whose docs do not exist': [(m) => { m.apps['link-analysis'].docs = 'docs/nowhere.md'; }, /apps\/link-analysis\/app\.toon: docs 'docs\/nowhere\.md' does not exist/],
    'a Product with no host': [(m) => { delete m.products.la.host; }, /products\/la\.toon: no host/],
    'a Product with no brand name': [(m) => { delete m.products.la.brand; }, /products\/la\.toon: no brand\.name/],
    'an unknown shape': [(m) => { m.products.la.shape = 'suite'; }, /shape 'suite' is not one of domain, platform/],
    'a Product whose docs do not exist': [(m) => { m.products.la.docs = 'docs/nowhere.md'; }, /products\/la\.toon: docs 'docs\/nowhere\.md' does not exist/],
    'a Product with no editions': [(m) => { m.products.la.editions = []; }, /products\/la\.toon: lists no editions/],
    'an unknown distribution': [(m) => m.products.la.distributions.push('beta'), /distribution 'beta' is not one of production, trial, demo/],
    'a Product naming an App with no definition': [(m) => m.products.la.apps.optional.push('fraud'), /App 'fraud' has no apps\/fraud\/app\.toon/],
    'an App both required and optional': [(m) => m.products.la.apps.optional.push('link-analysis'), /App 'link-analysis' is both required and optional/],
    'a Domain product that names Add-ons': [(m) => { m.products.la.addons = ['linkAnalysis']; }, /a Domain product names no addons/],
    'a Domain product naming an Industry with no definition': [(m) => { m.products.la.industry = 'retail'; }, /products\/la\.toon: Industry 'retail' has no industries\/retail\/industry\.toon/],
    'a Platform product naming an unknown Add-on': [(m) => m.products.inspecto.addons.push('ghostAddon'), /Add-on 'ghostAddon' is named by no Offering/],
    // PA-3: the nav an App declares.
    'a nav entry with no path': [(m) => { delete m.apps['link-analysis'].nav.data.path; }, /nav entry 'data' has no path/],
    'a nav entry with no label': [(m) => { delete m.apps['link-analysis'].nav.data.label; }, /nav entry 'data' has no label/],
    'two nav entries sharing a path': [(m) => { m.apps['link-analysis'].nav.alerts.path = 'data'; }, /nav entries 'data' and 'alerts' share the path 'data'/],
    'a nav entry keyed on a feature its Add-ons do not provide': [(m) => { m.apps['link-analysis'].nav.data.feature = 'reconciliation'; }, /nav entry 'data' keys on feature 'reconciliation', which no module of its Add-ons \(linkAnalysis\) provides/],
    // PA-4: the generated host data.
    'a sign-in title without its body': [(m) => { delete m.products.la.brand.signInBody; }, /brand\.signInTitle and brand\.signInBody go together/],
    'two Products on one host': [(m) => { m.products.la.host = 'gamma'; }, /products\/la\.toon: shares the default build of host 'gamma' with Product 'inspecto'/],
    // PA-5: a shared host (D-PA13) and the composed Space (D-PA14).
    'two default builds of one host': [(m) => { delete m.products.telco.hostConfiguration; }, /products\/telco\.toon: shares the default build of host 'la-app' with Product 'la'/],
    'a hostConfiguration the host does not have': [(m) => { m.products.telco.hostConfiguration = 'nope'; }, /hostConfiguration 'nope' is not a build configuration of host 'la-app'/],
    'a host configuration that does not swap the host data in': [(m) => { m.ngProjects['la-app'].architect.build.configurations.telco.fileReplacements = []; },
        /build configuration 'telco' of host 'la-app' must replace projects\/la-app\/src\/app\/product\.generated\.ts with projects\/la-app\/src\/app\/product\.telco\.generated\.ts/],
    'a space on a Platform product': [(m) => { m.products.inspecto.space = 'x'; }, /products\/inspecto\.toon: space 'x' is composed from Apps, so only a Domain product has one/],
    'a space named like a committed template': [(m) => { m.products.telco.space = 'telco-ra'; }, /space 'telco-ra' is the id of a committed template/],
    'a space with nothing to compose': [(m) => { m.apps['telco-ra'].templates = []; m.apps['telco-fraud'].templates = []; }, /space 'telco' has nothing to compose/],
    'two parts shipping one file': [(m) => { m.templateFiles['telco-bypass'].push(m.templateFiles['telco-ra'].find((f) => f.startsWith('config/registry/'))); },
        /space 'telco': templates 'telco-ra' and 'telco-bypass' both ship config\/registry\//],
    'two parts shipping one file that differs only in case': [(m) => { m.templateFiles['telco-bypass'].push(m.templateFiles['telco-ra'].find((f) => f.startsWith('config/registry/')).toUpperCase()); },
        /space 'telco': templates 'telco-ra' and 'telco-bypass' both ship CONFIG\/REGISTRY\//],
    'a demoSpace on a Product with no demo distribution': [(m) => { m.products.la.distributions = ['production']; }, /demoSpace is set but the Product offers no demo distribution/],
};
for (const [name, [plant, re]] of Object.entries(RED)) test(`RED: ${name}`, () => red(plant, re));
