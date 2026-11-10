#!/usr/bin/env node
// offering-classpath — the Offering → the bundle's jar list (MODULE-REORG-P3-THIN-JARS, P3d stage 1).
//
// WHY. package.ps1 used to hand-keep the module list FIVE times (the `-pl` list, the staging steps, and the jar
// list inside run.sh, run.bat, serve.sh, serve.bat, the boot smoke and the demo launchers - each with its own
// order, and an edition detected by `[ -f inspecto-oidc.jar ]`). This is the ONE place that decides which jars a
// bundle's classpath carries and in what order; package.ps1 writes its output into the bundle as `modules.list`
// + `edition.properties`, and every launcher READS them.
//
//   node tools/offering-classpath.mjs --edition standard [--demo] --print          modules.list to stdout
//   node tools/offering-classpath.mjs --edition standard --list-mvn                the `-pl` string for package.ps1
//   node tools/offering-classpath.mjs --list-core                                      the core thin jars (artifactId<TAB>dir) package.ps1 stages
//   node tools/offering-classpath.mjs --edition standard [--demo] --bundle <dir>   write modules.list +
//                                                                                      edition.properties into <dir>/lib
//                                                                                      after verifying the staged jars
//
// RESOLUTION. offerings/<edition>.toon (includes, transitively, + `modules`) -> the `requires.modules` closure over the module
// manifests -> that set MUST equal what tools/bundle-modules.mjs ships for the edition (the table the SBOM and the
// staging steps are held against), minus the always-present base/internal modules. Any disagreement THROWS: two
// sources of truth that quietly differ is the defect this tool exists to end.
//
// ORDER (documented, load-bearing for ServiceLoader first-match): CLASSPATH_ORDER below. It is serve.sh/serve.bat's
// historic order, byte for byte (the long-running control plane is where order is observable): processor, the
// auth/secrets/geo/premium provider group, policy, connectors, then the feature modules, the agents, then the
// third-party JDBC driver. run.sh/run.bat, the boot smoke and serve-demo each had a DIFFERENT hand-kept order and run.*
// never carried inspecto-policy; those now share this one (the jars have disjoint packages, so only
// multi-provider SPIs could notice - there is none today; tools/offering-classpath.test.mjs freezes the old strings).
//
// DEMO. -DemoAuth's build swaps the OIDC trio (oidc, secrets, geo-country) for inspecto-demo-auth.jar, which takes the
// OIDC jar's place (second on the classpath). The edition stays Advanced; `variant=demo` is recorded beside it.

import { existsSync, readdirSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EDITIONS, LIB_DIR, PG_SIDECAR, DEMO_JAR, DEMO_REPLACES, CLASSPATH_ORDER, bundleModules, editionOnlyModules, coreModules, needsPostgresDriver } from './bundle-modules.mjs';
import { gather, idOf } from './check-offerings.mjs';

/**
 * The marker file name and the list file name the launchers read (inspecto/package.ps1 here-strings). BUNDLE-LAYOUT-1: all three live
 * in `<bundle>/lib/` beside the jars, and each list names its jars as bundle-ROOT-relative `lib/<jar>` paths (listEntries) - the one seam
 * between the layout and the classpath: a launcher cd's to the bundle root and puts the list on -cp as it stands.
 */
export { LIB_DIR };
export const MODULES_LIST = 'modules.list';
export const EDITION_FILE = 'edition.properties';

/** The demo build's replacement jar (the build-time check lives in package.ps1's `if ($DemoAuth)` block); defined in bundle-modules.mjs. */
export { DEMO_JAR };

/** artifactId -> classpath position; its home is tools/bundle-modules.mjs since PRODUCT-ASSEMBLY-1 PA-1 (the derived table is ordered by it). */
export { CLASSPATH_ORDER };

/** 'standard' | 'Standard' | 'STANDARD' -> the EDITIONS spelling (case-insensitive). */
export function normalizeEdition(e) {
    const s = String(e ?? '').trim();
    const hit = EDITIONS.find((x) => x.toLowerCase() === s.toLowerCase()) ?? null;
    if (!hit) throw new Error(`unknown edition '${e}' (expected ${EDITIONS.join('|')})`);
    return hit;
}

/** The Offering's module ids for an edition: includes (transitive) + modules, then the requires.modules closure. */
export function resolveOffering(edition, g) {
    const resolve = (id, seen) => {
        const o = g.offerings[id];
        if (!o) throw new Error(`offering '${id}' not found under offerings/`);
        if (seen.includes(id)) throw new Error(`offering includes cycle ${[...seen, id].join(' -> ')}`);
        const out = new Set(o.modules ?? []);
        for (const inc of o.includes ?? []) resolve(inc, [...seen, id]).forEach((m) => out.add(m));
        return out;
    };
    const set = resolve(edition.toLowerCase(), []);
    const queue = [...set];
    while (queue.length) {
        for (const req of g.manifests[queue.pop()]?.requires?.modules ?? []) if (!set.has(req)) { set.add(req); queue.push(req); }
    }
    for (const id of set) if (!g.manifests[id]) throw new Error(`offering '${edition}': module '${id}' has no manifest`);
    return set;
}

/** Throws unless the Offering's (non-always-present) module set equals bundle-modules.mjs's for the edition. */
export function assertOfferingEqualsBundle(edition, g) {
    const exempt = (id) => ['base', 'internal'].includes(g.manifests[id]?.offeringRole);
    const offering = new Set([...resolveOffering(edition, g)].filter((m) => !exempt(m)));
    const bundle = new Set(bundleModules(edition).map((m) => idOf(m.artifactId)).filter((m) => !exempt(m)));
    const onlyOffering = [...offering].filter((m) => !bundle.has(m));
    const onlyBundle = [...bundle].filter((m) => !offering.has(m));
    if (onlyOffering.length || onlyBundle.length) {
        throw new Error(
            `offerings/${edition.toLowerCase()}.toon and tools/bundle-modules.mjs disagree for ${edition}:` +
            (onlyOffering.length ? `\n  only the Offering resolves: ${onlyOffering.join(', ')}` : '') +
            (onlyBundle.length ? `\n  only bundle-modules.mjs ships: ${onlyBundle.join(', ')}` : ''));
    }
}

/** The bundle's classpath as jar file names, in launcher order. `g` defaults to the repo (gather). */
export function classpath(edition, { demo = false, g = gather(join(dirname(fileURLToPath(import.meta.url)), '..')) } = {}) {
    const ed = normalizeEdition(edition);
    // LA-EDITIONS-1 ED-3: Preview (everything, demo sign-in) takes the demo swap as Advanced does.
    if (demo && ed !== 'Advanced' && ed !== 'Preview') throw new Error(`the demo build is an Advanced-capability build (got ${ed})`);
    assertOfferingEqualsBundle(ed, g);
    const shipped = new Map([...bundleModules(ed), ...coreModules()].map((m) => [m.artifactId, m.bundleFile]));
    const known = [...bundleModules('Preview'), ...coreModules()];
    for (const a of CLASSPATH_ORDER) if (!known.some((m) => m.artifactId === a)) throw new Error(`CLASSPATH_ORDER names '${a}', which bundle-modules.mjs does not know`);
    for (const a of shipped.keys()) if (!CLASSPATH_ORDER.includes(a)) throw new Error(`module '${a}' ships in ${ed} but has no place in CLASSPATH_ORDER (tools/offering-classpath.mjs) - add it, deliberately`);
    const jars = [];
    for (const a of CLASSPATH_ORDER) {
        if (!shipped.has(a)) continue;
        if (demo && DEMO_REPLACES.includes(a)) { if (a === 'inspecto-oidc') jars.push(DEMO_JAR); continue; }
        jars.push(shipped.get(a));
    }
    if (ed !== 'Basic') jars.push(PG_SIDECAR);
    return jars;
}

/** `-pl` string for package.ps1: every non-default-edition module the edition adds (the processor + connectors are built by step 1). */
export function mvnModules(edition, g) {
    const ed = normalizeEdition(edition);
    if (g) assertOfferingEqualsBundle(ed, g);
    const wanted = new Set(editionOnlyModules(ed).map((m) => m.artifactId));
    return CLASSPATH_ORDER.filter((a) => wanted.has(a)).map((a) => `:${a}`).join(',');
}

/** `--list-core`: one `artifactId<TAB>dir` per core thin jar, for package.ps1's staging loop (the one place that names them is bundle-modules.mjs). */
export const coreList = () => coreModules().map((m) => `${m.artifactId}\t${m.dir}`).join('\n') + '\n';


/**
 * The classpath for a STAGED bundle: the sidecar `postgresql.jar` is optional on a bundle that needs no driver (needsPostgresDriver), so
 * it leaves the list when package.ps1 did not stage it; a bundle that needs it keeps it, and verifyBundle then names the missing jar.
 */
export function stagedClasspath(jars, dir, needsPg) {
    return needsPg || existsSync(join(dir, LIB_DIR, PG_SIDECAR)) ? jars : jars.filter((j) => j !== PG_SIDECAR);
}

/** jar file names -> the entries a list file carries: bundle-root-relative `lib/<jar>` (always `/`, which java -cp accepts on Windows too). */
export const listEntries = (jars) => jars.map((j) => `${LIB_DIR}/${j}`);
/** modules.list text: one jar per line, LF. Pass listEntries(jars) for a bundle's file. */
export const renderList = (jars) => jars.join('\n') + '\n';
/** edition.properties text. */
export const renderEdition = (edition, demo = false) => `edition=${normalizeEdition(edition)}\n` + (demo ? 'variant=demo\n' : '');

/** Problems between a staged bundle (its ROOT) and the list: a listed jar missing from lib/, or a jar in lib/ the list omits. A jar left at the root is refused too. */
export function verifyBundle(jars, dir) {
    const problems = [];
    const lib = join(dir, LIB_DIR);
    for (const j of jars) if (!existsSync(join(lib, j))) problems.push(`${j} is on the classpath list but not staged in ${lib}`);
    for (const f of existsSync(lib) ? readdirSync(lib).filter((n) => n.endsWith('.jar')) : []) if (!jars.includes(f)) problems.push(`${f} is staged in ${lib} but not on the classpath list`);
    for (const f of readdirSync(dir).filter((n) => n.endsWith('.jar'))) problems.push(`${f} is staged at the bundle root ${dir} - every jar belongs in ${LIB_DIR}/`);
    return problems;
}

function main() {
    const a = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    try {
        if (a.includes('--list-core')) { process.stdout.write(coreList()); return; }
        const edition = val('--edition');
        if (!edition) throw new Error('--edition <basic|standard|advanced|preview> is required');
        const demo = a.includes('--demo');
        const g = gather(join(dirname(fileURLToPath(import.meta.url)), '..'));
        if (a.includes('--list-mvn')) { process.stdout.write(mvnModules(edition, g) + '\n'); return; }
        if (a.includes('--needs-pg')) { process.stdout.write(needsPostgresDriver(normalizeEdition(edition), { demo }) ? 'yes\n' : 'no\n'); return; }
        let jars = classpath(edition, { demo, g });
        const bundle = val('--bundle');
        if (bundle) {
            jars = stagedClasspath(jars, bundle, needsPostgresDriver(normalizeEdition(edition), { demo }));
            const problems = verifyBundle(jars, bundle);
            if (problems.length) throw new Error(`the staged bundle and the Offering classpath disagree:\n  ${problems.join('\n  ')}`);
            const lib = join(bundle, LIB_DIR);
            writeFileSync(join(lib, MODULES_LIST), renderList(listEntries(jars)));
            writeFileSync(join(lib, EDITION_FILE), renderEdition(edition, demo));
            console.log(`offering-classpath: ${normalizeEdition(edition)}${demo ? ' (demo)' : ''} -> ${MODULES_LIST} (${jars.length} jars) + ${EDITION_FILE} in ${lib}`);
        } else process.stdout.write(renderList(jars));
    } catch (e) {
        console.error(`offering-classpath: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
