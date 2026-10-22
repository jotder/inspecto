#!/usr/bin/env node
// customer-build — a customer-specific build from the Product Map (docs/archived-documents/plans-archive/product-map-plan.md, PRODUCT-MAP-1 slice S5).
//
//   node tools/customer-build.mjs <customer> [--bundle <edition-bundle-dir>] [--out target/customers/<customer>] [--root .]
//
// INPUT   product-map/private/customers/<customer>.toon (gitignored, D-PM3):
//           id: demo-telco
//           title: Demo Telco
//           edition: standard
//           orderCodes[2]: INS-AO-LINK-ANALYSIS,INS-SP-TELCO-FRAUD
// MEANING (operator 2026-10-18) the Edition gives the PLATFORM: its modules that no add-on names. Only the add-ons, Solution packs and
//         module/functionality items the customer buys are added. An item outside the Edition is refused (its jar is not in the bundle).
// CLOSURE a kept module's `requires.modules` are kept too; a module pulled in that way is listed on the bill of materials as
//         `required` ("needed by X"), so whether to charge for it is a decision, not an accident.
// PACKS   a bought Space Template's needs are DERIVED from the manifests: each `type:` in its config/jobs/*.toon against
//         provides.jobTypes, each config/registry/<kind>s/ folder against provides.configKinds. Those modules are kept like a
//         `requires.modules` edge (a `required` line). ⚠ Ops configs under config/ops/ (e.g. a Case Rule) name no owner in any
//         manifest, so they are not derived: a pack that needs Incidents only through them is not detected.
// OUTPUT  <out>/customer-<id>.toon (the Offering), <out>/bom.csv (WITH private prices; never shipped).
// BUNDLE  (operator 2026-10-18: trim, do not teach package.ps1) --bundle copies an Edition bundle built by package.ps1 into
//         <out>/bundle, removes the jars + per-jar SBOMs of modules not kept and the Space Templates not bought, filters
//         modules.list, stamps edition.properties with customer= and offering=, and writes customer-bom.csv WITHOUT prices. The
//         combined edition SBOM (sbom/inspecto-<edition>.*) is left as is and OVER-declares the trimmed bundle (recorded residual).

import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseToon } from './check-offerings.mjs';
import { gather } from './check-product-map.mjs';
import { readPrices } from './render-product-map.mjs';

const ROLE_ALWAYS = new Set(['base', 'internal']);

/** customer, product-map files, world, prices -> { offering, keep[], removed[], packs[], bom[], refusals[] }. Pure. */
export function resolve(customer, files, world, prices = {}) {
    const { manifests, offerings } = world;
    const refusals = [];
    const items = Object.values(files).map((t) => parseToon(t));
    if (!offerings[customer.edition]) return { refusals: [`edition '${customer.edition}' has no offerings/${customer.edition}.toon`] };
    const R = { modules: new Set(), addons: {}, packs: new Set() };
    const walk = (id, seen = new Set()) => {
        if (seen.has(id)) return; seen.add(id);
        const o = offerings[id];
        (o.modules ?? []).forEach((m) => R.modules.add(m));
        for (const [n, a] of Object.entries(o.addons ?? {})) R.addons[n] = a.modules ?? a.hostedBy ?? [];
        (o.contentPacks ?? []).forEach((p) => R.packs.add(p));
        (o.includes ?? []).forEach((i) => walk(i, seen));
    };
    walk(customer.edition);
    const addonModules = new Set(Object.values(R.addons).flat());
    const keep = new Map([...R.modules].filter((m) => !addonModules.has(m)).map((m) => [m, 'edition platform']));
    const packs = new Set();
    const price = (code) => prices[code] ?? {};
    const line = (it, kind, reason) => ({ orderCode: it?.orderCode ?? '', title: it?.title ?? '', kind: it?.kind ?? '', line: kind, reason,
        enforcement: it?.enforcement ?? '', listPrice: price(it?.orderCode).listPrice ?? '', currency: price(it?.orderCode).currency ?? '' });
    const bom = [];
    const edItem = items.find((i) => i.kind === 'edition' && i.offering === customer.edition);
    bom.push(line(edItem, 'edition', `base Edition ${customer.edition}`));
    const bought = new Set();
    const needs = []; // [module, who needs it] from bought Space Templates
    for (const code of customer.orderCodes ?? []) {
        const it = items.find((i) => i.orderCode === code);
        if (!it) { refusals.push(`Order Code '${code}' is in no Product Map Item`); continue; }
        if (it.billable !== 'true') { refusals.push(`${code} (${it.id}) is not billable`); continue; }
        if (it.kind === 'edition') { if (it.offering !== customer.edition) refusals.push(`${code} is Edition '${it.offering}', but the customer's Edition is '${customer.edition}'`); continue; }
        if (it.offeringAddon !== undefined) {
            if (!R.addons[it.offeringAddon]) { refusals.push(`${code} add-on '${it.offeringAddon}' is not in Edition '${customer.edition}'`); continue; }
            R.addons[it.offeringAddon].forEach((m) => keep.set(m, `bought ${code}`));
            bought.add(it.offeringAddon);
        } else if (it.template !== undefined) {
            if (!R.packs.has(it.template)) { refusals.push(`${code} Space Template '${it.template}' is not in Edition '${customer.edition}'`); continue; }
            packs.add(it.template);
            for (const m of world.templateNeeds?.[it.template] ?? []) needs.push([m, `Space Template '${it.template}'`]);
        } else if (it.module !== undefined) {
            const role = manifests[it.module]?.offeringRole;
            if (!ROLE_ALWAYS.has(role) && !R.modules.has(it.module)) { refusals.push(`${code} module '${it.module}' is not in Edition '${customer.edition}'`); continue; }
            if (!ROLE_ALWAYS.has(role)) keep.set(it.module, `bought ${code}`);
        }
        bom.push(line(it, 'bought', it.enforcement === 'build' ? 'left out of the build unless bought' : `enforced by ${it.enforcement}`));
    }
    // requires.modules closure (and bought Templates' needs); a module pulled in for another is a `required` line, not silently free.
    const queue = [...keep.keys()].map((m) => [m, m]);
    for (const [m, who] of needs) queue.push([null, who, m]);
    while (queue.length) {
        const [m, label = m, only] = queue.shift();
        for (const r of only ? [only] : manifests[m]?.requires?.modules ?? []) {
            if (ROLE_ALWAYS.has(manifests[r]?.offeringRole) || keep.has(r)) continue;
            keep.set(r, `required by ${label}`); queue.push([r, r]);
            if (!R.modules.has(r)) refusals.push(`${label} needs module '${r}', which Edition '${customer.edition}' does not ship`);
            const owners = Object.entries(R.addons).filter(([, ms]) => ms.includes(r)).map(([n]) => n);
            for (const n of owners) { const it = items.find((i) => i.offeringAddon === n); bom.push(line(it, 'required', `module '${r}' is needed by ${label === m ? `'${m}'` : label}`)); }
        }
    }
    // add-ons not bought whose modules are all kept anyway: installed, not sold (e.g. workflowSla when incidents is bought).
    for (const [n, ms] of Object.entries(R.addons)) {
        if (bought.has(n) || !ms.length || !ms.every((m) => keep.has(m))) continue;
        if (bom.some((b) => b.line === 'required' && b.orderCode === items.find((i) => i.offeringAddon === n)?.orderCode)) continue;
        bom.push(line(items.find((i) => i.offeringAddon === n), 'installed-not-sold', 'its modules ship for a bought item; not licensed'));
    }
    const removed = [...R.modules].filter((m) => !keep.has(m)).sort();
    const listed = [...keep.keys()].filter((m) => !ROLE_ALWAYS.has(manifests[m]?.offeringRole)).sort();
    const tier = offerings[customer.edition].tier ?? customer.edition;
    const offering = [`id: customer-${customer.id}`, `title: ${customer.title ?? customer.id}`, `tier: ${tier}`, `baseEdition: ${customer.edition}`,
        `modules[${listed.length}]: ${listed.join(',')}`, `contentPacks[${packs.size}]: ${[...packs].sort().join(',')}`].join('\n') + '\n';
    return { offering, keep: listed, keepReasons: Object.fromEntries(keep), removed, packs: [...packs].sort(), allPacks: [...R.packs], bom, refusals };
}

const BOM_COLS = ['line', 'orderCode', 'title', 'kind', 'enforcement', 'reason', 'listPrice', 'currency'];
const cell = (v) => (/[",\r\n]/.test(String(v ?? '')) ? `"${String(v).replace(/"/g, '""')}"` : String(v ?? ''));
export const bomCsv = (bom, { prices = true } = {}) => {
    const cols = prices ? BOM_COLS : BOM_COLS.filter((c) => c !== 'listPrice' && c !== 'currency');
    return [cols.join(','), ...bom.map((b) => cols.map((c) => cell(b[c])).join(','))].join('\r\n') + '\r\n';
};

/** spaces/_templates/<t> -> the modules its Jobs and registry configs need, from the manifests' provides.jobTypes / configKinds. */
export function templateNeeds(root, manifests) {
    const owner = {};
    for (const [id, m] of Object.entries(manifests)) {
        for (const j of m.provides?.jobTypes ?? []) owner[`job:${j}`] = id;
        for (const k of m.provides?.configKinds ?? []) owner[`kind:${k}`] = id;
    }
    const tdir = join(root, 'spaces', '_templates'); const out = {};
    for (const t of existsSync(tdir) ? listDirs(tdir) : []) {
        const need = new Set(); const cfg = join(tdir, t, 'config');
        const jobs = join(cfg, 'jobs');
        for (const f of existsSync(jobs) ? readdirSync(jobs).filter((n) => n.endsWith('.toon')) : []) {
            const type = /^type:\s*(\S+)/m.exec(readFileSync(join(jobs, f), 'utf8'))?.[1];
            if (owner[`job:${type}`]) need.add(owner[`job:${type}`]);
        }
        const reg = join(cfg, 'registry');
        for (const d of existsSync(reg) ? listDirs(reg) : []) { const k = d.replace(/s$/, ''); if (owner[`kind:${k}`]) need.add(owner[`kind:${k}`]); }
        out[t] = [...need].sort();
    }
    return out;
}

/** Copy an Edition bundle and trim it to the resolution. Returns a summary; throws on a bundle that does not match. */
export function trimBundle(src, dest, customer, r) {
    const props = readFileSync(join(src, 'edition.properties'), 'utf8');
    const ed = /^edition=(.+)$/m.exec(props)?.[1].trim().toLowerCase();
    if (ed !== customer.edition) throw new Error(`bundle ${src} is edition '${ed}', the customer's Edition is '${customer.edition}'`);
    rmSync(dest, { recursive: true, force: true });
    cpSync(src, dest, { recursive: true });
    const jars = [];
    for (const m of r.removed) {
        const jar = `inspecto-${m}.jar`;
        if (!existsSync(join(dest, jar))) throw new Error(`bundle has no ${jar} for removed module '${m}'`);
        rmSync(join(dest, jar)); rmSync(join(dest, 'sbom', `inspecto-${m}.sbom.cdx.json`), { force: true }); jars.push(jar);
    }
    const ml = join(dest, 'modules.list');
    writeFileSync(ml, readFileSync(ml, 'utf8').split(/\r?\n/).filter((l) => l && !jars.includes(l.trim())).join('\n') + '\n');
    const tdir = join(dest, 'spaces', '_templates');
    const templates = [];
    if (existsSync(tdir)) for (const t of listDirs(tdir)) {
        if (r.packs.includes(t) || !existsSync(join(tdir, t))) continue;
        rmSync(join(tdir, t), { recursive: true }); templates.push(t);
    }
    writeFileSync(join(dest, 'edition.properties'), props.trimEnd() + `\ncustomer=${customer.id}\noffering=customer-${customer.id}\n`);
    writeFileSync(join(dest, `customer-${customer.id}.toon`), r.offering);
    writeFileSync(join(dest, 'customer-bom.csv'), bomCsv(r.bom, { prices: false }));
    return { jars, templates };
}
const listDirs = (d) => readdirSync(d, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name);

function main() {
    const args = process.argv.slice(2);
    const arg = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
    const id = args.find((a, i) => !a.startsWith('--') && !['--bundle', '--out', '--root'].includes(args[i - 1]));
    if (!id) { console.error('usage: node tools/customer-build.mjs <customer> [--bundle <edition-bundle-dir>] [--out <dir>]'); process.exit(2); }
    const root = arg('--root', '.');
    const cf = join(root, 'product-map', 'private', 'customers', `${id}.toon`);
    if (!existsSync(cf)) { console.error(`no customer file ${cf}`); process.exit(2); }
    const customer = parseToon(readFileSync(cf, 'utf8'));
    const [files, world] = gather(root);
    world.templateNeeds = templateNeeds(root, world.manifests);
    const pf = join(root, 'product-map', 'private', 'prices.csv');
    const r = resolve(customer, files, world, existsSync(pf) ? readPrices(readFileSync(pf, 'utf8')) : {});
    for (const f of r.refusals) console.error(`✗ ${f}`);
    if (r.refusals.length) { console.error(`customer-build: REFUSED (${r.refusals.length}); nothing written`); process.exit(1); }
    const out = arg('--out', join(root, 'target', 'customers', id));
    mkdirSync(out, { recursive: true });
    writeFileSync(join(out, `customer-${id}.toon`), r.offering);
    writeFileSync(join(out, 'bom.csv'), bomCsv(r.bom));
    console.log(`customer-build ${id}: Edition ${customer.edition}; keeps ${r.keep.length} optional module(s), leaves out ${r.removed.length}: ${r.removed.join(', ') || '-'}`);
    for (const b of r.bom) console.log(`  ${b.line.padEnd(19)} ${b.orderCode.padEnd(28)} ${b.reason}`);
    if (args.includes('--bundle')) {
        const t = trimBundle(arg('--bundle'), join(out, 'bundle'), customer, r);
        console.log(`  bundle -> ${join(out, 'bundle')}: removed ${t.jars.length} jar(s), ${t.templates.length} Space Template(s); the combined edition SBOM still lists the removed jars`);
    }
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
