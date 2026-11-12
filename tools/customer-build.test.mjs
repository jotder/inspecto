// Fixture test for tools/customer-build.mjs. Run: node --test tools/customer-build.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
import { bomCsv, pilotSpace, pilotTemplate, pilotText, resolve, signedEntitlement, templateNeeds, trimBundle } from './customer-build.mjs';
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
        // PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1: what no manifest derives, the template declares
        writeFileSync(join(root, 'spaces', '_templates', 'u', 'template.toon'), 'name: U\nminEdition: standard\nrequiresModules[2]: cases,ops\n');
        assert.deepEqual(templateNeeds(root, world().manifests), { t: ['anomaly'], u: ['cases', 'ops'] });
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
test('GAPS-1 a bought template\'s declared needs keep ops and case-management, billed as required and entitled', () => {
    // the pilot shape: telco-fraud bought without the Incidents Add-on. Its Alert Rules need ops (POST /spaces refuses without it)
    // and its Case Rule case-management, which telco-fraud's template.toon declares.
    const R = repo();
    const { plan } = customerAssembly({ ...R, customer: record({ orderCodes: ['INS-AO-LINK-ANALYSIS', 'INS-SP-TELCO-FRAUD'] }), now });
    for (const m of ['ops', 'case-management']) {
        assert.ok(plan.customer.keep.includes(m) && !plan.customer.removed.includes(m), `${m} is kept`);
        assert.ok(plan.modules.includes(`inspecto-${m}.jar`), `${m} is on the classpath`);
        assert.ok(plan.customer.entitlement.modules.includes(m), `${m} is entitled`);
    }
    assert.ok(plan.customer.bom.some((b) => b.line === 'required' && b.orderCode === 'INS-AO-INCIDENTS' && /'ops' is needed by Space Template 'telco-fraud'/.test(b.reason)));
    assert.ok(plan.customer.bom.some((b) => b.line === 'required' && b.orderCode === 'INS-AO-CASE-MANAGEMENT'));
    // a template that declares nothing keeps neither
    const { plan: bare } = customerAssembly({ ...R, customer: record({ orderCodes: ['INS-SP-ORDERS-STARTER'] }), now });
    assert.ok(bare.customer.removed.includes('ops') && bare.customer.removed.includes('case-management'));
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
        // per-jar SBOMs (tools/sbom-modules.mjs): the jar's own purl and the third-party components it carries
        const fp = (j) => `pkg:maven/com.gamma.inspector/${j.replace('.jar', '')}@1`;
        const carries = { 'inspecto.jar': ['pkg:maven/a/shared@1'], 'inspecto-oidc.jar': [], 'inspecto-la.jar': ['pkg:maven/a/shared@1'],
            'inspecto-ops.jar': ['pkg:maven/a/shared@1', 'pkg:maven/a/only-ops@1'] };
        for (const j of jars) {
            writeFileSync(join(src, 'lib', j), 'x');
            writeFileSync(join(src, 'sbom', j.replace('.jar', '.sbom.cdx.json')), JSON.stringify({ metadata: { component: { purl: fp(j) } }, components: carries[j].map((purl) => ({ purl })) }));
        }
        // the combined edition SBOM, both serialisations (tools/sbom.mjs); 'pkg:maven/a/unattributed@1' no per-jar SBOM names
        const all = [...jars.map((j) => ({ purl: fp(j), properties: [{ name: 'inspecto:bundleFile', value: j }] })),
            ...['shared', 'only-ops', 'unattributed'].map((n) => ({ purl: `pkg:maven/a/${n}@1` }))];
        writeFileSync(join(src, 'sbom', 'inspecto-standard.cdx.json'), JSON.stringify({ components: all, dependencies: [{ ref: 'b', dependsOn: all.map((x) => x.purl) }] }));
        const sid = (p) => `S-${p.replace(/\W/g, '-')}`;
        writeFileSync(join(src, 'sbom', 'inspecto-standard.spdx.json'), JSON.stringify({
            packages: [{ SPDXID: 'SPDXRef-BUNDLE' }, ...all.map((x) => ({ SPDXID: sid(x.purl), externalRefs: [{ referenceType: 'purl', referenceLocator: x.purl }] }))],
            relationships: [{ spdxElementId: 'SPDXRef-DOCUMENT', relatedSpdxElement: 'SPDXRef-BUNDLE' }, ...all.map((x) => ({ spdxElementId: 'SPDXRef-BUNDLE', relatedSpdxElement: sid(x.purl) }))],
        }));
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
        assert.deepEqual(t, { jars: ['inspecto-ops.jar'], templates: ['starter'], sbom: ['pkg:maven/a/only-ops@1', fp('inspecto-ops.jar')] });
        // GAPS-1: the combined SBOM no longer lists the removed jar, nor what only it carries; a shared or unattributed component stays
        const want = [fp('inspecto.jar'), fp('inspecto-oidc.jar'), fp('inspecto-la.jar'), 'pkg:maven/a/shared@1', 'pkg:maven/a/unattributed@1'];
        const cdx = JSON.parse(readFileSync(join(dest, 'sbom', 'inspecto-standard.cdx.json'), 'utf8'));
        assert.deepEqual(cdx.components.map((x) => x.purl), want);
        assert.deepEqual(cdx.dependencies[0].dependsOn, want);
        const spdx = JSON.parse(readFileSync(join(dest, 'sbom', 'inspecto-standard.spdx.json'), 'utf8'));
        assert.deepEqual(spdx.packages.slice(1).map((p) => p.externalRefs[0].referenceLocator), want);
        assert.deepEqual(spdx.relationships.slice(1).map((r) => r.relatedSpdxElement), want.map(sid));
        assert.equal(spdx.relationships[0].relatedSpdxElement, 'SPDXRef-BUNDLE');
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
    assert.match(pilotText(plan), /The Space 'acme-pilot' is already created \(spaces\/acme-pilot\)/);
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
    // the launchers split INSPECTO_JAVA_OPTS on whitespace, and assist.safety.roots splits on ';'
    assert.throws(run(pilotRecord({ cdr: 'D:/my feeds/cdr', payments: 'D:/b', subscriber_events: 'D:/c' })), /pilot.poll.cdr .* no space, quote, ;/);
    assert.throws(run(pilotRecord({ cdr: 'D:/a;D:/x', payments: 'D:/b', subscriber_events: 'D:/c' })), /pilot.poll.cdr .* no space, quote, ;/);
    assert.throws(run(pilotRecord({ cdr: 'D:/feeds', payments: 'D:/feeds', subscriber_events: 'D:/c' })), /gives Pipelines cdr, payments the same folder/);
    assert.throws(run(pilotRecord({ cdr: 'D:/feeds', payments: 'd:/Feeds/', subscriber_events: 'D:/c' })), /gives Pipelines cdr, payments the same folder/);
    assert.throws(run({ ...pilotRecord(), orderCodes: ['INS-SP-BUSINESS-ASSURANCE'], pilot: { template: 'business-assurance', poll: {} } }),
        /'business-assurance' has no Pipeline with a dirs.poll folder/);
});
test('PA-10c pilotTemplate copies the bought template, wires dirs.poll, drops the samples, renames it', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-pilot-'));
    try {
        const t = join(root, 'fraud');
        mkdirSync(join(t, 'config', 'cdr'), { recursive: true });
        mkdirSync(join(t, 'data', 'samples'), { recursive: true });
        mkdirSync(join(t, 'data', 'cdr', 'database'), { recursive: true });
        writeFileSync(join(t, 'template.toon'), 'name: Telecom fraud\ntagline: "Samples in data/samples"\ndescription: "copy data/samples into data/inbox"\n');
        writeFileSync(join(t, 'config', 'cdr', 'cdr_pipeline.toon'), 'name: cdr\nactive: true\n\ndirs:\n  poll:       data/inbox/cdr\n  database:   data/cdr/database\n\nsink:\n  poll: untouched\n');
        writeFileSync(join(t, 'data', 'samples', 'cdr.csv'), 'a,b\n');
        writeFileSync(join(t, 'data', 'cdr', 'database', 'schema-seed.parquet'), 'x');
        pilotTemplate(root, { template: 'acme-pilot', from: 'fraud', title: 'Acme "pilot"', pipelines: [{ name: 'cdr', file: 'config/cdr/cdr_pipeline.toon', poll: 'D:/feeds/cdr' }] });
        const p = join(root, 'acme-pilot');
        assert.equal(readFileSync(join(p, 'config', 'cdr', 'cdr_pipeline.toon'), 'utf8'),
            'name: cdr\nactive: true\n\ndirs:\n  poll:       "D:/feeds/cdr"\n  database:   data/cdr/database\n\nsink:\n  poll: untouched\n');
        assert.ok(!existsSync(join(p, 'data', 'samples')), 'no golden corpus');
        assert.ok(existsSync(join(p, 'data', 'cdr', 'database', 'schema-seed.parquet')), 'the seeds a Space needs stay');
        assert.match(readFileSync(join(p, 'template.toon'), 'utf8'), /^name: "Acme pilot"\ntagline: "Pilot of fraud: [^\n]*\ndescription: "The fraud Space Template with each Pipeline reading the customer's own folder/);
        assert.ok(existsSync(join(t, 'data', 'samples', 'cdr.csv')), 'the bought template is untouched');
        assert.throws(() => pilotTemplate(root, { template: 'acme-pilot', from: 'fraud', pipelines: [] }), /already has a Space Template 'acme-pilot'/);
        assert.throws(() => pilotTemplate(root, { template: 'x-pilot', from: 'ghost', pipelines: [] }), /no Space Template 'ghost'/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});
test('PILOT-PRECREATED-SPACE-1 pilotSpace creates the pilot Space from the pilot template, as the server does', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-pspace-'));
    try {
        const t = join(root, '_templates', 'acme-pilot');
        mkdirSync(join(t, 'config', 'cdr'), { recursive: true });
        mkdirSync(join(t, 'config', 'jobs'), { recursive: true });
        mkdirSync(join(t, 'data', 'cdr', 'database'), { recursive: true });
        mkdirSync(join(t, 'portal'), { recursive: true });
        writeFileSync(join(t, 'template.toon'), 'name: "Acme pilot"\ntagline: "Pilot of fraud"\n');
        writeFileSync(join(t, 'portal', 'p.md'), 'gallery only');
        writeFileSync(join(t, 'config', 'cdr', 'cdr_pipeline.toon'), 'name: cdr\ndirs:\n  poll: "D:/feeds/cdr"\nspace: ${SPACE}\n');
        writeFileSync(join(t, 'config', 'jobs', 'a_job.toon'), 'job:\n  type: sql.template\n');
        writeFileSync(join(t, 'config', 'notes.md'), 'kept as is: ${SPACE}');
        writeFileSync(join(t, 'data', 'cdr', 'database', 'schema-seed.parquet'), 'x');
        const now = new Date('2026-11-12T00:00:00Z');
        const s = pilotSpace(root, { template: 'acme-pilot' }, now);
        assert.equal(s, join(root, 'acme-pilot'));
        for (const sub of ['config', 'data', 'audit', 'duckdb']) assert.ok(existsSync(join(s, sub)), sub);
        assert.equal(readFileSync(join(s, 'config', 'cdr', 'cdr_pipeline.toon'), 'utf8'), 'name: cdr\ndirs:\n  poll: "D:/feeds/cdr"\nspace: acme-pilot\n', 'the customer folder, ${SPACE} replaced');
        assert.equal(readFileSync(join(s, 'config', 'notes.md'), 'utf8'), 'kept as is: ${SPACE}', 'only .toon files are rewritten');
        assert.ok(existsSync(join(s, 'data', 'cdr', 'database', 'schema-seed.parquet')), 'the zero-row seeds come along');
        assert.ok(!existsSync(join(s, 'template.toon')) && !existsSync(join(s, 'portal')), 'gallery files stay in the gallery');
        assert.equal(readFileSync(join(s, 'space.toon'), 'utf8'), 'display_name: "Acme pilot"\ndescription: "Pilot of fraud"\ncreated_at: "2026-11-12T00:00:00.000Z"\n');
        assert.match(readFileSync(join(s, 'config', 'egress.toon'), 'utf8'), /^allow\[0\]:\n/);
        assert.ok(existsSync(join(t, 'template.toon')), 'the template stays');
        assert.throws(() => pilotSpace(root, { template: 'acme-pilot' }), /already has a Space 'acme-pilot'/);
        assert.throws(() => pilotSpace(root, { template: 'ghost' }), /no Space Template 'ghost'/);
        rmSync(s, { recursive: true });
        // a Job whose run depends on the author the server stamps is refused, and nothing is left behind
        for (const job of ['job:\n  type: maintenance\n  task: cleanup\n', 'job:\n  type: maintenance\n  task: restore\n',
            'job:\n  name: idx\n  type: LA.INDEX.BUILD\n', 'job:\n  type: "publish.postgres"\n', 'job:\n  name: nightly\n  template: nightly_restore\n']) {
            writeFileSync(join(t, 'config', 'jobs', 'b_job.toon'), job);
            assert.throws(() => pilotSpace(root, { template: 'acme-pilot' }), /depends on the author the server stamps/, job);
            assert.ok(!existsSync(s), 'a refused pilot Space leaves no folder');
        }
        // the Job template file itself (its type, e.g. maintenance, is read only at boot)
        rmSync(join(t, 'config', 'jobs', 'b_job.toon'));
        writeFileSync(join(t, 'config', 'jobs', 'nightly_restore_job_template.toon'), 'job_template:\n  name: nightly_restore\n  type: maintenance\n  task: restore\n');
        assert.throws(() => pilotSpace(root, { template: 'acme-pilot' }), /a Job template depends on the author/);
        assert.ok(!existsSync(s));
    } finally { rmSync(root, { recursive: true, force: true }); }
});
test('PILOT-PRECREATED-SPACE-1 the repo: telco-fraud makes a pilot Space; la-claims (an la.index.build Job) is refused', () => {
    const root = mkdtempSync(join(tmpdir(), 'pm-pspace-repo-'));
    try {
        for (const id of ['telco-fraud', 'la-claims']) cpSync(join('spaces', '_templates', id), join(root, '_templates', id), { recursive: true });
        assert.ok(existsSync(join(pilotSpace(root, { template: 'telco-fraud' }), 'config', 'cdr', 'cdr_pipeline.toon')));
        assert.throws(() => pilotSpace(root, { template: 'la-claims' }), /la\.index\.build Job depends on the author/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});
