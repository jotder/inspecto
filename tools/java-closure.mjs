#!/usr/bin/env node
// java-closure — the transitive dependency closure of some Java classes over src/main/java, by module.
//
//   node tools/java-closure.mjs --seeds ApiContext,Subject [--stop CollectorService,SpaceManager] [--root .] [--json]
//
// ⚠ A CLOSURE IS A PREDICTION, NOT A PROOF. It tells you what a cut will probably drag along; the proof that a
// cut is real is a CLEAN compile of the whole reactor: `node tools/compile-clean.mjs`. (D-1, 2026-10-01: an earlier
// script like this one said 20 classes where the truth was 46, and missed four references the compiler found.)
//
// It exists because the two blind spots that bit that script are the classic ones, and both are covered by
// tools/java-closure.test.mjs:
//   1. COMMENT STRIPPING by regex. A `/*` inside a STRING LITERAL ("**/*.java") opens a fake block comment that
//      swallows real code up to the next `*/`. This tool tokenises: string, char and text-block literals are consumed
//      whole, so only genuine comments are dropped.
//   2. FULLY-QUALIFIED INLINE REFERENCES. `com.gamma.spi.OptionalSpi.first(spi)` needs no import, so an import-
//      and-same-package scan never sees it. This tool resolves them too.
// Still invisible to it (so still the compiler's job): reflection / Class.forName strings, ServiceLoader providers,
// annotation-processor output, and a class named only inside a string.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const SKIP_DIRS = new Set(['.git', '.claude', 'node_modules', 'target', 'dist', 'worktrees', '.codegraph']);

/** Drop comments, keep literals out of the way. Returns the source with comments blanked and string BODIES blanked. */
export function strip(src) {
    let out = '';
    let i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i];
        const d = src[i + 1];
        if (c === '/' && d === '/') {                       // line comment
            while (i < n && src[i] !== '\n') i++;
        } else if (c === '/' && d === '*') {                // block comment
            const end = src.indexOf('*/', i + 2);
            i = end < 0 ? n : end + 2;
            out += ' ';
        } else if (c === '"' && src.startsWith('"""', i)) { // text block
            const end = src.indexOf('"""', i + 3);
            i = end < 0 ? n : end + 3;
            out += '""';
        } else if (c === '"') {                             // string literal
            i++;
            while (i < n && src[i] !== '"' && src[i] !== '\n') i += src[i] === '\\' ? 2 : 1;
            i++;
            out += '""';
        } else if (c === "'") {                             // char literal
            i++;
            while (i < n && src[i] !== "'" && src[i] !== '\n') i += src[i] === '\\' ? 2 : 1;
            i++;
            out += "''";
        } else {
            out += c;
            i++;
        }
    }
    return out;
}

function* javaFiles(dir) {
    for (const name of readdirSync(dir)) {
        if (SKIP_DIRS.has(name)) continue;
        const p = join(dir, name);
        const st = statSync(p);
        if (st.isDirectory()) yield* javaFiles(p);
        else if (name.endsWith('.java') && p.includes(`${sep}src${sep}main${sep}java${sep}`)) yield p;
    }
}

/** Index every main-source class: fqcn -> { module, pkg, simple, body }. */
export function indexClasses(root) {
    const cls = new Map();
    const bySimple = new Map();
    const byPkg = new Map();
    for (const f of javaFiles(root)) {
        const raw = readFileSync(f, 'utf8');
        const m = /^\s*package\s+([\w.]+)\s*;/m.exec(raw);
        if (!m) continue;
        const simple = f.slice(f.lastIndexOf(sep) + 1, -5);
        const fq = `${m[1]}.${simple}`;
        const module = relative(root, f).split(sep)[0];
        cls.set(fq, { module, pkg: m[1], simple, body: strip(raw) });
        if (!bySimple.has(simple)) bySimple.set(simple, []);
        bySimple.get(simple).push(fq);
        if (!byPkg.has(m[1])) byPkg.set(m[1], []);
        byPkg.get(m[1]).push(simple);
    }
    return { cls, bySimple, byPkg };
}

/** Direct dependencies of one class: imports, wildcard-imported same-name tokens, same-package tokens, inline FQNs. */
export function directDeps(index, fq) {
    const { cls, byPkg } = index;
    const { pkg, body } = cls.get(fq);
    const out = new Set();
    const tokens = new Set(body.match(/\b[A-Z]\w*\b/g) ?? []);
    for (const m of body.matchAll(/^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;/gm)) {
        let t = m[2];
        if (m[3]) {                                         // import pkg.*;  → classes of pkg that the body names
            for (const s of byPkg.get(t) ?? []) if (tokens.has(s) && `${t}.${s}` !== fq) out.add(`${t}.${s}`);
            continue;
        }
        if (m[1]) t = t.slice(0, t.lastIndexOf('.'));       // import static pkg.C.member → pkg.C
        while (t && !cls.has(t) && t.includes('.')) t = t.slice(0, t.lastIndexOf('.'));   // nested type → its outer class
        if (cls.has(t) && t !== fq) out.add(t);
    }
    for (const t of tokens) {
        const c = `${pkg}.${t}`;
        if (cls.has(c) && c !== fq) out.add(c);
    }
    for (const m of body.matchAll(/\b((?:[a-z][a-z0-9_]*\.)+[A-Z]\w*)/g)) {   // inline fully-qualified reference
        let t = m[1];
        while (t && !cls.has(t) && t.includes('.')) t = t.slice(0, t.lastIndexOf('.'));
        if (cls.has(t) && t !== fq) out.add(t);
    }
    return out;
}

/** Closure of `seeds` (fqcns). `stop(fq)` true ⇒ the class is counted as a dependency but not expanded. */
export function closure(index, seeds, stop = () => false) {
    const seen = new Set();
    const edgesToStop = new Set();
    const queue = [...seeds];
    while (queue.length) {
        const c = queue.pop();
        if (seen.has(c)) continue;
        seen.add(c);
        for (const d of directDeps(index, c)) {
            if (stop(d)) { edgesToStop.add(d); continue; }
            if (!seen.has(d)) queue.push(d);
        }
    }
    return { classes: seen, stopped: edgesToStop };
}

export function resolveSimple(index, names, label) {
    const out = [];
    for (const n of names) {
        const hits = index.bySimple.get(n) ?? [];
        if (hits.length === 0) throw new Error(`${label}: no class named '${n}' under src/main/java`);
        out.push(...hits);          // every same-named class: an ambiguous name is reported, never silently picked
    }
    return out;
}

function main() {
    const args = process.argv.slice(2);
    const flag = (k) => { const i = args.indexOf(k); return i >= 0 ? args[i + 1] : undefined; };
    const root = flag('--root') ?? '.';
    const seedNames = (flag('--seeds') ?? '').split(',').filter(Boolean);
    const stopNames = (flag('--stop') ?? '').split(',').filter(Boolean);
    if (!seedNames.length) {
        console.error('usage: node tools/java-closure.mjs --seeds A,B [--stop X,Y] [--root .] [--json]');
        process.exit(2);
    }
    const index = indexClasses(root);
    const seeds = resolveSimple(index, seedNames, '--seeds');
    const stopSet = new Set(resolveSimple(index, stopNames, '--stop'));
    const { classes, stopped } = closure(index, seeds, (d) => stopSet.has(d));
    const byModule = {};
    for (const c of classes) (byModule[index.cls.get(c).module] ??= []).push(index.cls.get(c).simple);
    for (const k of Object.keys(byModule)) byModule[k].sort();
    if (args.includes('--json')) {
        console.log(JSON.stringify({ size: classes.size, byModule, stopped: [...stopped].sort() }, null, 2));
        return;
    }
    console.log(`closure of [${seedNames.join(', ')}]${stopNames.length ? ` stopping at [${stopNames.join(', ')}]` : ''}: ${classes.size} classes`);
    for (const m of Object.keys(byModule).sort()) console.log(`  ${m} (${byModule[m].length}): ${byModule[m].join(' ')}`);
    if (stopped.size) console.log(`  reached but not expanded: ${[...stopped].map((s) => s.split('.').pop()).sort().join(' ')}`);
    console.log('\n⚠ A closure is a PREDICTION. Prove a cut with a CLEAN compile: node tools/compile-clean.mjs');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
