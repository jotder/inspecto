// Fixture test for tools/offering-classpath.mjs. Run: node --test tools/offering-classpath.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EDITIONS, editionOnlyModules, coreModules } from './bundle-modules.mjs';
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
const OLD = {
    serve: {   // serve.sh == serve.bat: the control plane's classpath, and the order the generator preserves EXACTLY.
        Personal: 'processor connectors',
        Professional: 'processor oidc secrets geo-country connectors-kafka telecom-asn1 connectors notify-channels backup entity-list la-graph la-storage la-core la-api geo-link exchange observability ops reconciliation scoring agent postgresql',
        Enterprise: 'processor oidc secrets geo-country connectors-kafka telecom-asn1 policy connectors notify-channels backup entity-list la-graph la-storage la-core la-api la-store-pg geo-link exchange observability ops reconciliation scoring agent intelligence postgresql',
    },
    run: {     // run.sh == run.bat: oidc group LAST, and NO inspecto-policy.
        Personal: 'processor connectors',
        Professional: 'processor connectors notify-channels backup entity-list la-graph la-storage la-core la-api geo-link exchange observability ops reconciliation scoring agent oidc secrets geo-country connectors-kafka telecom-asn1 postgresql',
        Enterprise: 'processor connectors notify-channels backup entity-list la-graph la-storage la-core la-api la-store-pg geo-link exchange observability ops reconciliation scoring agent intelligence oidc secrets geo-country connectors-kafka telecom-asn1 postgresql',
    },
    smoke: {   // the boot-smoke `$cp` literal: connectors before kafka/asn1, policy before connectors.
        Personal: 'processor connectors',
        Professional: 'processor oidc secrets geo-country connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-graph la-storage la-core la-api geo-link exchange observability ops reconciliation scoring agent postgresql',
        Enterprise: 'processor oidc secrets geo-country policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-graph la-storage la-core la-api la-store-pg geo-link exchange observability ops reconciliation scoring agent intelligence postgresql',
    },
    demo: 'processor demo-auth policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-graph la-storage la-core la-api la-store-pg geo-link exchange observability ops reconciliation scoring agent intelligence postgresql',
    demoSmoke: 'processor policy connectors connectors-kafka telecom-asn1 notify-channels backup entity-list la-graph la-storage la-core la-api la-store-pg geo-link exchange observability ops reconciliation scoring agent intelligence postgresql demo-auth',
};
const sorted = (s) => s.split(' ').sort().join(' ');

test('the generated classpath IS serve.sh/serve.bat\'s old classpath, byte for byte, for every edition (Preview = Enterprise)', () => {
    for (const ed of ['Personal', 'Professional', 'Enterprise']) assert.equal(short(classpath(ed, { g: g() })), OLD.serve[ed], ed);
    assert.equal(short(classpath('Preview', { g: g() })), OLD.serve.Enterprise);
});

test('run.* and the boot smoke carried the same jars - the generator differs only in ORDER (and run.* never had policy)', () => {
    for (const ed of ['Personal', 'Professional', 'Enterprise', 'Preview']) {
        const gen = short(classpath(ed, { g: g() }));
        const base = ed === 'Preview' ? 'Enterprise' : ed;
        assert.equal(sorted(gen), sorted(OLD.smoke[base]), `${ed} vs the old boot-smoke set`);
        const withoutPolicy = gen.split(' ').filter((j) => j !== 'policy').join(' ');
        assert.equal(sorted(withoutPolicy), sorted(OLD.run[base]), `${ed} vs the old run.sh set (policy excepted)`);
    }
});

test('the demo build: the OIDC trio is replaced by inspecto-demo-auth.jar in the OIDC jar\'s slot; same set as the old demo launchers', () => {
    const demo = classpath('Enterprise', { demo: true, g: g() });
    assert.equal(sorted(short(demo)), sorted(OLD.demo));
    assert.equal(sorted(short(demo)), sorted(OLD.demoSmoke));
    assert.equal(demo[1 + CORE_JARS.length], 'inspecto-demo-auth.jar');
    for (const j of ['inspecto-oidc.jar', 'inspecto-secrets.jar', 'inspecto-geo-country.jar']) assert.ok(!demo.includes(j), j);
    assert.throws(() => classpath('Professional', { demo: true, g: g() }), /Enterprise-capability/);
});

test('the Offering resolves to the SAME set bundle-modules.mjs ships, for every edition', () => {
    for (const ed of EDITIONS) assert.doesNotThrow(() => classpath(ed, { g: g() }), ed);
    assert.ok(resolveOffering('Enterprise', g()).has('policy') && !resolveOffering('Professional', g()).has('policy'));
});

test('RED: an Offering that drops a shipped module, or names one the bundle does not ship, fails with both sides named', () => {
    const a = g(); a.offerings.professional.modules = a.offerings.professional.modules.filter((m) => m !== 'backup');
    assert.throws(() => classpath('Professional', { g: a }), /only bundle-modules\.mjs ships: backup/);
    const b = g(); b.offerings.personal.modules = [...b.offerings.personal.modules, 'ops'];
    assert.throws(() => classpath('Personal', { g: b }), /only the Offering resolves: ops/);
});

test('RED: a shipped module with no place in CLASSPATH_ORDER fails (a new module cannot be forgotten silently)', () => {
    const at = CLASSPATH_ORDER.indexOf('inspecto-backup');
    CLASSPATH_ORDER.splice(at, 1);
    try { assert.throws(() => classpath('Professional', { g: g() }), /inspecto-backup' ships in Professional but has no place in CLASSPATH_ORDER/); }
    finally { CLASSPATH_ORDER.splice(at, 0, 'inspecto-backup'); }
});

test('RED: a CLASSPATH_ORDER entry bundle-modules.mjs does not know fails', () => {
    CLASSPATH_ORDER.push('inspecto-ghost');
    try { assert.throws(() => classpath('Personal', { g: g() }), /inspecto-ghost', which bundle-modules\.mjs does not know/); }
    finally { CLASSPATH_ORDER.pop(); }
});

test('--list-mvn: exactly the modules the edition adds beyond the default reactor, for each edition', () => {
    assert.equal(mvnModules('Personal', g()), '');
    for (const ed of ['Professional', 'Enterprise', 'Preview']) {
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
        const jars = classpath('Personal', { g: g() });
        for (const j of jars) writeFileSync(join(dir, j), '');
        execFileSync(process.execPath, [SCRIPT, '--edition', 'personal', '--bundle', dir], { encoding: 'utf8' });
        assert.equal(readFileSync(join(dir, 'modules.list'), 'utf8'), renderList(jars));
        assert.equal(readFileSync(join(dir, 'edition.properties'), 'utf8'), 'edition=Personal\n');
        writeFileSync(join(dir, 'zzz-stray.jar'), '');
        rmSync(join(dir, 'modules.list'));
        const r = spawnSync(process.execPath, [SCRIPT, '--edition', 'personal', '--bundle', dir], { encoding: 'utf8' });
        assert.equal(r.status, 1);
        assert.match(r.stderr, /zzz-stray\.jar is staged/);
        assert.throws(() => readFileSync(join(dir, 'modules.list')));
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('edition marker and edition-name handling', () => {
    assert.equal(renderEdition('professional'), 'edition=Professional\n');
    assert.equal(renderEdition('enterprise', true), 'edition=Enterprise\nvariant=demo\n');
    assert.equal(normalizeEdition('Standard'), 'Professional');
    assert.throws(() => normalizeEdition('gold'), /unknown edition/);
});

test('P3d stage 2: every edition carries the core thin jars, directly behind inspecto.jar and in front of every optional module', () => {
    for (const ed of EDITIONS) {
        const cp = classpath(ed, { g: g() });
        assert.equal(cp[0], 'inspecto.jar', ed);
        assert.deepEqual(cp.slice(1, 1 + CORE_JARS.length), CORE_JARS, `${ed}: the core jars follow the product jar in CORE_MODULES order`);
        assert.equal(new Set(cp).size, cp.length, `${ed}: no jar twice`);
    }
    assert.equal(CORE_JARS.length, 14);
});

test('RED: a core jar with no place in CLASSPATH_ORDER fails', () => {
    const at = CLASSPATH_ORDER.indexOf('inspecto-util');
    CLASSPATH_ORDER.splice(at, 1);
    try { assert.throws(() => classpath('Personal', { g: g() }), /inspecto-util' ships in Personal but has no place in CLASSPATH_ORDER/); }
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
        for (const j of classpath('Professional', { g: g() })) writeFileSync(join(dir, j), '');
        execFileSync(process.execPath, [SCRIPT, '--edition', 'professional', '--bundle', dir], { encoding: 'utf8' });
        const core = readFileSync(join(dir, 'core.list'), 'utf8');
        assert.equal(core, ['inspecto.jar', ...CORE_JARS].join('\n') + '\n');
        assert.ok(!core.includes('oidc') && !core.includes('connectors') && !core.includes('postgresql'));
        assert.equal(readFileSync(join(dir, 'modules.list'), 'utf8').split('\n')[0], 'inspecto.jar');
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
