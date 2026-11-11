#!/usr/bin/env node
// entitle - signed entitlements (PRODUCT-ASSEMBLY-1 PA-7a; plan docs/superpower/2026-11-10-product-assembly-plan.md §11 PA-7,
// decisions D-PA22 and D-PA23). An entitlement says what one customer's install may run: the Product, the Edition, the Apps
// and Add-ons bought, and the optional modules those bring. The server verifies it (com.gamma.module.Entitlements) and it
// only NARROWS: a module the build lacks cannot be switched on by a file. No file = today's behaviour (D-PA23).
//
//   node tools/entitle.mjs keygen --key-id <id>
//       a new Ed25519 issuer key: the private half to product-map/private/keys/<id>.pem (gitignored, never committed), the
//       public half as a line `<id>=<base64 SPKI>` in platform/inspecto-util/src/main/resources/META-INF/inspecto/
//       entitlement-keys.properties, which the core jar carries (a key beside an install cannot be trusted by editing a file)
//   node tools/entitle.mjs issue --customer <id> --title "Name" --product <id> [--product-file <picked.toon>] --edition <e>
//       [--addons a,b] [--apps x,y] [--nodes N] [--not-before YYYY-MM-DD] [--expires YYYY-MM-DD] --key <private.pem> [--out <dir>]
//       write <out>/entitlement.toon and <out>/entitlement.sig (base64 Ed25519 over the .toon file's bytes)
//   node tools/entitle.mjs verify --file <entitlement.toon> [--keys <entitlement-keys.properties>]
//   node tools/entitle.mjs show --file <entitlement.toon>
//
// The file is written in a fixed key order with values limited to a safe character set (no comma, colon, quote or #), so
// the server's TOON reader and tools/toon-subset.mjs read it identically. Exit 1 with the reason on any refusal.

import { createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify as edVerify } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gather } from './check-offerings.mjs';
import { addonClosure } from './pick-addons.mjs';
import { addonModules, builtAddons, gatherModel, loadProductFile, offeringAddons } from './products.mjs';
import { parseToon } from './toon-subset.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
export const SCHEMA = 'inspecto.entitlement/1';
export const KEYS_FILE = join(REPO, 'platform', 'inspecto-util', 'src', 'main', 'resources', 'META-INF', 'inspecto', 'entitlement-keys.properties');
const ID = /^[a-z][a-z0-9-]{1,63}$/;
const SAFE = /^[A-Za-z0-9 .&()'/+_-]{1,120}$/;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const list = (x) => (Array.isArray(x) ? x : []);
const fail = (m) => { throw new Error(m); };

/** Every module an Offering ships, along its `includes` chain. */
function offeringModules(edition, offerings, seen = []) {
    const o = offerings[edition] ?? fail(`edition '${edition}' has no offerings/${edition}.toon`);
    if (seen.includes(edition)) fail(`offering includes cycle ${[...seen, edition].join(' -> ')}`);
    const out = new Set(list(o.modules));
    for (const inc of list(o.includes)) offeringModules(inc, offerings, [...seen, edition]).forEach((m) => out.add(m));
    return out;
}

/**
 * The optional modules a customer may run in `edition`: the Edition's own platform (every module no Add-on owns) plus each
 * entitled Add-on's modules. A module two Add-ons share is entitled when either is.
 */
export function entitledModules(edition, addons, offerings) {
    const all = Object.keys(offeringAddons(edition, offerings));
    const owned = new Set(all.flatMap((a) => [...addonModules(edition, a, offerings)]));
    const out = new Set([...offeringModules(edition, offerings)].filter((m) => !owned.has(m)));
    for (const a of addons) addonModules(edition, a, offerings).forEach((m) => out.add(m));
    return [...out].sort();
}

/** The entitlement for a customer, resolved and checked against the definitions: { fields, added } (added = closure). */
export function resolveEntitlement(model, g, o) {
    const p = model.products[o.product] ?? fail(`unknown Product '${o.product}' (products/: ${Object.keys(model.products).join(', ')})`);
    if (!ID.test(o.customer ?? '')) fail(`--customer '${o.customer ?? ''}' must be lower-case letters, digits and hyphens`);
    if (!SAFE.test(o.title ?? '')) fail(`--title must be 1 to 120 letters, digits, spaces or . & ( ) ' / + _ - (no comma, colon or quote)`);
    const edition = String(o.edition ?? '').toLowerCase();
    if (!list(p.editions).includes(edition)) fail(`Product '${p.id}' does not come in edition '${edition}' (it lists ${list(p.editions).join(', ')})`);
    const built = builtAddons(edition, g.offerings);
    const apps = list(o.apps);
    const known = [...list(p.apps?.required), ...list(p.apps?.optional)];
    for (const a of apps) if (!known.includes(a)) fail(`App '${a}' is not one of Product '${p.id}''s Apps (${known.join(', ') || 'none'})`);
    for (const a of list(p.apps?.required)) if (!apps.includes(a)) fail(`App '${a}' is required by Product '${p.id}', so the entitlement lists it`);
    // A Domain product's Add-ons are its Apps' (§10: priced as Edition + Apps); a Platform product's are picked.
    const asked = p.shape === 'domain' && !list(o.addons).length ? [...new Set(apps.flatMap((a) => list(model.apps[a]?.requires?.addons)))] : list(o.addons);
    if (p.shape === 'platform') for (const a of asked) if (!list(p.addons).includes(a)) fail(`Add-on '${a}' is not one of Product '${p.id}''s (${list(p.addons).join(', ')})`);
    for (const a of asked) if (!built.has(a)) fail(`Add-on '${a}' is not built in edition '${edition}'`);
    const addons = new Set(asked);
    for (let grew = true; grew;) {
        const before = addons.size;
        for (const a of [...addons]) addonClosure(edition, a, g.offerings, g.manifests).forEach((x) => addons.add(x));
        grew = addons.size !== before;
    }
    for (const a of apps) {
        const missing = list(model.apps[a]?.requires?.addons).filter((x) => !addons.has(x));
        if (missing.length) fail(`App '${a}' needs Add-on(s) ${missing.join(', ')}, which the entitlement does not list`);
    }
    const nodes = o.nodes === undefined ? 1 : Number(o.nodes);
    if (!Number.isInteger(nodes) || nodes < 1) fail(`--nodes must be a whole number of at least 1`);
    for (const [k, v] of [['not-before', o.notBefore], ['expires', o.expires]]) if (v !== undefined && !DAY.test(v)) fail(`--${k} must be YYYY-MM-DD`);
    const issued = (o.now ?? new Date()).toISOString().slice(0, 10);
    const notBefore = o.notBefore ?? issued;
    if (o.expires && o.expires <= notBefore) fail(`--expires ${o.expires} is not after --not-before ${notBefore}`);
    const all = [...addons].sort();
    return {
        added: all.filter((a) => !asked.includes(a)),
        fields: {
            schema: SCHEMA, id: `${o.customer}-${issued}`, customer: o.customer, title: o.title, product: p.id, edition,
            apps: [...apps].sort(), addons: all, modules: entitledModules(edition, all, g.offerings), nodes,
            notBefore, expires: o.expires ?? null, issued, keyId: o.keyId,
        },
    };
}

/** The entitlement file's text, keys in a fixed order (the bytes the signature covers). */
export function toText(f) {
    for (const [k, v] of Object.entries(f)) if (typeof v === 'string' && /[,:"#\r\n]/.test(v) && k !== 'schema') fail(`${k} holds a character the file cannot carry: ${v}`);
    const arr = (k, v) => `${k}[${v.length}]:${v.length ? ` ${v.join(',')}` : ''}`;
    return [
        `schema: ${f.schema}`, `id: ${f.id}`, `customer: ${f.customer}`, `title: ${f.title}`, `product: ${f.product}`,
        `edition: ${f.edition}`, arr('apps', f.apps), arr('addons', f.addons), arr('modules', f.modules), `nodes: ${f.nodes}`,
        `notBefore: ${f.notBefore}`, ...(f.expires ? [`expires: ${f.expires}`] : []), `issued: ${f.issued}`, `keyId: ${f.keyId}`, '',
    ].join('\n');
}

/** base64 Ed25519 signature of `bytes` with a PEM private key. */
export const signBytes = (bytes, privatePem) => sign(null, bytes, createPrivateKey(privatePem)).toString('base64');

/** The public half as the core jar carries it: base64 of the SPKI DER (what Java's X509EncodedKeySpec reads). */
export const publicResource = (key) => (key.type === 'public' ? key : createPublicKey(key)).export({ type: 'spki', format: 'der' }).toString('base64');

/** The keys trusted for verification: { keyId: KeyObject } from an entitlement-keys.properties file (`id=base64`). */
export function trustedKeys(file = KEYS_FILE) {
    if (!existsSync(file)) return {};
    return Object.fromEntries(readFileSync(file, 'utf8').split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && l.includes('='))
        .map((l) => [l.slice(0, l.indexOf('=')).trim(), createPublicKey({ key: Buffer.from(l.slice(l.indexOf('=') + 1).trim(), 'base64'), format: 'der', type: 'spki' })]));
}

/** Verify a file's bytes and base64 signature against `trust`; returns the parsed fields or throws naming why. */
export function verifyBytes(bytes, sigText, trust) {
    const f = parseToon(bytes.toString('utf8'));
    if (f.schema !== SCHEMA) fail(`schema is '${f.schema ?? ''}', not ${SCHEMA}`);
    const key = trust[f.keyId] ?? fail(`key id '${f.keyId ?? ''}' is not a trusted issuer key (${Object.keys(trust).join(', ') || 'none'})`);
    if (!edVerify(null, bytes, key, Buffer.from(String(sigText).trim(), 'base64'))) fail('the signature does not match the file - it was changed after signing, or signed with another key');
    return f;
}

/** The signature file beside an entitlement file: entitlement.toon -> entitlement.sig. */
export const sigPathOf = (file) => join(dirname(file), basename(file).replace(/\.toon$/, '') + '.sig');

// ── command line ──

function main() {
    const a = process.argv.slice(2);
    const cmd = a[0];
    const val = (k) => { const i = a.indexOf(k); return i >= 0 && a[i + 1] !== undefined && !a[i + 1].startsWith('--') ? a[i + 1] : undefined; };
    const csv = (k) => (val(k) ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    try {
        if (cmd === 'keygen') {
            const id = val('--key-id') ?? fail('--key-id <id> is required');
            if (!ID.test(id)) fail(`--key-id '${id}' must be lower-case letters, digits and hyphens`);
            const priv = join(REPO, 'product-map', 'private', 'keys', `${id}.pem`);
            if (existsSync(priv) || trustedKeys()[id]) fail(`key '${id}' exists already - a key id is never reused`);
            const { publicKey, privateKey } = generateKeyPairSync('ed25519');
            mkdirSync(dirname(priv), { recursive: true });
            writeFileSync(priv, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
            mkdirSync(dirname(KEYS_FILE), { recursive: true });
            if (!existsSync(KEYS_FILE)) writeFileSync(KEYS_FILE, '# Entitlement issuer public keys (PRODUCT-ASSEMBLY-1 PA-7a): keyId=base64(SPKI DER). Written by tools/entitle.mjs keygen.\n');
            appendFileSync(KEYS_FILE, `${id}=${publicResource(publicKey)}\n`);
            console.log(`✓ private key ${priv} (gitignored - back it up; losing it means re-issuing every entitlement)\n✓ public key '${id}' added to ${KEYS_FILE} (commit it: the core jar trusts it)`);
        } else if (cmd === 'issue') {
            const keyFile = val('--key') ?? fail('--key <private.pem> is required');
            let model = gatherModel(REPO);
            let product = val('--product');
            if (val('--product-file')) ({ model, product } = loadProductFile(model, val('--product-file')));
            const { fields, added } = resolveEntitlement(model, gather(REPO), {
                customer: val('--customer'), title: val('--title'), product, edition: val('--edition'), addons: csv('--addons'),
                apps: csv('--apps'), nodes: val('--nodes'), notBefore: val('--not-before'), expires: val('--expires'),
                keyId: val('--key-id') ?? basename(keyFile).replace(/\.pem$/, ''),
            });
            const text = toText(fields);
            const out = resolve(val('--out') ?? join(REPO, 'product-map', 'private', 'entitlements', fields.customer));
            mkdirSync(out, { recursive: true });
            writeFileSync(join(out, 'entitlement.toon'), text);
            writeFileSync(join(out, 'entitlement.sig'), signBytes(Buffer.from(text), readFileSync(keyFile, 'utf8')) + '\n');
            if (added.length) console.log(`  the Add-ons' closure adds ${added.join(', ')}`);
            console.log(`✓ ${join(out, 'entitlement.toon')} + entitlement.sig - ${fields.product} x ${fields.edition}, ${fields.addons.length} Add-on(s), ${fields.modules.length} optional module(s)${fields.expires ? `, expires ${fields.expires}` : ''}. Install: copy both into the bundle's conf/.`);
        } else if (cmd === 'verify' || cmd === 'show') {
            const file = val('--file') ?? fail('--file <entitlement.toon> is required');
            const f = cmd === 'verify'
                ? verifyBytes(readFileSync(file), readFileSync(sigPathOf(file), 'utf8'), trustedKeys(val('--keys') ?? KEYS_FILE))
                : parseToon(readFileSync(file, 'utf8'));
            if (cmd === 'verify') console.log(`✓ ${file}: signed by '${f.keyId}', valid signature`);
            console.log(`  ${f.customer} (${f.title}) - ${f.product} x ${f.edition}, nodes ${f.nodes}, ${f.notBefore} to ${f.expires ?? 'no expiry'}`);
            console.log(`  Apps: ${list(f.apps).join(', ') || 'none'}\n  Add-ons: ${list(f.addons).join(', ') || 'none'}\n  optional modules: ${list(f.modules).join(', ') || 'none'}`);
        } else {
            fail('usage: entitle.mjs keygen --key-id <id> | issue ... --key <pem> | verify --file <f> | show --file <f>');
        }
    } catch (e) {
        console.error(`entitle: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
