// Fixture test for tools/offering-classpath.mjs. Run: node --test tools/offering-classpath.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EDITIONS, editionOnlyModules, coreModules, bundleModules } from './bundle-modules.mjs';
import { parsePom } from './check-offerings.mjs';
import { gather } from './check-offerings.mjs';
import {
    CLASSPATH_ORDER, classpath, mvnModules, normalizeEdition, renderEdition, renderList, resolveOffering, verifyBundle,
} from './offering-classpath.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = join(ROOT, 'tools', 'offering-classpath.mjs');
const g = () => gather(ROOT);
// P3d stage 2: the frozen strings below are the PRE-thin-core classpaths, so `short` drops the core thin jars (asserted separately below).
const CORE_JARS = coreModules().map((m) => m.bundleFile);
const short = (jars) => jars.filter((j) => !CORE_JARS.includes(j)).map((j) => (j === 'inspecto.jar' ? 'processor' : j === 'postgresql.jar' ? 'postgresql' : j.replace(/^inspecto-/, '').replace(/\.jar$/, ''))).join(' ');

// ── TODAY'S HAND-KEPT CLASSPATHS, captured from inspecto/package.ps1 on 2026-10-08 BEFORE it was changed ───────────
// (a throwaway script parsed the run.sh / serve.sh here-strings, the boot-smoke `$cp = @(...)` literal and the
// `$demoJars` literal, and applied each file-presence test over the jars each edition stages). Frozen here so the
// generator is held against what the launchers actually did, not against a comment.
// D-AN2 (2026-10-17): `la-graph` dropped out of these strings when the graph library became the core thin jar inspecto-graph.
const OLD = {
    serve: {   // serve.sh == serve.bat: the control plane's classpath, and the order the generator preserves EXACTLY.
        Basic: 'processor connectors entity-list la-storage la-core la-api geo-link',   // LA-EDITIONS-1 phase 3: the Link Analysis explorer ships in Basic
        Standard: 'processor oidc secrets geo-country connectors-kafka telecom-asn1 connectors notify-channels backup entity-list la-storage la-core la-api geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent postgresql',
        Advanced: 'processor oidc secrets geo-country connectors-kafka telecom-asn1 policy connectors notify-channels backup entity-list la-storage la-core la-api la-store-pg geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent intelligence postgresql',
    },
    run: {     // run.sh == run.bat: oidc group LAST, and NO inspecto-policy.
        Basic: 'processor connectors entity-list la-storage la-core la-api geo-link',   // LA-EDITIONS-1 phase 3: the Link Analysis explorer ships in Basic
        Standard: 'processor connectors notify-channels backup entity-list la-storage la-core la-api geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent oidc secrets geo-country connectors-kafka telecom-asn1 postgresql',
        Advanced: 'processor connectors notify-channels backup entity-list la-storage la-core la-api la-store-pg geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent intelligence oidc secrets geo-country connectors-kafka telecom-asn1 postgresql',
    },
    smoke: {   // the boot-smoke `$cp` literal: connectors before kafka/asn1, policy before connectors.
        Basic: 'processor connectors entity-list la-storage la-core la-api geo-link',   // LA-EDITIONS-1 phase 3: the Link Analysis explorer ships in Basic
        Standard: 'processor oidc secrets geo-country connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-storage la-core la-api geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent postgresql',
        Advanced: 'processor oidc secrets geo-country policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-storage la-core la-api la-store-pg geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent intelligence postgresql',
    },
    demo: 'processor demo-auth policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-storage la-core la-api la-store-pg geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent intelligence postgresql',
    demoSmoke: 'processor policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-storage la-core la-api la-store-pg geo-link exchange observability ops case-management action-requests regulatory-reporting reconciliation scoring screening anomaly agent intelligence postgresql demo-auth',
};
const sorted = (s) => s.split(' ').sort().join(' ');

test('the generated classpath IS serve.sh/serve.bat\'s old classpath, byte for byte, for every edition (Preview = Advanced)', () => {
    for (const ed of ['Basic', 'Standard', 'Advanced']) assert.equal(short(classpath(ed, { g: g() })), OLD.serve[ed], ed);
    assert.equal(short(classpath('Preview', { g: g() })), OLD.serve.Advanced);
});

test('run.* and the boot smoke carried the same jars - the generator differs only in ORDER (and run.* never had policy)', () => {
    for (const ed of ['Basic', 'Standard', 'Advanced', 'Preview']) {
        const gen = short(classpath(ed, { g: g() }));
        const base = ed === 'Preview' ? 'Advanced' : ed;
        assert.equal(sorted(gen), sorted(OLD.smoke[base]), `${ed} vs the old boot-smoke set`);
        const withoutPolicy = gen.split(' ').filter((j) => j !== 'policy').join(' ');
        assert.equal(sorted(withoutPolicy), sorted(OLD.run[base]), `${ed} vs the old run.sh set (policy excepted)`);
    }
});

test('the demo build: the OIDC trio is replaced by inspecto-demo-auth.jar in the OIDC jar\'s slot; same set as the old demo launchers', () => {
    const demo = classpath('Advanced', { demo: true, g: g() });
    assert.equal(sorted(short(demo)), sorted(OLD.demo));
    assert.equal(sorted(short(demo)), sorted(OLD.demoSmoke));
    assert.equal(demo[1 + CORE_JARS.length], 'inspecto-demo-auth.jar');
    for (const j of ['inspecto-oidc.jar', 'inspecto-secrets.jar', 'inspecto-geo-country.jar']) assert.ok(!demo.includes(j), j);
    assert.throws(() => classpath('Standard', { demo: true, g: g() }), /Advanced-capability/);
});

test('the Offering resolves to the SAME set bundle-modules.mjs ships, for every edition', () => {
    for (const ed of EDITIONS) assert.doesNotThrow(() => classpath(ed, { g: g() }), ed);
    assert.ok(resolveOffering('Advanced', g()).has('policy') && !resolveOffering('Standard', g()).has('policy'));
});

test('RED: an Offering that drops a shipped module, or names one the bundle does not ship, fails with both sides named', () => {
    const a = g(); a.offerings.standard.modules = a.offerings.standard.modules.filter((m) => m !== 'backup');
    assert.throws(() => classpath('Standard', { g: a }), /only bundle-modules\.mjs ships: backup/);
    const b = g(); b.offerings.basic.modules = [...b.offerings.basic.modules, 'ops'];
    assert.throws(() => classpath('Basic', { g: b }), /only the Offering resolves: ops/);
});

test('RED: a shipped module with no place in CLASSPATH_ORDER fails (a new module cannot be forgotten silently)', () => {
    const at = CLASSPATH_ORDER.indexOf('inspecto-backup');
    CLASSPATH_ORDER.splice(at, 1);
    try { assert.throws(() => classpath('Standard', { g: g() }), /inspecto-backup' ships in Standard but has no place in CLASSPATH_ORDER/); }
    finally { CLASSPATH_ORDER.splice(at, 0, 'inspecto-backup'); }
});

test('RED: a CLASSPATH_ORDER entry bundle-modules.mjs does not know fails', () => {
    CLASSPATH_ORDER.push('inspecto-ghost');
    try { assert.throws(() => classpath('Basic', { g: g() }), /inspecto-ghost', which bundle-modules\.mjs does not know/); }
    finally { CLASSPATH_ORDER.pop(); }
});

test('--list-mvn: exactly the modules the edition adds beyond the default reactor, for each edition', () => {
    assert.equal(mvnModules('Basic', g()), '');
    for (const ed of ['Standard', 'Advanced', 'Preview']) {
        const got = new Set(mvnModules(ed, g()).split(','));
        const want = new Set(editionOnlyModules(ed).map((m) => `:${m.artifactId}`));
        assert.deepEqual([...got].sort(), [...want].sort(), ed);
    }
});

test('verifyBundle: a listed jar that is not staged, and a staged jar that is not listed, are both RED', () => {
    const dir = mkdtempSync(join(tmpdir(), 'oc-'));
    try {
        for (const j of ['inspecto.jar', 'stray.jar']) writeFileSync(join(dir, j), '');
        const p = verifyBundle(['inspecto.jar', 'inspecto-connectors.jar'], dir).join('\n');
        assert.match(p, /inspecto-connectors\.jar is on the classpath list but not staged/);
        assert.match(p, /stray\.jar is staged .* but not on the classpath list/);
        assert.deepEqual(verifyBundle(['inspecto.jar', 'stray.jar'], dir), []);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('CLI --bundle writes modules.list + edition.properties; a stray staged jar fails it with exit 1 and writes nothing', () => {
    const dir = mkdtempSync(join(tmpdir(), 'oc-'));
    try {
        const jars = classpath('Basic', { g: g() });
        for (const j of jars) writeFileSync(join(dir, j), '');
        execFileSync(process.execPath, [SCRIPT, '--edition', 'basic', '--bundle', dir], { encoding: 'utf8' });
        assert.equal(readFileSync(join(dir, 'modules.list'), 'utf8'), renderList(jars));
        assert.equal(readFileSync(join(dir, 'edition.properties'), 'utf8'), 'edition=Basic\n');
        writeFileSync(join(dir, 'zzz-stray.jar'), '');
        rmSync(join(dir, 'modules.list'));
        const r = spawnSync(process.execPath, [SCRIPT, '--edition', 'basic', '--bundle', dir], { encoding: 'utf8' });
        assert.equal(r.status, 1);
        assert.match(r.stderr, /zzz-stray\.jar is staged/);
        assert.throws(() => readFileSync(join(dir, 'modules.list')));
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('edition marker and edition-name handling', () => {
    assert.equal(renderEdition('standard'), 'edition=Standard\n');
    assert.equal(renderEdition('advanced', true), 'edition=Advanced\nvariant=demo\n');
    assert.equal(normalizeEdition('Standard'), 'Standard');
    assert.throws(() => normalizeEdition('gold'), /unknown edition/);
});

test('P3d stage 2: every edition carries the core thin jars, directly behind inspecto.jar and in front of every optional module', () => {
    for (const ed of EDITIONS) {
        const cp = classpath(ed, { g: g() });
        assert.equal(cp[0], 'inspecto.jar', ed);
        assert.deepEqual(cp.slice(1, 1 + CORE_JARS.length), CORE_JARS, `${ed}: the core jars follow the product jar in CORE_MODULES order`);
        assert.equal(new Set(cp).size, cp.length, `${ed}: no jar twice`);
    }
    assert.equal(CORE_JARS.length, 16);   // 14 until ANALYTICS-CORE-1 added inspecto-analytics and D-AN2 (2026-10-17) added inspecto-graph
});

test('RED: a core jar with no place in CLASSPATH_ORDER fails', () => {
    const at = CLASSPATH_ORDER.indexOf('inspecto-util');
    CLASSPATH_ORDER.splice(at, 1);
    try { assert.throws(() => classpath('Basic', { g: g() }), /inspecto-util' ships in Basic but has no place in CLASSPATH_ORDER/); }
    finally { CLASSPATH_ORDER.splice(at, 0, 'inspecto-util'); }
});

test('--list-core prints artifactId<TAB>dir for every core jar, no edition needed', () => {
    const out = execFileSync(process.execPath, [SCRIPT, '--list-core'], { encoding: 'utf8' }).trim().split('\n');
    assert.deepEqual(out.map((l) => l.split('\t')[0]), coreModules().map((m) => m.artifactId));
    assert.ok(out.every((l) => l.split('\t').length === 2));
});

test('--bundle writes core.list = inspecto.jar + the core thin jars only (the one-shot tools\' classpath: no optional module, no sidecar)', () => {
    const dir = mkdtempSync(join(tmpdir(), 'oc-core-'));
    try {
        for (const j of classpath('Standard', { g: g() })) writeFileSync(join(dir, j), '');
        execFileSync(process.execPath, [SCRIPT, '--edition', 'standard', '--bundle', dir], { encoding: 'utf8' });
        const core = readFileSync(join(dir, 'core.list'), 'utf8');
        assert.equal(core, ['inspecto.jar', ...CORE_JARS].join('\n') + '\n');
        assert.ok(!core.includes('oidc') && !core.includes('connectors') && !core.includes('postgresql'));
        assert.equal(readFileSync(join(dir, 'modules.list'), 'utf8').split('\n')[0], 'inspecto.jar');
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

// LA-EDITIONS-1 phase 7: Preview is EVERYTHING with demo sign-in (ED-3). Held three ways: the generator's table, the Preview
// Offering's resolved classpath (plain and demo) and the pom's edition-preview profile - each must carry every module of every
// other edition, so a module added to Basic, Standard or Advanced and forgotten in Preview turns this red.
test('Preview carries every module every other edition ships, on the plain and the demo classpath and in the pom profile', () => {
    const every = new Set(['Basic', 'Standard', 'Advanced'].flatMap((e) => bundleModules(e).map((m) => m.bundleFile)));
    const preview = new Set(bundleModules('Preview').map((m) => m.bundleFile));
    for (const j of every) assert.ok(preview.has(j), `bundle-modules: Preview lacks ${j}`);
    const plain = new Set(classpath('Preview', { g: g() }));
    for (const j of every) assert.ok(plain.has(j), `Preview classpath lacks ${j}`);
    const demo = new Set(classpath('Preview', { demo: true, g: g() }));
    const trio = ['inspecto-oidc.jar', 'inspecto-secrets.jar', 'inspecto-geo-country.jar'];
    for (const j of every) if (!trio.includes(j)) assert.ok(demo.has(j), `Preview demo classpath lacks ${j}`);
    assert.ok(demo.has('inspecto-demo-auth.jar'), 'the Preview demo build signs in with the Demo User');
    for (const j of trio) assert.ok(!demo.has(j), `the demo build swaps out ${j}`);
    assert.equal(renderEdition('preview', true), 'edition=Preview' + String.fromCharCode(10) + 'variant=demo' + String.fromCharCode(10));
    const pom = parsePom(readFileSync(join(ROOT, 'pom.xml'), 'utf8'));
    const built = new Set([...pom.defaults, ...pom.profiles['edition-preview']]);
    for (const m of bundleModules('Preview')) assert.ok(built.has(m.dir.split('/').pop().replace(/^inspecto-/, '')), `pom edition-preview + default reactor lack ${m.dir}`);
});

// ── PACKAGE-DEMOAUTH-ADVANCED-1: the PostgreSQL driver is needed only by a bundle that carries a Postgres module ───────────────
import { needsPostgresDriver } from './bundle-modules.mjs';
import { stagedClasspath } from './offering-classpath.mjs';

test('needsPostgresDriver: only a bundle carrying inspecto-la-store-pg (Advanced, Preview) - never Basic, Standard or the demo build', () => {
    assert.equal(needsPostgresDriver('Basic'), false);
    assert.equal(needsPostgresDriver('Standard'), false);
    assert.equal(needsPostgresDriver('Advanced'), true);
    assert.equal(needsPostgresDriver('Preview'), true);
    assert.equal(needsPostgresDriver('Advanced', { demo: true }), false);
    assert.equal(needsPostgresDriver('Preview', { demo: true }), false);
});

test('the answer follows the module table: it is exactly the editions whose bundle lists inspecto-la-store-pg', () => {
    for (const ed of EDITIONS) assert.equal(needsPostgresDriver(ed), bundleModules(ed).some((m) => m.artifactId === 'inspecto-la-store-pg'), ed);
});

test('--needs-pg answers yes/no for package.ps1', () => {
    const ask = (...a) => execFileSync('node', [SCRIPT, ...a], { encoding: 'utf8' }).trim();
    assert.equal(ask('--edition', 'Standard', '--needs-pg'), 'no');
    assert.equal(ask('--edition', 'Advanced', '--needs-pg'), 'yes');
    assert.equal(ask('--edition', 'Advanced', '--needs-pg', '--demo'), 'no');
});

test('stagedClasspath: postgresql.jar leaves the list when it was not staged and nothing needs it; a bundle that needs it keeps it (and verifyBundle names it)', () => {
    const dir = mkdtempSync(join(tmpdir(), 'cp-'));
    const jars = ['inspecto.jar', 'postgresql.jar'];
    writeFileSync(join(dir, 'inspecto.jar'), '');
    assert.deepEqual(stagedClasspath(jars, dir, false), ['inspecto.jar']);
    assert.deepEqual(stagedClasspath(jars, dir, true), jars);
    assert.match(verifyBundle(stagedClasspath(jars, dir, true), dir).join('\n'), /postgresql\.jar is on the classpath list but not staged/);
    writeFileSync(join(dir, 'postgresql.jar'), '');
    assert.deepEqual(stagedClasspath(jars, dir, false), jars);
    assert.deepEqual(verifyBundle(stagedClasspath(jars, dir, false), dir), []);
    rmSync(dir, { recursive: true, force: true });
});
