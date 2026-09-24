#!/usr/bin/env node
/**
 * Seed every space's inboxes, reference files and engine-expected directories from the pristine,
 * TRACKED samples — derived from the Pipeline configs themselves, never from a hand-kept list.
 *
 * WHY THIS EXISTS. `.gitignore:70` ignores everything under `/spaces/<space>/data/` while force-tracking
 * `data/samples/**`, so a fresh clone has the samples and NOTHING else: no `data/inbox/<pipeline>/`,
 * no `data/ref/`, none of the eight per-Pipeline working dirs. Two shipped reference-join examples
 * (`join_step`, `orders_enriched_rollup`) name `spaces/<space>/data/ref/region_dim.csv`, so on a
 * fresh checkout their test run AND their dry-run fail 422 with a leaked DuckDB internal
 * ("No files found that match the pattern"). Row: `REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`; item
 * `WB-16` of `docs/superpower/workbench-trust-plan.md`.
 *
 * 🔴 THE ROW'S STATED CAUSE WAS WRONG, and this script exists because of what is actually broken.
 * The row says the reference file "ships at `data/samples/ref/` and **nothing copies it**". Something
 * does: `spaces/<space>/data/samples/seed-inbox.sh` (and its `.ps1` twin) copy `ref/*` into
 * `../ref/` in all three spaces that have one. The real defect is that seeding is a manual step no
 * launch path, no skill and no README runs — and that those six scripts carry a HAND-WRITTEN list of
 * pipeline names, so a Pipeline added to a space is silently unseeded until someone edits two files.
 * This script derives the list instead, which is the half a hand-kept script cannot get right.
 *
 * ── WHAT IT DOES ──────────────────────────────────────────────────────────────────────────────────
 *
 *   1. Finds every `<space>/config` file whose name ends `_pipeline.toon`, at any depth.
 *   2. Reads each one's `dirs:` block. `dirs.poll` is the inbox; every OTHER `dirs.*` leaf is a
 *      working directory the engine expects to exist — `PipelineConfig.prepare()` creates only the
 *      status dir, so the rest must be on disk before a run.
 *   3. Copies `data/samples/<pipeline>/` (recursively — `collect_step` demonstrates recursion) into
 *      that Pipeline's `dirs.poll`.
 *   4. Copies `data/samples/ref/*` into `data/ref/` for every space that ships one.
 *
 * ⚠ IDEMPOTENT, and deliberately NOT a sync: it creates and overwrites, it never deletes. Re-running
 * after the engine has consumed an inbox re-seeds it, which is the point. It will not remove a file
 * you put in an inbox by hand.
 *
 * ⚠ It seeds by the sample directory's NAME matching the Pipeline's name. A Pipeline with no
 * same-named sample directory is reported as unseeded rather than skipped in silence — that report
 * is how `lookup_step` (ships no sample at all, `DEMO-CORPUS-FORMAT-COVERAGE-1`) stays visible.
 *
 * Usage:  node tools/seed-samples.mjs [<space> | --all] [--dry-run]
 *         --all is the default. --dry-run prints the plan and writes nothing.
 */

import { readFileSync, readdirSync, mkdirSync, copyFileSync, statSync, existsSync } from 'node:fs';
import { join, relative, dirname, basename, resolve, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const SPACES = join(REPO, 'spaces');

const argv = process.argv.slice(2);
const dryRun = argv.includes('--dry-run');
const target = argv.find((a) => !a.startsWith('--')) ?? '--all';

/** Every file under `dir`, recursively, as paths relative to `dir`. */
function walk(dir, prefix = '') {
    const out = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
        if (entry.isDirectory()) out.push(...walk(join(dir, entry.name), rel));
        else out.push(rel);
    }
    return out;
}

/**
 * The `dirs:` block of a pipeline toon, as {leaf: path as authored} — resolve it with {@link dataPath}.
 *
 * ⚠ Deliberately a narrow line reader, not the TOON parser: this tool runs on a fresh clone before
 * anything is built, so it may not depend on the engine. The block is flat, two-space indented and
 * `key: value` — the shape every shipped Pipeline uses. A `dirs:` leaf that is quoted or nested
 * would be missed, which is why the caller reports what it seeded rather than claiming completeness.
 */
function dirsOf(toonPath) {
    const dirs = {};
    let inBlock = false;
    for (const raw of readFileSync(toonPath, 'utf8').split(/\r?\n/)) {
        if (/^dirs:\s*$/.test(raw)) { inBlock = true; continue; }
        if (!inBlock) continue;
        if (/^\S/.test(raw)) break;                       // dedent ends the block
        const m = raw.match(/^\s+([a-z_]+):\s*(\S.*?)\s*$/);
        if (m) dirs[m[1]] = m[2].replace(/^["']|["']$/g, '');
    }
    return dirs;
}

/**
 * The Space directory a config belongs to: the parent of the nearest ancestor directory named `config`.
 * Mirrors `PathJail.spaceDirOf`.
 */
function spaceDirOf(cfgPath) {
    for (let d = dirname(resolve(cfgPath)); dirname(d) !== d; d = dirname(d)) {
        if (basename(d) === 'config') return dirname(d);
    }
    return null;
}

/**
 * Where the engine reads a `dirs.*` value: mirrors `PathJail.resolveDataPath`. A relative data path resolves
 * under the config's Space directory (never the working directory); an absolute path or a URI is kept as
 * written. A relative value that repeats the Space's own path (`spaces/demo/data/x` under `spaces/demo/`) is
 * refused by the engine, so it is returned as `null` here and reported rather than seeded somewhere wrong.
 *
 * 🔴 This used to be `join(REPO, value)`. On a fresh checkout that put every inbox under `<repo>/data/inbox/`,
 * which the engine then REFUSED for every Pipeline (the old-spelling-exists ambiguity in `resolveAgainst`) —
 * the shared tree only worked because stale inboxes already sat under `spaces/<id>/data/`.
 */
function dataPath(cfgPath, value) {
    if (isAbsolute(value) || /^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return value;
    const spaceDir = spaceDirOf(cfgPath);
    if (!spaceDir) return resolve(value);           // no Space: the engine keeps the working-directory reading
    const authored = value.split(/[\\/]+/).filter(Boolean);
    const space = spaceDir.split(sep).filter(Boolean);
    for (let k = Math.min(space.length, authored.length); k >= 2; k--) {
        if (space.slice(space.length - k).join('/') === authored.slice(0, k).join('/')) return null;
    }
    return join(spaceDir, ...authored);
}

function pipelineConfigs(spaceDir) {
    const configRoot = join(spaceDir, 'config');
    if (!existsSync(configRoot)) return [];
    return walk(configRoot)
        .filter((p) => p.endsWith('_pipeline.toon'))
        .map((p) => join(configRoot, p));
}

function copyTree(fromDir, toDir, plan) {
    for (const rel of walk(fromDir)) {
        const dest = join(toDir, rel);
        plan.files.push(relative(REPO, dest));
        if (dryRun) continue;
        mkdirSync(dirname(dest), { recursive: true });
        copyFileSync(join(fromDir, rel), dest);
    }
}

const spaceNames = readdirSync(SPACES, { withFileTypes: true })
    .filter((e) => e.isDirectory() && !e.name.startsWith('_'))
    .map((e) => e.name)
    .filter((n) => target === '--all' || n === target);

if (spaceNames.length === 0) {
    console.error(`✗ No such space: ${target}. Spaces: ${readdirSync(SPACES).join(', ')}`);
    process.exit(2);
}

let seeded = 0;
let unseeded = [];
let refs = 0;
const refused = [];
const plan = { files: [], dirs: [] };

for (const space of spaceNames) {
    const spaceDir = join(SPACES, space);
    const samples = join(spaceDir, 'data', 'samples');
    if (!existsSync(samples)) continue;

    for (const cfg of pipelineConfigs(spaceDir)) {
        const name = basename(cfg).replace(/_pipeline\.toon$/, '');
        const dirs = dirsOf(cfg);
        if (!dirs.poll) continue;

        // Every dirs.* leaf but poll is a working directory the engine expects to exist.
        const resolved = {};
        for (const [leaf, p] of Object.entries(dirs)) {
            const abs = dataPath(cfg, p);
            if (abs === null) {
                refused.push(`${relative(REPO, cfg)}: dirs.${leaf} '${p}' repeats its own Space's path`);
                continue;
            }
            resolved[leaf] = abs;
            plan.dirs.push(relative(REPO, abs));
            if (!dryRun) mkdirSync(abs, { recursive: true });
        }
        if (!resolved.poll) continue;

        const sampleDir = join(samples, name);
        if (existsSync(sampleDir) && statSync(sampleDir).isDirectory()) {
            copyTree(sampleDir, resolved.poll, plan);
            seeded++;
        }

        // ⚠ The question is whether the inbox HAS files, not whether a same-named sample directory
        // exists — infer the first from the second and you get false alarms in both directions. Two
        // Pipelines were reported unseeded and neither was: one is Dataset-fed (no inbox at all, and the
        // test run refuses a file run for it by name, WB-07), the other SHARES the `orders` inbox, so
        // the `orders` samples seed it. A warning that is wrong for a whole class of configs is the
        // defect WB-04 removed from the validator; it does not belong here either.
        const pollDir = resolved.poll;
        const empty = dryRun
            ? !existsSync(sampleDir) && !existsSync(pollDir)
            : !existsSync(pollDir) || walk(pollDir).length === 0;
        // ⛔ BOTH conditions, because each catches what the other misses. Emptiness alone passed here
        // only because a stale parquet happened to sit in the Dataset-fed Pipeline's declared poll dir —
        // on a fresh checkout it would have warned about a Pipeline that correctly has no inbox.
        const datasetFed = /^\s*connector:\s*dataset\s*$/m.test(readFileSync(cfg, 'utf8'));
        if (empty && !datasetFed) unseeded.push(`${space}/${name}`);
    }

    // The references every reference-join example resolves at run time.
    const sampleRef = join(samples, 'ref');
    if (existsSync(sampleRef)) {
        copyTree(sampleRef, join(spaceDir, 'data', 'ref'), plan);
        refs++;
    }
}

const verb = dryRun ? 'would seed' : 'seeded';
console.log(
    `${dryRun ? '•' : '✓'} seed-samples: ${verb} ${seeded} Pipeline inbox(es) and ${refs} reference set(s) ` +
        `across ${spaceNames.length} space(s) — ${plan.files.length} file(s), ${new Set(plan.dirs).size} directory(ies). ` +
        `Derived from each Pipeline's own dirs.poll, never a hand-kept list. Idempotent: it creates and ` +
        `overwrites, never deletes.`,
);
if (unseeded.length) {
    console.log(
        `  ⚠ ${unseeded.length} file-fed Pipeline(s) have an EMPTY inbox after seeding, so they cannot be ` +
            `test-run ` +
            `as shipped: ${unseeded.join(', ')}`,
    );
}
if (refused.length) {
    console.log(
        `  ⚠ ${refused.length} dirs.* value(s) NOT seeded — the engine refuses them too: ${refused.join('; ')}`,
    );
}
if (dryRun) {
    for (const f of plan.files) console.log(`    ${f}`);
    for (const d of new Set(plan.dirs)) console.log(`  d ${d}`);
}
