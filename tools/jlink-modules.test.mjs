import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
    parseJdepsOutput, parseExtra, validateExtra, parseListModules, union, diff, parseLock, renderLock,
    validateLock, missingSections, expectedJars, requireJars, lockMismatch, computeSet,
} from './jlink-modules.mjs';
import { EDITIONS } from './bundle-modules.mjs';

test('parseJdepsOutput takes the comma line and ignores warnings', () => {
    const out = 'Warning: split package\njava.base,java.sql,java.naming\n';
    assert.deepEqual(parseJdepsOutput(out), ['java.base', 'java.naming', 'java.sql']);
    assert.deepEqual(parseJdepsOutput(''), []);
});

test('parseExtra / validateExtra', () => {
    const e = parseExtra('# c\njdk.zipfs   # nio zip\nbogus.mod\njdk.zipfs # again\n');
    assert.equal(e.length, 3);
    const p = validateExtra(e, parseListModules('java.base@27\njdk.zipfs@27\n'));
    assert.ok(p.some((x) => x.includes("'bogus.mod' is not a JDK module")));
    assert.ok(p.some((x) => x.includes("'bogus.mod' has no WHY")));
    assert.ok(p.some((x) => x.includes('listed twice')));
});

test('union and diff', () => {
    assert.deepEqual(union(['b', 'a'], ['a', 'c']), ['a', 'b', 'c']);
    assert.deepEqual(diff(['a', 'b', 'c'], ['b']), ['a', 'c']);
});

test('lock round trip and validation', () => {
    const lock = { Standard: ['java.base', 'java.sql'] };
    assert.deepEqual(parseLock(renderLock(lock)), lock);
    assert.deepEqual(validateLock(lock), []);
    assert.ok(validateLock({ Standard: ['java.sql'] }).some((x) => x.includes('java.base')));
    assert.ok(validateLock({ Nope: ['java.base'] }).some((x) => x.includes('unknown edition')));
    assert.ok(validateLock({ Basic: ['java.base', 'a.b', 'a.a'] }).some((x) => x.includes('not sorted')));
});

test('every edition needs a lock section', () => {
    const full = Object.fromEntries(EDITIONS.map((e) => [e, ['java.base']]));
    assert.deepEqual(missingSections(full), []);
    const { Preview, ...noPreview } = full;
    assert.deepEqual(missingSections(noPreview), ['no [Preview] section — package that edition and run --write-lock']);
});

test('missing staged jar fails', () => {
    const need = expectedJars('Basic');
    assert.doesNotThrow(() => requireJars('Basic', need));
    assert.throws(() => requireJars('Basic', need.slice(1)), /would under-report/);
});

test('lock mismatch message', () => {
    assert.equal(lockMismatch('Basic', ['a'], ['a']), null);
    assert.match(lockMismatch('Basic', ['a', 'b'], ['a', 'c']), /\+b .* -c/);
    assert.match(lockMismatch('Basic', ['a'], undefined), /no \[Basic\] section/);
});

test('computeSet unions jdeps with extra and enforces jars', () => {
    const dir = mkdtempSync(join(tmpdir(), 'jlink-'));
    assert.throws(() => computeSet({ edition: 'Basic', stagedDir: dir, jdeps: () => [], extraText: '' }), /lacks/);
    for (const j of expectedJars('Basic')) writeFileSync(join(dir, j), '');
    const r = computeSet({ edition: 'Basic', stagedDir: dir, jdeps: () => ['java.base', 'java.sql'], extraText: 'jdk.zipfs # why\n' });
    assert.deepEqual(r.all, ['java.base', 'java.sql', 'jdk.zipfs']);
});

// ── PACKAGE-DEMOAUTH-ADVANCED-1: the demo bundle's module set follows the bundle it really stages ─────────────────────────────
import { DEMO_JAR, DEMO_REPLACES } from './bundle-modules.mjs';

test('demo: the OIDC trio is no longer expected, inspecto-demo-auth.jar is', () => {
    const plain = expectedJars('Advanced');
    const demo = expectedJars('Advanced', { demo: true });
    for (const a of DEMO_REPLACES) assert.ok(plain.includes(`${a}.jar`) && !demo.includes(`${a}.jar`), a);
    assert.ok(demo.includes(DEMO_JAR) && !plain.includes(DEMO_JAR));
    assert.equal(demo.length, plain.length - DEMO_REPLACES.length + 1);
});

test('demo: requireJars accepts exactly the swapped set and still fails closed on a missing jar', () => {
    const demo = expectedJars('Advanced', { demo: true });
    assert.doesNotThrow(() => requireJars('Advanced', demo, { demo: true }));
    assert.throws(() => requireJars('Advanced', expectedJars('Advanced'), { demo: true }), new RegExp(DEMO_JAR.replace(/\./g, '\.')));
    assert.throws(() => requireJars('Advanced', demo.slice(1), { demo: true }), /would under-report/);
    // the old behaviour (the bug): a demo staged dir judged against the non-demo set lacks the three removed jars
    assert.throws(() => requireJars('Advanced', demo), /lacks 3 expected jar\(s\)/);
});

test('demo: computeSet over a demo staged dir works with demo=true (and fails without it)', () => {
    const dir = mkdtempSync(join(tmpdir(), 'jlink-demo-'));
    for (const j of expectedJars('Advanced', { demo: true })) writeFileSync(join(dir, j), '');
    const r = computeSet({ edition: 'Advanced', stagedDir: dir, jdeps: () => ['java.base'], extraText: '', demo: true });
    assert.deepEqual(r.all, ['java.base']);
    assert.throws(() => computeSet({ edition: 'Advanced', stagedDir: dir, jdeps: () => [], extraText: '' }), /lacks 3 expected jar/);
});
