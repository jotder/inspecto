// customer — a customer build is an assembly (PRODUCT-ASSEMBLY-1 PA-10b; plan docs/superpower/2026-11-10-product-assembly-plan.md
// §11 PA-10, D-PA33 and D-PA34). The resolution below is PRODUCT-MAP-1 S5's (docs/okf/product/layer/platform/spec-product-map.md),
// moved here unchanged apart from App Order Codes, so a customer build cannot drift from the one it replaces.
//
// RECORD  product-map/private/customers/<id>.toon (gitignored, D-PM3; never committed - it is client data):
//           id: demo-telco
//           title: Demo Telco
//           product: inspecto            (PA-10b; optional, default inspecto - the Platform product)
//           edition: standard
//           orderCodes[2]: INS-AO-LINK-ANALYSIS,INS-SP-TELCO-FRAUD
//           expires: 2027-12-31          (optional; none = no expiry)
//           nodes: 2                     (optional; default 1)
// MEANING (operator 2026-10-18) the Edition gives the PLATFORM: its modules that no add-on names. Only the add-ons, Solution packs,
//         Apps (PA-10b) and module/functionality items the customer buys are added. An item outside the Edition is refused.
// CLOSURE a kept module's `requires.modules` are kept too; a module pulled in that way is listed on the bill of materials as
//         `required` ("needed by X"), so whether to charge for it is a decision, not an accident.
// PACKS   a bought Space Template's needs are DERIVED from the manifests: each `type:` in its config/jobs/*.toon against
//         provides.jobTypes, each config/registry/<kind>s/ folder against provides.configKinds. ⚠ Ops configs under config/ops/
//         (e.g. a Case Rule) name no owner in any manifest, so they are not derived (PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1, D-PA37).
// APPS    (PA-10b) an `app` item's Order Code buys the App: its Add-ons' modules are kept and its templates staged. A Domain
//         product's required Apps must be bought; a Platform product's optional App comes along when every Add-on it needs is held.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseToon } from './check-offerings.mjs';
import { assemble } from './assemble.mjs';
import { resolveEntitlement } from './entitle.mjs';
import { gatherModel, productApps } from './products.mjs';
import { gather as gatherOfferings } from './check-offerings.mjs';
import { gather as gatherMap } from './check-product-map.mjs';
import { readPrices } from './render-product-map.mjs';

const ROLE_ALWAYS = new Set(['base', 'internal']);
const list = (x) => (Array.isArray(x) ? x : []);
const fail = (m) => { throw new Error(m); };

/**
 * customer, product-map files, world, prices -> { offering, keep[], removed[], packs[], apps[], addons[], bom[], refusals[] }. Pure.
 * world: { manifests, offerings, templateNeeds, apps?, product? } - `apps` (the App definitions) and `product` (the customer's
 * Product definition) are needed only to resolve App Order Codes.
 */
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
    const apps = new Set();
    const price = (code) => prices[code] ?? {};
    const line = (it, kind, reason) => ({ orderCode: it?.orderCode ?? '', title: it?.title ?? '', kind: it?.kind ?? '', line: kind, reason,
        enforcement: it?.enforcement ?? '', listPrice: price(it?.orderCode).listPrice ?? '', currency: price(it?.orderCode).currency ?? '' });
    const bom = [];
    const edItem = items.find((i) => i.kind === 'edition' && i.offering === customer.edition);
    bom.push(line(edItem, 'edition', `base Edition ${customer.edition}`));
    const bought = new Set();
    const needs = []; // [module, who needs it] from bought Space Templates
    const productAppIds = world.product ? [...list(world.product.apps?.required), ...list(world.product.apps?.optional)] : null;
    for (const code of customer.orderCodes ?? []) {
        const it = items.find((i) => i.orderCode === code);
        if (!it) { refusals.push(`Order Code '${code}' is in no Product Map Item`); continue; }
        if (it.billable !== 'true') { refusals.push(`${code} (${it.id}) is not billable`); continue; }
        if (it.kind === 'edition') { if (it.offering !== customer.edition) refusals.push(`${code} is Edition '${it.offering}', but the customer's Edition is '${customer.edition}'`); continue; }
        if (it.offeringAddon !== undefined) {
            if (!R.addons[it.offeringAddon]) { refusals.push(`${code} add-on '${it.offeringAddon}' is not in Edition '${customer.edition}'`); continue; }
            R.addons[it.offeringAddon].forEach((m) => keep.set(m, `bought ${code}`));
            bought.add(it.offeringAddon);
        } else if (it.app !== undefined) {
            const app = world.apps?.[it.app];
            if (!app) { refusals.push(`${code} App '${it.app}' has no apps/${it.app}/app.toon`); continue; }
            if (productAppIds && !productAppIds.includes(it.app)) { refusals.push(`${code} App '${it.app}' is not one of Product '${world.product.id}''s Apps (${productAppIds.join(', ') || 'none'})`); continue; }
            const missing = list(app.requires?.addons).filter((a) => !R.addons[a]);
            if (missing.length) { refusals.push(`${code} App '${it.app}' needs Add-on(s) ${missing.join(', ')}, which Edition '${customer.edition}' does not have`); continue; }
            for (const a of list(app.requires?.addons)) { R.addons[a].forEach((m) => keep.set(m, `bought ${code}`)); bought.add(a); }
            for (const t of list(app.templates)) { packs.add(t); for (const m of world.templateNeeds?.[t] ?? []) needs.push([m, `Space Template '${t}'`]); }
            apps.add(it.app);
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
    for (const a of list(world.product?.apps?.required)) if (!apps.has(a)) refusals.push(`App '${a}' is required by Product '${world.product.id}', so the record buys it (its Order Code)`);
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
    return { offering, keep: listed, keepReasons: Object.fromEntries(keep), removed, packs: [...packs].sort(), allPacks: [...R.packs],
        apps: [...apps].sort(), addons: [...bought].sort(), bom, refusals };
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
const listDirs = (d) => readdirSync(d, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => e.name);

/** A customer record: a path to a .toon file, or an id under product-map/private/customers/. */
export function readCustomer(root, idOrFile) {
    const file = /\.toon$/i.test(idOrFile) ? idOrFile : join(root, 'product-map', 'private', 'customers', `${idOrFile}.toon`);
    if (!existsSync(file)) fail(`no customer record ${file}`);
    const c = parseToon(readFileSync(file, 'utf8'));
    if (!c.id || !c.edition) fail(`${file}: a customer record has an id and an edition`);
    return { ...c, edition: String(c.edition).toLowerCase(), product: c.product ?? 'inspecto' };
}

/**
 * The Assembly Plan of one customer's build (D-PA33): the Product's plan, narrowed to what the record buys, with a `customer` block
 * (the Offering, the unpriced bill, the jars to remove, the entitlement to sign). `model` is products.mjs gatherModel(), `g`
 * check-offerings gather(), `files`/`world` check-product-map gather() with world.templateNeeds filled. Returns { plan, bom } (bom: the
 * PRICED bill, never shipped). Throws on any refusal.
 */
export function customerAssembly({ model, g, files, world, customer, prices = {}, distribution = 'production', target = 'windows', keyId = 'inspecto-issuer-1', now }) {
    const p = model.products[customer.product] ?? fail(`customer '${customer.id}': unknown Product '${customer.product}' (products/: ${Object.keys(model.products).join(', ')})`);
    const r = resolve(customer, files, { ...world, apps: model.apps, product: p }, prices);
    if (r.refusals.length) fail(`customer '${customer.id}' is refused:\n  ${r.refusals.join('\n  ')}`);
    // A Platform product's optional App comes along when every Add-on it needs is held (bought or bought through an App);
    // a Domain product's Apps are what the record buys.
    const held = new Set(r.addons);
    const { optional } = productApps(p);
    const apps = p.shape === 'domain' ? optional.filter((a) => r.apps.includes(a))
        : optional.filter((a) => r.apps.includes(a) || list(model.apps[a]?.requires?.addons).every((x) => held.has(x)));
    const plan = assemble(model, g, { product: p.id, edition: customer.edition, distribution, apps, target });
    const removedJars = new Set(r.removed.map((m) => `inspecto-${m}.jar`));
    const templates = plan.templates.filter((t) => r.packs.includes(t) || plan.apps.some((a) => list(model.apps[a.id]?.templates).includes(t)));
    const { fields } = resolveEntitlement(model, g, {
        customer: customer.id, title: customer.title ?? customer.id, product: p.id, edition: customer.edition,
        addons: [...held], apps: plan.apps.map((a) => a.id), nodes: customer.nodes, expires: customer.expires, keyId, now,
    });
    // D-PA34: the file entitles exactly the optional modules this build keeps, so the jars and the file agree by construction.
    fields.modules = [...r.keep];
    return { bom: r.bom, plan: {
        ...plan,
        modules: plan.modules.filter((j) => !removedJars.has(j)),
        templates,
        partnerTemplates: plan.partnerTemplates.filter((t) => templates.includes(t.id)),
        editionProperties: { ...plan.editionProperties, customer: customer.id, offering: `customer-${customer.id}` },
        customer: {
            id: customer.id, title: customer.title ?? customer.id, offering: r.offering, keep: r.keep, removed: r.removed,
            // the Product's own Apps: a bundle built with any other (a partner App, --app-root) is refused by the trim
            productApps: [...productApps(p).required, ...productApps(p).optional],
            removedJars: [...removedJars].sort(), bom: r.bom.map(({ listPrice, currency, ...b }) => b), entitlement: fields,
        },
    } };
}

/** customerAssembly() over a repo root: the record (an id or a .toon path), the definitions, the map and private prices. */
export function customerPlan(root, idOrFile, opts = {}) {
    const customer = readCustomer(root, idOrFile);
    const [files, world] = gatherMap(root);
    world.templateNeeds = templateNeeds(root, world.manifests);
    const pf = join(root, 'product-map', 'private', 'prices.csv');
    return customerAssembly({ model: gatherModel(root), g: gatherOfferings(root), files, world, customer,
        prices: existsSync(pf) ? readPrices(readFileSync(pf, 'utf8')) : {}, ...opts });
}
