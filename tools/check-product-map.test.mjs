// Fixture test for tools/check-product-map.mjs. Run: node --test tools/check-product-map.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check, gather } from './check-product-map.mjs';

const world = () => ({
    manifests: {
        processor: { id: 'processor', offeringRole: 'base' },
        ops: { id: 'ops', offeringRole: 'optional', provides: { features: ['ops'] } },
        recon: { id: 'recon', offeringRole: 'optional' },
    },
    offerings: { basic: { id: 'basic', addons: { incidents: { status: 'built', modules: ['ops'] }, recon: { status: 'built', modules: ['recon'] } } } },
    templates: ['aml'], industryPacks: ['telecom'], docExists: (p) => p === 'docs/x.md',
});
const files = () => ({
    'layer/platform': 'id: layer.platform\nkind: layer\ntitle: Platform\nbillable: false\nstatus: ga\n',
    'module/processor': 'id: module.processor\nkind: module\ntitle: Host\nparent: layer.platform\nmodule: processor\nbillable: false\nstatus: ga\n',
    'module/ops': 'id: module.ops\nkind: module\ntitle: Ops\nparent: layer.platform\nmodule: ops\nbillable: false\nstatus: ga\n',
    'module/recon': 'id: module.recon\nkind: module\ntitle: Recon\nparent: layer.platform\nmodule: recon\nbillable: false\nstatus: ga\ndocs: docs/x.md\n',
    'functionality/sla': 'id: functionality.sla\nkind: functionality\ntitle: SLA\nparent: module.ops\nmodule: ops\nbillable: true\norderCode: INS-F-SLA\nenforcement: enabled-gate\nbilling:\n  model: per-user\nstatus: ga\n',
    'addon/incidents': 'id: addon.incidents\nkind: addon\ntitle: Incidents\nofferingAddon: incidents\nbillable: true\norderCode: INS-AO-INC\nenforcement: build\nbilling:\n  model: flat\nsupport:\n  tier: standard\nstatus: ga\n',
    'addon/recon': 'id: addon.recon\nkind: addon\ntitle: Recon\nofferingAddon: recon\nbillable: true\norderCode: INS-AO-RECON\nenforcement: build\nbilling:\n  model: flat\nstatus: ga\n',
    'solution-pack/aml': 'id: solution-pack.aml\nkind: solution-pack\ntitle: AML\ntemplate: aml\nbillable: false\nstatus: ga\n',
    'solution-pack/industry-telecom': 'id: solution-pack.industry-telecom\nkind: solution-pack\ntitle: Telecom\nindustryPack: telecom\nbillable: false\nstatus: ga\n',
    'edition/basic': 'id: edition.basic\nkind: edition\ntitle: Basic\noffering: basic\nbillable: false\nstatus: ga\n',
});
/** Apply edits to the fixture: { 'kind/name': text | (old) => new | null }, plus an optional world mutation. */
const run = (edits = {}, mutateWorld) => {
    const f = files();
    for (const [k, v] of Object.entries(edits)) { if (v === null) delete f[k]; else f[k] = typeof v === 'function' ? v(f[k]) : v; }
    const w = world(); mutateWorld?.(w);
    return check(f, w).join('\n');
};
const swap = (a, b) => (t) => { assert.ok(t.includes(a), `fixture lacks '${a}'`); return t.replace(a, b); };

test('a consistent Product Map is GREEN', () => assert.equal(run(), ''));
test('the real tree is GREEN', () => { const [f, w] = gather('.'); assert.deepEqual(check(f, w), []); });

test('1 an unparseable file, a wrong kind and a wrong id are RED', () => {
    assert.match(run({ 'module/ops': 'id module.ops' }), /module\/ops: does not parse/);
    assert.match(run({ 'module/ops': swap('kind: module', 'kind: addon') }), /kind 'addon' does not match its folder 'module'/);
    assert.match(run({ 'module/ops': swap('id: module.ops', 'id: ops') }), /id 'ops' must be 'module.ops'/);
    assert.match(run({ 'widget/x': 'id: widget.x\nkind: widget\n' }), /unknown kind folder 'widget'/);
});
test('2 a price or a copied technical fact is RED', () => {
    assert.match(run({ 'addon/recon': swap('status: ga', 'status: ga\nlistPrice: 900') }), /key 'listPrice' is not allowed/);
    assert.match(run({ 'addon/recon': swap('  model: flat', '  model: flat\n  price: 9') }), /key 'billing.price' is not allowed/);
    assert.match(run({ 'module/ops': swap('status: ga', 'status: ga\nofferingRole: optional') }), /key 'offeringRole' is not allowed/);
});
test('3 a value outside a closed vocabulary is RED', () => {
    assert.match(run({ 'addon/recon': swap('model: flat', 'model: usage') }), /billing.model 'usage' is not one of/);
    assert.match(run({ 'addon/recon': swap('enforcement: build', 'enforcement: licence') }), /enforcement 'licence'/);
    assert.match(run({ 'module/ops': swap('status: ga', 'status: beta') }), /status 'beta'/);
    assert.match(run({ 'module/ops': swap('billable: false', 'billable: yes') }), /billable 'yes'/);
    assert.match(run({ 'addon/incidents': swap('tier: standard', 'tier: gold') }), /support.tier 'gold'/);
});
test('4 a broken link is RED', () => {
    assert.match(run({ 'module/ops': swap('module: ops', 'module: ghost') }), /module 'ghost' has no manifest/);
    assert.match(run({ 'edition/basic': swap('offering: basic', 'offering: gold') }), /offering 'gold' has no offerings/);
    assert.match(run({ 'addon/recon': swap('offeringAddon: recon', 'offeringAddon: nope') }), /offeringAddon 'nope' is in no Offering/);
    assert.match(run({ 'solution-pack/aml': swap('template: aml', 'template: kyc') }), /template 'kyc' is not under/);
    assert.match(run({ 'solution-pack/industry-telecom': swap('industryPack: telecom', 'industryPack: cable') }), /industryPack 'cable' does not exist/);
    assert.match(run({ 'module/recon': swap('docs/x.md', 'docs/y.md') }), /docs 'docs\/y.md' does not exist/);
    assert.match(run({ 'module/ops': swap('parent: layer.platform', 'parent: layer.nowhere') }), /parent 'layer.nowhere' does not exist/);
    assert.match(run({ 'module/ops': swap('parent: layer.platform\n', '') }), /a module needs a parent/);
    assert.match(run({ 'functionality/sla': swap('module: ops\n', '') }), /a functionality needs a module/);
    assert.match(run({ 'layer/platform': swap('title: Platform', 'title: Platform\nparent: module.ops') }), /cycle/);
});
test('5 a manifest, Offering, add-on, template or Industry pack with no item is RED', () => {
    assert.match(run({ 'module/recon': null, 'addon/recon': swap('', '') }), /module 'recon' has no Product Map Item/);
    assert.match(run({ 'edition/basic': null }), /Offering 'basic' has no edition item/);
    assert.match(run({ 'addon/recon': null }), /add-on 'recon' has no addon item/);
    assert.match(run({ 'solution-pack/aml': null }), /Space Template 'aml' has no solution-pack item/);
    assert.match(run({ 'solution-pack/industry-telecom': null }), /Industry pack 'telecom' has no solution-pack item/);
});
test('6 a billable item without its commercial fields, or a duplicate orderCode, is RED', () => {
    assert.match(run({ 'addon/recon': swap('orderCode: INS-AO-RECON\n', '') }), /billable but no orderCode/);
    assert.match(run({ 'addon/recon': swap('enforcement: build\n', '') }), /billable but no enforcement/);
    assert.match(run({ 'addon/recon': swap('billing:\n  model: flat\n', '') }), /billable but no billing.model/);
    assert.match(run({ 'addon/recon': swap('INS-AO-RECON', 'INS-AO-INC') }), /orderCode 'INS-AO-INC' is used by/);
});
test('7 an impossible enforcement is RED', () => {
    assert.match(run({ 'functionality/sla': swap('enforcement: enabled-gate', 'enforcement: build') }), /a functionality cannot be left out of a build/);
    assert.match(run({ 'module/processor': swap('billable: false', 'billable: false\nenforcement: build') }), /module 'processor' is base/);
    assert.match(run({}, (w) => { w.offerings.basic.addons.workflowSla = { status: 'built', modules: ['ops'] }; }), /module 'ops' is also named by add-on\(s\) workflowSla/);
    assert.match(run({ 'addon/recon': swap('enforcement: build', 'enforcement: enabled-gate') }), /module 'recon' declares no feature id/);
});
