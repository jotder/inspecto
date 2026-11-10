// products — the Product assembly model (PRODUCT-ASSEMBLY-1 PA-1; plan docs/superpower/2026-11-10-product-assembly-plan.md,
// words in docs/GLOSSARY.md §15 "Product assembly"). One loader and one checker, shared by tools/check-products.mjs (the guard)
// and tools/assemble.mjs (the assembler), so the two can never disagree about what a definition means.
//
//   products/<id>.toon          a Product: shape domain|platform, host (the Angular project), editions, apps{required,optional},
//                               addons (platform shape), templates, distributions, demoSpace, brand{name}, docs
//   apps/<id>/app.toon          an App: version, platform (a version range), industries (empty = Industry-neutral),
//                               requires{addons}, templates, docs
//   industries/<id>/industry.toon  an Industry: title, pack (the Industry pack id under platform/inspecto-entity-store)
//
// Every definition NAMES ids (Offerings, Add-ons, Space Templates, Industry packs, Angular projects); nothing here restates
// what those own (the Product Map rule D-PM1).

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseToon } from './toon-subset.mjs';

export const SHAPES = ['domain', 'platform'];
/** Distributions the assembler builds today, and the ones the plan names for later phases. */
export const DISTRIBUTIONS = ['production', 'demo'];
export const PLANNED_DISTRIBUTIONS = { trial: 'PA-7 (signed entitlements)', pilot: 'PA-7 (signed entitlements)' };
/** The editions a demo build exists for (tools/offering-classpath.mjs: the demo sign-in swap is an Advanced-capability build). */
export const DEMO_EDITIONS = ['advanced', 'preview'];
export const INDUSTRY_PACK_DIR = 'platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs';

const toonFiles = (dir) => (existsSync(dir) ? readdirSync(dir).filter((n) => n.endsWith('.toon')) : []);
const subdirsWith = (dir, file) => (existsSync(dir) ? readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory() && existsSync(join(dir, d.name, file))).map((d) => d.name) : []);
const read = (f) => parseToon(readFileSync(f, 'utf8'));

/** Everything a check or an assembly needs, read from `root`. */
export function gatherModel(root) {
    const products = Object.fromEntries(toonFiles(join(root, 'products')).map((f) => [f.slice(0, -5), read(join(root, 'products', f))]));
    const apps = Object.fromEntries(subdirsWith(join(root, 'apps'), 'app.toon').map((d) => [d, read(join(root, 'apps', d, 'app.toon'))]));
    const industries = Object.fromEntries(subdirsWith(join(root, 'industries'), 'industry.toon').map((d) => [d, read(join(root, 'industries', d, 'industry.toon'))]));
    const offerings = Object.fromEntries(toonFiles(join(root, 'offerings')).map((f) => [f.slice(0, -5), read(join(root, 'offerings', f))]));
    const tdir = join(root, 'spaces', '_templates');
    const templates = existsSync(tdir) ? readdirSync(tdir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name) : [];
    const ng = join(root, 'inspecto-ui', 'angular.json');
    const hosts = existsSync(ng) ? Object.keys(JSON.parse(readFileSync(ng, 'utf8')).projects ?? {}) : [];
    const industryPacks = subdirsWith(join(root, INDUSTRY_PACK_DIR), 'industry-pack.toon');
    const pomVersion = (/<version>([^<]+)<\/version>/.exec(existsSync(join(root, 'pom.xml')) ? readFileSync(join(root, 'pom.xml'), 'utf8') : '') ?? [])[1] ?? null;
    return { root, products, apps, industries, offerings, templates, hosts, industryPacks, pomVersion, exists: (p) => existsSync(join(root, p)) };
}

/** An Offering's Add-ons with `includes` resolved (an including Offering's entry wins): { name: {status, modules} }. */
export function offeringAddons(id, offerings, seen = []) {
    const o = offerings[id];
    if (!o) throw new Error(`offering '${id}' not found under offerings/`);
    if (seen.includes(id)) throw new Error(`offering includes cycle ${[...seen, id].join(' -> ')}`);
    const out = {};
    for (const inc of o.includes ?? []) Object.assign(out, offeringAddons(inc, offerings, [...seen, id]));
    return Object.assign(out, o.addons ?? {});
}

/** The built Add-on ids an edition offers. */
export const builtAddons = (edition, offerings) =>
    new Set(Object.entries(offeringAddons(edition, offerings)).filter(([, a]) => a.status === 'built').map(([n]) => n));

/** `4.0.0-SNAPSHOT` -> [4, 0, 0]. */
const parts = (v) => String(v).replace(/^v/, '').split(/[-+]/)[0].split('.').map((n) => Number(n) || 0);
const cmp = (a, b) => { for (let i = 0; i < 3; i++) { const d = (a[i] ?? 0) - (b[i] ?? 0); if (d) return d; } return 0; };

/** Does `version` satisfy a space-separated range such as `>=4.0 <5`? Operators: >= > <= < = (bare = means equal). */
export function satisfies(version, range) {
    const v = parts(version);
    for (const tok of String(range).replace(/^"|"$/g, '').trim().split(/\s+/).filter(Boolean)) {
        const m = /^(>=|<=|>|<|=)?(\d+(?:\.\d+){0,2})$/.exec(tok);
        if (!m) throw new Error(`unreadable version range token '${tok}' in '${range}'`);
        const c = cmp(v, parts(m[2]));
        const ok = { '>=': c >= 0, '>': c > 0, '<=': c <= 0, '<': c < 0, '=': c === 0 }[m[1] ?? '='];
        if (!ok) return false;
    }
    return true;
}

const list = (x) => (Array.isArray(x) ? x : []);
/** A Product's App ids: { required, optional }. */
export const productApps = (p) => ({ required: list(p.apps?.required), optional: list(p.apps?.optional) });

/** The Space Templates a Product stages for the given App selection: its own, then each App's, in definition order. */
export function productTemplates(p, appIds, apps) {
    const out = [...list(p.templates)];
    for (const a of appIds) for (const t of list(apps[a]?.templates)) if (!out.includes(t)) out.push(t);
    return out;
}

/** Every finding about the definitions in `model` (an empty list = green). */
export function checkModel(model) {
    const { products, apps, industries, offerings, templates, hosts, industryPacks, pomVersion, exists } = model;
    const out = [];
    const editionIds = Object.keys(offerings);

    for (const [file, ind] of Object.entries(industries)) {
        const at = `industries/${file}/industry.toon`;
        if (ind.id !== file) out.push(`${at}: id '${ind.id}' does not equal its folder name '${file}'`);
        if (!ind.title) out.push(`${at}: no title`);
        if (!industryPacks.includes(ind.pack)) out.push(`${at}: pack '${ind.pack}' is not an Industry pack (${INDUSTRY_PACK_DIR}/<id>/industry-pack.toon)`);
    }

    for (const [file, app] of Object.entries(apps)) {
        const at = `apps/${file}/app.toon`;
        if (app.id !== file) out.push(`${at}: id '${app.id}' does not equal its folder name '${file}'`);
        for (const k of ['title', 'version', 'platform', 'docs']) if (!app[k]) out.push(`${at}: no ${k}`);
        if (app.platform && pomVersion) {
            try { if (!satisfies(pomVersion, app.platform)) out.push(`${at}: platform '${app.platform}' does not admit this Platform version ${pomVersion}`); }
            catch (e) { out.push(`${at}: ${e.message}`); }
        }
        for (const i of list(app.industries)) if (!industries[i]) out.push(`${at}: Industry '${i}' has no industries/${i}/industry.toon`);
        const known = new Set(editionIds.flatMap((e) => Object.keys(offeringAddons(e, offerings))));
        for (const a of list(app.requires?.addons)) if (!known.has(a)) out.push(`${at}: requires Add-on '${a}', which no Offering names`);
        for (const t of list(app.templates)) if (!templates.includes(t)) out.push(`${at}: template '${t}' is not under spaces/_templates`);
        if (app.docs && !exists(app.docs)) out.push(`${at}: docs '${app.docs}' does not exist`);
    }

    const shipped = new Set();
    for (const [file, p] of Object.entries(products)) {
        const at = `products/${file}.toon`;
        if (p.id !== file) out.push(`${at}: id '${p.id}' does not equal its file name '${file}'`);
        for (const k of ['title', 'shape', 'host', 'docs']) if (!p[k]) out.push(`${at}: no ${k}`);
        if (!p.brand?.name) out.push(`${at}: no brand.name`);
        if (p.shape && !SHAPES.includes(p.shape)) out.push(`${at}: shape '${p.shape}' is not one of ${SHAPES.join(', ')}`);
        if (p.host && !hosts.includes(p.host)) out.push(`${at}: host '${p.host}' is not an Angular project in inspecto-ui/angular.json (${hosts.join(', ')})`);
        if (p.docs && !exists(p.docs)) out.push(`${at}: docs '${p.docs}' does not exist`);
        const eds = list(p.editions);
        if (!eds.length) out.push(`${at}: lists no editions`);
        for (const e of eds) if (!offerings[e]) out.push(`${at}: edition '${e}' has no offerings/${e}.toon`);
        for (const d of list(p.distributions)) {
            if (PLANNED_DISTRIBUTIONS[d]) out.push(`${at}: distribution '${d}' is not built yet (${PLANNED_DISTRIBUTIONS[d]})`);
            else if (!DISTRIBUTIONS.includes(d)) out.push(`${at}: distribution '${d}' is not one of ${DISTRIBUTIONS.join(', ')}`);
        }
        if (list(p.distributions).includes('demo') && !eds.some((e) => DEMO_EDITIONS.includes(e))) out.push(`${at}: offers the demo distribution but none of ${DEMO_EDITIONS.join(', ')}, the editions a demo build exists for`);

        const { required, optional } = productApps(p);
        const all = [...required, ...optional];
        for (const a of all) if (!apps[a]) out.push(`${at}: App '${a}' has no apps/${a}/app.toon`);
        for (const a of required) if (optional.includes(a)) out.push(`${at}: App '${a}' is both required and optional`);
        if (p.shape === 'domain') {
            if (!required.length) out.push(`${at}: a Domain product needs at least one required App`);
            if (p.addons) out.push(`${at}: a Domain product names no addons (its Apps require them)`);
            if (p.industry && !industries[p.industry]) out.push(`${at}: Industry '${p.industry}' has no industries/${p.industry}/industry.toon`);
            for (const a of all.filter((x) => apps[x])) {
                const inds = list(apps[a].industries);
                if (inds.length && !p.industry) out.push(`${at}: App '${a}' serves ${inds.join(', ')}, so the Product must name its industry`);
                if (inds.length && p.industry && !inds.includes(p.industry)) out.push(`${at}: App '${a}' serves ${inds.join(', ')}, not the Product's Industry '${p.industry}'`);
            }
        }
        if (p.shape === 'platform') {
            const known = new Set(editionIds.flatMap((e) => Object.keys(offeringAddons(e, offerings))));
            if (!list(p.addons).length) out.push(`${at}: a Platform product names its Add-ons`);
            for (const a of list(p.addons)) if (!known.has(a)) out.push(`${at}: Add-on '${a}' is named by no Offering`);
        }
        for (const e of eds.filter((x) => offerings[x])) {
            const offered = builtAddons(e, offerings);
            for (const a of required.filter((x) => apps[x])) {
                const missing = list(apps[a].requires?.addons).filter((x) => !offered.has(x));
                if (missing.length) out.push(`${at}: edition '${e}' lacks Add-on(s) ${missing.join(', ')} that required App '${a}' needs`);
            }
        }

        const own = list(p.templates);
        for (const t of own) if (!templates.includes(t)) out.push(`${at}: template '${t}' is not under spaces/_templates`);
        const seen = new Map();
        for (const [src, ts] of [['the Product', own], ...all.filter((a) => apps[a]).map((a) => [`App '${a}'`, list(apps[a].templates)])]) {
            for (const t of ts) {
                if (seen.has(t)) out.push(`${at}: template '${t}' is staged twice (${seen.get(t)} and ${src})`);
                else seen.set(t, src);
                shipped.add(t);
            }
        }
        if (p.demoSpace && !seen.has(p.demoSpace)) out.push(`${at}: demoSpace '${p.demoSpace}' is not one of the Product's templates`);
        if (p.demoSpace && !list(p.distributions).includes('demo')) out.push(`${at}: demoSpace is set but the Product offers no demo distribution`);
    }
    for (const t of templates) if (!shipped.has(t)) out.push(`spaces/_templates/${t}: no Product or App ships this template - add it to one, or delete it`);
    return out;
}
