#!/usr/bin/env node
// customer-build — one customer's build, assembled (PRODUCT-ASSEMBLY-1 PA-10b; plan docs/superpower/2026-11-10-product-assembly-plan.md
// §11 PA-10, D-PA33 and D-PA34; first built as PRODUCT-MAP-1 slice S5).
//
//   node tools/customer-build.mjs <customer|record.toon> [--bundle <product-bundle-dir>] [--key <issuer.pem>] [--out target/customers/<customer>]
//
// RESOLVE  tools/customer.mjs customerAssembly(): the customer record (its Product, Edition and Order Codes) becomes an Assembly
//          Plan (tools/assemble.mjs) narrowed to the modules and templates the customer keeps, with the entitlement to sign.
// OUTPUT   <out>/assembly-plan.json, <out>/customer-<id>.toon (the Offering), <out>/bom.csv (WITH private prices; never shipped),
//          <out>/entitlement.toon + .sig (D-PA34: every customer build carries one; refused without the issuer key).
// BUNDLE   (D-PM8, unchanged: trim, do not teach package.ps1) --bundle copies a bundle package.ps1 built for the same Product and
//          Edition into <out>/bundle and executes the plan's customer block: removes the jars and per-jar SBOMs of modules not kept
//          and the Space Templates not kept, filters modules.list, writes lib/product.json from the plan, stamps edition.properties
//          with customer= and offering=, stages conf/entitlement.toon + .sig, and writes customer-bom.csv WITHOUT prices. The combined
//          edition SBOM (sbom/inspecto-<edition>.*) is left as is and OVER-declares the trimmed bundle (recorded residual).

import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { bomCsv, customerPlan } from './customer.mjs';
import { signBytes, toText } from './entitle.mjs';

export { bomCsv, resolve, templateNeeds } from './customer.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
export const DEFAULT_KEY = join(REPO, 'product-map', 'private', 'keys', 'inspecto-issuer-1.pem');
const fail = (m) => { throw new Error(m); };

/** The entitlement files of a plan, signed with a PEM private key: { toon, sig }. */
export function signedEntitlement(plan, privatePem) {
    const toon = toText(plan.customer.entitlement);
    return { toon, sig: signBytes(Buffer.from(toon), privatePem) + '\n' };
}

/**
 * Copy a Product bundle and trim it to a customer plan. Returns { jars, templates } removed. Throws on a bundle that does not
 * match the plan (another Product or Edition, a jar the plan removes that is missing, or a composed Space joined from other Apps).
 */
export function trimBundle(src, dest, plan, entitlement) {
    // BUNDLE-LAYOUT-1: jars, modules.list and edition.properties live in <bundle>/lib; list entries are root-relative lib/<jar>.
    const c = plan.customer ?? fail('the plan has no customer block (assemble.mjs --customer)');
    const props = readFileSync(join(src, 'lib', 'edition.properties'), 'utf8');
    const ed = /^edition=(.+)$/m.exec(props)?.[1].trim();
    if (ed?.toLowerCase() !== plan.edition.toLowerCase()) fail(`bundle ${src} is edition '${ed}', the customer's Edition is '${plan.edition}'`);
    const descFile = join(src, 'lib', 'product.json');
    const built = existsSync(descFile) ? JSON.parse(readFileSync(descFile, 'utf8')) : fail(`bundle ${src} has no lib/product.json - build it with package.ps1 -Product ${plan.product.id}`);
    if (built.id !== plan.product.id) fail(`bundle ${src} is Product '${built.id}', the customer's Product is '${plan.product.id}'`);
    const foreign = (built.apps ?? []).map((a) => a.id).filter((a) => c.productApps && !c.productApps.includes(a));
    if (foreign.length) fail(`bundle ${src} was built with App(s) ${foreign.join(', ')}, which Product '${plan.product.id}' does not define (a partner App, --app-root): a customer build of it is not supported yet`);
    if (plan.space) {
        const want = plan.descriptor.apps.map((a) => a.id).join(','), has = (built.apps ?? []).map((a) => a.id).join(',');
        if (want !== has) fail(`bundle ${src} composed Space '${plan.space.id}' from Apps ${has}; this customer holds ${want}. A customer build trims jars and templates, not a composed Space`);
    }
    rmSync(dest, { recursive: true, force: true });
    cpSync(src, dest, { recursive: true });
    const jars = [];
    for (const jar of c.removedJars) {
        if (!existsSync(join(dest, 'lib', jar))) fail(`bundle has no lib/${jar}, which the plan removes`);
        rmSync(join(dest, 'lib', jar)); rmSync(join(dest, 'sbom', jar.replace(/\.jar$/, '.sbom.cdx.json')), { force: true }); jars.push(jar);
    }
    const ml = join(dest, 'lib', 'modules.list');
    writeFileSync(ml, readFileSync(ml, 'utf8').split(/\r?\n/).filter((l) => l && !jars.includes(l.trim().replace(/^lib\//, ''))).join('\n') + '\n');
    const keepT = new Set([...plan.templates, ...(plan.space ? [plan.space.id] : [])]);
    const tdir = join(dest, 'spaces', '_templates');
    const templates = [];
    if (existsSync(tdir)) for (const t of listDirs(tdir)) { if (keepT.has(t)) continue; rmSync(join(tdir, t), { recursive: true }); templates.push(t); }
    writeFileSync(join(dest, 'lib', 'product.json'), JSON.stringify(plan.descriptor, null, 2) + '\n');
    // The bundle's `apps=` line is the Product build's selection; the customer's is the plan's.
    const apps = plan.editionProperties?.apps ?? '';
    const stamped = /^apps=.*$/m.test(props) ? props.replace(/^apps=.*$/m, apps ? `apps=${apps}` : '').replace(/\n{2,}/g, '\n') : props;
    writeFileSync(join(dest, 'lib', 'edition.properties'), stamped.trimEnd() + `\ncustomer=${c.id}\noffering=customer-${c.id}\n`);
    writeFileSync(join(dest, `customer-${c.id}.toon`), c.offering);
    writeFileSync(join(dest, 'customer-bom.csv'), bomCsv(c.bom, { prices: false }));
    mkdirSync(join(dest, 'conf'), { recursive: true });
    writeFileSync(join(dest, 'conf', 'entitlement.toon'), entitlement.toon);
    writeFileSync(join(dest, 'conf', 'entitlement.sig'), entitlement.sig);
    return { jars, templates };
}
const listDirs = (d) => readdirSync(d, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name);

function main() {
    const args = process.argv.slice(2);
    const arg = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
    const id = args.find((a, i) => !a.startsWith('--') && !['--bundle', '--out', '--root', '--key'].includes(args[i - 1]));
    if (!id) { console.error('usage: node tools/customer-build.mjs <customer|record.toon> [--bundle <product-bundle-dir>] [--key <issuer.pem>] [--out <dir>]'); process.exit(2); }
    try {
        const root = arg('--root', REPO);
        const keyFile = arg('--key', DEFAULT_KEY);
        if (!existsSync(keyFile)) fail(`no issuer key ${keyFile}: every customer build carries a signed entitlement (D-PA34) - pass --key <issuer.pem>`);
        const { plan, bom } = customerPlan(root, id, { keyId: keyFile.split(/[\\/]/).pop().replace(/\.pem$/, '') });
        const customer = plan.customer;
        const entitlement = signedEntitlement(plan, readFileSync(keyFile, 'utf8'));
        const out = arg('--out', join(root, 'target', 'customers', customer.id));
        mkdirSync(out, { recursive: true });
        writeFileSync(join(out, 'assembly-plan.json'), JSON.stringify(plan, null, 2) + '\n');
        writeFileSync(join(out, `customer-${customer.id}.toon`), plan.customer.offering);
        writeFileSync(join(out, 'bom.csv'), bomCsv(bom));
        writeFileSync(join(out, 'entitlement.toon'), entitlement.toon);
        writeFileSync(join(out, 'entitlement.sig'), entitlement.sig);
        const c = plan.customer;
        console.log(`customer-build ${customer.id}: ${plan.product.id} x ${plan.edition}; keeps ${c.keep.length} optional module(s), leaves out ${c.removed.length}: ${c.removed.join(', ') || '-'}`);
        console.log(`  Apps: ${plan.apps.map((a) => a.id).join(', ') || 'none'}; templates: ${plan.templates.join(', ') || 'none'}; entitlement ${c.entitlement.id} (key ${c.entitlement.keyId})`);
        for (const b of bom) console.log(`  ${b.line.padEnd(19)} ${b.orderCode.padEnd(28)} ${b.reason}`);
        if (args.includes('--bundle')) {
            const t = trimBundle(arg('--bundle'), join(out, 'bundle'), plan, entitlement);
            console.log(`  bundle -> ${join(out, 'bundle')}: removed ${t.jars.length} jar(s), ${t.templates.length} Space Template(s); conf/entitlement.toon staged; the combined edition SBOM still lists the removed jars`);
        }
    } catch (e) {
        console.error(`customer-build: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
