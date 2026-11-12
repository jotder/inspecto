/**
 * The first-party module set a PACKAGED BUNDLE ships, per edition — read by `tools/sbom.mjs`, `tools/check-sbom-modules.mjs`
 * (which holds it against `inspecto/package.ps1`), `tools/offering-classpath.mjs`, `tools/jlink-modules.mjs` and the guards.
 *
 * DERIVED, NOT HAND-KEPT (PRODUCT-ASSEMBLY-1 PA-1, 2026-11-10). Until then this file held a literal MODULES table that had to
 * equal what `offerings/*.toon` resolved to, and `offering-classpath.mjs` threw when the two disagreed: two module tables
 * edited in lockstep for every new module. Now the Offerings are the ONE table, and this file computes the rows from them and
 * the module manifests (`<dir>/src/main/resources/META-INF/inspecto/module.toon`):
 *   - a row per optional / provider module that some Offering resolves to (its `includes` chain plus the manifests'
 *     `requires.modules` closure), plus `inspecto-processor`, the product jar (offeringRole base, always staged);
 *   - `from` is the lowest tier whose Offering resolves the module ('all' = Basic and up, 'standard', 'advanced', or
 *     'preview' for a module only preview.toon lists - it then ships in Preview alone);
 *   - `dir` is where the module's manifest lives; `bundleFile` is `<artifactId>.jar` (the processor ships as `inspecto.jar`);
 *   - CORE_MODULES (the always-present thin jars) are the manifests whose offeringRole is base, minus the processor.
 * Rows are ordered by CLASSPATH_ORDER (below), so the staging loop, the SBOM and the classpath agree on one order.
 * Adding a module now means: its manifest, its Offering line, and a CLASSPATH_ORDER position — nothing here.
 *
 * ⛔ NOT the whole bundle. `postgresql.jar` (PG-1) rides Standard/Advanced too, but it is third-party and test-scoped in the
 * reactor, so sbom.mjs adds it from the parent pom's `postgresql.version`. It is named here as PG_SIDECAR only so the guard
 * can account for every staged jar.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseToon } from './toon-subset.mjs';

/** The third-party JDBC sidecar package.ps1 stages for Standard and Advanced (PG-1). */
export const PG_SIDECAR = 'postgresql.jar';

/**
 * BUNDLE-LAYOUT-1 (operator 2026-11-06): every jar of a bundle - and modules.list / edition.properties - lives in
 * `<bundle>/lib/`. A jar's identity (bundleFile, the SBOM names) stays the bare file name; only paths join this directory.
 */
export const LIB_DIR = 'lib';

// Preview is not a customer-facing tier (operator decision 2026-09-21): it exists for testing/
// incubation and bundles EVERY optional module unconditionally — see bundleModules() below, which
// gives it a dedicated branch instead of a 'from' floor so a brand-new module needs no edit here to
// be included in Preview the day it's added to an Offering.
export const EDITIONS = ['Basic', 'Standard', 'Advanced', 'Preview'];

/**
 * artifactId -> classpath position (ORDER only; which modules ship is the Offerings' call). Load-bearing for ServiceLoader
 * first-match: serve.sh/serve.bat's historic order, byte for byte: processor, the core thin jars, the auth/secrets/geo/premium
 * provider group, policy, connectors, then the feature modules, the agents. The third-party JDBC driver follows the list.
 * Every shipped module must appear exactly once (tools/offering-classpath.mjs refuses a module with no place here).
 * Moved here from tools/offering-classpath.mjs (which re-exports it) so the derived table can be ordered by it.
 */
export const CLASSPATH_ORDER = [
    'inspecto-processor',
    // P3d stage 2: the first-party core libraries, thin jars, directly behind the product jar - exactly where the shade used to
    // put them (the processor's own classes first, then its first-party dependencies).
    'inspecto-api', 'inspecto-graph', 'inspecto-util', 'inspecto-config', 'inspecto-sql', 'inspecto-etl', 'inspecto-audit-spi', 'inspecto-auth-spi',
    'inspecto-access', 'inspecto-http-spi', 'inspecto-entity-store', 'inspecto-event', 'inspecto-workflow', 'inspecto-analytics', 'inspecto-acquire', 'inspecto-engine',
    'inspecto-oidc', 'inspecto-secrets', 'inspecto-geo-country', 'inspecto-connectors-kafka', 'inspecto-telecom-asn1',
    'inspecto-policy',
    'inspecto-connectors', 'inspecto-notify-channels', 'inspecto-backup',
    'inspecto-entity-list', 'inspecto-la-storage', 'inspecto-la-core', 'inspecto-la-api', 'inspecto-la-store-pg', 'inspecto-geo-link',
    'inspecto-exchange', 'inspecto-observability', 'inspecto-ops', 'inspecto-case-management', 'inspecto-action-requests', 'inspecto-regulatory-reporting', 'inspecto-reconciliation', 'inspecto-scoring', 'inspecto-screening', 'inspecto-anomaly', 'inspecto-predictive', 'inspecto-realtime',
    'inspecto-agent', 'inspecto-intelligence',
];

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const MANIFEST_SUFFIX = '/src/main/resources/META-INF/inspecto/module.toon';
// Preview is a tier too: a module only preview.toon lists ships in Preview alone (bundleModules('Preview') takes every row).
const TIERS = ['basic', 'standard', 'advanced', 'preview'];
const FLOOR_OF = { basic: 'all', standard: 'standard', advanced: 'advanced', preview: 'preview' };
const PROCESSOR = 'inspecto-processor';

/** Module id -> artifactId (`processor` -> `inspecto-processor`, `ops` -> `inspecto-ops`). */
const artifactOf = (id) => `inspecto-${id}`;

/** Read the inputs the table is derived from: { manifests: {id: {..., dir}}, offerings: {id: {...}} }. */
export function readModuleSources(root = REPO_ROOT) {
    let files;
    try {
        files = execFileSync('git', ['ls-files', `*${MANIFEST_SUFFIX.slice(1)}`], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean);
    } catch (e) {
        throw new Error(`tools/bundle-modules.mjs derives the module table from the git-tracked module manifests, and git could not list them in ${root}: ${e.message}`);
    }
    const manifests = {};
    for (const f of files) {
        const m = parseToon(readFileSync(join(root, f), 'utf8'));
        manifests[m.id] = { ...m, dir: f.slice(0, -MANIFEST_SUFFIX.length) };
    }
    const odir = join(root, 'offerings');
    const offerings = {};
    for (const f of existsSync(odir) ? readdirSync(odir).filter((n) => n.endsWith('.toon')) : []) offerings[f.slice(0, -5)] = parseToon(readFileSync(join(odir, f), 'utf8'));
    return { manifests, offerings };
}

/** An Offering's module ids: its `includes` (transitive) + `modules`, then the manifests' `requires.modules` closure. */
export function resolveOfferingModules(id, { manifests, offerings }, seen = []) {
    const o = offerings[id];
    if (!o) throw new Error(`offering '${id}' not found under offerings/`);
    if (seen.includes(id)) throw new Error(`offering includes cycle ${[...seen, id].join(' -> ')}`);
    const set = new Set(o.modules ?? []);
    for (const inc of o.includes ?? []) resolveOfferingModules(inc, { manifests, offerings }, [...seen, id]).forEach((m) => set.add(m));
    const queue = [...set];
    while (queue.length) for (const req of manifests[queue.pop()]?.requires?.modules ?? []) if (!set.has(req)) { set.add(req); queue.push(req); }
    for (const m of set) if (!manifests[m]) throw new Error(`offering '${id}': module '${m}' has no manifest`);
    return set;
}

const position = (artifactId) => { const i = CLASSPATH_ORDER.indexOf(artifactId); return i < 0 ? Number.MAX_SAFE_INTEGER : i; };
const byClasspath = (a, b) => position(a.artifactId) - position(b.artifactId) || a.artifactId.localeCompare(b.artifactId);

/** The derived tables: { modules: [{artifactId, dir, bundleFile, from}], core: [{artifactId, dir, bundleFile, from, kind}] }. */
export function deriveTables(sources) {
    const { manifests } = sources;
    const processor = manifests.processor;
    if (!processor) throw new Error('no manifest with id `processor` - the product jar has to be a module like every other');
    const resolved = Object.fromEntries(TIERS.map((t) => [t, resolveOfferingModules(t, sources)]));
    const optional = new Set([...resolved.preview].filter((id) => !['base', 'internal'].includes(manifests[id].offeringRole)));
    const modules = [{ artifactId: PROCESSOR, dir: processor.dir, bundleFile: 'inspecto.jar', from: 'all' }];
    for (const id of optional) {
        const tier = TIERS.find((t) => resolved[t].has(id));
        modules.push({ artifactId: artifactOf(id), dir: manifests[id].dir, bundleFile: `${artifactOf(id)}.jar`, from: FLOOR_OF[tier] });
    }
    const core = Object.values(manifests)
        .filter((m) => m.offeringRole === 'base' && m.id !== 'processor')
        .map((m) => ({ artifactId: artifactOf(m.id), dir: m.dir, bundleFile: `${artifactOf(m.id)}.jar`, from: 'all', kind: 'base' }));
    return { modules: modules.sort(byClasspath), core: core.sort(byClasspath) };
}

let tables = null;
/** The repo's tables, derived once per process. */
const repoTables = () => (tables ??= deriveTables(readModuleSources()));

/** The core thin jars every edition stages (the manifests' offeringRole `base`, minus the processor), in classpath order. */
export const coreModules = () => repoTables().core.slice();

/** The Maven profile that activates an edition's extra modules, or null for Basic (the default build). */
export function editionProfile(edition) {
    if (edition === 'Preview') return 'edition-preview';
    if (edition === 'Advanced') return 'edition-advanced';
    if (edition === 'Standard') return 'edition-standard';
    return null;
}

/**
 * The first-party modules staged for `edition`, in classpath order.
 * Basic 7, Standard 27, Advanced 30, Preview 30 — first-party only; add PG_SIDECAR for the jar count.
 * ⚠ Those numbers are ASSERTED by tools/check-sbom-modules.mjs against this table — it parses this very line.
 */
export function bundleModules(edition) {
    if (!EDITIONS.includes(edition)) throw new Error(`unknown edition '${edition}'`);
    const all = repoTables().modules;
    // Preview: every module, unconditionally — deliberately not floor-based, so a module added at any
    // 'from' tier (including a future one) needs no edit here to reach Preview.
    if (edition === 'Preview') return all.slice();
    return all.filter(
        (m) =>
            m.from === 'all' ||
            (m.from === 'standard' && edition !== 'Basic') ||
            (m.from === 'advanced' && edition === 'Advanced'),
    );
}

/** The modules an edition adds beyond Basic — exactly what package.ps1's `$modules` builds. */
export function editionOnlyModules(edition) {
    return bundleModules(edition).filter((m) => m.from !== 'all');
}

/**
 * The demo build (DEMO-AUTH-1) swaps the sign-in trio for ONE jar: inspecto-oidc, inspecto-secrets and
 * inspecto-geo-country leave the bundle and DEMO_JAR takes the OIDC jar's place. Read by tools/offering-classpath.mjs (the demo
 * modules.list) and tools/jlink-modules.mjs (the staged jars the demo runtime must be derived from).
 */
export const DEMO_JAR = 'inspecto-demo-auth.jar';
export const DEMO_REPLACES = ['inspecto-oidc', 'inspecto-secrets', 'inspecto-geo-country'];

/**
 * Does this bundle NEED the PostgreSQL JDBC driver at package time? Only when it carries a module that talks to Postgres:
 * `inspecto-la-store-pg` (Advanced and Preview). Basic and Standard carry none; the `postgresql.jar` sidecar they may also stage
 * is an optional extra there (inert until INSPECTO_DB_URL / a postgres sink asks for it) and never fails a package.
 * The demo build (`demo`) is loopback + DuckDB (`-Dobjects.backend=db`): its la-store-pg jar is inert, so it needs no driver either.
 * (PACKAGE-DEMOAUTH-ADVANCED-1; operator 2026-10-23: the next release ships without Postgres.)
 */
export function needsPostgresDriver(edition, { demo = false } = {}) {
    return !demo && bundleModules(edition).some((m) => m.artifactId === 'inspecto-la-store-pg');
}
