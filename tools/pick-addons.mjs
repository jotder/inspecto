#!/usr/bin/env node
// pick-addons - the Add-on picker (PRODUCT-ASSEMBLY-1 PA-6c; plan docs/superpower/2026-11-10-product-assembly-plan.md §8, §11).
// A Platform product build is the Inspecto console with the Add-ons a builder, integrator or OEM picks. The picker shows each
// Add-on's closure (the other Add-ons its modules need) and Edition floor (the lowest Edition that builds it and its closure),
// with prices from product-map/private/prices.csv when that file exists. A pick becomes a NARROWED Platform product: the
// Platform product it narrows (`narrows: inspecto`) with only the picked Add-ons and their closure, and only the Editions at or
// above the floor. It keeps that product's host, Apps and templates, and builds in its place (assemble.mjs --product-file,
// package.ps1 -ProductFile). It is written to product-map/private/products/<id>.toon, gitignored like every customer record.
//
//   node tools/pick-addons.mjs                                            the catalog: Add-on, closure, Edition floor, price
//   node tools/pick-addons.mjs --pick a,b --id <id> [--title "T"] [--write [<file>]]
//       resolve the pick, assemble it on every Edition it comes in, print the definition; --write also writes it
//
// Exit 1 with the reason when a pick does not resolve or does not assemble.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gather } from './check-offerings.mjs';
import { addonModules, builtAddons, DEMO_EDITIONS, gatherModel, narrowModel, offeringAddons } from './products.mjs';
import { readPrices } from './render-product-map.mjs';
import { parseToon } from './toon-subset.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
/** The Platform product a pick narrows. */
export const PLATFORM_PRODUCT = 'inspecto';
const ID = /^[a-z][a-z0-9-]{1,40}$/;
const ALWAYS = new Set(['base', 'internal']);
const list = (x) => (Array.isArray(x) ? x : []);

/** The Add-ons `addon` needs in `edition`: those owning a module its modules require (transitively), itself excluded. */
export function addonClosure(edition, addon, offerings, manifests) {
    const all = Object.keys(offeringAddons(edition, offerings));
    const owners = (m) => all.filter((a) => addonModules(edition, a, offerings).has(m));
    const need = new Set([addon]);
    const queue = [addon];
    while (queue.length) {
        const a = queue.shift();
        const mods = [...addonModules(edition, a, offerings)];
        const seen = new Set(mods);
        for (let i = 0; i < mods.length; i++) {
            for (const r of list(manifests[mods[i]]?.requires?.modules)) {
                if (seen.has(r) || ALWAYS.has(manifests[r]?.offeringRole)) continue;
                seen.add(r); mods.push(r);
                for (const o of owners(r)) if (!need.has(o)) { need.add(o); queue.push(o); }
            }
        }
    }
    need.delete(addon);
    return [...need].sort();
}

/** The lowest of `editions` (in order) that builds every Add-on of `addons`, or null. */
const floorOf = (addons, editions, offerings) => editions.find((e) => addons.every((a) => builtAddons(e, offerings).has(a))) ?? null;

/** `addons` grown by their closure until the floor stops moving: { all (sorted), floor (null when no Edition builds them) }. */
function grow(addons, editions, g) {
    const picked = new Set(addons);
    for (;;) {
        const floor = floorOf([...picked], editions, g.offerings);
        if (!floor) return { all: [...picked].sort(), floor: null };
        const before = picked.size;
        for (const a of [...picked]) addonClosure(floor, a, g.offerings, g.manifests).forEach((x) => picked.add(x));
        if (picked.size === before) return { all: [...picked].sort(), floor };
    }
}

/** The Product Map's billable items by what they sell: { addon: {id: item}, edition: {id: item} }. */
export function mapItems(root) {
    const read = (kind) => {
        const dir = join(root, 'product-map', kind);
        return existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.toon')).map((f) => parseToon(readFileSync(join(dir, f), 'utf8'))) : [];
    };
    return {
        addon: Object.fromEntries(read('addon').map((i) => [i.offeringAddon, i])),
        edition: Object.fromEntries(read('edition').map((i) => [i.offering, i])),
    };
}

/** prices.csv under product-map/private/, or {} when there is none (prices are never committed). */
export const readPrivatePrices = (root) => {
    const f = join(root, 'product-map', 'private', 'prices.csv');
    return existsSync(f) ? readPrices(readFileSync(f, 'utf8')) : {};
};

const priceOf = (item, prices) => {
    const p = prices[item?.orderCode];
    return p?.listPrice ? { listPrice: Number(p.listPrice), currency: p.currency ?? '' } : null;
};

/** One row per Add-on of the Platform product: { addon, title, orderCode, closure[], floor, price }. */
export function catalog(model, g, { items = { addon: {}, edition: {} }, prices = {}, platform = PLATFORM_PRODUCT } = {}) {
    const p = model.products[platform] ?? fail(`no Platform product '${platform}' under products/`);
    const eds = list(p.editions);
    return list(p.addons).map((a) => {
        const { all, floor } = grow([a], eds, g);
        const closure = all.filter((x) => x !== a);
        return { addon: a, title: items.addon[a]?.title ?? a, orderCode: items.addon[a]?.orderCode ?? '', closure, floor, price: priceOf(items.addon[a], prices) };
    }).sort((x, y) => x.addon.localeCompare(y.addon));
}

/**
 * A pick resolved: its Add-ons with their closure (grown until the floor stops moving), the floor, and the narrowed Platform
 * product definition. Throws on an unknown Add-on, one no Edition builds, or a bad id.
 */
export function resolvePick(model, g, { addons, id, title = null, items = { addon: {}, edition: {} }, prices = {}, platform = PLATFORM_PRODUCT }) {
    const base = model.products[platform] ?? fail(`no Platform product '${platform}' under products/`);
    if (!ID.test(id ?? '')) fail(`id '${id ?? ''}' must be lower-case letters, digits and hyphens, 2 to 41 characters, starting with a letter`);
    if (model.products[id] && id !== platform) fail(`id '${id}' is already the Product products/${id}.toon`);
    if (!addons.length) fail('pick at least one Add-on (--pick a,b)');
    const eds = list(base.editions);
    for (const a of addons) if (!list(base.addons).includes(a)) fail(`Add-on '${a}' is not one of the ${platform} Platform product's (${list(base.addons).join(', ')})`);
    const grown = grow(addons, eds, g);
    if (!grown.floor) fail(`no Edition of ${eds.join(', ')} builds ${grown.all.join(' + ')} together`);
    const { floor } = grown;
    const picked = new Set(grown.all);
    const all = grown.all;
    const editions = eds.slice(eds.indexOf(floor));
    const distributions = list(base.distributions).filter((d) => d !== 'demo' || editions.some((e) => DEMO_EDITIONS.includes(e)));
    const definition = {
        id, title: title ?? id, shape: 'platform', narrows: platform, host: base.host,
        ...(base.hostConfiguration ? { hostConfiguration: base.hostConfiguration } : {}),
        // An optional App rides along only when the pick holds every Add-on it requires (Link Analysis needs linkAnalysis).
        editions, addons: all, apps: { required: list(base.apps?.required),
            optional: list(base.apps?.optional).filter((x) => list(model.apps[x]?.requires?.addons).every((r) => picked.has(r))) },
        templates: list(base.templates), distributions, brand: { name: title ?? base.brand?.name ?? id }, docs: base.docs,
    };
    const added = all.filter((a) => !addons.includes(a));
    const lines = [
        ...(items.edition[floor] ? [{ kind: 'edition', id: floor, orderCode: items.edition[floor].orderCode, price: priceOf(items.edition[floor], prices) }] : []),
        ...all.map((a) => ({ kind: added.includes(a) ? 'addon (closure)' : 'addon', id: a, orderCode: items.addon[a]?.orderCode ?? '', price: priceOf(items.addon[a], prices) })),
    ];
    const priced = lines.every((l) => l.price);
    const total = priced && lines.length ? lines.reduce((s, l) => s + l.price.listPrice, 0) : null;
    return { definition, picked: [...addons], added, floor, lines, total, currency: priced ? lines[0]?.price?.currency ?? '' : null };
}

/** The definition as TOON (the subset tools/toon-subset.mjs reads). */
export function toToon(d) {
    const arr = (k, v, pad = '') => `${pad}${k}[${v.length}]:${v.length ? ` ${v.join(',')}` : ''}`;
    return [
        `id: ${d.id}`, `title: ${d.title}`, `shape: ${d.shape}`, `narrows: ${d.narrows}`, `host: ${d.host}`,
        ...(d.hostConfiguration ? [`hostConfiguration: ${d.hostConfiguration}`] : []),
        arr('editions', d.editions), arr('addons', d.addons),
        'apps:', arr('required', d.apps.required, '  '), arr('optional', d.apps.optional, '  '),
        arr('templates', d.templates), arr('distributions', d.distributions),
        'brand:', `  name: ${d.brand.name}`, `docs: ${d.docs}`, '',
    ].join('\n');
}

function fail(msg) { throw new Error(msg); }

// ── command line ──

function main() {
    const a = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 && a[i + 1] && !a[i + 1].startsWith('--') ? a[i + 1] : undefined; };
    try {
        const model = gatherModel(REPO);
        const g = gather(REPO);
        const items = mapItems(REPO);
        const prices = readPrivatePrices(REPO);
        const money = (p) => (p ? `${p.listPrice} ${p.currency}`.trim() : '-');
        if (!a.includes('--pick')) {
            const rows = catalog(model, g, { items, prices });
            console.log(`Add-ons of the ${PLATFORM_PRODUCT} Platform product${Object.keys(prices).length ? '' : ' (no product-map/private/prices.csv: no prices)'}:`);
            for (const r of rows) console.log(`  ${r.addon.padEnd(22)} floor ${String(r.floor ?? 'none').padEnd(9)} ${r.orderCode.padEnd(28)} ${money(r.price).padEnd(14)} ${r.closure.length ? `needs ${r.closure.join(', ')}` : ''}`);
            return;
        }
        const r = resolvePick(model, g, { addons: (val('--pick') ?? '').split(',').map((s) => s.trim()).filter(Boolean), id: val('--id'), title: val('--title') ?? null, items, prices });
        const narrowed = narrowModel(model, r.definition);
        for (const e of r.definition.editions) assemble(narrowed, g, { product: r.definition.id, edition: e });
        console.log(`Picked ${r.picked.join(', ')}${r.added.length ? `; the closure adds ${r.added.join(', ')}` : ''}. Edition floor: ${r.floor}; assembles on ${r.definition.editions.join(', ')}.`);
        for (const l of r.lines) console.log(`  ${l.kind.padEnd(16)} ${l.id.padEnd(22)} ${l.orderCode.padEnd(28)} ${money(l.price)}`);
        if (r.total !== null) console.log(`  total (list, ${r.floor})  ${r.total} ${r.currency}`.trimEnd());
        const text = toToon(r.definition);
        if (a.includes('--write')) {
            const out = resolve(val('--write') ?? join(REPO, 'product-map', 'private', 'products', `${r.definition.id}.toon`));
            mkdirSync(dirname(out), { recursive: true });
            writeFileSync(out, text);
            console.log(`✓ wrote ${out}. Build it: pwsh -File inspecto/package.ps1 -ProductFile ${out} -Edition <${r.definition.editions.join('|')}>`);
        } else {
            process.stdout.write(`\n${text}`);
            console.log('(add --write to write it to product-map/private/products/)');
        }
    } catch (e) {
        console.error(`pick-addons: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
