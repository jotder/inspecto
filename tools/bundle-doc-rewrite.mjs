#!/usr/bin/env node
/**
 * Package-time link rewrite for the SHIPPED docs — the ONE implementation, used by both sides.
 *
 *   - `inspecto/package.ps1` step 7 runs this file as a CLI over the staged bundle.
 *   - `tools/check-bundle-doc-links.mjs` imports `rewriteForBundle` and applies it in simulated mode, so the
 *     guard reports the links that SURVIVE packaging, not the pre-rewrite count.
 *
 * WHY ONE FILE (BUNDLE-DANGLING-LINKS-1, re-opened 2026-09-24). The rewrite used to be a PowerShell regex
 * inside step 7 and the guard never modelled it, so the guard stayed red at 320 after the fix shipped and a
 * later pass closed the row by declaring the red "working as designed". A guard that cannot see the fix it
 * measures reports on a bundle nobody ships — the same drift the guard's own header forbids for the
 * exclusion lists.
 *
 * For every inline markdown link `[label](target)` outside a code fence whose target does NOT resolve in the
 * bundle:
 *   1. WITHHELD — the target is a withheld doc tree/file (step 7's two lists, passed in, never restated
 *      here): the link is replaced by `label (internal document - not shipped)`.
 *   2. RELOCATED — the target ships, but packaging moved it (`inspecto/README.md` → `README.md`,
 *      `inspecto/examples/` → `examples/`), or moved the linking file: the link is re-pointed at where the
 *      target actually is in the bundle.
 *   3. Anything else is LEFT ALONE and the guard reports it: a source-code citation that never ships, or a
 *      link broken in the repo too. Hiding those at package time would hide real rot.
 *
 * Decided by RESOLVING the target, not by a substring match on the tree name (the old PowerShell pattern
 * also neutralised any link whose text merely contained `BACKLOG` or `superpower`).
 *
 * CLI:  node tools/bundle-doc-rewrite.mjs --bundle <dir> --withheld-trees a,b --withheld-files x.md,y.md
 *       Rewrites every shipped markdown file in place; prints the counts. Exit 2 on bad arguments.
 */

import { readdirSync, readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';

export const LINK = /\[([^\]]*)\]\(\s*([^)\s]+?)(?:\s+"[^"]*")?\s*\)/g;
const EXTERNAL = /^(https?:|mailto:|tel:|ftp:|data:|#)/i;
export const NOT_SHIPPED = ' (internal document - not shipped)';

/**
 * Repo path prefix → bundle path prefix for everything packaging MOVES. Mirrors `package.ps1` step 4b
 * (examples) and step 7 (README). Everything not listed keeps its repo path.
 */
export const RELOCATIONS = [
    ['inspecto/README.md', 'README.md'],
    ['inspecto/examples', 'examples'],
];

const swap = (p, from, to) => {
    for (const pair of RELOCATIONS) {
        const [a, b] = [pair[from], pair[to]];
        if (p === a) return b;
        if (p.startsWith(a + '/')) return b + p.slice(a.length);
    }
    return p;
};
export const repoToBundle = (p) => swap(p, 0, 1);
export const bundleToRepo = (p) => swap(p, 1, 0);

/** True if a bundle path falls inside a doc tree/file step 7 withholds. */
export function isWithheld(bundlePath, excl) {
    const parts = bundlePath.split('/');
    return parts[0] === 'docs' && (excl.trees.includes(parts[1]) || excl.files.includes(parts[1]));
}

/** Fence-aware line walk, shared so the guard and the rewrite agree on what is quoted text. */
export function forEachLiveLine(lines, fn) {
    let inFence = false;
    lines.forEach((line, i) => {
        const trimmed = line.trimStart();
        if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
            inFence = !inFence;
            return;
        }
        if (!inFence) fn(line, i);
    });
}

const decode = (t) => {
    try {
        return decodeURIComponent(t);
    } catch {
        return t;
    }
};

/**
 * Rewrite one shipped markdown file. `bundleFile` is its path in the bundle; `present(p)` says whether a
 * bundle path (file or directory) ships; `excl` is step 7's `{ trees, files }`.
 */
export function rewriteForBundle(text, bundleFile, present, excl) {
    const lines = text.split('\n');
    let neutralised = 0;
    let retargeted = 0;
    const bundleDir = posix.dirname(bundleFile);
    const repoDir = posix.dirname(bundleToRepo(bundleFile));
    forEachLiveLine(lines, (line, i) => {
        lines[i] = line.replace(LINK, (whole, label, raw) => {
            if (EXTERNAL.test(raw)) return whole;
            const hash = raw.indexOf('#');
            const target = hash >= 0 ? raw.slice(0, hash) : raw;
            if (!target) return whole;
            const decoded = decode(target);
            const inBundle = posix.normalize(posix.join(bundleDir, decoded)).replace(/\/$/, '');
            if (present(inBundle)) return whole;
            const viaRepo = repoToBundle(posix.normalize(posix.join(repoDir, decoded)).replace(/\/$/, ''));
            if (isWithheld(inBundle, excl) || isWithheld(viaRepo, excl)) {
                neutralised++;
                return label + NOT_SHIPPED;
            }
            if (!viaRepo.startsWith('..') && present(viaRepo)) {
                retargeted++;
                const rel = posix.relative(bundleDir, viaRepo) || '.';
                const at = whole.indexOf(raw, whole.indexOf(']('));
                return whole.slice(0, at) + rel + (hash >= 0 ? raw.slice(hash) : '') + whole.slice(at + raw.length);
            }
            return whole;
        });
    });
    return { text: lines.join('\n'), neutralised, retargeted };
}

function cli(argv) {
    const arg = (name) => {
        const i = argv.indexOf(name);
        return i >= 0 ? argv[i + 1] : undefined;
    };
    const dir = arg('--bundle');
    const trees = (arg('--withheld-trees') ?? '').split(',').filter(Boolean);
    const files = (arg('--withheld-files') ?? '').split(',').filter(Boolean);
    if (!dir || !existsSync(dir) || !statSync(dir).isDirectory() || !trees.length || !files.length) {
        console.error('usage: bundle-doc-rewrite.mjs --bundle <dir> --withheld-trees a,b --withheld-files x.md,y.md');
        process.exit(2);
    }
    const all = new Set();
    const walk = (abs, prefix) => {
        for (const e of readdirSync(abs, { withFileTypes: true })) {
            const rel = prefix ? `${prefix}/${e.name}` : e.name;
            all.add(rel);
            if (e.isDirectory()) walk(join(abs, e.name), rel);
        }
    };
    walk(dir, '');
    const excl = { trees, files };
    let neutralised = 0;
    let retargeted = 0;
    for (const rel of [...all].filter((p) => p.endsWith('.md'))) {
        const abs = join(dir, rel);
        const before = readFileSync(abs, 'utf8');
        const out = rewriteForBundle(before, rel, (p) => all.has(p), excl);
        neutralised += out.neutralised;
        retargeted += out.retargeted;
        if (out.text !== before) writeFileSync(abs, out.text, 'utf8');
    }
    console.log(`docs: neutralised ${neutralised} link(s) into withheld docs; re-pointed ${retargeted} link(s) at relocated targets`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) cli(process.argv.slice(2));
