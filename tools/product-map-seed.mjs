#!/usr/bin/env node
// product-map-seed — writes the Product Map skeleton (docs/superpower/product-map-plan.md, PRODUCT-MAP-1 slice S1) from what already
// exists: one Product Map Item per layer directory, module manifest, Offering add-on, Solution pack and Edition.
//
//   node tools/product-map-seed.mjs [--root .] [--dry-run]
//
// IDEMPOTENT  an existing item file is never overwritten (it may hold hand edits); the run reports new vs kept.
// IDS         qualified by kind (`module.reconciliation`, `addon.reconciliation`) because the add-on and module namespaces overlap.
// NOT SEEDED  Functionality items (the module pages have no list of them — authored by hand) and every price (D-PM3: prices live
//             only in the gitignored product-map/private/). Commercial fields are placeholders for the commercial owner to set.
// TOON        same subset parseToon() reads; no '#' lines (TOON has no comments).

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseToon } from './check-offerings.mjs';

const LAYER_TITLE = {
    spi: 'Service contracts (SPI)', platform: 'Platform', features: 'Feature modules', la: 'Link Analysis modules',
    providers: 'Providers', product: 'Product host',
};
const unquote = (s) => (s ?? '').replace(/^"(.*)"$/, '$1');
const kebab = (s) => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
const code = (...parts) => ['INS', ...parts].join('-').toUpperCase().replace(/[^A-Z0-9-]/g, '-');

/** One item -> TOON text (flat keys, one level of nesting for `billing` / `support`). */
export function toToon(item) {
    const out = [];
    for (const [k, v] of Object.entries(item)) {
        if (v === undefined) continue;
        if (v && typeof v === 'object') { out.push(`${k}:`); for (const [ck, cv] of Object.entries(v)) out.push(`  ${ck}: ${cv}`); }
        else out.push(`${k}: ${v}`);
    }
    return out.join('\n') + '\n';
}

/** Everything the seed derives, as { '<kind>/<name>': item }. Pure apart from reading the repo. */
export function seed(root) {
    const items = {};
    // `docs:` is not seeded: tools/render-product-docs.mjs writes each item's hub and points `docs:` at it (PRODUCT-DOCS-HUB-1).
    const put = (kind, name, item) => { items[`${kind}/${name}`] = { id: `${kind}.${name}`, kind, ...item }; };

    const files = execFileSync('git', ['ls-files', '*META-INF/inspecto/module.toon'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
    for (const f of files) {
        const dir = f.split('/')[0];
        const layer = f.startsWith('inspecto/') ? 'product' : dir;
        const m = parseToon(readFileSync(join(root, f), 'utf8'));
        if (!items[`layer/${layer}`]) put('layer', layer, { title: LAYER_TITLE[layer] ?? layer, billable: false, status: 'ga' });
        put('module', m.id, {
            title: unquote(m.title) || m.id, parent: `layer.${layer}`, module: m.id, billable: false, status: 'ga'
        });
    }

    const odir = join(root, 'offerings');
    const offerings = readdirSync(odir).filter((n) => n.endsWith('.toon')).map((f) => parseToon(readFileSync(join(odir, f), 'utf8')));
    // An add-on whose module another add-on also names (incidents + workflowSla -> ops) cannot be left out of a build on its own.
    const addonsOf = {};
    for (const o of offerings) for (const [name, a] of Object.entries(o.addons ?? {})) for (const x of a.modules ?? a.hostedBy ?? []) (addonsOf[x] ??= new Set()).add(name);
    const shared = (a) => (a.modules ?? a.hostedBy ?? []).some((x) => addonsOf[x].size > 1);
    for (const o of offerings) {
        put('edition', o.id, {
            title: unquote(o.title), offering: o.id, billable: true, orderCode: code('ED', o.id), enforcement: 'build',
            billing: { model: 'per-node' }, support: { tier: 'standard' }, status: 'ga',
        });
        for (const [name, a] of Object.entries(o.addons ?? {})) {
            if (items[`addon/${name}`]) continue;
            put('addon', name, {
                title: name.replace(/([A-Z])/g, ' $1').replace(/^./, (c) => c.toUpperCase()), offeringAddon: name, billable: true,
                orderCode: code('AO', kebab(name)), enforcement: shared(a) || a.status !== 'built' ? 'contract' : 'build', billing: { model: 'flat' }, support: { tier: 'standard' },
                status: a.status === 'built' ? 'ga' : 'planned',
            });
        }
    }

    const tdir = join(root, 'spaces', '_templates');
    for (const t of readdirSync(tdir).filter((n) => existsSync(join(tdir, n, 'template.toon')))) {
        const name = /^name:\s*(.+)$/m.exec(readFileSync(join(tdir, t, 'template.toon'), 'utf8'))?.[1].trim();
        // A Space Template no Offering ships (the LA showcases) is a demo, not something sold.
        const sold = offerings.some((o) => (o.contentPacks ?? []).includes(t));
        put('solution-pack', t, sold
            ? { title: unquote(name) || t, template: t, billable: true, orderCode: code('SP', t), enforcement: 'build', billing: { model: 'flat' },
                support: { tier: 'standard' }, status: 'ga' }
            : { title: unquote(name) || t, template: t, billable: false, status: 'preview' });
    }
    const ipacks = execFileSync('git', ['ls-files', '*META-INF/inspecto/industry-packs/*'], { cwd: root, encoding: 'utf8' })
        .split('\n').filter(Boolean).map((p) => /industry-packs\/([^/]+)\//.exec(p)?.[1]).filter(Boolean);
    for (const p of new Set(ipacks)) {
        put('solution-pack', `industry-${p}`, {
            title: `Industry pack ${p}`, industryPack: p, billable: true, orderCode: code('IP', p), enforcement: 'contract',
            billing: { model: 'flat' }, support: { tier: 'standard' }, status: 'ga',
        });
    }
    return items;
}

function main() {
    const args = process.argv.slice(2);
    const root = args.includes('--root') ? args[args.indexOf('--root') + 1] : '.';
    const dry = args.includes('--dry-run');
    let made = 0, kept = 0;
    for (const [rel, item] of Object.entries(seed(root))) {
        const file = join(root, 'product-map', `${rel}.toon`);
        if (existsSync(file)) { kept++; continue; }
        made++;
        if (dry) { console.log(`would write product-map/${rel}.toon`); continue; }
        mkdirSync(join(file, '..'), { recursive: true });
        writeFileSync(file, toToon(item));
    }
    console.log(`product-map-seed: ${made} new, ${kept} kept${dry ? ' (dry run)' : ''}`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
