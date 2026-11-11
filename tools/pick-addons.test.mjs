// tools/pick-addons.mjs - the Add-on picker (PRODUCT-ASSEMBLY-1 PA-6c).
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gather } from './check-offerings.mjs';
import { addonClosure, catalog, mapItems, resolvePick, toToon } from './pick-addons.mjs';
import { gatherModel, loadProductFile, narrowModel } from './products.mjs';
import { parseToon } from './toon-subset.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const model = gatherModel(REPO);
const g = gather(REPO);
const items = mapItems(REPO);
const EDS = ['basic', 'standard', 'advanced', 'preview'];

test('the catalog lists every Add-on of the Platform product with its closure and an Edition floor that builds the closure', () => {
    const rows = catalog(model, g, { items });
    assert.deepEqual(rows.map((r) => r.addon), [...model.products.inspecto.addons].sort());
    for (const r of rows) {
        assert.ok(EDS.includes(r.floor), `${r.addon} has a floor`);
        assert.ok(r.orderCode.startsWith('INS-AO-'), `${r.addon} has its Order Code`);
        assert.equal(r.price, null, 'no prices without product-map/private/prices.csv');
        for (const c of r.closure) {
            const cf = rows.find((x) => x.addon === c).floor;
            assert.ok(EDS.indexOf(cf) <= EDS.indexOf(r.floor), `${r.addon}'s floor ${r.floor} builds its closure member ${c} (floor ${cf})`);
        }
    }
    assert.equal(rows.find((r) => r.addon === 'linkAnalysis').floor, 'basic');
});

test('a closure follows module requires to the Add-ons owning them, transitively, and only those', () => {
    const offerings = { e: { addons: { a: { status: 'built', modules: ['ma'] }, b: { status: 'built', modules: ['mb'] }, c: { status: 'built', modules: ['mc'] }, d: { status: 'built', modules: ['md'] } } } };
    const manifests = { ma: { requires: { modules: ['mb', 'core'] } }, mb: { requires: { modules: ['mc'] } }, mc: {}, md: {}, core: { offeringRole: 'base' } };
    assert.deepEqual(addonClosure('e', 'a', offerings, manifests), ['b', 'c']);
    assert.deepEqual(addonClosure('e', 'b', offerings, manifests), ['c']);
    assert.deepEqual(addonClosure('e', 'd', offerings, manifests), []);
});

test('a pick grows by its closure, starts at its floor, keeps only the optional Apps it can serve, and assembles on each Edition', () => {
    const r = resolvePick(model, g, { addons: ['screening'], id: 'acme-console', title: 'Acme Console', items });
    const own = addonClosure(r.floor, 'screening', g.offerings, g.manifests);
    assert.deepEqual(r.added, own);
    assert.deepEqual(r.definition.addons, ['screening', ...own].sort());
    assert.deepEqual(r.definition.editions, EDS.slice(EDS.indexOf(r.floor)));
    assert.deepEqual(r.definition.apps.optional, [], 'Link Analysis needs linkAnalysis, which was not picked');
    assert.equal(r.definition.narrows, 'inspecto');
    assert.equal(r.definition.host, model.products.inspecto.host);
    assert.deepEqual(r.definition.templates, model.products.inspecto.templates);
    const narrowed = narrowModel(model, r.definition);
    assert.ok(!narrowed.products.inspecto, 'built in place of the Platform product it narrows');
    for (const e of r.definition.editions) {
        const plan = assemble(narrowed, g, { product: 'acme-console', edition: e });
        assert.equal(plan.product.id, 'acme-console');
        assert.deepEqual(plan.addons, r.definition.addons);
        assert.equal(plan.descriptor.brand?.name ?? plan.product.brand, 'Acme Console');
    }
    if (r.floor !== 'basic') assert.throws(() => assemble(narrowed, g, { product: 'acme-console', edition: 'basic' }), /does not come in edition 'basic'/);
    assert.ok(model.products.inspecto, 'the model itself is left as it was');

    const la = resolvePick(model, g, { addons: ['linkAnalysis'], id: 'la-oem', items });
    assert.equal(la.floor, 'basic');
    assert.deepEqual(la.definition.apps.optional, ['link-analysis']);
});

test('the written definition reads back as the definition, and --product-file builds it', () => {
    const r = resolvePick(model, g, { addons: ['incidents', 'linkAnalysis'], id: 'acme-two', title: 'Acme Two', items });
    const back = parseToon(toToon(r.definition));
    for (const k of ['id', 'title', 'shape', 'narrows', 'host', 'editions', 'addons', 'templates', 'distributions', 'docs']) assert.deepEqual(back[k], r.definition[k], k);
    assert.deepEqual(back.apps, r.definition.apps);
    assert.equal(back.brand.name, 'Acme Two');
    const dir = mkdtempSync(join(tmpdir(), 'pick-addons-'));
    try {
        const f = join(dir, 'acme-two.toon');
        writeFileSync(f, toToon(r.definition));
        const loaded = loadProductFile(model, f);
        assert.equal(loaded.product, 'acme-two');
        assert.equal(assemble(loaded.model, g, { product: 'acme-two', edition: r.floor }).product.title, 'Acme Two');
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('prices: Edition + Add-ons when every line has one; no total when any is missing', () => {
    const codes = [items.edition.standard.orderCode, ...model.products.inspecto.addons.map((a) => items.addon[a].orderCode)];
    const prices = Object.fromEntries(codes.map((c, i) => [c, { orderCode: c, listPrice: String(100 + i), currency: 'EUR' }]));
    const r = resolvePick(model, g, { addons: ['incidents'], id: 'priced', items, prices });
    assert.equal(r.floor, 'standard');
    assert.deepEqual(r.lines.map((l) => l.id), ['standard', ...r.definition.addons]);
    assert.equal(r.total, r.lines.reduce((s, l) => s + Number(prices[l.orderCode].listPrice), 0));
    assert.equal(r.currency, 'EUR');
    delete prices[items.addon.incidents.orderCode];
    assert.equal(resolvePick(model, g, { addons: ['incidents'], id: 'priced', items, prices }).total, null);
});

test('refusals: an unknown Add-on, a taken or malformed id, an empty pick; a narrowed product off its host or beyond its base', () => {
    assert.throws(() => resolvePick(model, g, { addons: ['nope'], id: 'x1' }), /'nope' is not one of the inspecto Platform product's/);
    assert.throws(() => resolvePick(model, g, { addons: ['incidents'], id: 'la' }), /already the Product products\/la.toon/);
    assert.throws(() => resolvePick(model, g, { addons: ['incidents'], id: 'Bad Id' }), /lower-case letters/);
    assert.throws(() => resolvePick(model, g, { addons: [], id: 'x1' }), /at least one Add-on/);
    const d = resolvePick(model, g, { addons: ['incidents'], id: 'x1' }).definition;
    assert.throws(() => narrowModel(model, { ...d, narrows: undefined }), /narrows a Platform product/);
    assert.throws(() => narrowModel(model, { ...d, narrows: 'la' }), /narrows a Platform product/);
    assert.throws(() => narrowModel(model, { ...d, host: 'la-app' }), /keeps the host/);
    assert.throws(() => narrowModel(model, { ...d, addons: [...d.addons, 'ghost'] }), /ghost are not in 'inspecto'/);
    assert.throws(() => narrowModel(model, { ...d, id: 'la' }), /already the Product products\/la.toon/, 'a hand-edited file may not replace another Product');
});
