#!/usr/bin/env node
/**
 * Bundle-doc-link guard — every relative markdown link in a SHIPPED doc must resolve to something the
 * CUSTOMER actually has.
 *
 * WHY THIS EXISTS. `tools/check-doc-links.mjs` proves every current-tier link resolves IN THE REPO, and it
 * is green today. That says nothing about the artifact we hand a customer. Since BUNDLE-SHIPS-THE-ARCHIVE-1
 * (2026-09-16) `package.ps1` step 7 withholds two whole doc trees (`archived-documents/`, `superpower/`)
 * and two files (`BACKLOG.md`, `PROJECT_NOTES.md`) — 280 of 496 files — and withholding does not rewrite
 * the documents that pointed at them. The bundle also carries NO source tree, so every `../inspecto/…` or
 * `../compliance/…` citation dies at the bundle boundary too. Nothing could see any of this: the repo guard
 * resolves against the repo, and the packaging assertions only check WHICH FILES are present.
 *
 * This is the same class as the failures this project keeps paying for — a staged jar that was present but
 * unloadable, a bundle whose every jar was there and which still would not boot: the shipped artifact
 * differs from the repo, and only a check that models the ARTIFACT can see it.
 *
 * TWO MODES, because the honest check and the runnable check are not the same thing.
 *   (default)        SIMULATED. Builds the bundle's file set from `git ls-files` + the exclusion lists
 *                    parsed out of `package.ps1` itself, so it needs no `pwsh` and no build. What it
 *                    cannot see: anything packaging generates rather than copies.
 *   --bundle <dir>   REAL. Walks an actual staged bundle (or unzipped `inspecto-deploy`) and resolves
 *                    every link against the files that are genuinely in it. This is the verdict; the
 *                    simulation is the early warning.
 *
 * ⛔ The exclusion lists are PARSED FROM `package.ps1`, never restated here. A guard that keeps its own
 * copy of the thing it checks drifts away from it, silently, and then reports on a bundle nobody ships.
 * If the parse fails this exits 2 (CANNOT RUN), never 0.
 *
 * WHAT IT CHECKS
 *   1. Every inline markdown link `[text](target)` in a markdown file that SHIPS, whose target is
 *      relative, resolves to a file or directory that also ships. `#anchor` suffixes are stripped.
 *   2. Emptiness floors. A run that parsed almost nothing FAILS instead of passing green — this repo's
 *      recurring failure mode is a probe that could not return a hit reporting "absent" and exiting 0.
 *
 * WHAT IT DELIBERATELY DOES NOT CHECK
 *   - External URLs, anchors inside a target, reference-style links, raw `<a href>` — same scope as
 *     `check-doc-links.mjs`, for the same reasons.
 *   - Anything inside a fenced block: a link in a code fence is QUOTED TEXT, not a live link.
 *   - Whether the target is USEFUL. A link that resolves to a stub still counts as resolved.
 *   - In simulated mode, `ui/` (a built artifact, no markdown targets) and the generated launchers are not
 *     modelled, so a doc linking at one of those reads as dangling here and must be confirmed with
 *     `--bundle`. This exemption is PRINTED on every run: a guard's scope is a silent exemption.
 *
 * ⚠ NOT WIRED INTO CI OR pre-push, deliberately, as of 2026-09-16: it is RED on master today (323
 * dangling links, 224 of them caused by the withholding) and the remedy is an owed operator call —
 * BUNDLE-DANGLING-LINKS-1 offers rewrite at package time / marked stubs / accept-and-say-so. Wiring it
 * is part of whichever option wins; turning the build red on an undecided question would just teach the
 * next shift to ignore it.
 *
 * Pure Node, no dependencies.
 */

import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, sep, posix } from 'node:path';
import { trackedPaths } from './tracked-paths.mjs';

const ROOT = process.cwd();
const PACKAGE_PS1 = 'inspecto/package.ps1';

/** Floors — see the header. Today: 207 shipped markdown files, ~1,670 relative links. */
const MIN_FILES = 100;
const MIN_LINKS = 400;

const LINK = /\[[^\]]*\]\(\s*([^)\s]+?)(?:\s+"[^"]*")?\s*\)/g;
const EXTERNAL = /^(https?:|mailto:|tel:|ftp:|data:|#)/i;

const slash = (p) => p.split(sep).join('/');

function fail(message) {
    console.error(`✗ Bundle-doc-link guard: ${message}`);
    process.exit(1);
}

/** ⛔ CANNOT-RUN is exit 2, distinct from a violation — see `check-doc-links.mjs` for why that matters. */
function cannotRun(message) {
    console.error(`✗ Bundle-doc-link guard could not run: ${message}`);
    process.exit(2);
}

/**
 * The step-7 exclusion lists, read out of `package.ps1`.
 *
 * ⚠ PowerShell comments are stripped BEFORE the quoted entries are read. The comments carry apostrophes
 * ("the product's name"), which a naive quote scan pairs with the next entry's opening quote and returns
 * as a phantom tree — the first draft of this parser did exactly that and silently dropped `superpower`
 * from the exclusion set, which made the bundle look 30 files larger and 13 dangling links cleaner than
 * it is. Note `/#.*$/` does NOT work on this file: it is CRLF, and JS treats `\r` as a line terminator,
 * so `$` never matches before it and nothing is stripped at all.
 */
function step7Exclusions() {
    if (!existsSync(PACKAGE_PS1)) cannotRun(`${PACKAGE_PS1} not found — run this from the repo root.`);
    const ps = readFileSync(PACKAGE_PS1, 'utf8');
    const listOf = (name) => {
        const m = ps.match(new RegExp('\\$' + name + '\\s*=\\s*@\\(([\\s\\S]*?)\\n\\)'));
        if (!m) cannotRun(`could not find \`$${name}\` in ${PACKAGE_PS1}. Step 7 was restructured; fix this parser, do not guess the list.`);
        const body = m[1]
            .split('\n')
            .map((l) => l.replace(/#.*/, ''))
            .join('\n');
        const entries = [...body.matchAll(/'([^']+)'/g)].map((x) => x[1]);
        if (!entries.length) cannotRun(`\`$${name}\` in ${PACKAGE_PS1} parsed as EMPTY. An empty exclusion list would make every link look fine.`);
        return entries;
    };
    return { trees: listOf('docsExcludedTrees'), files: listOf('docsExcludedFiles') };
}

/**
 * Where a repo path lands in the bundle, or null if it does not ship.
 * Mirrors `package.ps1` steps 4, 4b and 7 — the only staging steps that copy files a doc can link to.
 */
function toBundlePath(rel, excl) {
    const parts = rel.split('/');
    if (parts[0] === 'docs') {
        const top = parts[1];
        if (excl.trees.includes(top) || excl.files.includes(top)) return null;
        return rel;
    }
    // step 4b: inspecto/examples/** → examples/**
    if (rel.startsWith('inspecto/examples/')) return rel.slice('inspecto/'.length);
    // step 7: inspecto/README.md → README.md (its `../docs/` links are rewritten to `docs/` there)
    if (rel === 'inspecto/README.md') return 'README.md';
    // step 4: spaces/**, minus the runtime trees packaging skips
    if (parts[0] === 'spaces') {
        if (['uat', '_shared'].includes(parts[1])) return null;
        if (['audit', 'duckdb', 'flows', 'views'].includes(parts[2])) return null;
        if (parts[2] === 'data' && parts[3] !== 'samples') return null;
        return rel;
    }
    return null;
}

/** Simulated bundle: bundle path → absolute source file to read. */
function simulatedBundle() {
    let tracked;
    try {
        tracked = trackedPaths();
    } catch (e) {
        cannotRun(e.message);
    }
    const excl = step7Exclusions();
    const map = new Map();
    const repo = new Set(tracked);
    for (const rel of tracked) {
        let d = posix.dirname(rel);
        while (d && d !== '.') {
            repo.add(d);
            d = posix.dirname(d);
        }
        const b = toBundlePath(rel, excl);
        if (b) map.set(b, join(ROOT, rel));
    }
    return { map, excl, repo, label: `simulated from git ls-files + ${PACKAGE_PS1} step 7` };
}

/** Real bundle: bundle path → absolute file on disk. */
function realBundle(dir) {
    if (!existsSync(dir) || !statSync(dir).isDirectory()) cannotRun(`--bundle '${dir}' is not a directory.`);
    const map = new Map();
    const walk = (abs, prefix) => {
        for (const e of readdirSync(abs, { withFileTypes: true })) {
            const child = join(abs, e.name);
            const rel = prefix ? `${prefix}/${e.name}` : e.name;
            if (e.isDirectory()) walk(child, rel);
            else map.set(rel, child);
        }
    };
    walk(dir, '');
    return { map, excl: null, label: `real staged bundle at ${slash(dir)}` };
}

const bundleArg = process.argv.indexOf('--bundle');
const bundle = bundleArg >= 0 ? realBundle(process.argv[bundleArg + 1] ?? '') : simulatedBundle();

const dirs = new Set();
for (const p of bundle.map.keys()) {
    let d = posix.dirname(p);
    while (d && d !== '.') {
        dirs.add(d);
        d = posix.dirname(d);
    }
}
const present = (p) => bundle.map.has(p) || dirs.has(p);

const markdown = [...bundle.map.keys()].filter((p) => p.endsWith('.md')).sort();
if (markdown.length < MIN_FILES) {
    fail(
        `only ${markdown.length} shipped markdown file(s) in the ${bundle.label}, below the floor of ` +
            `${MIN_FILES}. Either the bundle model is wrong or this ran over nothing — a link check over ` +
            `nothing is the bug this floor exists for.`,
    );
}

let checked = 0;
const dangling = [];

/**
 * Step 7 does not only MOVE `inspecto/README.md` to the bundle root, it REWRITES its `../docs/` links to
 * `docs/` on the way. The simulation has to apply the same rewrite or it reports 35 phantom breaks in the
 * one file a customer opens first. (`--bundle` mode reads the already-rewritten file and skips this.)
 */
function bundleContent(file) {
    const raw = readFileSync(bundle.map.get(file), 'utf8');
    if (bundle.excl && file === 'README.md') return raw.replace(/\.\.\/docs\//g, 'docs/');
    return raw;
}

for (const file of markdown) {
    const lines = bundleContent(file).split('\n');
    let inFence = false;
    lines.forEach((line, i) => {
        const trimmed = line.trimStart();
        if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
            inFence = !inFence;
            return;
        }
        if (inFence) return;
        let m;
        LINK.lastIndex = 0;
        while ((m = LINK.exec(line))) {
            const raw = m[1];
            if (EXTERNAL.test(raw)) continue;
            const target = raw.split('#')[0];
            if (!target) continue;
            checked++;
            let decoded;
            try {
                decoded = decodeURIComponent(target);
            } catch {
                decoded = target;
            }
            // Resolve inside the bundle's own namespace: posix.join on the link's directory, which is
            // what a reader clicking in the unzipped tree does. A `..` that climbs out of the bundle
            // root can never resolve, and posix.join leaves it as a leading `..` — still absent.
            const resolved = posix.normalize(posix.join(posix.dirname(file), decoded)).replace(/\/$/, '');
            if (present(resolved)) continue;
            dangling.push({ file, line: i + 1, target: raw, resolved, why: classify(resolved, bundle) });
        }
    });
}

/**
 * Why a target is absent from the bundle — only knowable in simulated mode, where the repo is at hand.
 *
 * ⚠ The last class is NOT this row's problem and must not be folded into it: those links are broken in
 * the repository as well, and are invisible to `check-doc-links.mjs` only because its ROOTS do not include
 * `inspecto/` — which is where the file that BECOMES the bundle's root README lives.
 */
function classify(resolved, bundle) {
    const excl = bundle.excl;
    if (!excl) return 'absent from the bundle';
    const parts = resolved.split('/');
    if (parts[0] === 'docs' && excl.trees.includes(parts[1])) return `withheld tier: docs/${parts[1]}/`;
    if (parts[0] === 'docs' && excl.files.includes(parts[1])) return `withheld audience: docs/${parts[1]}`;
    if (resolved.startsWith('..')) return 'climbs above the bundle root';
    if (bundle.repo.has(resolved)) return 'exists in the repo but is not staged into the bundle';
    return 'BROKEN IN THE REPO TOO — pre-existing link rot, not a packaging effect';
}

if (checked < MIN_LINKS) {
    fail(
        `only ${checked} relative link(s) parsed out of ${markdown.length} shipped file(s), below the ` +
            `floor of ${MIN_LINKS}. The link pattern has almost certainly stopped matching — fix the ` +
            `parser rather than the floor.`,
    );
}

const scopeNote =
    `scope: ${bundle.label}; ${bundle.map.size} file(s) staged, ${markdown.length} of them markdown; ` +
    (bundle.excl
        ? `withheld by step 7 — trees: ${bundle.excl.trees.join(', ')}; files: ${bundle.excl.files.join(', ')}; ` +
          `⚠ ui/ and the generated launchers are NOT modelled here — confirm with --bundle`
        : `every target resolved against the real staged tree`);

if (dangling.length) {
    const byWhy = new Map();
    for (const d of dangling) byWhy.set(d.why, (byWhy.get(d.why) ?? 0) + 1);
    const byFile = new Map();
    for (const d of dangling) byFile.set(d.file, (byFile.get(d.file) ?? 0) + 1);

    console.error(`✗ Bundle-doc-link guard: ${dangling.length} link(s) in shipped docs point at something the customer does not have\n`);
    for (const d of dangling) console.error(`  ${d.file}:${d.line}  ->  ${d.target}   [${d.why}]`);
    console.error(`\n  by cause:`);
    for (const [why, n] of [...byWhy].sort((a, b) => b[1] - a[1])) console.error(`    ${String(n).padStart(4)}  ${why}`);
    console.error(`\n  worst files:`);
    for (const [f, n] of [...byFile].sort((a, b) => b[1] - a[1]).slice(0, 10)) console.error(`    ${String(n).padStart(4)}  ${f}`);
    console.error(`\n  ${scopeNote}`);
    console.error(
        `\n  ⚠ These links are FINE in the repo — \`tools/check-doc-links.mjs\` is green. They break only in` +
            `\n  the bundle, because the target does not ship. See BUNDLE-DANGLING-LINKS-1: the remedy is an` +
            `\n  owed product call (rewrite at package time · marked stubs · accept and say so in the README),` +
            `\n  not a change to this guard.`,
    );
    process.exit(1);
}

console.log(`✓ Bundle-doc-link guard: ${checked} relative link(s) in ${markdown.length} shipped doc(s) all resolve inside the bundle — ${scopeNote}.`);
