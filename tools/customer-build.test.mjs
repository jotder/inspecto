// Fixture test for tools/customer-build.mjs. Run: node --test tools/customer-build.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bomCsv, resolve, templateNeeds, trimBundle } from './customer-build.mjs';

const world = () => ({
    manifests: {
        processor: { offeringRole: 'base' }, oidc: { offeringRole: 'provider' }, la: { offeringRole: 'optional' },
        ops: { offeringRole: 'optional' }, cases: { offeringRole: 'optional', requires: { modules: ['ops'] } },
        anomaly: { offeringRole: 'optional', requires: { modules: ['lists'] }, provides: { jobTypes: ['anomaly.score'], configKinds: ['anomaly-model'] } },
        lists: { offeringRole: 'optional' }, premium: { offeringRole: 'optional' },
    },
    offerings: {
        basic: { id: 'basic', tier: 'basic', modules: ['la'], addons: { la: { status: 'built', modules: ['la'] } }, contentPacks: ['starter'] },
        standard: { id: 'standard', tier: 'standard', includes: ['basic'], modules: ['oidc', 'ops', 'cases', 'anomaly', 'lists'],
            addons: { incidents: { status: 'built', modules: ['ops'] }, workflow: { status: 'built', modules: ['ops'] }, cases: { status: 'built', modules: ['cases'] },
                anomaly: { status: 'built', modules: ['anomaly'] }, lists: { status: 'built', modules: ['lists'] } }, contentPacks: ['fraud'] },
        advanced: { id: 'advanced', tier: 'advanced', includes: ['standard'], modules: ['premium'], addons: { premium: { status: 'built', modules: ['premium'] } } },
    },
    templateNeeds: { fraud: ['anomaly'], starter: [] },
});
const item = (kind, name, extra) => [`${kind}/${name}`, `id: ${kind}.${name}\nkind: ${kind}\ntitle: ${name}\n${extra}status: ga\n`];
const bill = (code, enf = 'build') => `billable: true\norderCode: ${code}\nenforcement: ${enf}\nbilling:\n  model: flat\n`;
const files = () => Object.fromEntries([
    item('edition', 'standard', `offering: standard\n${bill('ED-STD')}`),
    item('addon', 'la', `offeringAddon: la\n${bill('AO-LA')}`),
    item('addon', 'incidents', `offeringAddon: incidents\n${bill('AO-INC', 'contract')}`),
    item('addon', 'workflow', `offeringAddon: workflow\n${bill('AO-WF', 'contract')}`),
    item('addon', 'cases', `offeringAddon: cases\n${bill('AO-CASES')}`),
    item('addon', 'anomaly', `offeringAddon: anomaly\n${bill('AO-ANOM')}`),
    item('addon', 'lists', `offeringAddon: lists\n${bill('AO-LISTS')}`),
    item('addon', 'premium', `offeringAddon: premium\n${bill('AO-PREM')}`),
    item('solution-pack', 'fraud', `template: fraud\n${bill('SP-FRAUD')}`),
    item('module', 'la', 'parent: layer.x\nmodule: la\nbillable: false\n'),
]);
const cust = (codes, edition = 'standard') => ({ id: 'acme', title: 'Acme', edition, orderCodes: codes });
const lines = (r) => r.bom.map((b) => `${b.line}:${b.orderCode}`);

test('Edition platform + bought add-ons only: unbought add-on modules are left out', () => {
    const r = resolve(cust(['AO-LA']), files(), world());
    assert.deepEqual(r.refusals, []);
    assert.deepEqual(r.keep, ['la', 'oidc']);
    assert.deepEqual(r.removed, ['anomaly', 'cases', 'lists', 'ops']);
    assert.match(r.offering, /^modules\[2\]: la,oidc$/m);
    assert.deepEqual(lines(r), ['edition:ED-STD', 'bought:AO-LA']);
});
test('requires.modules closure keeps a needed module and bills it as a `required` line', () => {
    const r = resolve(cust(['AO-CASES']), files(), world());
    assert.ok(r.keep.includes('ops'));
    assert.match(r.bom.find((b) => b.orderCode === 'AO-INC').reason, /module 'ops' is needed by 'cases'/);
    assert.equal(r.bom.find((b) => b.orderCode === 'AO-INC').line, 'required');
});
test('a bought Space Template keeps the modules it needs, transitively', () => {
    const r = resolve(cust(['SP-FRAUD']), files(), world());
    assert.ok(r.keep.includes('anomaly') && r.keep.includes('lists'));
    assert.deepEqual(r.packs, ['fraud']);
    assert.match(r.bom.find((b) => b.orderCode === 'AO-ANOM').reason, /needed by Space Template 'fraud'/);
    assert.match(r.bom.find((b) => b.orderCode === 'AO-LISTS').reason, /needed by 'anomaly'/);
});
test('an add-on whose modules ship anyway is marked installed-not-sold', () => {
    const r = resolve(cust(['AO-INC']), files(), world());
    assert.deepEqual(r.bom.find((b) => b.orderCode === 'AO-WF')?.line, 'installed-not-sold');
});
test('refused: unknown code, not billable, outside the Edition, wrong Edition, missing Edition', () => {
    assert.match(resolve(cust(['NOPE']), files(), world()).refusals.join(), /'NOPE' is in no Product Map Item/);
    assert.match(resolve(cust(['AO-PREM']), files(), world()).refusals.join(), /add-on 'premium' is not in Edition 'standard'/);
    assert.match(resolve(cust(['ED-STD'], 'basic'), files(), world()).refusals.join(), /Edition 'standard', but the customer's Edition is 'basic'/);
    assert.match(resolve(cust([], 'gold'), files(), world()).refusals.join(), /edition 'gold' has no offerings/);
    const f = files(); f['addon/la'] = f['addon/la'].replace('billable: true', 'billable: false');
    assert.match(resolve(cust(['AO-LA']), f, world()).refusals.join(), /AO-LA \(addon.la\) is not billable/);
});
test('prices appear on the private bill of materials only', () => {
    const r = resolve(cust(['AO-LA']), files(), world(), { 'AO-LA': { listPrice: '900', currency: 'USD' } });
    assert.match(bomCsv(r.bom), /bought,AO-LA,la,addon,build,left out of the build unless bought,900,USD/);
    assert.doesNotMatch(bomCsv(r.bom, { prices: false }), /900|listPrice|USD/);
});
test('templateNeeds derives needs from Job types and registry folders', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-tn-'));
    try {
        mkdirSync(join(root, 'spaces', '_templates', 't', 'config', 'jobs'), { recursive: true });
        mkdirSync(join(root, 'spaces', '_templates', 't', 'config', 'registry', 'anomaly-models'), { recursive: true });
        mkdirSync(join(root, 'spaces', '_templates', 'u', 'config', 'jobs'), { recursive: true });
        writeFileSync(join(root, 'spaces', '_templates', 't', 'config', 'jobs', 'j.toon'), 'id: j\ntype: anomaly.score\n');
        writeFileSync(join(root, 'spaces', '_templates', 'u', 'config', 'jobs', 'j.toon'), 'id: j\ntype: sql.template\n');
        assert.deepEqual(templateNeeds(root, world().manifests), { t: ['anomaly'], u: [] });
    } finally { rmSync(root, { recursive: true, force: true }); }
});
test('trimBundle removes unbought jars, SBOMs and Templates and stamps the customer', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-tb-'));
    try {
        const src = join(root, 'src');
        mkdirSync(join(src, 'sbom'), { recursive: true });
        for (const t of ['fraud', 'starter']) mkdirSync(join(src, 'spaces', '_templates', t), { recursive: true });
        const jars = ['inspecto.jar', 'inspecto-oidc.jar', 'inspecto-la.jar', 'inspecto-ops.jar', 'inspecto-cases.jar', 'inspecto-anomaly.jar', 'inspecto-lists.jar'];
        for (const j of jars) { writeFileSync(join(src, j), 'x'); writeFileSync(join(src, 'sbom', j.replace('.jar', '.sbom.cdx.json')), '{}'); }
        writeFileSync(join(src, 'modules.list'), jars.join('\n') + '\n');
        writeFileSync(join(src, 'edition.properties'), 'edition=Standard\n');
        const r = resolve(cust(['AO-LA'], 'standard'), files(), world(), { 'AO-LA': { listPrice: '900', currency: 'USD' } });
        const dest = join(root, 'out');
        const t = trimBundle(src, dest, cust(['AO-LA']), r);
        assert.deepEqual(t.jars.sort(), ['inspecto-anomaly.jar', 'inspecto-cases.jar', 'inspecto-lists.jar', 'inspecto-ops.jar']);
        assert.equal(readFileSync(join(dest, 'modules.list'), 'utf8'), 'inspecto.jar\ninspecto-oidc.jar\ninspecto-la.jar\n');
        assert.ok(!existsSync(join(dest, 'sbom', 'inspecto-ops.sbom.cdx.json')) && existsSync(join(dest, 'sbom', 'inspecto-la.sbom.cdx.json')));
        assert.ok(!existsSync(join(dest, 'spaces', '_templates', 'fraud')) && !existsSync(join(dest, 'spaces', '_templates', 'starter')));
        assert.match(readFileSync(join(dest, 'edition.properties'), 'utf8'), /^edition=Standard\ncustomer=acme\noffering=customer-acme\n$/);
        assert.doesNotMatch(readFileSync(join(dest, 'customer-bom.csv'), 'utf8'), /900/);
        assert.ok(existsSync(join(src, 'inspecto-ops.jar')), 'the source bundle is untouched');
        assert.throws(() => trimBundle(src, dest, cust([], 'advanced'), r), /edition 'standard', the customer's Edition is 'advanced'/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});
