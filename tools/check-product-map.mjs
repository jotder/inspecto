#!/usr/bin/env node
// check-product-map — holds product-map/<kind>/<name>.toon (docs/superpower/product-map-plan.md, PRODUCT-MAP-1 slice S2) against the
// module manifests, offerings/*.toon, Space Templates and Industry packs. Reads files only; no build.
//
//   node tools/check-product-map.mjs [--root .]      exit 1 on any finding
//
// CHECKS  1 every file parses; kind is known and equals its folder; id is `<kind>.<name>` and equals the file name
//         2 only the allowed keys appear (ALLOWED below) — this is what keeps technical facts (roles, requires, which Edition holds
//           what) and prices (D-PM3: only in the gitignored product-map/private/) out of the committed tree
//         3 closed vocabularies: billable, enforcement, billing.model (D-PM5), support.tier, status
//         4 links resolve: module (manifest id), offering, offeringAddon, template, industryPack, parent (no cycle), docs (a file)
//         5 coverage: every manifest, Offering, Offering add-on, Space Template and Industry pack has an item
//         6 a billable item has an orderCode (unique), an enforcement and a billing.model
//         7 enforcement is possible (D-PM4): `build` only for an item whose every module is optional/provider and named by no other
//           add-on (a Functionality is never `build`); `enabled-gate` only when every module declares a feature id

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseToon } from './check-offerings.mjs';

export const KINDS = ['layer', 'module', 'functionality', 'addon', 'solution-pack', 'edition'];
export const VOCAB = {
    billable: ['true', 'false'],
    enforcement: ['build', 'enabled-gate', 'contract'],
    'billing.model': ['included', 'flat', 'per-user', 'per-space', 'per-node'],
    'support.tier': ['community', 'standard', 'premium'],
    status: ['planned', 'preview', 'ga', 'deprecated'],
};
const ALLOWED = new Set(['id', 'kind', 'title', 'parent', 'module', 'offering', 'offeringAddon', 'template', 'industryPack', 'billable',
    'orderCode', 'enforcement', 'billing', 'billing.model', 'support', 'support.tier', 'support.owner', 'support.runbook', 'status', 'docs']);

const flat = (o, pre = '') => Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' && !Array.isArray(v)
    ? [[pre + k, v], ...flat(v, `${pre}${k}.`)] : [[pre + k, v]]));

/** files: { 'kind/name': text }; world: { manifests, offerings, templates[], industryPacks[], docExists(path) }. Returns findings. */
export function check(files, world) {
    const out = [];
    const items = {};
    for (const [rel, text] of Object.entries(files)) {
        const [kind, name] = rel.split('/');
        let it;
        try { it = parseToon(text); } catch (e) { out.push(`${rel}: does not parse (${e.message})`); continue; }
        if (!KINDS.includes(kind)) out.push(`${rel}: unknown kind folder '${kind}' (one of ${KINDS.join(', ')})`);
        if (it.kind !== kind) out.push(`${rel}: kind '${it.kind}' does not match its folder '${kind}'`);
        if (it.id !== `${kind}.${name}`) out.push(`${rel}: id '${it.id}' must be '${kind}.${name}'`);
        for (const [k] of flat(it)) if (!ALLOWED.has(k)) out.push(`${rel}: key '${k}' is not allowed (technical facts are looked up by id; prices live only in product-map/private/)`);
        for (const [k, v] of flat(it)) if (VOCAB[k] && !VOCAB[k].includes(v)) out.push(`${rel}: ${k} '${v}' is not one of ${VOCAB[k].join(', ')}`);
        items[it.id] = { ...it, rel };
    }

    const { manifests, offerings, templates, industryPacks, docExists } = world;
    const addons = {};
    for (const o of Object.values(offerings)) for (const [n, a] of Object.entries(o.addons ?? {})) addons[n] = a;
    const addonsOf = {};
    for (const [n, a] of Object.entries(addons)) for (const x of a.modules ?? a.hostedBy ?? []) (addonsOf[x] ??= new Set()).add(n);

    const orderCodes = {};
    for (const it of Object.values(items)) {
        const r = it.rel;
        if (it.module !== undefined && !manifests[it.module]) out.push(`${r}: module '${it.module}' has no manifest`);
        if (it.offering !== undefined && !offerings[it.offering]) out.push(`${r}: offering '${it.offering}' has no offerings/${it.offering}.toon`);
        if (it.offeringAddon !== undefined && !addons[it.offeringAddon]) out.push(`${r}: offeringAddon '${it.offeringAddon}' is in no Offering's addons`);
        if (it.template !== undefined && !templates.includes(it.template)) out.push(`${r}: template '${it.template}' is not under spaces/_templates`);
        if (it.industryPack !== undefined && !industryPacks.includes(it.industryPack)) out.push(`${r}: industryPack '${it.industryPack}' does not exist`);
        if (it.docs !== undefined && !docExists(it.docs)) out.push(`${r}: docs '${it.docs}' does not exist`);
        if ((it.kind === 'module' || it.kind === 'functionality') && it.parent === undefined) out.push(`${r}: a ${it.kind} needs a parent`);
        if (it.kind === 'functionality' && it.module === undefined) out.push(`${r}: a functionality needs a module`);
        if (it.parent !== undefined) {
            const seen = new Set([it.id]);
            for (let p = it.parent; p !== undefined; p = items[p]?.parent) {
                if (!items[p]) { out.push(`${r}: parent '${p}' does not exist`); break; }
                if (seen.has(p)) { out.push(`${r}: parent chain has a cycle at '${p}'`); break; }
                seen.add(p);
            }
        }
        if (it.billable === 'true') {
            for (const k of ['orderCode', 'enforcement']) if (it[k] === undefined) out.push(`${r}: billable but no ${k}`);
            if (it.billing?.model === undefined) out.push(`${r}: billable but no billing.model`);
            if (it.orderCode !== undefined) (orderCodes[it.orderCode] ??= []).push(r);
        }
        // Which modules this item's enforcement acts on.
        const mods = it.module !== undefined ? [it.module]
            : it.offeringAddon !== undefined ? (addons[it.offeringAddon]?.modules ?? addons[it.offeringAddon]?.hostedBy ?? []) : null;
        if (it.enforcement === 'build') {
            if (it.kind === 'functionality') out.push(`${r}: a functionality cannot be left out of a build (use enabled-gate or contract)`);
            else if (mods) {
                for (const x of mods) {
                    const role = manifests[x]?.offeringRole;
                    if (role && role !== 'optional' && role !== 'provider') out.push(`${r}: enforcement build, but module '${x}' is ${role} (always present)`);
                    if (it.kind === 'addon' && addonsOf[x]?.size > 1) out.push(`${r}: enforcement build, but module '${x}' is also named by add-on(s) ${[...addonsOf[x]].filter((n) => n !== it.offeringAddon).join(', ')}`);
                }
            }
        }
        if (it.enforcement === 'enabled-gate' && mods) {
            for (const x of mods) if (manifests[x] && !(manifests[x].provides?.features ?? []).length) out.push(`${r}: enforcement enabled-gate, but module '${x}' declares no feature id`);
        }
    }
    for (const [c, rs] of Object.entries(orderCodes)) if (rs.length > 1) out.push(`orderCode '${c}' is used by ${rs.join(', ')}`);

    const has = (pred) => Object.values(items).some(pred);
    for (const id of Object.keys(manifests)) if (!has((i) => i.kind === 'module' && i.module === id)) out.push(`module '${id}' has no Product Map Item (run tools/product-map-seed.mjs)`);
    for (const id of Object.keys(offerings)) if (!has((i) => i.kind === 'edition' && i.offering === id)) out.push(`Offering '${id}' has no edition item`);
    for (const n of Object.keys(addons)) if (!has((i) => i.offeringAddon === n)) out.push(`add-on '${n}' has no addon item`);
    for (const t of templates) if (!has((i) => i.template === t)) out.push(`Space Template '${t}' has no solution-pack item`);
    for (const p of industryPacks) if (!has((i) => i.industryPack === p)) out.push(`Industry pack '${p}' has no solution-pack item`);
    return out;
}

/** Everything check() needs, read from a repo root. product-map/private/ is never read. */
export function gather(root) {
    const files = {};
    const pm = join(root, 'product-map');
    for (const d of existsSync(pm) ? readdirSync(pm, { withFileTypes: true }) : []) {
        if (!d.isDirectory() || d.name === 'private') continue;
        for (const f of readdirSync(join(pm, d.name)).filter((n) => n.endsWith('.toon'))) files[`${d.name}/${f.slice(0, -5)}`] = readFileSync(join(pm, d.name, f), 'utf8');
    }
    const ls = (spec) => execFileSync('git', ['ls-files', spec], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
    const manifests = {};
    for (const f of ls('*META-INF/inspecto/module.toon')) { const m = parseToon(readFileSync(join(root, f), 'utf8')); manifests[m.id] = m; }
    const offerings = {};
    for (const f of readdirSync(join(root, 'offerings')).filter((n) => n.endsWith('.toon'))) offerings[f.slice(0, -5)] = parseToon(readFileSync(join(root, 'offerings', f), 'utf8'));
    const tdir = join(root, 'spaces', '_templates');
    return [files, {
        manifests, offerings,
        templates: readdirSync(tdir).filter((t) => existsSync(join(tdir, t, 'template.toon'))),
        industryPacks: [...new Set(ls('*META-INF/inspecto/industry-packs/*').map((p) => /industry-packs\/([^/]+)\//.exec(p)?.[1]).filter(Boolean))],
        docExists: (p) => existsSync(join(root, p)),
    }];
}

function main() {
    const args = process.argv.slice(2);
    const root = args.includes('--root') ? args[args.indexOf('--root') + 1] : '.';
    const [files, world] = gather(root);
    const findings = check(files, world);
    for (const f of findings) console.error(`✗ ${f}`);
    console.log(`check-product-map: ${Object.keys(files).length} items, ${findings.length} finding(s)`);
    process.exit(findings.length ? 1 : 0);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
