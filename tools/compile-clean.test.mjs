// Fixture test for tools/compile-clean.mjs — the verdict must come from the LOG, never from an exit code, and each way
// a compile can look green while being wrong must be refused. Run: node --test tools/compile-clean.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseMavenLog, summarise } from './compile-clean.mjs';

const summary = (rows, verdict = 'BUILD SUCCESS') => [
    '[INFO] Reactor Summary:', '[INFO] ',
    ...rows.map(([name, status]) => `[INFO] ${name} 4.0.0-SNAPSHOT ........ ${status} [  0.100 s]`),
    '[INFO] ------------------------------------------------------------------------',
    `[INFO] ${verdict}`,
].join('\n');
const err = (mod, file, line, col = 9) =>
    `[ERROR] /C:/repo/${mod}/src/main/java/com/gamma/x/${file}:[${line},${col}] cannot find symbol`;

test('a clean build with every module SUCCESS is a PASS', () => {
    const v = parseMavenLog(summary([['inspecto-util', 'SUCCESS'], ['inspecto-processor', 'SUCCESS']]));
    assert.equal(v.ok, true, v.problems.join(';'));
    assert.equal(v.modules.length, 2, 'the BUILD SUCCESS line is not a module');
});

test('compile errors fail the verdict and are counted ONCE even though Maven prints each twice', () => {
    const log = [err('inspecto-ops', 'ObjectRoutes.java', 704), err('inspecto-ops', 'ObjectRoutes.java', 712),
        summary([['inspecto-util', 'SUCCESS'], ['inspecto-ops', 'FAILURE']], 'BUILD FAILURE'),
        err('inspecto-ops', 'ObjectRoutes.java', 704), err('inspecto-ops', 'ObjectRoutes.java', 712)].join('\n');
    const v = parseMavenLog(log);
    assert.equal(v.ok, false);
    assert.equal(v.errors.length, 2);
    assert.deepEqual(v.failed, ['inspecto-ops']);
    assert.match(summarise(v), /inspecto-ops \(main\): ObjectRoutes\.java×2/);
});

test('a SKIPPED module means an upstream failed — never a pass, even if BUILD SUCCESS was somehow printed', () => {
    const v = parseMavenLog(summary([['inspecto-util', 'SUCCESS'], ['inspecto-exchange', 'SKIPPED']]));
    assert.equal(v.ok, false);
    assert.deepEqual(v.skipped, ['inspecto-exchange']);
});

test('no Reactor Summary (Maven died, or nothing was built) is a non-verdict, not a pass', () => {
    const v = parseMavenLog('[INFO] Scanning for projects...\n[ERROR] Could not resolve dependencies');
    assert.equal(v.ok, false);
    assert.ok(v.problems.some((p) => /no Reactor Summary|BUILD SUCCESS/.test(p)), v.problems.join(';'));
});

test('"Nothing to compile" is the incremental false green: refused, because stale classes can hide a break', () => {
    const log = ['[INFO] Nothing to compile - all classes are up to date.', summary([['inspecto-geo-link', 'SUCCESS']])].join('\n');
    const v = parseMavenLog(log);
    assert.equal(v.ok, false);
    assert.ok(v.problems.some((p) => /NOT clean/.test(p)), v.problems.join(';'));
});
