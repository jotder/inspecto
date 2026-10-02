// Fixture test for tools/check-module-deps.mjs — each way an extracted module can start reaching the core must turn the
// guard RED, and the legitimate shapes must stay GREEN. Run: node --test tools/check-module-deps.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ALLOWED, check, directDeps, enforcerAllowlist } from './check-module-deps.mjs';

const G = 'com.gamma.inspector';
const dep = (a, scope) => `<dependency><groupId>${G}</groupId><artifactId>${a}</artifactId>${scope ? `<scope>${scope}</scope>` : ''}</dependency>`;
const enforcer = (allowed) => `<build><plugins><plugin><artifactId>maven-enforcer-plugin</artifactId><executions><execution><configuration><rules>
    <bannedDependencies><excludes><exclude>${G}:*:*:*:compile</exclude></excludes>
    <includes>${[...allowed].sort().map((a) => `<include>${G}:${a}</include>`).join('')}</includes></bannedDependencies>
    </rules></configuration></execution></executions></plugin></plugins></build>`;
const pom = (deps, build = '') => `<project><parent><groupId>${G}</groupId><artifactId>inspecto-parent</artifactId></parent>
    <dependencies>${deps.join('')}</dependencies>${build}</project>`;

/** A throwaway reactor: `poms` = { module: pomText }. */
function repo(poms) {
    const root = mkdtempSync(join(tmpdir(), 'module-deps-'));
    for (const [m, text] of Object.entries(poms)) { mkdirSync(join(root, m), { recursive: true }); writeFileSync(join(root, m, 'pom.xml'), text); }
    return root;
}
const POLICY = { 'x-spi': ['x-util'] };
const green = () => ({ 'x-spi': pom([dep('x-util')], enforcer(['x-util'])), 'x-util': pom([]) });
const run = (poms, policy = POLICY) => { const root = repo(poms); try { return check(root, policy); } finally { rmSync(root, { recursive: true, force: true }); } };

test('a module that reaches only its allowed modules, with a matching enforcer rule, is GREEN', () => {
    assert.deepEqual(run(green()), []);
});

test('a DIRECT dependency on a host module is RED', () => {
    const p = green();
    p['x-spi'] = pom([dep('x-util'), dep('x-processor')], enforcer(['x-util']));
    p['x-processor'] = pom([]);
    const r = run(p);
    assert.equal(r.length, 1);
    assert.match(r[0], /x-spi reaches x-processor \(through x-processor\)/);
});

test('a TRANSITIVE reach through an allowed module is RED and names the module it came through', () => {
    const p = green();
    p['x-util'] = pom([dep('x-engine')]);          // the allowed module itself starts depending on the host
    p['x-engine'] = pom([]);
    const r = run(p);
    assert.match(r.join('\n'), /x-spi reaches x-engine \(through x-util\)/);
});

test('a TEST-scope dependency on the core is exempt (a test may boot it); promoting it to provided is RED', () => {
    const exempt = green();
    exempt['x-spi'] = pom([dep('x-util'), dep('x-processor', 'test')], enforcer(['x-util']));
    exempt['x-processor'] = pom([]);
    assert.deepEqual(run(exempt), []);
    const promoted = { ...exempt, 'x-spi': pom([dep('x-util'), dep('x-processor', 'provided')], enforcer(['x-util'])) };
    assert.match(run(promoted).join('\n'), /x-spi reaches x-processor/);
});

test('a governed module with NO enforcer rule is RED (Decision 5 needs both layers)', () => {
    const p = green();
    p['x-spi'] = pom([dep('x-util')]);
    assert.match(run(p).join('\n'), /no maven-enforcer bannedDependencies rule/);
});

test('an enforcer allowlist that has DRIFTED from the policy table is RED', () => {
    const p = green();
    p['x-spi'] = pom([dep('x-util')], enforcer(['x-util', 'x-engine']));
    assert.match(run(p).join('\n'), /enforcer allowlist \[x-engine, x-util\] has drifted/);
});

test('a governed module that vanished (renamed or deleted) is RED, never silently skipped', () => {
    assert.match(run({ 'x-util': pom([]) }).join('\n'), /x-spi: no pom\.xml/);
});

test('plugin dependencies, dependencyManagement and comments are not module dependencies', () => {
    const text = pom([dep('x-util')], `<!-- ${dep('x-engine')} -->` + enforcer(['x-util']))
        .replace('<dependencies>', `<dependencyManagement><dependencies>${dep('x-engine')}</dependencies></dependencyManagement><dependencies>`);
    assert.deepEqual(directDeps(text).map((d) => d.artifact), ['x-util']);
    assert.deepEqual(enforcerAllowlist(text), ['x-util']);
});

test('the real ALLOWED table is internally consistent: no module may allow a host module', () => {
    const host = ['inspecto-processor', 'inspecto-engine', 'inspecto-etl', 'inspecto-acquire', 'inspecto-event'];
    for (const [m, ok] of Object.entries(ALLOWED)) for (const h of host) assert.ok(!ok.includes(h), `${m} must not allow ${h}`);
});

test('D-3: la-storage may reach la-core, but la-core reaching la-storage is RED and la-storage reaching the engine is RED', () => {
    assert.ok(ALLOWED['inspecto-la-storage'].includes('inspecto-la-core'));
    assert.ok(!ALLOWED['inspecto-la-core'].includes('inspecto-la-storage'));
    const policy = { 'inspecto-la-core': ALLOWED['inspecto-la-core'], 'inspecto-la-storage': ALLOWED['inspecto-la-storage'] };
    const ok = {
        'inspecto-la-core': pom([], enforcer(ALLOWED['inspecto-la-core'])),
        'inspecto-la-storage': pom([dep('inspecto-la-core')], enforcer(ALLOWED['inspecto-la-storage'])),
    };
    assert.deepEqual(run(ok, policy), []);
    const back = { ...ok, 'inspecto-la-core': pom([dep('inspecto-la-storage')], enforcer(ALLOWED['inspecto-la-core'])) };
    assert.match(run(back, policy).join(' '), /inspecto-la-core reaches inspecto-la-storage/);
    const engine = { ...ok, 'inspecto-la-storage': pom([dep('inspecto-la-core'), dep('inspecto-engine')], enforcer(ALLOWED['inspecto-la-storage'])), 'inspecto-engine': pom([]) };
    assert.match(run(engine, policy).join(' '), /inspecto-la-storage reaches inspecto-engine/);
});
