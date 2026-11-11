// tools/entitle.mjs - signed entitlements (PRODUCT-ASSEMBLY-1 PA-7a).
import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gather } from './check-offerings.mjs';
import { entitledModules, publicResource, resolveEntitlement, signBytes, toText, trustedKeys, verifyBytes } from './entitle.mjs';
import { addonModules, gatherModel, offeringAddons } from './products.mjs';
import { parseToon } from './toon-subset.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const model = gatherModel(REPO);
const g = gather(REPO);
const now = new Date('2026-11-11T09:00:00Z');
const base = { customer: 'acme', title: 'Acme Ltd', product: 'inspecto', edition: 'standard', keyId: 'k', now };

test('a Platform product entitlement grows by the Add-on closure and lists the optional modules it allows', () => {
    const { fields, added } = resolveEntitlement(model, g, { ...base, addons: ['screening'], nodes: '2', expires: '2027-11-11' });
    assert.ok(added.includes('incidents') && added.includes('scoringLists'), `closure added: ${added}`);
    assert.deepEqual(fields.addons, ['screening', ...added].sort());
    assert.ok(fields.modules.includes('screening'));
    assert.equal(fields.nodes, 2);
    assert.equal(fields.notBefore, '2026-11-11');
    assert.equal(fields.id, 'acme-2026-11-11');
    // an Add-on left out keeps its own modules out, unless an entitled Add-on shares them
    const all = Object.keys(offeringAddons('standard', g.offerings));
    for (const a of all.filter((x) => !fields.addons.includes(x)))
        for (const m of addonModules('standard', a, g.offerings))
            if (!fields.addons.some((x) => addonModules('standard', x, g.offerings).has(m))) assert.ok(!fields.modules.includes(m), `${m} of ${a} is out`);
});

test('the Edition platform (modules no Add-on owns) is always listed; a shared module comes with either owner', () => {
    const offerings = { e: { modules: ['plat', 'a1', 'shared'], addons: { a: { status: 'built', modules: ['a1', 'shared'] }, b: { status: 'built', modules: ['shared'] } } } };
    assert.deepEqual(entitledModules('e', [], offerings), ['plat']);
    assert.deepEqual(entitledModules('e', ['b'], offerings), ['plat', 'shared']);
    assert.deepEqual(entitledModules('e', ['a'], offerings), ['a1', 'plat', 'shared']);
});

test('a Domain product takes its Apps Add-ons; a required App must be listed', () => {
    const telco = model.products.telco;
    const apps = [...telco.apps.required, ...(telco.apps.optional ?? [])];
    const { fields } = resolveEntitlement(model, g, { ...base, product: 'telco', edition: 'standard', apps });
    for (const a of apps) for (const x of model.apps[a].requires?.addons ?? []) assert.ok(fields.addons.includes(x), `${a} needs ${x}`);
    if (telco.apps.required.length)
        assert.throws(() => resolveEntitlement(model, g, { ...base, product: 'telco', apps: [] }), /is required by Product 'telco'/);
});

test('refusals name the problem', () => {
    assert.throws(() => resolveEntitlement(model, g, { ...base, product: 'nope' }), /unknown Product 'nope'/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, edition: 'gold' }), /does not come in edition 'gold'/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, edition: 'basic', addons: ['screening'] }), /not built in edition 'basic'/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, addons: ['ghost'] }), /'ghost' is not one of Product 'inspecto'/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, title: 'Acme, Inc' }), /no comma, colon or quote/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, customer: 'Acme' }), /lower-case/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, nodes: '0' }), /at least 1/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, expires: '2026-11-01' }), /is not after/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, expires: '11/11/2027' }), /YYYY-MM-DD/);
    assert.throws(() => resolveEntitlement(model, g, { ...base, apps: ['ghost-app'] }), /'ghost-app' is not one of Product 'inspecto''s Apps/);
});

test('the signed file reads back, verifies, and refuses a changed byte or an untrusted key', () => {
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    const pem = privateKey.export({ type: 'pkcs8', format: 'pem' });
    const { fields } = resolveEntitlement(model, g, { ...base, addons: ['incidents'], expires: '2027-11-11' });
    const text = toText(fields);
    const back = parseToon(text);
    for (const k of ['customer', 'title', 'product', 'edition', 'keyId', 'expires']) assert.equal(back[k], String(fields[k]), k);
    assert.deepEqual(back.modules, fields.modules);
    const sig = signBytes(Buffer.from(text), pem);
    const dir = mkdtempSync(join(tmpdir(), 'entitle-'));
    try {
        const keys = join(dir, 'keys.properties');
        writeFileSync(keys, `# test\nk=${publicResource(publicKey)}\n`);
        const trust = trustedKeys(keys);
        assert.equal(verifyBytes(Buffer.from(text), sig, trust).customer, 'acme');
        assert.throws(() => verifyBytes(Buffer.from(text.replace('nodes: 1', 'nodes: 5')), sig, trust), /signature does not match/);
        assert.throws(() => verifyBytes(Buffer.from(text), sig, {}), /key id 'k' is not a trusted issuer key/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('the repo trusts the issuer key the core jar carries', () => {
    assert.ok(trustedKeys()['inspecto-issuer-1'], 'platform/inspecto-util/.../entitlement-keys.properties holds inspecto-issuer-1');
});
