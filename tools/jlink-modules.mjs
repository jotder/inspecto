#!/usr/bin/env node
// The jlinked bundle runtime's module set, DERIVED instead of hand-kept (MODULE-REORG-P3-THIN-JARS, P3c).
//
//   set = jdeps(every staged first-party jar + every sidecar)  UNION  tools/jlink-runtime-extra.txt
//
// jdeps runs once over the whole staged classpath (a shaded sidecar is one jar, so it covers its
// bundled dependencies). It cannot see Class.forName strings, run-time ServiceLoader providers, JDBC
// DriverManager lookups or native loaders; those go in tools/jlink-runtime-extra.txt, each with a WHY.
// ⚠ `--ignore-missing-deps` hides imports from jars not on the scan path, so a MISSING staged jar
// would silently shrink the set: the tool FAILS when a jar the edition should stage is absent.
// The result is held against tools/jlink-modules.lock (per edition) — `--write-lock` refreshes it.
//
// Modes
//   --edition <e> --staged-dir <dir> [--runtime-modules a,b] [--emit]   compute, print, compare to the lock
//   --edition <e> --staged-dir <dir> --write-lock                       rewrite that edition's lock section
//   --check                                                             no jars needed: lock well-formed,
//                                                                       extra.txt names are real JDK modules
// CI (NOT wired in this step): add to the guards job in .github/workflows/ci.yml
//   `- run: node tools/jlink-modules.mjs --check`
import { readFileSync, writeFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { bundleModules, coreModules, EDITIONS } from './bundle-modules.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
export const LOCK_FILE = join(HERE, 'jlink-modules.lock');
export const EXTRA_FILE = join(HERE, 'jlink-runtime-extra.txt');
const MODULE_NAME = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/;

/** `--print-module-deps` prints one comma-separated line (warnings go to stderr, but be tolerant). */
export function parseJdepsOutput(text) {
    const line = String(text).split(/\r?\n/).map((l) => l.trim()).filter((l) => MODULE_NAME.test(l.split(',')[0] ?? '')).pop() ?? '';
    return line.split(',').map((s) => s.trim()).filter(Boolean).sort();
}

/** `module   # why` lines. Returns [{module, why}]; a line without a WHY is reported by validateExtra. */
export function parseExtra(text) {
    return String(text).split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith('#')).map((l) => {
        const [mod, ...rest] = l.split('#');
        return { module: mod.trim(), why: rest.join('#').trim() };
    });
}

/** Problems with extra.txt entries; `jdkModules` is the `java --list-modules` set (or null to skip that check). */
export function validateExtra(entries, jdkModules) {
    const problems = [];
    const seen = new Set();
    for (const { module, why } of entries) {
        if (!MODULE_NAME.test(module)) problems.push(`'${module}' is not a module name`);
        else if (jdkModules && !jdkModules.has(module)) problems.push(`'${module}' is not a JDK module (java --list-modules)`);
        if (!why) problems.push(`'${module}' has no WHY comment`);
        if (seen.has(module)) problems.push(`'${module}' is listed twice`);
        seen.add(module);
    }
    return problems;
}

/** `java --list-modules` lines look like `java.base@27`. */
export function parseListModules(text) {
    return new Set(String(text).split(/\r?\n/).map((l) => l.trim().split('@')[0]).filter((m) => MODULE_NAME.test(m)));
}

export const union = (...sets) => [...new Set(sets.flat())].sort();
export const diff = (a, b) => a.filter((x) => !b.includes(x));

/** Lock: `[Edition]` section headers followed by one module per line; `#` comments. Returns {Edition: [modules]}. */
export function parseLock(text) {
    const lock = {};
    let cur = null;
    for (const raw of String(text).split(/\r?\n/)) {
        const l = raw.trim();
        if (!l || l.startsWith('#')) continue;
        const h = /^\[(\w+)\]$/.exec(l);
        if (h) { cur = h[1]; lock[cur] = []; continue; }
        if (!cur) throw new Error(`lock line before any [Edition] header: '${l}'`);
        lock[cur].push(l);
    }
    return lock;
}

export function renderLock(lock) {
    const head = '# jlink runtime module set per edition = jdeps(staged jars) UNION tools/jlink-runtime-extra.txt.\n'
        + '# Refresh: node tools/jlink-modules.mjs --edition <e> --staged-dir <bundle dir> --write-lock\n';
    return head + EDITIONS.filter((e) => lock[e]).map((e) => `\n[${e}]\n${lock[e].join('\n')}\n`).join('');
}

export function validateLock(lock) {
    const problems = [];
    for (const [ed, mods] of Object.entries(lock)) {
        if (!EDITIONS.includes(ed)) problems.push(`unknown edition section [${ed}]`);
        if (!mods.includes('java.base')) problems.push(`[${ed}] lacks java.base`);
        for (const m of mods) if (!MODULE_NAME.test(m)) problems.push(`[${ed}] '${m}' is not a module name`);
        if (new Set(mods).size !== mods.length) problems.push(`[${ed}] has duplicates`);
        if (mods.join() !== [...mods].sort().join()) problems.push(`[${ed}] is not sorted`);
    }
    return problems;
}

/** The jar file names an edition's bundle must stage (first-party: the edition's modules + the core thin jars, P3d stage 2); PG sidecar is scanned when present. */
export function expectedJars(edition) {
    return [...bundleModules(edition), ...coreModules()].map((m) => m.bundleFile);
}

/** Throws when a jar the edition should stage is absent: --ignore-missing-deps would hide it. */
export function requireJars(edition, present) {
    const missing = expectedJars(edition).filter((j) => !present.includes(j));
    if (missing.length) throw new Error(`staged dir lacks ${missing.length} expected jar(s) for ${edition}: ${missing.join(', ')} — jdeps would under-report`);
}

/** Message for a computed set that differs from the lock. */
export function lockMismatch(edition, computed, locked) {
    if (!locked) return `no [${edition}] section in tools/jlink-modules.lock — run with --write-lock`;
    const add = diff(computed, locked), rem = diff(locked, computed);
    if (!add.length && !rem.length) return null;
    return `[${edition}] computed set != lock: +${add.join(',') || '-'} / -${rem.join(',') || '-'} — if intended, refresh with --write-lock`;
}

function javaTool(name) {
    const exe = process.platform === 'win32' ? `${name}.exe` : name;
    const jh = process.env.JAVA_HOME;
    return jh && existsSync(join(jh, 'bin', exe)) ? join(jh, 'bin', exe) : name;
}

export function runJdeps(jars, tool = javaTool('jdeps')) {
    const cp = jars.join(delimiter);
    const r = spawnSync(tool, ['--multi-release', '27', '--ignore-missing-deps', '--print-module-deps', '-cp', cp, ...jars], { encoding: 'utf8', maxBuffer: 1 << 26 });
    if (r.status !== 0) throw new Error(`jdeps failed (${r.status}): ${r.stderr || r.error}`);
    return parseJdepsOutput(r.stdout);
}

export function computeSet({ edition, stagedDir, jdeps = runJdeps, extraText }) {
    const present = readdirSync(stagedDir).filter((f) => f.endsWith('.jar'));
    requireJars(edition, present);
    const jars = present.sort().map((f) => join(stagedDir, f));
    const fromJdeps = jdeps(jars);
    const extra = parseExtra(extraText).map((e) => e.module);
    return { jdeps: fromJdeps, extra, all: union(fromJdeps, extra), jars: present };
}

function arg(argv, name) { const i = argv.indexOf(name); return i >= 0 ? argv[i + 1] : undefined; }

export function main(argv) {
    const lock = existsSync(LOCK_FILE) ? parseLock(readFileSync(LOCK_FILE, 'utf8')) : {};
    const extraText = readFileSync(EXTRA_FILE, 'utf8');
    if (argv.includes('--check')) {
        const list = spawnSync(javaTool('java'), ['--list-modules'], { encoding: 'utf8' });
        const jdk = list.status === 0 ? parseListModules(list.stdout) : null;
        if (!jdk) console.error('WARN: java --list-modules unavailable; module names not verified against the JDK');
        const problems = [...validateLock(lock), ...validateExtra(parseExtra(extraText), jdk)];
        problems.forEach((p) => console.error(`jlink-modules --check: ${p}`));
        console.log(problems.length ? `FAIL (${problems.length})` : `OK (lock sections: ${Object.keys(lock).join(', ') || 'none'})`);
        return problems.length ? 1 : 0;
    }
    const edition = arg(argv, '--edition') === 'Standard' ? 'Professional' : arg(argv, '--edition');
    const stagedDir = arg(argv, '--staged-dir');
    if (!EDITIONS.includes(edition) || !stagedDir) { console.error('usage: --edition <Personal|Professional|Enterprise|Preview> --staged-dir <dir> [--write-lock|--runtime-modules a,b|--emit]  |  --check'); return 2; }
    const r = computeSet({ edition, stagedDir, extraText });
    console.log(`jdeps over ${r.jars.length} jars: ${r.jdeps.join(',')}`);
    console.log(`extra (hand, with WHY): ${r.extra.join(',')}`);
    console.log(`computed set (${r.all.length}): ${r.all.join(',')}`);
    let code = 0;
    const hand = arg(argv, '--runtime-modules');
    if (hand) {
        const h = hand.split(',').filter(Boolean);
        const needed = diff(r.all, h), unexplained = diff(h, r.all);
        console.log(`vs hand list: needed-but-missing [${needed.join(',') || '-'}]  hand-only [${unexplained.join(',') || '-'}]`);
        if (needed.length) code = 1;
    }
    if (argv.includes('--write-lock')) {
        lock[edition] = r.all;
        writeFileSync(LOCK_FILE, renderLock(lock));
        console.log(`wrote [${edition}] to ${LOCK_FILE}`);
    } else {
        const m = lockMismatch(edition, r.all, lock[edition]);
        if (m) { console.error(m); code = 1; }
    }
    if (argv.includes('--emit')) console.log(`JLINK_MODULES=${r.all.join(',')}`);
    return code;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) process.exit(main(process.argv.slice(2)));
