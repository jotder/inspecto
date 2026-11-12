// Fixture test for tools/customer-build.mjs. Run: node --test tools/customer-build.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
import { bomCsv, pilotTemplate, pilotText, resolve, signedEntitlement, templateNeeds, trimBundle } from './customer-build.mjs';
import { customerAssembly } from './customer.mjs';
import { gather as gatherOfferings } from './check-offerings.mjs';
import { gather } from './check-product-map.mjs';
import { gatherModel } from './products.mjs';
import { verifyBytes } from './entitle.mjs';

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
// PA-10b (D-PA33, D-PA34): a customer build is an assembly and carries a signed entitlement. Over the repo's own definitions,
// with SYNTHETIC customer records (never a real customer's).
const repo = () => {
    const [files, world] = gather('.');
    world.templateNeeds = templateNeeds('.', world.manifests);
    return { model: gatherModel('.'), g: gatherOfferings('.'), files, world };
};
const record = (extra) => ({ id: 'acme', title: 'Acme', product: 'inspecto', edition: 'standard', ...extra });
const now = new Date('2026-11-12T00:00:00Z');

test('PA-10b a Platform customer: the plan keeps what resolve() keeps, the bought template, and the Apps its Add-ons cover', () => {
    const R = repo();
    const c = record({ orderCodes: ['INS-AO-LINK-ANALYSIS', 'INS-SP-TELCO-FRAUD'] });
    const { plan, bom } = customerAssembly({ ...R, customer: c, now });
    const r = resolve(c, R.files, R.world);
    assert.deepEqual(plan.customer.keep, r.keep);
    assert.deepEqual(plan.customer.removed, r.removed);
    assert.deepEqual(plan.templates, ['telco-fraud']);
    assert.deepEqual(plan.apps.map((a) => a.id), ['link-analysis']);
    for (const m of r.removed) assert.ok(!plan.modules.includes(`inspecto-${m}.jar`), `${m} is not on the classpath`);
    assert.ok(plan.modules.includes('inspecto-la-core.jar'));
    assert.deepEqual(plan.customer.entitlement.modules, r.keep, 'the file entitles exactly the kept optional modules');
    assert.deepEqual(plan.customer.entitlement.addons, ['linkAnalysis']);
    assert.equal(plan.editionProperties.customer, 'acme');
    assert.ok(plan.customer.bom.every((b) => !('listPrice' in b)), 'the plan carries no prices');
    assert.ok(bom.every((b) => 'listPrice' in b), 'the private bill does');
});
test('PA-10b a Domain customer buys Apps by their Order Codes; a required App must be bought; a foreign App is refused', () => {
    const R = repo();
    const { plan } = customerAssembly({ ...R, customer: record({ product: 'telco', orderCodes: ['INS-APP-TELCO-RA', 'INS-APP-CASE-MANAGEMENT'] }), now });
    assert.deepEqual(plan.apps.map((a) => a.id), ['telco-ra', 'case-management']);
    assert.deepEqual(plan.space.parts.map((p) => p.app), ['telco-ra']);
    assert.deepEqual(plan.customer.entitlement.apps, ['case-management', 'telco-ra']);
    assert.throws(() => customerAssembly({ ...R, customer: record({ product: 'telco', orderCodes: ['INS-APP-TELCO-RA'] }), now }),
        /App 'case-management' is required by Product 'telco'/);
    assert.throws(() => customerAssembly({ ...R, customer: record({ product: 'la', edition: 'basic', orderCodes: ['INS-APP-TELCO-RA'] }), now }),
        /App 'telco-ra' is not one of Product 'la''s Apps/);
    assert.throws(() => customerAssembly({ ...R, customer: record({ product: 'nope', orderCodes: [] }), now }), /unknown Product 'nope'/);
});
test('PA-10b the record\'s expiry and nodes reach the entitlement, and it verifies against the issuing key', () => {
    const R = repo();
    const { plan } = customerAssembly({ ...R, customer: record({ orderCodes: ['INS-AO-LINK-ANALYSIS'], expires: '2027-12-31', nodes: '3' }), keyId: 'test-1', now });
    const { privateKey, publicKey } = generateKeyPairSync('ed25519');
    const e = signedEntitlement(plan, privateKey.export({ type: 'pkcs8', format: 'pem' }));
    const f = verifyBytes(Buffer.from(e.toon), e.sig, { 'test-1': publicKey });
    assert.equal(f.expires, '2027-12-31');
    assert.equal(String(f.nodes), '3');
    assert.equal(f.customer, 'acme');
});
test('PA-10b trimBundle executes the plan: jars, SBOMs, templates, descriptor, stamp, entitlement', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-tb-'));
    try {
        const src = join(root, 'src');
        mkdirSync(join(src, 'sbom'), { recursive: true });
        mkdirSync(join(src, 'lib'), { recursive: true });
        for (const t of ['fraud', 'starter']) mkdirSync(join(src, 'spaces', '_templates', t), { recursive: true });
        const jars = ['inspecto.jar', 'inspecto-oidc.jar', 'inspecto-la.jar', 'inspecto-ops.jar'];
        for (const j of jars) { writeFileSync(join(src, 'lib', j), 'x'); writeFileSync(join(src, 'sbom', j.replace('.jar', '.sbom.cdx.json')), '{}'); }
        writeFileSync(join(src, 'lib', 'modules.list'), jars.map((j) => `lib/${j}`).join('\n') + '\n');
        writeFileSync(join(src, 'lib', 'edition.properties'), 'edition=Standard\nproduct=inspecto\napps=link-analysis\n');
        writeFileSync(join(src, 'lib', 'product.json'), JSON.stringify({ id: 'inspecto', apps: [{ id: 'link-analysis' }] }));
        const plan = {
            product: { id: 'inspecto' }, edition: 'Standard', templates: ['fraud'], space: null,
            descriptor: { schema: 'inspecto.product/1', id: 'inspecto', apps: [] },
            editionProperties: { apps: '' },
            customer: { id: 'acme', offering: 'id: customer-acme\n', productApps: ['link-analysis'], removedJars: ['inspecto-ops.jar'], bom: [{ line: 'bought', orderCode: 'AO-LA' }] },
        };
        const dest = join(root, 'out');
        const t = trimBundle(src, dest, plan, { toon: 'schema: x\n', sig: 'c2ln\n' });
        assert.deepEqual(t, { jars: ['inspecto-ops.jar'], templates: ['starter'] });
        assert.equal(readFileSync(join(dest, 'lib', 'modules.list'), 'utf8'), 'lib/inspecto.jar\nlib/inspecto-oidc.jar\nlib/inspecto-la.jar\n');
        assert.ok(!existsSync(join(dest, 'sbom', 'inspecto-ops.sbom.cdx.json')) && existsSync(join(dest, 'sbom', 'inspecto-la.sbom.cdx.json')));
        assert.ok(existsSync(join(dest, 'spaces', '_templates', 'fraud')) && !existsSync(join(dest, 'spaces', '_templates', 'starter')));
        assert.match(readFileSync(join(dest, 'lib', 'edition.properties'), 'utf8'), /^edition=Standard\nproduct=inspecto\ncustomer=acme\noffering=customer-acme\n$/, 'the Product build\'s apps= line follows the plan');
        assert.deepEqual(JSON.parse(readFileSync(join(dest, 'lib', 'product.json'), 'utf8')), plan.descriptor);
        assert.equal(readFileSync(join(dest, 'conf', 'entitlement.toon'), 'utf8'), 'schema: x\n');
        assert.equal(readFileSync(join(dest, 'conf', 'entitlement.sig'), 'utf8'), 'c2ln\n');
        assert.ok(existsSync(join(src, 'lib', 'inspecto-ops.jar')), 'the source bundle is untouched');
        assert.throws(() => trimBundle(src, dest, { ...plan, edition: 'Advanced' }, {}), /edition 'Standard', the customer's Edition is 'Advanced'/);
        assert.throws(() => trimBundle(src, dest, { ...plan, product: { id: 'la' } }, {}), /is Product 'inspecto', the customer's Product is 'la'/);
        assert.throws(() => trimBundle(src, dest, { ...plan, customer: { ...plan.customer, removedJars: ['inspecto-ghost.jar'] } }, {}), /no lib\/inspecto-ghost.jar/);
        trimBundle(src, dest, { ...plan, editionProperties: { apps: 'link-analysis' } }, { toon: '', sig: '' });
        assert.match(readFileSync(join(dest, 'lib', 'edition.properties'), 'utf8'), /^edition=Standard\nproduct=inspecto\napps=link-analysis\ncustomer=acme\n/);
        assert.throws(() => trimBundle(src, dest, { ...plan, customer: { ...plan.customer, productApps: [] } }, {}),
            /built with App\(s\) link-analysis, which Product 'inspecto' does not define/);
        assert.throws(() => trimBundle(src, dest, { ...plan, space: { id: 'telco' }, descriptor: { apps: [{ id: 'x' }] } }, {}), /not a composed Space/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

// PA-10c (D-PA35): the pilot Distribution - the customer's Collectors pre-wired, no data. SYNTHETIC folders only.
const pilotRecord = (poll) => record({ orderCodes: ['INS-AO-LINK-ANALYSIS', 'INS-SP-TELCO-FRAUD'],
    pilot: { template: 'telco-fraud', poll: poll ?? { cdr: 'D:/feeds/cdr', payments: 'D:/feeds/payments', subscriber_events: '/srv/feeds/events' } } });
test('PA-10c a pilot plan wires every Pipeline of the bought template and says pilot', () => {
    const { plan } = customerAssembly({ ...repo(), customer: pilotRecord(), distribution: 'pilot', now });
    assert.equal(plan.descriptor.distribution, 'pilot');
    assert.equal(plan.pilot.template, 'acme-pilot');
    assert.deepEqual(plan.pilot.pipelines.map((p) => `${p.name}=${p.poll}`), ['cdr=D:/feeds/cdr', 'payments=D:/feeds/payments', 'subscriber_events=/srv/feeds/events']);
    assert.deepEqual(plan.pilot.roots, ['/srv/feeds/events', 'D:/feeds/cdr', 'D:/feeds/payments']);
    assert.match(pilotText(plan), /INSPECTO_JAVA_OPTS=-Dassist\.safety\.roots=\/srv\/feeds\/events;D:\/feeds\/cdr;D:\/feeds\/payments/);
    assert.match(pilotText(plan), /create a Space from the template 'acme-pilot'/);
    assert.equal(customerAssembly({ ...repo(), customer: pilotRecord(), now }).plan.pilot, null, 'a production build has no pilot');
});
test('PA-10c a pilot is refused without its block, for a template not bought, or with a Pipeline unwired, unknown or relative', () => {
    const R = repo();
    const run = (c) => () => customerAssembly({ ...R, customer: c, distribution: 'pilot', now });
    assert.throws(run(record({ orderCodes: ['INS-SP-TELCO-FRAUD'] })), /needs a pilot: block/);
    assert.throws(run({ ...pilotRecord(), pilot: { template: 'telco-ra', poll: {} } }), /pilot.template 'telco-ra' is not one of this customer's templates/);
    assert.throws(run(pilotRecord({ cdr: 'D:/feeds/cdr', payments: 'D:/p' })), /does not wire every Pipeline of 'telco-fraud': subscriber_events/);
    assert.throws(run(pilotRecord({ cdr: 'D:/a', payments: 'D:/b', subscriber_events: 'D:/c', ghost: 'D:/d' })), /names no Pipeline of 'telco-fraud': ghost/);
    assert.throws(run(pilotRecord({ cdr: 'feeds/cdr', payments: 'D:/b', subscriber_events: 'D:/c' })), /pilot.poll.cdr 'feeds\/cdr' must be an absolute folder/);
    assert.throws(run(pilotRecord({ cdr: 'D:/a"b', payments: 'D:/b', subscriber_events: 'D:/c' })), /must be an absolute folder/);
});
test('PA-10c pilotTemplate copies the bought template, wires dirs.poll, drops the samples, renames it', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-pilot-'));
    try {
        const t = join(root, 'fraud');
        mkdirSync(join(t, 'config', 'cdr'), { recursive: true });
        mkdirSync(join(t, 'data', 'samples'), { recursive: true });
        mkdirSync(join(t, 'data', 'cdr', 'database'), { recursive: true });
        writeFileSync(join(t, 'template.toon'), 'name: Telecom fraud\ntagline: "Samples in data/samples"\n');
        writeFileSync(join(t, 'config', 'cdr', 'cdr_pipeline.toon'), 'name: cdr\nactive: true\n\ndirs:\n  poll:       data/inbox/cdr\n  database:   data/cdr/database\n\nsink:\n  poll: untouched\n');
        writeFileSync(join(t, 'data', 'samples', 'cdr.csv'), 'a,b\n');
        writeFileSync(join(t, 'data', 'cdr', 'database', 'schema-seed.parquet'), 'x');
        pilotTemplate(root, { template: 'acme-pilot', from: 'fraud', title: 'Acme "pilot"', pipelines: [{ name: 'cdr', file: 'config/cdr/cdr_pipeline.toon', poll: 'D:/feeds/cdr' }] });
        const p = join(root, 'acme-pilot');
        assert.equal(readFileSync(join(p, 'config', 'cdr', 'cdr_pipeline.toon'), 'utf8'),
            'name: cdr\nactive: true\n\ndirs:\n  poll:       "D:/feeds/cdr"\n  database:   data/cdr/database\n\nsink:\n  poll: untouched\n');
        assert.ok(!existsSync(join(p, 'data', 'samples')), 'no golden corpus');
        assert.ok(existsSync(join(p, 'data', 'cdr', 'database', 'schema-seed.parquet')), 'the seeds a Space needs stay');
        assert.match(readFileSync(join(p, 'template.toon'), 'utf8'), /^name: "Acme pilot"\ntagline: "Pilot of fraud: /);
        assert.ok(existsSync(join(t, 'data', 'samples', 'cdr.csv')), 'the bought template is untouched');
        assert.throws(() => pilotTemplate(root, { template: 'acme-pilot', from: 'fraud', pipelines: [] }), /already has a Space Template 'acme-pilot'/);
        assert.throws(() => pilotTemplate(root, { template: 'x-pilot', from: 'ghost', pipelines: [] }), /no Space Template 'ghost'/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});
