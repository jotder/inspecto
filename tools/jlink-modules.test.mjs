import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
    parseJdepsOutput, parseExtra, validateExtra, parseListModules, union, diff, parseLock, renderLock,
    validateLock, expectedJars, requireJars, lockMismatch, computeSet,
} from './jlink-modules.mjs';

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
    const lock = { Professional: ['java.base', 'java.sql'] };
    assert.deepEqual(parseLock(renderLock(lock)), lock);
    assert.deepEqual(validateLock(lock), []);
    assert.ok(validateLock({ Professional: ['java.sql'] }).some((x) => x.includes('java.base')));
    assert.ok(validateLock({ Nope: ['java.base'] }).some((x) => x.includes('unknown edition')));
    assert.ok(validateLock({ Personal: ['java.base', 'a.b', 'a.a'] }).some((x) => x.includes('not sorted')));
});

test('missing staged jar fails', () => {
    const need = expectedJars('Personal');
    assert.doesNotThrow(() => requireJars('Personal', need));
    assert.throws(() => requireJars('Personal', need.slice(1)), /would under-report/);
});

test('lock mismatch message', () => {
    assert.equal(lockMismatch('Personal', ['a'], ['a']), null);
    assert.match(lockMismatch('Personal', ['a', 'b'], ['a', 'c']), /\+b .* -c/);
    assert.match(lockMismatch('Personal', ['a'], undefined), /no \[Personal\] section/);
});

test('computeSet unions jdeps with extra and enforces jars', () => {
    const dir = mkdtempSync(join(tmpdir(), 'jlink-'));
    assert.throws(() => computeSet({ edition: 'Personal', stagedDir: dir, jdeps: () => [], extraText: '' }), /lacks/);
    for (const j of expectedJars('Personal')) writeFileSync(join(dir, j), '');
    const r = computeSet({ edition: 'Personal', stagedDir: dir, jdeps: () => ['java.base', 'java.sql'], extraText: 'jdk.zipfs # why\n' });
    assert.deepEqual(r.all, ['java.base', 'java.sql', 'jdk.zipfs']);
});
