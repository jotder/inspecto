#!/usr/bin/env node
// assemble — the Product assembler (PRODUCT-ASSEMBLY-1 PA-2; plan docs/superpower/2026-11-10-product-assembly-plan.md §5).
// Resolves Product -> Apps -> Add-ons -> the Edition's Offering classpath and writes an ASSEMBLY PLAN: everything
// inspecto/package.ps1 needs to build one Product build, so package.ps1 executes a plan instead of deciding one.
//
//   node tools/assemble.mjs --product la --edition advanced [--distribution demo] [--apps a,b] [--target windows] [--out f]
//   node tools/assemble.mjs --product la --lock [--out products/la.lock]     the Product lock (versions + content hashes)
//   node tools/assemble.mjs --diff-lock <old.lock> <new.lock>                 the upgrade diff between two locks
//
// REFUSES (exit 1, the reason named): an unknown Product / App; an edition, distribution or target the Product does not
// list or that is not built yet; a demo build on an edition without the demo sign-in swap; a chosen App whose Add-ons the
// edition lacks; and any finding of tools/check-products.mjs (a plan is never assembled from broken definitions).
//
// WHAT A PLAN DOES NOT DO YET (PA-2 scope): the module set is the whole Edition's Offering classpath, exactly as before -
// trimming to the Product's Apps is customer-build's job until PA-10. Entitlements (PA-7), container / air-gap targets
// (PA-8) and the per-Product nav (PA-3) are not in it.

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { addonModules, builtAddons, carryPartnerApps, checkModel, DEMO_EDITIONS, demoTextOf, DISTRIBUTIONS, gatherModel, PLANNED_DISTRIBUTIONS, productApps, productNavByApp, productTemplates, seedToolOf, spaceParts } from './products.mjs';
import { classpath, mvnModules, normalizeEdition } from './offering-classpath.mjs';
import { gather } from './check-offerings.mjs';

export const PLAN_SCHEMA = 'inspecto.assembly-plan/1';
export const LOCK_SCHEMA = 'inspecto.product-lock/1';
/** One package.ps1 run makes the windows_amd64 zip and, with a Linux runtime cache, the linux_amd64 zip (see package.ps1's header). */
export const TARGETS = ['windows', 'linux'];
export const PLANNED_TARGETS = { container: 'PA-8 (OCI image + Helm chart per Product)', airgap: 'PA-8 (air-gapped bundle)' };

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const fail = (msg) => { throw new Error(msg); };

/**
 * The Assembly Plan for one Product build. `model` is tools/products.mjs gatherModel(); `g` is check-offerings gather()
 * (the Offering classpath's inputs). `apps` (optional) narrows the Product's OPTIONAL Apps; required Apps always ship.
 */
export function assemble(model, g, { product, edition, distribution = 'production', apps: chosen = null, target = 'windows' }) {
    const findings = checkModel(model);
    if (findings.length) fail(`the definitions do not pass tools/check-products.mjs - fix them first:\n  ${findings.join('\n  ')}`);
    const p = model.products[product] ?? fail(`unknown Product '${product}' (products/: ${Object.keys(model.products).join(', ')})`);
    const ed = String(edition ?? '').toLowerCase();
    if (!(p.editions ?? []).includes(ed)) fail(`Product '${product}' does not come in edition '${edition}' (it lists ${(p.editions ?? []).join(', ')})`);
    if (PLANNED_DISTRIBUTIONS[distribution]) fail(`distribution '${distribution}' is not built yet: ${PLANNED_DISTRIBUTIONS[distribution]}`);
    if (!DISTRIBUTIONS.includes(distribution)) fail(`distribution '${distribution}' is not one of ${DISTRIBUTIONS.join(', ')}`);
    if (!(p.distributions ?? []).includes(distribution)) fail(`Product '${product}' does not offer the '${distribution}' distribution (it lists ${(p.distributions ?? []).join(', ')})`);
    if (PLANNED_TARGETS[target]) fail(`target '${target}' is not built yet: ${PLANNED_TARGETS[target]}`);
    if (!TARGETS.includes(target)) fail(`target '${target}' is not one of ${TARGETS.join(', ')}`);
    const demo = distribution === 'demo';
    if (demo && !DEMO_EDITIONS.includes(ed)) fail(`the demo distribution exists for ${DEMO_EDITIONS.join(' and ')} only (the demo sign-in replaces the OIDC jars), not '${ed}'`);

    const offered = builtAddons(ed, model.offerings);
    const { required, optional } = productApps(p);
    for (const a of chosen ?? []) if (!required.includes(a) && !optional.includes(a)) fail(`App '${a}' is not one of Product '${product}''s Apps (${[...required, ...optional].join(', ') || 'none'})`);
    const lacking = (a) => (model.apps[a].requires?.addons ?? []).filter((x) => !offered.has(x));
    const notes = [];
    const selected = [...required];
    for (const a of optional) {
        if (chosen && !chosen.includes(a)) continue;
        const missing = lacking(a);
        if (!missing.length) selected.push(a);
        else if (chosen) fail(`App '${a}' needs Add-on(s) ${missing.join(', ')}, which edition '${ed}' does not offer`);
        else notes.push(`optional App '${a}' left out: edition '${ed}' lacks Add-on(s) ${missing.join(', ')}`);
    }
    for (const a of required) { const missing = lacking(a); if (missing.length) fail(`required App '${a}' needs Add-on(s) ${missing.join(', ')}, which edition '${ed}' does not offer`); }

    const addons = p.shape === 'platform'
        ? (p.addons ?? []).filter((a) => offered.has(a))
        : [...new Set(selected.flatMap((a) => model.apps[a].requires?.addons ?? []))];
    const Edition = normalizeEdition(ed);
    const templates = productTemplates(p, selected, model.apps);
    const seedSpace = demo && p.demoSpace ? p.demoSpace : null;
    return {
        schema: PLAN_SCHEMA,
        product: { id: p.id, title: p.title, shape: p.shape, brand: p.brand?.name ?? p.title },
        edition: Edition,
        distribution,
        target,
        platformVersion: model.pomVersion,
        host: p.host,
        // PA-5: the host's build configuration when the host builds several Products (null = its default build).
        hostConfiguration: p.hostConfiguration ?? null,
        demo,
        industry: p.industry ?? null,
        apps: selected.map((a) => ({ id: a, version: model.apps[a].version })),
        addons,
        modules: classpath(Edition, { demo, g }),
        mvnModules: mvnModules(Edition, g),
        templates,
        // PA-6a (D-PA18): the templates a partner App ships from its own folder, staged from there rather than from git.
        partnerTemplates: templates.filter((t) => model.templateDirs?.[t]).map((t) => ({ id: t, dir: model.templateDirs[t] })),
        // PA-5 (D-PA14): the Space Template composed from the selected Apps' templates (tools/compose-space.mjs), or null.
        space: p.space
            ? { id: p.space, title: p.brand?.name ?? p.title, industry: p.industry ?? null, parts: spaceParts(selected, model.apps).map(({ app, template }) => ({ app, appTitle: model.apps[app].title, template })) }
            : null,
        seedSpace,
        seedTool: seedSpace ? seedToolOf(seedSpace) : null,
        demoText: seedSpace ? demoTextOf(p.id) : null,
        posture: model.offerings[ed].posture ?? null,
        editionProperties: { edition: Edition, ...(demo ? { variant: 'demo' } : {}), product: p.id, apps: selected.join(',') },
        descriptor: productDescriptor(model, p, ed, distribution, selected),
        notes,
    };
}

export const DESCRIPTOR_SCHEMA = 'inspecto.product/1';

/**
 * The Product build's descriptor (PA-3): what the running server reads from `lib/product.json` to answer `/bootstrap.product`.
 * Resolved here, never by the server: each App carries the modules its Add-ons bring in THIS edition (the server checks
 * they are ACTIVE) and its nav in drawing order. Fields absent from a nav entry are left out, not written as null.
 */
export function productDescriptor(model, p, ed, distribution, selected) {
    const entry = (n) => Object.fromEntries(['id', 'path', 'label', 'icon', 'blurb', 'feature', 'capability']
        .filter((k) => n[k] !== undefined && n[k] !== '').map((k) => [k, n[k]]));
    return {
        schema: DESCRIPTOR_SCHEMA,
        id: p.id,
        title: p.title,
        shape: p.shape,
        host: p.host,
        brand: { name: p.brand?.name ?? p.title },
        edition: normalizeEdition(ed),
        distribution,
        // PA-5b: a page two Apps both declare is drawn once, under the first of them (productNavByApp).
        apps: productNavByApp(selected, model.apps).map(({ app: a, entries }) => {
            const app = model.apps[a];
            const modules = [...new Set((app.requires?.addons ?? []).flatMap((x) => [...addonModules(ed, x, model.offerings)]))];
            return { id: a, title: app.title, version: app.version, modules, nav: entries.map(entry) };
        }),
    };
}

/** git's tree hash of a committed path (content identity, independent of the working tree). */
const treeOf = (root, path) => execFileSync('git', ['rev-parse', `HEAD:${path}`], { cwd: root, encoding: 'utf8' }).trim();

/**
 * The Product lock: the Platform version, each App's version, each Industry pack and Space Template by git tree hash, and the
 * classpath of every edition the Product comes in. Committed for a RELEASED Product (plan §5); `--diff-lock` reads two of them.
 */
export function productLock(model, g, product, { tree = (path) => treeOf(model.root, path) } = {}) {
    const p = model.products[product] ?? fail(`unknown Product '${product}'`);
    const { required, optional } = productApps(p);
    const appIds = [...required, ...optional];
    const industries = [...new Set([p.industry, ...appIds.flatMap((a) => model.apps[a]?.industries ?? [])].filter(Boolean))];
    return {
        schema: LOCK_SCHEMA,
        product: p.id,
        platformVersion: model.pomVersion,
        apps: appIds.map((a) => ({ id: a, version: model.apps[a].version, required: required.includes(a) })),
        industries: industries.map((i) => ({ id: i, pack: model.industries[i].pack, tree: tree(`platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/${model.industries[i].pack}`) })),
        templates: productTemplates(p, appIds, model.apps).map((t) => ({ id: t, tree: tree(`spaces/_templates/${t}`) })),
        editions: Object.fromEntries((p.editions ?? []).map((e) => [e, classpath(normalizeEdition(e), { g })])),
    };
}

/** The upgrade diff between two locks of one Product, one human line per change (empty = identical). */
export function diffLocks(a, b) {
    const out = [];
    if (a.product !== b.product) out.push(`product: ${a.product} -> ${b.product} (these locks are of different Products)`);
    if (a.platformVersion !== b.platformVersion) out.push(`Platform ${a.platformVersion} -> ${b.platformVersion}`);
    const byId = (xs) => new Map((xs ?? []).map((x) => [x.id, x]));
    for (const [kind, key] of [['App', 'version'], ['Industry', 'tree'], ['template', 'tree']]) {
        const field = { App: 'apps', Industry: 'industries', template: 'templates' }[kind];
        const A = byId(a[field]), B = byId(b[field]);
        for (const [id, x] of B) if (!A.has(id)) out.push(`${kind} '${id}' added`);
        for (const [id] of A) if (!B.has(id)) out.push(`${kind} '${id}' removed`);
        for (const [id, x] of B) if (A.has(id) && A.get(id)[key] !== x[key]) out.push(`${kind} '${id}' changed${key === 'version' ? ` ${A.get(id).version} -> ${x.version}` : ''}`);
    }
    for (const e of new Set([...Object.keys(a.editions ?? {}), ...Object.keys(b.editions ?? {})])) {
        const A = new Set(a.editions?.[e] ?? []), B = new Set(b.editions?.[e] ?? []);
        for (const j of B) if (!A.has(j)) out.push(`${e}: ${j} added to the classpath`);
        for (const j of A) if (!B.has(j)) out.push(`${e}: ${j} removed from the classpath`);
    }
    return out;
}

function main() {
    const a = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    const write = (obj) => {
        const text = typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2) + '\n';
        const out = val('--out');
        if (out) { mkdirSync(dirname(resolve(out)), { recursive: true }); writeFileSync(out, text); console.error(`assemble: wrote ${out}`); }
        else process.stdout.write(text);
    };
    try {
        if (a.includes('--diff-lock')) {
            const i = a.indexOf('--diff-lock');
            const [x, y] = [a[i + 1], a[i + 2]].map((f) => JSON.parse(readFileSync(f, 'utf8')));
            const d = diffLocks(x, y);
            write(d.length ? d.join('\n') + '\n' : 'no change\n');
            return;
        }
        // PA-6a (D-PA18): `--app-root <folder>`, repeatable, adds an App that lives outside apps/.
        const appRoots = a.flatMap((x, i) => (x === '--app-root' && a[i + 1] ? [a[i + 1]] : []));
        const model = gatherModel(REPO, { appRoots });
        const g = gather(REPO);
        const product = val('--product') ?? fail('--product <id> is required');
        carryPartnerApps(model, product);
        if (a.includes('--lock')) { write(productLock(model, g, product)); return; }
        const plan = assemble(model, g, {
            product,
            edition: val('--edition') ?? fail('--edition <basic|standard|advanced|preview> is required'),
            distribution: val('--distribution') ?? 'production',
            apps: val('--apps') ? val('--apps').split(',').map((s) => s.trim()).filter(Boolean) : null,
            target: val('--target') ?? 'windows',
        });
        for (const n of plan.notes) console.error(`assemble: note: ${n}`);
        // PA-3: `--descriptor <file>` also writes the Product build's descriptor (package.ps1 places it at lib/product.json).
        const descriptor = val('--descriptor');
        if (descriptor) {
            mkdirSync(dirname(resolve(descriptor)), { recursive: true });
            writeFileSync(descriptor, JSON.stringify(plan.descriptor, null, 2) + '\n');
            console.error(`assemble: wrote ${descriptor}`);
        }
        write(plan);
    } catch (e) {
        console.error(`assemble: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
