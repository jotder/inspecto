#!/usr/bin/env node
// check-product-docs — holds the documentation hubs docs/okf/product/<kind>/<name>/index.md
// (docs/archived-documents/plans-archive/product-docs-hub-plan.md, PRODUCT-DOCS-HUB-1) against product-map/<kind>/<name>.toon.
//
//   node tools/check-product-docs.mjs [--root .]      exit 1 on any finding; prints slot coverage
//
// CHECKS  1 the hub's frontmatter `item:` equals `<kind>.<name>` of its folder, and that Product Map Item exists
//         2 the slot table has every SLOT exactly once, in order, each with a status from STATUSES
//         3 the Product Map Item's `docs:` points at its hub
// Links inside the table are resolved by check-doc-links.mjs, not here. Items with no hub yet are listed, not failed (wave 2).

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

export const SLOTS = ['plan', 'spec', 'architecture', 'features', 'feature-db', 'user-guide', 'admin-guide', 'api', 'tests', 'brochure'];
export const STATUSES = ['current', 'linked', 'stub', 'missing', 'n/a'];

/** hub: index.md text; kind/name: its folder; mapText: the product-map TOON text or undefined. Returns { findings, slots }. */
export function checkHub(kind, name, hub, mapText) {
    const id = `${kind}.${name}`, findings = [], slots = {};
    const item = /^item:\s*(\S+)\s*$/m.exec(hub)?.[1];
    if (item !== id) findings.push(`${id}: frontmatter item '${item ?? ''}' must be '${id}'`);
    if (mapText === undefined) findings.push(`${id}: no product-map/${kind}/${name}.toon`);
    else if (!new RegExp(`^docs:\\s*docs/okf/product/${kind}/${name}/index\\.md\\s*$`, 'm').test(mapText))
        findings.push(`${id}: product-map docs: must be docs/okf/product/${kind}/${name}/index.md`);
    const rows = [...hub.matchAll(/^\|\s*([a-z-/]+)\s*\|\s*([a-z/]+)\s*\|/gm)].map((m) => [m[1], m[2]]).filter(([s]) => s !== 'slot');
    const names = rows.map(([s]) => s);
    if (names.join() !== SLOTS.join()) findings.push(`${id}: slot rows must be exactly [${SLOTS.join(', ')}] in order, got [${names.join(', ')}]`);
    for (const [s, st] of rows) {
        if (!STATUSES.includes(st)) findings.push(`${id}: slot ${s} has unknown status '${st}'`);
        slots[s] = st;
    }
    return { findings, slots };
}

function main() {
    const ri = process.argv.indexOf('--root');
    const root = ri > 0 ? process.argv[ri + 1] : '.';
    const hubRoot = join(root, 'docs/okf/product'), mapRoot = join(root, 'product-map');
    const findings = [], coverage = Object.fromEntries(SLOTS.map((s) => [s, Object.fromEntries(STATUSES.map((t) => [t, 0]))]));
    const hubs = new Set();
    for (const kind of existsSync(hubRoot) ? readdirSync(hubRoot, { withFileTypes: true }).filter((d) => d.isDirectory()) : []) {
        for (const nd of readdirSync(join(hubRoot, kind.name), { withFileTypes: true }).filter((d) => d.isDirectory())) {
            const k = kind.name, n = nd.name, hubFile = join(hubRoot, k, n, 'index.md'), mapFile = join(mapRoot, k, `${n}.toon`);
            if (!existsSync(hubFile)) { findings.push(`${k}.${n}: folder has no index.md`); continue; }
            hubs.add(`${k}/${n}`);
            const r = checkHub(k, n, readFileSync(hubFile, 'utf8'), existsSync(mapFile) ? readFileSync(mapFile, 'utf8') : undefined);
            findings.push(...r.findings);
            for (const [s, st] of Object.entries(r.slots)) if (coverage[s]?.[st] !== undefined) coverage[s][st]++;
        }
    }
    const without = [];
    for (const k of readdirSync(mapRoot, { withFileTypes: true }).filter((d) => d.isDirectory() && d.name !== 'private'))
        for (const f of readdirSync(join(mapRoot, k.name)).filter((f) => f.endsWith('.toon')))
            if (!hubs.has(`${k.name}/${f.slice(0, -5)}`)) without.push(`${k.name}.${f.slice(0, -5)}`);
    console.log(`check-product-docs: ${hubs.size} hub(s); ${without.length} Product Map Item(s) without a hub yet`);
    for (const s of SLOTS) console.log(`  ${s.padEnd(13)} ${STATUSES.map((t) => `${t} ${coverage[s][t]}`).join(' · ')}`);
    for (const f of findings) console.error(`  ✖ ${f}`);
    process.exit(findings.length ? 1 : 0);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
