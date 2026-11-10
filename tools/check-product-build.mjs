#!/usr/bin/env node
// check-product-build - the Product matrix check (PRODUCT-ASSEMBLY-1 PA-5d; plan docs/superpower/2026-11-10-product-assembly-plan.md
// §11). For one Product x Edition (x Distribution) it assembles the plan and the descriptor WITHOUT building anything (no Maven,
// no UI) and checks that they hold together:
//   - the plan's Apps are the Product's required Apps plus its optional ones (none deselected), each named in the descriptor;
//   - every App the descriptor carries brings its Add-ons' modules onto the classpath (lib/inspecto-<module>.jar in plan.modules);
//   - every App draws at least one nav entry, and no nav path is drawn twice;
//   - a Product with a composed Space composes it from the staged parts (tools/compose-space.mjs), and a telecom Space gets its
//     one usage feed (PA-5c);
//   - a demo build names its demoSpace, its seed tool and its presenter text, and they exist.
//
//   node tools/check-product-build.mjs --product telco --edition standard [--distribution demo]
// CI runs it as the Telco Product matrix (Standard, Advanced; Advanced also as the demo) - the `product-matrix` job in ci.yml.

import { cpSync, existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble } from './assemble.mjs';
import { gather } from './check-offerings.mjs';
import { composeSpace } from './compose-space.mjs';
import { gatherModel } from './products.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const list = (v) => (Array.isArray(v) ? v : v ? [v] : []);

/** Assemble one Product build and check it: { plan, findings }, findings [] when it holds together. */
export function checkProductBuild({ product, edition, distribution = 'production', repo = repoRoot }) {
    const model = gatherModel(repo);
    const plan = assemble(model, gather(repo), { product, edition, distribution });
    return { plan, findings: checkPlan(plan, model.products[product], { repo }) };
}

/** The findings for an Assembly Plan of the Product definition `p`, as sentences. */
export function checkPlan(plan, p, { repo = repoRoot } = {}) {
    const out = [];
    const { distribution } = plan;
    const at = `${p.id} x ${plan.edition} x ${distribution}`;
    const want = [...list(p.apps?.required), ...list(p.apps?.optional)];
    const apps = plan.apps.map((a) => a.id);
    if (apps.join() !== want.join()) out.push(`${at}: the plan's Apps are ${apps.join(', ')}, the Product names ${want.join(', ')}`);
    const d = plan.descriptor;
    if (d.apps.map((a) => a.id).join() !== apps.join()) out.push(`${at}: the descriptor's Apps (${d.apps.map((a) => a.id).join(', ')}) are not the plan's`);
    const jars = new Set(plan.modules);
    const paths = new Map();
    for (const a of d.apps) {
        for (const m of a.modules) if (!jars.has(`inspecto-${m}.jar`)) out.push(`${at}: App '${a.id}' needs module '${m}', which the plan's classpath does not carry`);
        if (!a.nav.length) out.push(`${at}: App '${a.id}' draws no nav entry`);
        for (const n of a.nav) {
            if (paths.has(n.path)) out.push(`${at}: nav path '${n.path}' is drawn by both '${paths.get(n.path)}' and '${a.id}'`);
            paths.set(n.path, a.id);
        }
    }
    if (plan.space) {
        const stage = mkdtempSync(join(tmpdir(), 'check-product-build-'));
        try {
            for (const t of plan.templates) cpSync(join(repo, 'spaces', '_templates', t), join(stage, t), { recursive: true });
            const r = composeSpace(plan.space, stage);
            if (plan.space.industry === 'telecom' && plan.space.parts.length > 1 && !r.usageFeed) out.push(`${at}: the composed telecom Space has no one usage feed`);
        } catch (e) {
            out.push(`${at}: the Space '${plan.space.id}' does not compose: ${e.message}`);
        } finally {
            rmSync(stage, { recursive: true, force: true });
        }
    }
    if (distribution === 'demo') {
        if (!plan.seedSpace) out.push(`${at}: a demo build without a demoSpace bakes nothing`);
        for (const f of [plan.seedTool, plan.demoText].filter(Boolean)) if (!existsSync(join(repo, f))) out.push(`${at}: ${f} is missing`);
    }
    return out;
}

function main() {
    const a = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    try {
        const args = { product: val('--product'), edition: val('--edition'), distribution: val('--distribution') ?? 'production' };
        if (!args.product || !args.edition) throw new Error('usage: check-product-build.mjs --product <id> --edition <edition> [--distribution demo]');
        const { plan, findings } = checkProductBuild(args);
        if (findings.length) { for (const f of findings) console.error(`✗ ${f}`); process.exit(1); }
        console.log(`✓ ${args.product} x ${plan.edition} x ${plan.distribution}: ${plan.apps.length} Apps (${plan.apps.map((x) => x.id).join(', ')}), `
            + `${plan.descriptor.apps.reduce((n, x) => n + x.nav.length, 0)} nav entries, ${plan.modules.length} modules`
            + `${plan.space ? `, Space '${plan.space.id}' composed` : ''}${plan.seedSpace ? `, demo Space '${plan.seedSpace}'` : ''}`);
    } catch (e) {
        console.error(`check-product-build: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
