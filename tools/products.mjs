// products — the Product assembly model (PRODUCT-ASSEMBLY-1 PA-1; plan docs/superpower/2026-11-10-product-assembly-plan.md,
// words in docs/GLOSSARY.md §15 "Product assembly"). One loader and one checker, shared by tools/check-products.mjs (the guard)
// and tools/assemble.mjs (the assembler), so the two can never disagree about what a definition means.
//
//   products/<id>.toon          a Product: shape domain|platform, host (the Angular project), hostConfiguration (PA-5: the
//                               host's build configuration when the host is shared), editions, apps{required,optional},
//                               addons (platform shape), templates, space (PA-5: the id of the Space Template composed from
//                               its Apps' templates), distributions, demoSpace, brand{name}, docs
//   apps/<id>/app.toon          an App: version, platform (a version range), industries (empty = Industry-neutral),
//                               requires{addons}, templates, docs
//   industries/<id>/industry.toon  an Industry: title, pack (the Industry pack id under platform/inspecto-entity-store)
//
// Every definition NAMES ids (Offerings, Add-ons, Space Templates, Industry packs, Angular projects); nothing here restates
// what those own (the Product Map rule D-PM1).

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { parseToon } from './toon-subset.mjs';
import { readModuleSources } from './bundle-modules.mjs';

export const SHAPES = ['domain', 'platform'];
/** Distributions the assembler builds today, and the ones the plan names for later phases. */
export const DISTRIBUTIONS = ['production', 'trial', 'demo'];
// PA-7b: `trial` is built (a production build carrying a signed entitlement with an expiry, D-PA24). `pilot` needs the customer
// record (its Collectors pre-wired), so it waits for PA-10.
export const PLANNED_DISTRIBUTIONS = { pilot: 'PA-10 (customer Collectors pre-wired, from the customer record)' };
/** The editions a demo build exists for (tools/offering-classpath.mjs: the demo sign-in swap is an Advanced-capability build). */
export const DEMO_EDITIONS = ['advanced', 'preview'];
export const INDUSTRY_PACK_DIR = 'platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs';

const toonFiles = (dir) => (existsSync(dir) ? readdirSync(dir).filter((n) => n.endsWith('.toon')) : []);
const subdirsWith = (dir, file) => (existsSync(dir) ? readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory() && existsSync(join(dir, d.name, file))).map((d) => d.name) : []);
const read = (f) => parseToon(readFileSync(f, 'utf8'));

/** Everything a check or an assembly needs, read from `root`. */
export function gatherModel(root, { appRoots = [] } = {}) {
    const products = Object.fromEntries(toonFiles(join(root, 'products')).map((f) => [f.slice(0, -5), read(join(root, 'products', f))]));
    const apps = Object.fromEntries(subdirsWith(join(root, 'apps'), 'app.toon').map((d) => [d, read(join(root, 'apps', d, 'app.toon'))]));
    // PA-6a (D-PA18): an App may live in any folder - <app-root>/app.toon, its Space Templates under <app-root>/templates/.
    // Kept apart (partnerApps: id -> folder, templateDirs: template -> folder) so a first-party name it reuses is refused.
    const partnerApps = {};
    const partnerTemplates = {};
    const collisions = [];
    for (const r of appRoots) {
        const dir = resolve(r);
        if (!existsSync(join(dir, 'app.toon'))) { collisions.push(`${dir}: no app.toon - an App root holds app.toon (and templates/<id>/)`); continue; }
        const app = read(join(dir, 'app.toon'));
        const id = app.id ?? '';
        if (!id) { collisions.push(`${dir}/app.toon: no id`); continue; }
        if (apps[id]) { collisions.push(`${dir}/app.toon: App id '${id}' is already ${partnerApps[id] ? `the App at ${partnerApps[id]}` : `a first-party App (apps/${id})`}`); continue; }
        apps[id] = app;
        partnerApps[id] = dir;
        for (const t of existsSync(join(dir, 'templates')) ? readdirSync(join(dir, 'templates'), { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name) : [])
            if (partnerTemplates[t]) collisions.push(`${join(dir, 'templates', t)}: template '${t}' is already shipped by the partner App '${partnerTemplates[t].app}'`);
            else partnerTemplates[t] = { app: id, dir: join(dir, 'templates', t) };
    }
    const industries = Object.fromEntries(subdirsWith(join(root, 'industries'), 'industry.toon').map((d) => [d, read(join(root, 'industries', d, 'industry.toon'))]));
    const offerings = Object.fromEntries(toonFiles(join(root, 'offerings')).map((f) => [f.slice(0, -5), read(join(root, 'offerings', f))]));
    const tdir = join(root, 'spaces', '_templates');
    const templates = existsSync(tdir) ? readdirSync(tdir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name) : [];
    const templateDirs = {};
    for (const [t, { app, dir }] of Object.entries(partnerTemplates)) {
        if (templates.includes(t)) { collisions.push(`${dir}: template '${t}' of App '${app}' is already a first-party template (spaces/_templates)`); continue; }
        templates.push(t);
        templateDirs[t] = dir;
    }
    const templateDir = (t) => templateDirs[t] ?? join(tdir, t);
    const ng = join(root, 'inspecto-ui', 'angular.json');
    const ngProjects = existsSync(ng) ? JSON.parse(readFileSync(ng, 'utf8')).projects ?? {} : {};
    const hosts = Object.keys(ngProjects);
    const industryPacks = subdirsWith(join(root, INDUSTRY_PACK_DIR), 'industry-pack.toon');
    const pomVersion = (/<version>([^<]+)<\/version>/.exec(existsSync(join(root, 'pom.xml')) ? readFileSync(join(root, 'pom.xml'), 'utf8') : '') ?? [])[1] ?? null;
    // PA-3: the feature ids each module declares (`provides.features`), so a nav entry's `feature` can be checked.
    const moduleFeatures = Object.fromEntries(Object.values(readModuleSources(root).manifests).map((m) => [m.id, list(m.provides?.features)]));
    // PA-5: the files (`/`-separated, relative to the template) of every template a composed Space takes as a part - read
    // only for those, and kept as data so a test can clone and plant the model.
    const parts = Object.values(products).filter((p) => p.space)
        .flatMap((p) => [...list(p.apps?.required), ...list(p.apps?.optional)]).flatMap((a) => list(apps[a]?.templates));
    const templateFiles = Object.fromEntries([...new Set(parts)].filter((t) => templates.includes(t)).map((t) => [t, walk(templateDir(t))]));
    return { root, products, apps, industries, offerings, templates, hosts, ngProjects, industryPacks, pomVersion, moduleFeatures, templateFiles,
        partnerApps, templateDirs, collisions, exists: (p) => existsSync(join(root, p)) };
}

/**
 * PA-6a (D-PA18): the partner Apps loaded from `appRoots` ride on the Platform product `productId` as optional Apps, their
 * Add-ons added to its own. A Domain product's Apps are its definition, so it takes none (refused).
 */
export function carryPartnerApps(model, productId) {
    const ids = Object.keys(model.partnerApps ?? {});
    const p = model.products[productId];
    if (!ids.length || !p) return model;
    if (p.shape !== 'platform') throw new Error(`Product '${productId}' is a Domain product: its Apps are its definition, so a partner App (--app-root) rides on a Platform product`);
    p.apps = { required: list(p.apps?.required), optional: [...new Set([...list(p.apps?.optional), ...ids])] };
    p.addons = [...new Set([...list(p.addons), ...ids.flatMap((a) => list(model.apps[a]?.requires?.addons))])];
    return model;
}

/**
 * PA-6c: the model with a narrowed Platform product in place of the one it narrows (same host default build and templates), so
 * the assembler and its checks see one Platform product. Refuses a definition that narrows no Platform product.
 */
export function narrowModel(model, definition) {
    const base = model.products[definition.narrows];
    if (!definition.narrows || !base || base.shape !== 'platform') refuse(`a picked Product narrows a Platform product (narrows: ${definition.narrows ?? 'missing'})`);
    if (definition.host !== base.host) refuse(`a picked Product keeps the host of '${definition.narrows}' (${base.host}), not '${definition.host}'`);
    const extra = list(definition.addons).filter((a) => !list(base.addons).includes(a));
    if (extra.length) refuse(`Add-on(s) ${extra.join(', ')} are not in '${definition.narrows}', the Platform product it narrows`);
    if (definition.id !== definition.narrows && model.products[definition.id]) refuse(`id '${definition.id}' is already the Product products/${definition.id}.toon - a picked Product takes an id of its own`);
    const products = { ...model.products };
    delete products[definition.narrows];
    products[definition.id] = definition;
    return { ...model, products };
}

/** Read a picked Product file and put it in the model (assemble.mjs --product-file). Returns { model, product }. */
export function loadProductFile(model, file) {
    const d = read(file);
    if (!d.id) refuse(`${file}: no id`);
    return { model: narrowModel(model, d), product: d.id };
}

const refuse = (msg) => { throw new Error(msg); };

/** Every file under `dir`, as `/`-separated paths relative to it. */
function walk(dir, rel = '') {
    if (!existsSync(join(dir, rel))) return [];
    return readdirSync(join(dir, rel), { withFileTypes: true }).flatMap((d) => {
        const r = rel ? `${rel}/${d.name}` : d.name;
        return d.isDirectory() ? walk(dir, r) : [r];
    });
}

/**
 * PA-5: a host's generated data file for a Product, relative to the host's `sourceRoot`. The host's default build reads
 * `app/product.generated.ts`; a Product built with a `hostConfiguration` gets its own file, which that configuration's
 * `fileReplacements` puts in the default's place.
 */
export const hostDataFile = (p) => (p.hostConfiguration ? `app/product.${p.id}.generated.ts` : 'app/product.generated.ts');

/** PA-5: the files a composed Space writes itself, so two parts may each ship one (tools/compose-space.mjs). */
export const COMPOSER_OWNED = ['template.toon', 'config/examples/sla-escalation-examples.md'];

/** PA-5: the parts of a Product's composed Space: each selected App's templates, in App order: [{ app, template }]. */
export const spaceParts = (appIds, apps) => appIds.flatMap((a) => list(apps[a]?.templates).map((t) => ({ app: a, template: t })));

/** The modules an Add-on brings in an edition: the union of its `modules` along the Offering's `includes` chain. */
export function addonModules(edition, addon, offerings, seen = []) {
    const o = offerings[edition];
    if (!o) throw new Error(`offering '${edition}' not found under offerings/`);
    if (seen.includes(edition)) throw new Error(`offering includes cycle ${[...seen, edition].join(' -> ')}`);
    const out = new Set();
    for (const inc of o.includes ?? []) addonModules(inc, addon, offerings, [...seen, edition]).forEach((m) => out.add(m));
    for (const m of list(o.addons?.[addon]?.modules)) out.add(m);
    return out;
}

/** An App's nav entries in drawing order (PA-3): [{id, path, label, icon, blurb?, feature?, capability?}]. */
export const appNav = (app) => Object.entries(app?.nav && typeof app.nav === 'object' ? app.nav : {}).map(([id, e]) => ({ id, ...e }));

/**
 * PA-5b: the nav of a Product's Apps, in Product order (required, then optional): [{ app, entries }]. A page is drawn
 * once: an entry whose path an earlier App already declared is left out, so in Inspecto Telco Case Management's Alerts
 * wins over Link Analysis' (both open the one Alerts page). The assembler (the descriptor the server resolves) and the
 * host data generator use this same rule, so the server's nav and the fallback agree.
 */
export function productNavByApp(appIds, apps) {
    const seen = new Set();
    return appIds.map((app) => ({
        app,
        entries: appNav(apps[app]).filter((n) => (seen.has(n.path) ? false : (seen.add(n.path), true))),
    }));
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
/** The tool that scaffolds and seeds a demo Space: `--scaffold <spacesRoot>` then `--base <url> --space <id> --apply` (the la-showcase contract). */
export const seedToolOf = (space) => `tools/seed-${space}.mjs`;
/** A Product's demo-bundle presenter text (written as the bundle's DEMO-BUILD.txt when its demoSpace is baked in). */
export const demoTextOf = (product) => `products/${product}.demo-build.txt`;
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
    const out = [...(model.collisions ?? [])];
    const editionIds = Object.keys(offerings);

    for (const [file, ind] of Object.entries(industries)) {
        const at = `industries/${file}/industry.toon`;
        if (ind.id !== file) out.push(`${at}: id '${ind.id}' does not equal its folder name '${file}'`);
        if (!ind.title) out.push(`${at}: no title`);
        if (!industryPacks.includes(ind.pack)) out.push(`${at}: pack '${ind.pack}' is not an Industry pack (${INDUSTRY_PACK_DIR}/<id>/industry-pack.toon)`);
    }

    for (const [file, app] of Object.entries(apps)) {
        // PA-6a: a partner App (any folder, D-PA18) is named by its id, and its docs path is relative to its own folder.
        const home = model.partnerApps?.[file];
        const at = home ? `${home}/app.toon` : `apps/${file}/app.toon`;
        if (!home && app.id !== file) out.push(`${at}: id '${app.id}' does not equal its folder name '${file}'`);
        for (const k of ['title', 'version', 'platform', 'docs']) if (!app[k]) out.push(`${at}: no ${k}`);
        if (app.platform && pomVersion) {
            try { if (!satisfies(pomVersion, app.platform)) out.push(`${at}: platform '${app.platform}' does not admit this Platform version ${pomVersion}`); }
            catch (e) { out.push(`${at}: ${e.message}`); }
        }
        for (const i of list(app.industries)) if (!industries[i]) out.push(`${at}: Industry '${i}' has no industries/${i}/industry.toon`);
        const known = new Set(editionIds.flatMap((e) => Object.keys(offeringAddons(e, offerings))));
        for (const a of list(app.requires?.addons)) if (!known.has(a)) out.push(`${at}: requires Add-on '${a}', which no Offering names`);
        for (const t of list(app.templates)) {
            if (!templates.includes(t)) out.push(`${at}: template '${t}' is not under ${home ? `${home}/templates` : 'spaces/_templates'}`);
            else if (home && !model.templateDirs?.[t]) out.push(`${at}: template '${t}' is a first-party template; a partner App ships its own under ${home}/templates`);
        }
        if (app.docs && !(home ? existsSync(join(home, app.docs)) : exists(app.docs))) out.push(`${at}: docs '${app.docs}' does not exist`);
        // PA-3: the nav the shells draw. A `feature` must be one the App's own Add-on modules provide (in any edition), or
        // the server could never report it true and the entry would never show.
        const provided = new Set(editionIds.flatMap((e) => list(app.requires?.addons).flatMap((a) => [...addonModules(e, a, offerings)]))
            .flatMap((m) => model.moduleFeatures?.[m] ?? []));
        const paths = new Map();
        for (const n of appNav(app)) {
            if (typeof n.path !== 'string' || !n.path) out.push(`${at}: nav entry '${n.id}' has no path`);
            else if (paths.has(n.path)) out.push(`${at}: nav entries '${paths.get(n.path)}' and '${n.id}' share the path '${n.path}'`);
            else paths.set(n.path, n.id);
            if (typeof n.label !== 'string' || !n.label) out.push(`${at}: nav entry '${n.id}' has no label`);
            if (n.feature !== undefined && !provided.has(n.feature)) out.push(`${at}: nav entry '${n.id}' keys on feature '${n.feature}', which no module of its Add-ons (${list(app.requires?.addons).join(', ') || 'none'}) provides`);
        }
    }

    const shipped = new Set();
    for (const [file, p] of Object.entries(products)) {
        const at = `products/${file}.toon`;
        if (p.id !== file) out.push(`${at}: id '${p.id}' does not equal its file name '${file}'`);
        for (const k of ['title', 'shape', 'host', 'docs']) if (!p[k]) out.push(`${at}: no ${k}`);
        if (!p.brand?.name) out.push(`${at}: no brand.name`);
        // PA-4: the sign-in pitch is two lines that only make sense together.
        if (!!p.brand?.signInTitle !== !!p.brand?.signInBody) out.push(`${at}: brand.signInTitle and brand.signInBody go together - set both or neither`);
        // PA-5: a host may build several Products (la-app is the suite shell, D-PA13), but only one is its default build;
        // every other one names a build configuration that puts its own generated data in the default's place.
        const sameHost = Object.entries(products).find(([other, q]) => other < file && q.host && q.host === p.host && !q.hostConfiguration && !p.hostConfiguration);
        if (sameHost) out.push(`${at}: shares the default build of host '${p.host}' with Product '${sameHost[0]}' - give one of them a hostConfiguration`);
        if (p.shape && !SHAPES.includes(p.shape)) out.push(`${at}: shape '${p.shape}' is not one of ${SHAPES.join(', ')}`);
        if (p.host && !hosts.includes(p.host)) out.push(`${at}: host '${p.host}' is not an Angular project in inspecto-ui/angular.json (${hosts.join(', ')})`);
        if (p.hostConfiguration && hosts.includes(p.host)) {
            const proj = model.ngProjects?.[p.host] ?? {};
            const cfg = (proj.architect ?? proj.targets)?.build?.configurations?.[p.hostConfiguration];
            const base = `${proj.sourceRoot}/app/product.generated.ts`;
            const mine = `${proj.sourceRoot}/${hostDataFile(p)}`;
            if (!cfg) out.push(`${at}: hostConfiguration '${p.hostConfiguration}' is not a build configuration of host '${p.host}' in inspecto-ui/angular.json`);
            else if (!list(cfg.fileReplacements).some((r) => r.replace === base && r.with === mine)) out.push(`${at}: build configuration '${p.hostConfiguration}' of host '${p.host}' must replace ${base} with ${mine}`);
        }
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
        // PA-5: the composed Space (D-PA14). Its parts are the Apps' templates; two parts may not ship the same file, except
        // the ones tools/compose-space.mjs writes itself. Checked over every App the Product can ship, so no Edition's
        // selection can collide.
        if (p.space) {
            if (p.shape !== 'domain') out.push(`${at}: space '${p.space}' is composed from Apps, so only a Domain product has one`);
            if (templates.includes(p.space)) out.push(`${at}: space '${p.space}' is the id of a committed template under spaces/_templates - a composed Space needs its own id`);
            const parts = spaceParts(all.filter((a) => apps[a]), apps).filter((x) => templates.includes(x.template));
            if (!parts.length) out.push(`${at}: space '${p.space}' has nothing to compose - none of its Apps ships a template`);
            // Keyed on the lower-cased path: on a case-insensitive file system (Windows) `X.toon` and `x.toon` are one file.
            const owner = new Map();
            for (const { template } of parts) {
                for (const f of model.templateFiles?.[template] ?? []) {
                    if (COMPOSER_OWNED.includes(f)) continue;
                    const key = f.toLowerCase();
                    if (owner.has(key)) out.push(`${at}: space '${p.space}': templates '${owner.get(key)}' and '${template}' both ship ${f}`);
                    else owner.set(key, template);
                }
            }
        }
        if (p.demoSpace && !seen.has(p.demoSpace) && p.demoSpace !== p.space) out.push(`${at}: demoSpace '${p.demoSpace}' is not one of the Product's templates`);
        if (p.demoSpace && !exists(seedToolOf(p.demoSpace))) out.push(`${at}: demoSpace '${p.demoSpace}' has no seed tool ${seedToolOf(p.demoSpace)} (--scaffold / --apply, as tools/seed-la-showcase.mjs)`);
        if (p.demoSpace && !exists(demoTextOf(file))) out.push(`${at}: demoSpace '${p.demoSpace}' has no presenter text ${demoTextOf(file)} (the bundle's DEMO-BUILD.txt)`);
        if (p.demoSpace && !list(p.distributions).includes('demo')) out.push(`${at}: demoSpace is set but the Product offers no demo distribution`);
    }
    for (const t of templates) if (!shipped.has(t)) out.push(`spaces/_templates/${t}: no Product or App ships this template - add it to one, or delete it`);
    return out;
}
