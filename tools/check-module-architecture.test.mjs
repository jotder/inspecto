// Fixture test for the pure helpers of tools/check-module-architecture.mjs. Run: node --test tools/check-module-architecture.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { balancedAfter, loc, moduleOf, packageOf, registries, splitPackages, vocabHits } from './check-module-architecture.mjs';

test('packageOf reads the package line and ignores commented-out ones', () => {
    assert.equal(packageOf('/* package a.b; */\n// package c.d;\npackage com.x.y;\nclass A {}'), 'com.x.y');
    assert.equal(packageOf('class A {}'), null);
});

test('moduleOf is the top-level directory', () => {
    assert.equal(moduleOf('inspecto-ops/src/main/java/A.java'), 'inspecto-ops');
    assert.equal(moduleOf('pom.xml'), null);
});

test('splitPackages lists only packages present in more than one module, with per-module file counts', () => {
    const f = [
        { module: 'a', pkg: 'com.x' }, { module: 'a', pkg: 'com.x' }, { module: 'b', pkg: 'com.x' },
        { module: 'a', pkg: 'com.only' }, { module: 'b', pkg: 'com.other' }, { module: 'b', pkg: null },
    ];
    assert.deepEqual(splitPackages(f), [{ pkg: 'com.x', modules: { a: 2, b: 1 } }]);
    assert.deepEqual(splitPackages([{ module: 'a', pkg: 'p' }, { module: 'a', pkg: 'q' }]), []);
});

test('loc skips blanks and comment-only lines', () => {
    assert.equal(loc('// c\n\n/* x */\n * y\nint a;\nint b;'), 2);
});

test('vocabHits counts telecom words but not the event-bus subscriber', () => {
    const t = 'String msisdn; // IMSI and CDR\nEventBus subscriber list;\nvar sim = dealer;\nSubscriber s = new Subscriber();';
    assert.deepEqual(vocabHits(t), { msisdn: 1, imsi: 1, cdr: 1, sim: 1, dealer: 1, subscriber: 2 });
    assert.deepEqual(vocabHits('class Simple { int similar; }'), {});
});

test('balancedAfter returns the balanced parenthesised block', () => {
    assert.equal(balancedAfter('x for (R m : List.of(new A(), new B())) { }', 'List.of('), 'List.of(new A(), new B())');
    assert.equal(balancedAfter('nothing', 'List.of('), '');
});

test('registries measures a fixture and reports a missing file as null', () => {
    const files = ['inspecto/src/main/java/com/gamma/control/AbsentXRoutes.java', 'inspecto/src/main/java/com/gamma/control/AbsentYRoutes.java'];
    const src = {
        'inspecto/src/main/java/com/gamma/control/ControlApi.java': 'for (RouteModule module : List.of(new AaRoutes(), new BbRoutes(), new CcRoutes())) {}',
        'inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java': 'api.hasRoute("GET","/a"); api.hasRoute("GET","/b");',
        'inspecto-ui/src/app/core/navigation/navigation.service.ts': "private static readonly OPS_NAV_IDS = new Set(['a', 'b']);\nprivate static readonly EVENTS_NAV_IDS = new Set(['c']);",
        'inspecto-auth-spi/src/main/java/com/gamma/control/CapabilityManifest.java': 'new Entry("POST","/a","c"), new Entry("GET","/b","d")',
    };
    const by = Object.fromEntries(registries((p) => src[p] ?? null, files).map((r) => [r.name, r.size]));
    assert.equal(by['Built-in route list'], 3);
    assert.equal(by['Absent-module 503 stubs'], 2);
    assert.equal(by['Feature flags (features{})'], 2);
    assert.equal(by['SPA nav gating'], 3);
    assert.equal(by['RBAC capabilities'], 2);
    assert.equal(by['Governable config kinds'], null);
    assert.equal(registries(() => null, []).length, 10);
});
