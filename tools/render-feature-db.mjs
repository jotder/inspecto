#!/usr/bin/env node
// render-feature-db — the `feature-db` slot of the documentation hubs (PRODUCT-DOCS-HUB-1, docs/archived-documents/plans-archive/product-docs-hub-plan.md
// wave 3): a GENERATED, sortable feature list per Product Map Item, for build and release engineers.
//
//   node tools/render-feature-db.mjs [--root .] [--check]      --check: exit 1 if any written file would change
//
// Writes docs/okf/product/<kind>/<name>/feature-db.md for every module and add-on item that has rows, and the whole product as
// target/product-docs/feature-db.csv (gitignored, not checked). DERIVED only, never hand-kept (D-PDH2) — rows come from:
//   * module manifests META-INF/inspecto/module.toon — every `provides:` list except `contracts` (features, configKinds, routes, ...)
//   * the processor catalog contract — a processor belongs to the module its `requires` names (`inspecto-<id>`), else to the
//     module its `capability` names, else to `engine` (where ProcessorCatalog lives)
//   * docs/FEATURE_INVENTORY.md §1 — a row whose Doc / code cell links into a module hub (`okf/product/module/<id>/`)
// Editions come from offerings/*.toon (`includes` resolved; a base/internal module is in every Edition); add-ons from the
// Offerings' `addons:` blocks. A feature-db.md this tool would no longer write is removed.

import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { gather } from './check-product-map.mjs';
import { parseToon } from './check-offerings.mjs';

const TIERS = ['basic', 'standard', 'advanced', 'preview'];
export const COLUMNS = ['feature', 'type', 'module', 'editions', 'addon', 'status', 'key', 'source'];
const CATALOG = 'inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json', INVENTORY = 'docs/FEATURE_INVENTORY.md';
const SINGULAR = { features: 'feature', configKinds: 'config kind', routes: 'route', jobTypes: 'job type', consequences: 'consequence',
    background: 'background task', maintenanceTasks: 'maintenance task', storeFamilies: 'store family', stepKinds: 'step kind' };

/** FEATURE_INVENTORY.md text -> [{ section, cells }] for every §1 table row. */
export function inventoryRows(text) {
    const out = []; let section = '', inS1 = false;
    for (const line of text.split(/\r?\n/)) {
        if (/^## /.test(line)) inS1 = /^## 1\./.test(line);
        if (!inS1) continue;
        const h = /^### ([A-Z]) /.exec(line); if (h) section = h[1];
        if (!line.startsWith('| ') || /^\|\s*Feature\s*\|/.test(line) || /^\|[-| ]+\|$/.test(line)) continue;
        out.push({ section, cells: line.slice(1, -1).split(/(?<!\\)\|/).map((c) => c.trim()) });
    }
    return out;
}

/** world: gather()'s world + { catalog, inventory, manifestPaths }; files: product-map items. Returns rows (COLUMNS keys), sorted. */
export function rows(files, world) {
    const { manifests, offerings, catalog = { processors: [] }, inventory = '', manifestPaths = {} } = world;
    const items = Object.values(files).map((t) => parseToon(t));
    const status = Object.fromEntries(items.filter((i) => i.module).map((i) => [i.module, i.status ?? '']));
    const resolved = (id, seen = new Set()) => {
        if (seen.has(id)) return new Set();
        seen.add(id);
        const acc = new Set(offerings[id].modules ?? []);
        for (const inc of offerings[id].includes ?? []) resolved(inc, seen).forEach((m) => acc.add(m));
        return acc;
    };
    const eds = Object.keys(offerings).sort((a, b) => TIERS.indexOf(a) - TIERS.indexOf(b));
    const edMods = Object.fromEntries(eds.map((e) => [e, resolved(e)]));
    const addonsOf = {};
    for (const o of Object.values(offerings)) for (const [n, a] of Object.entries(o.addons ?? {}))
        for (const m of a.modules ?? a.hostedBy ?? []) (addonsOf[m] ??= new Set()).add(n);
    const editions = (m) => {
        const role = manifests[m]?.offeringRole;
        return eds.filter((e) => role === 'base' || role === 'internal' || edMods[e].has(m)).join(';');
    };
    const row = (module, r) => ({ module, editions: editions(module), addon: [...(addonsOf[module] ?? [])].sort().join(';'),
        status: status[module] ?? '', key: '', ...r });
    const out = [];
    for (const [id, m] of Object.entries(manifests)) for (const [k, list] of Object.entries(m.provides ?? {})) {
        if (k === 'contracts' || !Array.isArray(list)) continue;
        for (const v of list) out.push(row(id, { feature: v, type: SINGULAR[k] ?? k, key: v, source: manifestPaths[id] ?? 'module.toon' }));
    }
    for (const p of catalog.processors ?? []) {
        const req = p.requires?.replace(/^inspecto-/, '');
        const module = manifests[req] ? req : manifests[p.capability] ? p.capability : 'engine';
        if (!manifests[module]) continue;
        out.push(row(module, { feature: p.label, type: 'processor', status: p.status, key: p.id, source: CATALOG }));
    }
    for (const { section, cells } of inventoryRows(inventory)) {
        const module = /okf\/product\/module\/([a-z0-9-]+)\//.exec(cells.join(' '))?.[1];
        if (!module || !manifests[module]) continue;
        const tag = /\[(LIVE|NATIVE|PROPOSED)\]/.exec(cells.join(' '))?.[1];
        out.push(row(module, { feature: cells[0], type: 'inventory', status: tag ? tag.toLowerCase() : status[module] ?? '',
            key: cells[1] ?? '', source: `${INVENTORY} §1${section}` }));
    }
    return out.sort((a, b) => a.module.localeCompare(b.module) || a.type.localeCompare(b.type) || a.feature.localeCompare(b.feature));
}

/** Items -> { 'kind/name': rows } for every module item (its module's rows) and add-on item (its modules' rows) with rows. */
export function hubs(files, world, rs) {
    const out = {};
    const addonModules = {};
    for (const o of Object.values(world.offerings)) for (const [n, a] of Object.entries(o.addons ?? {})) addonModules[n] = a.modules ?? a.hostedBy ?? [];
    for (const [key, text] of Object.entries(files)) {
        const it = parseToon(text);
        const mods = it.kind === 'module' && it.module ? [it.module] : it.kind === 'addon' ? addonModules[it.offeringAddon] ?? [] : [];
        const mine = rs.filter((r) => mods.includes(r.module));
        if (mine.length) out[key] = { title: it.title ?? it.id, id: it.id, rows: mine };
    }
    return out;
}

const md = (v) => String(v).replace(/\r?\n/g, ' ').replace(/(?<!\\)\|/g, '\\|');
export function toMarkdown({ title, id, rows: rs }) {
    const kind = id.split('.')[0], name = id.slice(kind.length + 1);
    return `---
type: FeatureDb
title: ${title} — feature database
item: ${id}
description: Generated feature list for ${id} — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, ${kind}]
---

# ${title} — feature database (\`${id}\`)

> **Generated** by \`tools/render-feature-db.mjs\` from the module manifests, \`offerings/*.toon\`, the processor catalog
> contract and \`docs/FEATURE_INVENTORY.md\` — never edit this table; change its sources and re-run the tool. The whole
> product is in \`target/product-docs/feature-db.csv\` (sortable). Hub: [index](index.md) · Product Map Item
> [\`${id}\`](../../../../../product-map/${kind}/${name}.toon).

${rs.length} row(s).

| ${COLUMNS.join(' | ')} |
|${COLUMNS.map(() => '---').join('|')}|
${rs.map((r) => `| ${COLUMNS.map((c) => (c === 'key' || c === 'source') && r[c] && !r[c].includes('`') ? `\`${md(r[c])}\`` : md(r[c])).join(' | ')} |`).join('\n')}
`;
}

const csvCell = (v) => { const s = String(v).replace(/\\\|/g, '|'); return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
export const toCsv = (rs) => [COLUMNS.join(','), ...rs.map((r) => COLUMNS.map((c) => csvCell(r[c])).join(','))].join('\r\n') + '\r\n';

function main() {
    const args = process.argv.slice(2);
    const root = args.includes('--root') ? args[args.indexOf('--root') + 1] : '.';
    const check = args.includes('--check');
    const [files, world] = gather(root);
    const manifestPaths = {};
    for (const f of execFileSync('git', ['ls-files', '*META-INF/inspecto/module.toon'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean))
        manifestPaths[parseToon(readFileSync(join(root, f), 'utf8')).id] = f;
    const read = (p) => (existsSync(join(root, p)) ? readFileSync(join(root, p), 'utf8') : '');
    world.catalog = JSON.parse(read(CATALOG) || '{}');
    world.inventory = read(INVENTORY);
    world.manifestPaths = manifestPaths;
    const rs = rows(files, world);
    const hs = hubs(files, world, rs);
    let stale = 0;
    const want = new Set(Object.keys(hs).map((k) => join(root, 'docs/okf/product', k, 'feature-db.md')));
    for (const [k, h] of Object.entries(hs)) {
        const path = join(root, 'docs/okf/product', k, 'feature-db.md'), text = toMarkdown(h);
        if (existsSync(path) && readFileSync(path, 'utf8') === text) continue;
        stale++;
        if (check) { console.error(`  stale: ${path}`); continue; }
        mkdirSync(join(path, '..'), { recursive: true });
        writeFileSync(path, text);
    }
    const hubRoot = join(root, 'docs/okf/product');
    for (const kind of existsSync(hubRoot) ? readdirSync(hubRoot, { withFileTypes: true }).filter((d) => d.isDirectory()) : [])
        for (const n of readdirSync(join(hubRoot, kind.name), { withFileTypes: true }).filter((d) => d.isDirectory())) {
            const path = join(hubRoot, kind.name, n.name, 'feature-db.md');
            if (!existsSync(path) || want.has(path) || !readFileSync(path, 'utf8').includes('tools/render-feature-db.mjs')) continue;
            stale++;
            if (check) console.error(`  stale (orphan): ${path}`); else rmSync(path);
        }
    if (!check) {
        mkdirSync(join(root, 'target/product-docs'), { recursive: true });
        writeFileSync(join(root, 'target/product-docs/feature-db.csv'), '﻿' + toCsv(rs)); // BOM so Excel reads UTF-8
    }
    console.log(`render-feature-db: ${rs.length} row(s), ${Object.keys(hs).length} hub(s); ${stale} file(s) ${check ? 'stale' : 'written'}`);
    if (check && stale) process.exit(1);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
