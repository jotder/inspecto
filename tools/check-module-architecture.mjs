#!/usr/bin/env node
// check-module-architecture — REPORT-ONLY baseline of the module-architecture reorg (docs/superpower/module-architecture-reorg-plan.md §3, §6 P0, §7).
//
//   node tools/check-module-architecture.mjs [--root .] [--json] [--check]
//
// Prints the baseline numbers the plan tracks: split packages, closed central registries, module sizes, and telecom vocabulary
// inside the generic modules. ALWAYS exits 0 unless --check is passed; --check exits 1 when split packages or closed registries
// are non-zero (the P1..P3 targets are 0) — it is NOT wired into CI. --json prints only the one-line JSON summary.
// Files come from `git ls-files`, so untracked/ignored output never counts.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { status as offeringsStatus } from './check-offerings.mjs';
import { moduleDirOf, moduleLabel, reactorModuleDirs } from './reactor-modules.mjs';

/**
 * The module of a repo path, or null for root files. With `dirs` (reactor module dirs from reactor-modules.mjs) it is the
 * outermost reactor module's directory NAME, so it survives `features/inspecto-ops/...`; without, the top-level dir.
 */
export const moduleOf = (path, dirs = new Set()) => moduleLabel(path, dirs);

/** The package declared by Java source text, or null. Comments are ignored. */
export function packageOf(javaText) {
    const body = javaText.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    return /^\s*package\s+([\w.]+)\s*;/m.exec(body)?.[1] ?? null;
}

/** files: [{ module, pkg }] -> packages present in more than one module: [{ pkg, modules: {m: fileCount} }], sorted. */
export function splitPackages(files) {
    const by = new Map();
    for (const f of files) {
        if (!f.pkg) continue;
        const m = by.get(f.pkg) ?? new Map();
        m.set(f.module, (m.get(f.module) ?? 0) + 1);
        by.set(f.pkg, m);
    }
    return [...by].filter(([, m]) => m.size > 1).map(([pkg, m]) => ({ pkg, modules: Object.fromEntries([...m].sort()) })).sort((a, b) => a.pkg.localeCompare(b.pkg));
}

/** Non-blank, non-comment-only line count. */
export function loc(text) {
    return text.split('\n').filter((l) => { const t = l.trim(); return t && !t.startsWith('//') && !t.startsWith('*') && !t.startsWith('/*'); }).length;
}

const VOCAB = {
    msisdn: /\bmsisdn\b/gi, imsi: /\bimsi\b/gi, cdr: /\bcdrs?\b/gi, sim: /\bsims?\b/gi, dealer: /\bdealers?\b/gi,
    subscriber: /\bsubscribers?\b/gi,
};
const EVENT_BUS_LINE = /event.?bus|EventSubscriber|\bFlow\.|subscribe\s*\(|unsubscribe|Subscription/i;

/** Telecom-word hits in one source text -> { word: n }. A line about the event-bus 'subscriber' does not count. */
export function vocabHits(text) {
    const out = {};
    for (const line of text.split('\n')) {
        for (const [w, re] of Object.entries(VOCAB)) {
            if (w === 'subscriber' && EVENT_BUS_LINE.test(line)) continue;
            const n = (line.match(re) ?? []).length;
            if (n) out[w] = (out[w] ?? 0) + n;
        }
    }
    return out;
}

/** Text of the first balanced (...) after `marker`, or '' when absent. */
export function balancedAfter(text, marker) {
    const s = text.indexOf(marker);
    if (s < 0) return '';
    let depth = 0;
    for (let i = text.indexOf('(', s); i < text.length; i++) {
        if (text[i] === '(') depth++;
        else if (text[i] === ')' && --depth === 0) return text.slice(s, i + 1);
    }
    return '';
}

const count = (text, re) => (text.match(re) ?? []).length;

/** The ten closed registries of plan §3.2 with a measured size (null size = the file is gone, i.e. the registry moved). */
export function registries(read, files) {
    const R = [];
    const add = (name, where, size, unit) => R.push({ name, where, size, unit });
    const sizeOf = (path, fn) => { const t = read(path); return t == null ? null : fn(t); };
    const ca = 'inspecto/src/main/java/com/gamma/control/ControlApi.java';
    add('Built-in route list', ca, sizeOf(ca, (t) => count(balancedAfter(t, 'for (RouteModule module : List.of('), /\bnew\s+\w+Routes\s*\(/g)), 'RouteModules');
    add('Absent-module 503 stubs', 'com.gamma.control Absent*Routes', files.filter((f) => /\/Absent\w*Routes\.java$/.test(f)).length, 'classes');
    const br = 'inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java';
    add('Feature flags (features{})', br, sizeOf(br, (t) => count(t, /\bhasRoute\s*\(/g)), 'hasRoute probes');
    const nav = 'inspecto-ui/src/app/core/navigation/navigation.service.ts';
    add('SPA nav gating', nav, sizeOf(nav, (t) => [...t.matchAll(/static readonly \w+_NAV_IDS\s*=\s*new Set\(\[([^\]]*)\]/g)].reduce((n, m) => n + count(m[1], /'[^']+'/g), 0)), 'hard-coded nav ids');
    const cm = 'inspecto-auth-spi/src/main/java/com/gamma/control/CapabilityManifest.java';
    add('RBAC capabilities', cm, sizeOf(cm, (t) => count(t, /\bnew Entry\s*\(/g)), 'ENTRIES');
    const ap = 'inspecto/src/main/java/com/gamma/control/ApprovalPolicy.java';
    add('Governable config kinds', ap, sizeOf(ap, (t) => { const m = /private static Set<String> governable\(\)\s*\{([\s\S]*?)\n    \}/.exec(t); return m ? count(m[1], /\bs\.(add|addAll|remove)\s*\(/g) : 0; }), 'hand-edit sites in the computed set');
    const oa = 'docs/api/openapi-v1.json';
    add('API contract', oa, sizeOf(oa, (t) => { try { return Object.keys(JSON.parse(t).paths ?? {}).length; } catch { return 0; } }), 'paths in one file');
    const bm = 'tools/bundle-modules.mjs', pk = 'inspecto/package.ps1';
    add('Edition / bundle lists', `${bm}, ${pk}`, (sizeOf(pk, (t) => count(t, /\$modules\s*=/g)) ?? 0) + (read(bm) == null ? 0 : 1), 'hand-kept list sites');
    add('jlink module set', pk, sizeOf(pk, (t) => { const m = /\$runtimeModules\s*=\s*'([^']*)'/.exec(t); return m ? m[1].split(',').length : 0; }), 'java modules');
    const of = 'inspecto/src/main/java/com/gamma/service/OperationalDb.java';
    add('Operational store families', of, sizeOf(of, (t) => { const m = /enum Family\s*\{([\s\S]*?);\s*\n/.exec(t); return m ? count(m[1].replace(/\/\/.*$/gm, ''), /^\s*[A-Z][A-Z_]+\(/gm) : 0; }), 'Family constants');
    return R;
}

export function gather(root) {
    const files = execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 }).split('\n').filter(Boolean);
    const cache = new Map();
    const read = (p) => { if (!cache.has(p)) { const f = join(root, p); cache.set(p, existsSync(f) ? readFileSync(f, 'utf8') : null); } return cache.get(p); };
    const dirs = reactorModuleDirs(read);
    const java = files.filter((f) => /\/src\/main\/java\/.*\.java$/.test(f)).map((path) => {
        const t = read(path) ?? '';
        return { path, module: moduleOf(path, dirs), dir: moduleDirOf(path, dirs) ?? path.split('/')[0], pkg: packageOf(t), loc: loc(t) };
    });
    const mods = {};
    for (const j of java) { const m = (mods[j.module] ??= { files: 0, loc: 0 }); m.files++; m.loc += j.loc; }
    // 4. vocabulary inside generic modules: la-*, engine, and the engine's query / alert packages
    const generic = (j) => {
        if (/^inspecto-la-/.test(j.module)) return j.module;
        if (j.module !== 'inspecto-engine') return null;
        if (/\.query(\.|$)/.test(j.pkg ?? '')) return 'inspecto-engine:query';
        if (/\.alert(\.|$)/.test(j.pkg ?? '')) return 'inspecto-engine:alert';
        return 'inspecto-engine (rest)';
    };
    const vocab = {};
    for (const j of java) {
        const g = generic(j); if (!g) continue;
        const h = vocabHits(read(j.path) ?? '');
        const v = (vocab[g] ??= { total: 0 });
        for (const [w, n] of Object.entries(h)) { v[w] = (v[w] ?? 0) + n; v.total += n; }
    }
    // P2a: a module with main Java must ship META-INF/inspecto/module.toon (asn-parser is a nested reactor, checked by ModuleManifestGuardTest)
    const dirOfModule = Object.fromEntries(java.map((j) => [j.module, j.dir]));
    const withoutManifest = Object.keys(mods).filter((m) => m.startsWith('inspecto')
        && !files.includes(`${dirOfModule[m]}/src/main/resources/META-INF/inspecto/module.toon`)).sort();
    return {
        splitPackages: splitPackages(java),
        withoutManifest,
        registries: registries(read, files),
        modules: Object.fromEntries(Object.entries(mods).sort((a, b) => b[1].loc - a[1].loc)),
        vocab: Object.fromEntries(Object.entries(vocab).sort()),
    };
}

export function render(r) {
    const L = [];
    L.push(`## 1. Split packages: ${r.splitPackages.length}`);
    for (const s of r.splitPackages) L.push(`- ${s.pkg}  ${Object.entries(s.modules).map(([m, n]) => `${m}(${n})`).join(', ')}`);
    L.push('', `## 2. Closed central registries: ${r.registries.length}`, '', '| Registry | Where | Size | Unit |', '|---|---|---|---|');
    for (const x of r.registries) L.push(`| ${x.name} | ${x.where} | ${x.size ?? 'MISSING'} | ${x.unit} |`);
    const ms = Object.entries(r.modules);
    L.push('', `## 3. Modules with main code: ${ms.length} — modules without manifest: ${r.withoutManifest.length}${r.withoutManifest.length ? ` (${r.withoutManifest.join(', ')})` : ''}`, '', '| Module | Java files | Main LOC |', '|---|---|---|');
    for (const [m, v] of ms) L.push(`| ${m} | ${v.files} | ${v.loc} |`);
    L.push('', '## 4. Telecom vocabulary in generic modules', '', '| Module | Total | Words |', '|---|---|---|');
    for (const [m, v] of Object.entries(r.vocab)) L.push(`| ${m} | ${v.total} | ${Object.entries(v).filter(([k]) => k !== 'total').map(([k, n]) => `${k}=${n}`).join(' ') || '-'} |`);
    return L.join('\n');
}

export function summary(r) {
    return {
        splitPackages: r.splitPackages.length,
        closedRegistries: r.registries.length,
        registrySizes: Object.fromEntries(r.registries.map((x) => [x.name, x.size])),
        modules: Object.keys(r.modules).length,
        modulesWithoutManifest: r.withoutManifest.length,
        mainLoc: Object.fromEntries(Object.entries(r.modules).map(([m, v]) => [m, v.loc])),
        vocab: Object.fromEntries(Object.entries(r.vocab).map(([m, v]) => [m, v.total])),
    };
}

function main() {
    const a = process.argv.slice(2);
    const i = a.indexOf('--root');
    const r = gather(i >= 0 ? a[i + 1] : '.');
    if (!a.includes('--json')) console.log(render(r) + `\n\n## 5. Offerings (tools/check-offerings.mjs): ${offeringsStatus(i >= 0 ? a[i + 1] : '.')}\n`);
    console.log(JSON.stringify(summary(r)));
    if (a.includes('--ratchet')) {
        const bad = ratchetViolations(ratchetCounts(r), JSON.parse(readFileSync(new URL('./module-architecture-baseline.json', import.meta.url), 'utf8')));
        if (bad.length) { console.error(`RATCHET FAILED (lower tools/module-architecture-baseline.json as work lands, never raise it): ${bad.join('; ')}`); process.exit(1); }
    }
    if (a.includes('--check') && (r.splitPackages.length || r.registries.some((x) => x.size))) process.exit(1);
}

/** Counts the ratchet tracks. A count may only fall; `populatedRegistries` = closed registries still holding entries (size > 0). */
export function ratchetCounts(r) {
    return { splitPackages: r.splitPackages.length, populatedRegistries: r.registries.filter((x) => x.size).length };
}

/** Names every tracked count that rose above its baseline; empty = pass. */
export function ratchetViolations(counts, baseline) {
    return Object.entries(baseline).filter(([k, v]) => counts[k] > v).map(([k, v]) => `${k}: ${counts[k]} > baseline ${v}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
