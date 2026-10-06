#!/usr/bin/env node
// check-module-deps — the extracted platform modules may reach ONLY the `inspecto-*` modules they are allowed to.
//
//   node tools/check-module-deps.mjs [--root .]
//
// LA-separation D-1 (docs/archived-documents/plans-archive/la-separation-d1-design.md, Decision 5): the SPI modules and the LA graph module
// exist so that Link Analysis can stand on a thin platform layer WITHOUT the whole core. That only holds while nobody adds
// `inspecto-processor`, `-engine`, `-etl`, `-acquire` or `-event` to one of them — one convenient `<dependency>` undoes the
// extraction silently, and nothing fails until the standalone product is built. This guard makes it fail the day it happens.
//
// DERIVED, not hand-kept: the module graph is read from the poms' direct `com.gamma.inspector` dependencies (scope `test`
// ignored — a test may boot the core), closed transitively, and compared with the ALLOWED table below, which is the policy.
// The same policy is also enforced at build time by `maven-enforcer` `bannedDependencies` in each module's pom; this guard
// additionally FAILS if a governed module lacks that enforcer rule, or if the rule's allowlist has drifted from this table.
// Both layers are falsified in tools/check-module-deps.test.mjs.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const GROUP = 'com.gamma.inspector';

/** module -> the inspecto-* modules it may reach, directly or transitively (non-test scope). Layer order: low → high. */
export const ALLOWED = {
    'inspecto-la-graph': [],                                                                                   // the JDK only
    'inspecto-audit-spi': ['inspecto-api', 'inspecto-util'],
    'inspecto-auth-spi': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi'],
    'inspecto-http-spi': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi'],
    'inspecto-entity-store': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi'],
    // D-1 step 5b: the host-free halves of Link Analysis. inspecto-sql is host-free too (SqlGuard / SqlSandboxPolicy: it reaches only
    // api, config and util). inspecto-http-spi arrives through inspecto-entity-store, so la-core reaches it transitively whether or not it names it.
    'inspecto-la-core': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi', 'inspecto-entity-store', 'inspecto-sql', 'inspecto-la-graph'],
    // D-3 step 2: the Link Analysis index store. la-core's closure plus la-core itself; la-core must NEVER reach it (la-api depends on it since D-3 step 4).
    'inspecto-la-storage': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi', 'inspecto-entity-store', 'inspecto-sql', 'inspecto-la-graph', 'inspecto-la-core'],
    // D-3 step 4: la-api now depends on la-storage (the index build service + manifest); the reverse edge, and la-core -> la-storage, stay banned.
    'inspecto-la-api': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi', 'inspecto-entity-store', 'inspecto-sql', 'inspecto-la-graph', 'inspecto-la-core', 'inspecto-la-storage'],
    // LA-INVESTIGATION-STORE-DESIGN-1 S2 (D-IS7): the Postgres InvestigationStore - la-core's closure plus la-core itself. JDBC only (java.sql); the driver is the host's. la-core must NEVER reach it.
    'inspecto-la-store-pg': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi', 'inspecto-http-spi', 'inspecto-entity-store', 'inspecto-sql', 'inspecto-la-graph', 'inspecto-la-core'],
    'inspecto-oidc': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi'],
    'inspecto-secrets': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi'],
    'inspecto-geo-country': ['inspecto-api', 'inspecto-util', 'inspecto-config', 'inspecto-audit-spi', 'inspecto-auth-spi'],
};

/** Direct inspecto-* dependencies of a pom: [{ artifact, scope }]. */
export function directDeps(pomText) {
    const body = pomText
        .replace(/<parent>[\s\S]*?<\/parent>/, '')
        .replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '')
        .replace(/<profiles>[\s\S]*?<\/profiles>/g, '')
        .replace(/<build>[\s\S]*?<\/build>/g, '')           // plugin dependencies are not module dependencies
        .replace(/<!--[\s\S]*?-->/g, '');
    const out = [];
    for (const m of body.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
        const g = /<groupId>(.*?)<\/groupId>/.exec(m[1])?.[1];
        const a = /<artifactId>(.*?)<\/artifactId>/.exec(m[1])?.[1];
        if (g === GROUP && a) out.push({ artifact: a, scope: /<scope>(.*?)<\/scope>/.exec(m[1])?.[1] ?? 'compile' });
    }
    return out;
}

function readPom(root, module) {
    const p = join(root, module, 'pom.xml');
    return existsSync(p) ? readFileSync(p, 'utf8') : null;
}

/** Transitive non-test closure of `module` over the poms found under `root` (modules without a pom are leaves). */
export function reach(root, module) {
    const seen = new Set();
    const via = new Map();              // reached module -> the direct dependency it was reached through
    const stack = (directDeps(readPom(root, module) ?? '')).filter((d) => d.scope !== 'test').map((d) => [d.artifact, d.artifact]);
    while (stack.length) {
        const [a, first] = stack.pop();
        if (seen.has(a)) continue;
        seen.add(a);
        via.set(a, first);
        const pom = readPom(root, a);
        if (pom) for (const d of directDeps(pom)) if (d.scope !== 'test') stack.push([d.artifact, first]);
    }
    return { reached: seen, via };
}

/** The `includes` of the module's maven-enforcer bannedDependencies rule, or null if there is no such rule. */
export function enforcerAllowlist(pomText) {
    const rule = /<bannedDependencies>([\s\S]*?)<\/bannedDependencies>/.exec(pomText);
    if (!rule) return null;
    const inc = /<includes>([\s\S]*?)<\/includes>/.exec(rule[1]);
    return inc ? [...inc[1].matchAll(/<include>\s*[^:<]+:([^:<]+)[^<]*<\/include>/g)].map((m) => m[1]).sort() : [];
}

export function check(root, allowed = ALLOWED) {
    const problems = [];
    for (const [module, ok] of Object.entries(allowed)) {
        const pom = readPom(root, module);
        if (pom === null) { problems.push(`${module}: no pom.xml under ${root} — a governed module vanished or was renamed`); continue; }
        const { reached, via } = reach(root, module);
        for (const r of [...reached].sort()) {
            if (!ok.includes(r)) problems.push(`${module} reaches ${r} (through ${via.get(r)}) — not in its allowlist [${ok.join(', ') || 'none'}]`);
        }
        const rule = enforcerAllowlist(pom);
        if (rule === null) problems.push(`${module}: pom.xml has no maven-enforcer bannedDependencies rule (Decision 5 requires both layers)`);
        else if (JSON.stringify(rule) !== JSON.stringify([...ok].sort()))
            problems.push(`${module}: enforcer allowlist [${rule.join(', ')}] has drifted from tools/check-module-deps.mjs [${[...ok].sort().join(', ')}]`);
    }
    return problems;
}

function main() {
    const i = process.argv.indexOf('--root');
    const root = i >= 0 ? process.argv[i + 1] : '.';
    const problems = check(root);
    if (problems.length) {
        console.error('✖ check-module-deps: an extracted module reaches a module it must not (LA separation, D-1):');
        for (const p of problems) console.error('  - ' + p);
        process.exit(1);
    }
    console.log(`✓ Module-deps guard: ${Object.keys(ALLOWED).length} extracted module(s) reach only their allowed inspecto-* modules, and each carries a matching maven-enforcer rule`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
