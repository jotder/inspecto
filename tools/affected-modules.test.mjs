// Fixture test for tools/affected-modules.mjs. Run: node --test tools/affected-modules.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readReactor, dependentsClosure, discoverConsumers, decide, FULL_THRESHOLD } from './affected-modules.mjs';

const pom = (id, deps = [], packaging = '') => `<project><parent><artifactId>parent</artifactId></parent>${packaging}
<artifactId>${id}</artifactId><dependencies>${deps.map((d) => `<dependency><artifactId>${d}</artifactId></dependency>`).join('')}
<dependency><artifactId>junit-jupiter</artifactId></dependency></dependencies></project>`;
// util ← sql ← engine ← ops ;  util ← leaf ;  aggregator agg/ holding agg/inner
const POMS = {
    'pom.xml': pom('parent', [], '<packaging>pom</packaging>'),
    'platform/util/pom.xml': pom('util'),
    'platform/sql/pom.xml': pom('sql', ['util']),
    'platform/engine/pom.xml': pom('engine', ['sql', 'util']),
    'features/ops/pom.xml': pom('ops', ['engine']),
    'providers/leaf/pom.xml': pom('leaf', ['util']),
    'agg/pom.xml': pom('agg', [], '<packaging>pom</packaging>'),
    'agg/inner/pom.xml': pom('inner'),
};
const mods = () => readReactor(Object.keys(POMS), (f) => POMS[f]);
const run = (changed, extra = {}) => decide({ mods: mods(), changed, consumers: new Map(), ...extra });

test('reactor model keeps only internal deps and flags aggregators', () => {
    const m = mods();
    assert.deepEqual([...m['platform/engine'].deps].sort(), ['sql', 'util']);
    assert.equal(m['agg'].aggregator, true);
    assert.equal(m['platform/util'].deps.size, 0);
});

test('dependents closure is transitive and downstream only', () => {
    assert.deepEqual([...dependentsClosure(mods(), ['sql'])].sort(), ['engine', 'ops', 'sql']);
    assert.deepEqual([...dependentsClosure(mods(), ['ops'])], ['ops']);
});

test('a leaf change tests only the leaf, with an upstream install first', () => {
    const p = run(['features/ops/src/main/java/A.java']);
    assert.equal(p.mode, 'affected');
    assert.deepEqual(p.testModules, ['ops']);
    assert.match(p.commands[0], /-DskipTests .*-pl :ops -am$/);
    assert.match(p.commands[1], /test .*-pl :ops$/);
});

test('a shared-module change tests every dependent (never only -am)', () => {
    assert.deepEqual(run(['platform/sql/src/main/java/S.java']).testModules, ['engine', 'ops', 'sql']);
});

test('docs-only change needs no Java build', () => {
    const p = run(['docs/okf/product/module/x/spec.md', 'product-map/module/x.toon', 'tools/check-foo.mjs']);
    assert.equal(p.mode, 'none');
    assert.deepEqual(p.commands, []);
});

test('a doc a test READS runs that test module only, not its dependents', () => {
    const consumers = new Map([['docs/api/openapi-v1.json', new Set(['platform/sql'])]]);
    const p = run(['docs/api/openapi-v1.json'], { consumers });
    assert.equal(p.mode, 'affected');
    assert.deepEqual(p.testModules, ['sql']);
});

test('root pom, .mvn, aggregator and module poms force full', () => {
    assert.equal(run(['features/ops/pom.xml']).mode, 'full');
    assert.equal(run(['pom.xml']).mode, 'full');
    assert.equal(run(['.mvn/jvm.config']).mode, 'full');
    assert.equal(run(['agg/pom.xml']).mode, 'full');
});

test('comment-only pom or java edits are ignored', () => {
    const p = run(['pom.xml', 'platform/util/src/main/java/U.java'], { pomChanged: () => false });
    assert.equal(p.mode, 'none');
});

test('an unclassified path fails safe to full', () => {
    const p = run(['some-new-root-dir/thing.bin']);
    assert.equal(p.mode, 'full');
    assert.match(p.reasons[0], /fail-safe/);
});

test('closure above the threshold escalates to full', () => {
    // util's closure is 5 of 6 real modules (> 70%)
    const p = run(['platform/util/src/main/java/U.java']);
    assert.ok(5 / 6 > FULL_THRESHOLD);
    assert.equal(p.mode, 'full');
});

test('UI and packaging changes are flagged without a Java build', () => {
    const p = run(['inspecto-ui/src/app/x.ts', 'inspecto/package.ps1']);
    assert.equal(p.mode, 'none');
    assert.equal(p.ui, true);
    assert.equal(p.package, true);
});

test('consumer discovery finds tracked repo paths named by test literals', () => {
    const files = { 'platform/sql/src/test/java/T.java': 'Path.of("../../docs/api/openapi-v1.json"); String x = "not/a/path";' };
    const c = discoverConsumers(mods(), Object.keys(files), (f) => files[f], (p) => p === 'docs/api/openapi-v1.json');
    assert.deepEqual([...c.keys()], ['docs/api/openapi-v1.json']);
    assert.deepEqual([...c.get('docs/api/openapi-v1.json')], ['platform/sql']);
});
