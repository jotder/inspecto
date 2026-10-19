#!/usr/bin/env node
// Generates the `api` slot page `docs/okf/product/module/<name>/api-routes.md` for every Product Map module
// whose Maven module registers /api/v1 routes (PRODUCT-DOCS-HUB-1).
//
// Routes come from tools/route-gating-report.mjs's source scanner (one scanner, not two); each is owned by the
// Maven module whose folder holds the registering file, and that module's manifest id
// (`META-INF/inspecto/module.toon`) names its Product Map Item (`product-map/module/*.toon` `module:`).
// Each route is joined with its docs/api/openapi-v1.json operation (summary, tag). Drift is listed, never hidden:
// a route in code with no OpenAPI operation goes in its module's page; an OpenAPI operation no module registers
// goes in the processor page (the control-plane host that serves the contract).
//
// Usage:  node tools/render-api-routes.mjs           # write the pages
//         node tools/render-api-routes.mjs --check   # exit 1 if any page would change

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseToon } from './check-offerings.mjs';
import { scan } from './route-gating-report.mjs';

const MANIFEST = 'src/main/resources/META-INF/inspecto/module.toon';
const HOST = 'processor';
const METHODS = ['get', 'put', 'post', 'patch', 'delete'];

// OpenAPI `{id}` and a registration's regex group `([^/]+)` are the same path segment.
// A lookahead `(?!…)` matches no characters, so it is dropped first.
const norm = (p) => p.replace(/\(\?[!=][^()]*\)/g, '').replace(/\{[^}]*\}/g, '{}').replace(/\((?:[^()]|\([^()]*\))*\)/g, '{}');
const posix = (p) => p.replace(/\\/g, '/');
const cell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\s+/g, ' ').trim();

/** world: { routes[{method, pattern, capability, file, line}], manifests: {dir: id}, items: {moduleId: name}, openapi }. */
export function render({ routes, manifests, items, openapi }) {
    const dirs = Object.keys(manifests).sort((a, b) => b.length - a.length);
    const ops = new Map();
    for (const [path, byMethod] of Object.entries(openapi.paths ?? {}))
        for (const m of METHODS) if (byMethod[m]) ops.set(`${m.toUpperCase()} ${norm(path)}`, { path, ...byMethod[m] });

    const byItem = new Map();
    const seen = new Set();
    const unowned = [];
    for (const r of routes) {
        const file = posix(r.file);
        const dir = dirs.find((d) => file.startsWith(d + '/src/main/'));
        const name = dir && items[manifests[dir]];
        if (!name) { unowned.push(r); continue; }
        const key = `${r.method} ${norm(r.pattern)}`;
        seen.add(key);
        if (!byItem.has(name)) byItem.set(name, []);
        byItem.get(name).push({ ...r, file, op: ops.get(key) });
    }
    const contractOnly = [...ops.entries()].filter(([k]) => !seen.has(k)).map(([k, op]) => ({ method: k.split(' ')[0], path: op.path, op }));
    if (contractOnly.length && !byItem.has(HOST)) byItem.set(HOST, []);

    const pages = {};
    for (const [name, rows] of [...byItem.entries()].sort()) {
        rows.sort((a, b) => a.pattern.localeCompare(b.pattern) || a.method.localeCompare(b.method));
        const documented = rows.filter((r) => r.op);
        const missing = rows.filter((r) => !r.op);
        const out = [
            '---',
            'type: Concept',
            `title: HTTP routes — module.${name}`,
            `description: Every /api/v1 route the ${name} module registers, joined with the OpenAPI contract. Generated.`,
            'tags: [api, routes, generated]',
            '---',
            '',
            `# HTTP routes — \`module.${name}\``,
            '',
            '> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module\'s',
            '> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).',
            '> Never edit by hand — change the code or the contract and re-run the generator.',
            '',
            `${rows.length} route(s) registered; ${documented.length} in the OpenAPI contract, ${missing.length} not.`,
            '',
        ];
        if (documented.length) {
            out.push('| Method | Path | Summary | Tag | Capability | Registered in |', '|---|---|---|---|---|---|');
            for (const r of documented)
                out.push(`| ${r.method} | \`${r.op.path}\` | ${cell(r.op.summary) || '—'} | ${cell((r.op.tags ?? []).join(', ')) || '—'} | ${r.capability ? '`' + r.capability + '`' : '—'} | \`${r.file}\` |`);
            out.push('');
        }
        const drift = name === HOST ? contractOnly : [];
        out.push('## Drift', '');
        if (!missing.length && !drift.length) out.push('None — every route here is in the contract.', '');
        if (missing.length) {
            out.push('Registered in code, **missing from the OpenAPI contract**:', '', '| Method | Path | Capability | Registered in |', '|---|---|---|---|');
            for (const r of missing) out.push(`| ${r.method} | \`${r.pattern}\` | ${r.capability ? '`' + r.capability + '`' : '—'} | \`${r.file}\` |`);
            out.push('');
        }
        if (drift.length) {
            out.push('In the OpenAPI contract, **registered by no module\'s `api.<method>("…")` call** (may be served by another mechanism):', '', '| Method | Path | Summary |', '|---|---|---|');
            for (const d of drift.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method)))
                out.push(`| ${d.method} | \`${d.path}\` | ${cell(d.op.summary) || '—'} |`);
            out.push('');
        }
        pages[`docs/okf/product/module/${name}/api-routes.md`] = out.join('\n');
    }
    return { pages, unowned };
}

/** Reads the world from a repo root. */
export function gather(root) {
    const ls = (spec) => execFileSync('git', ['ls-files', spec], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
    const manifests = {};
    for (const f of ls('*' + MANIFEST)) manifests[f.slice(0, -(MANIFEST.length + 1))] = parseToon(readFileSync(join(root, f), 'utf8')).id;
    const items = {};
    const pm = join(root, 'product-map', 'module');
    for (const f of existsSync(pm) ? readdirSync(pm).filter((n) => n.endsWith('.toon')) : []) {
        const id = parseToon(readFileSync(join(pm, f), 'utf8')).module;
        if (id) items[id] = f.slice(0, -5);
    }
    const openapi = JSON.parse(readFileSync(join(root, 'docs/api/openapi-v1.json'), 'utf8'));
    return { routes: scan(root), manifests, items, openapi };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const check = process.argv.includes('--check');
    const { pages, unowned } = render(gather('.'));
    for (const r of unowned) console.warn(`⚠ ${r.method} ${r.pattern} (${posix(r.file)}) has no Product Map module — not rendered`);
    const stale = [];
    for (const [path, text] of Object.entries(pages)) {
        if ((existsSync(path) ? readFileSync(path, 'utf8') : null) === text) continue;
        stale.push(path);
        if (!check) writeFileSync(path, text);
    }
    if (check && stale.length) {
        console.error(`✗ ${stale.length} api-routes page(s) are stale: ${stale.join(', ')}. Run \`node tools/render-api-routes.mjs\` and commit.`);
        process.exit(1);
    }
    console.log(`✓ api-routes: ${Object.keys(pages).length} page(s) ${check ? 'up to date' : `(${stale.length} written)`}.`);
}
