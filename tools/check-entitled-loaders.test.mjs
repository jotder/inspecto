// Fixture test for tools/check-entitled-loaders.mjs: the real repo is green, and each planted hole turns it red.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { check, OPEN, UNFILTERED_CALLERS, codeOf } from './check-entitled-loaders.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

/** A git repo holding every OPEN file copied from this repo, plus `extra` files. */
function fixture(extra = {}, edit = {}) {
    const dir = mkdtempSync(join(tmpdir(), 'entitled-loaders-'));
    execFileSync('git', ['init', '-q'], { cwd: dir });
    const listed = [...Object.keys(OPEN), ...Object.keys(UNFILTERED_CALLERS)];
    const files = { ...Object.fromEntries(listed.map((f) => [f, readFileSync(join(repo, f), 'utf8')])), ...extra };
    for (const [f, fn] of Object.entries(edit)) files[f] = fn(files[f]);
    for (const [f, text] of Object.entries(files)) {
        if (text === null) continue;
        mkdirSync(dirname(join(dir, f)), { recursive: true });
        writeFileSync(join(dir, f), text);
    }
    execFileSync('git', ['add', '-A'], { cwd: dir });
    return dir;
}

const run = (dir) => { try { return check(dir); } finally { rmSync(dir, { recursive: true, force: true }); } };

test('this repo is green', () => {
    assert.deepEqual(check(repo), []);
});

test('the copied open files alone are green (the fixture is sound)', () => {
    assert.deepEqual(run(fixture()), []);
});

test('a new raw ServiceLoader.load in main code is refused', () => {
    const p = run(fixture({ 'x/src/main/java/a/New.java': 'class New { Object o = java.util.ServiceLoader.load(Runnable.class); }' }));
    assert.equal(p.length, 1);
    assert.match(p[0], /New\.java: 1 raw ServiceLoader\.load/);
});

test('a site in a comment, a string or test code is not a site', () => {
    assert.deepEqual(run(fixture({
        'x/src/main/java/a/Doc.java': '/** {@code ServiceLoader.load(X.class)} */ class Doc { String s = "ServiceLoader.load("; // ServiceLoader.load(\n}',
        'x/src/test/java/a/T.java': 'class T { Object o = java.util.ServiceLoader.load(Runnable.class); }',
    })), []);
});

test('an open file that gains a site, or a fail-soft one that stops filtering, is refused', () => {
    const slot = 'spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/SpiSlot.java';
    const opt = 'spi/inspecto-auth-spi/src/main/java/com/gamma/spi/OptionalSpi.java';
    const p = run(fixture({}, {
        [slot]: (t) => t.replace(/}\s*$/, '  static Object more() { return java.util.ServiceLoader.load(Runnable.class); }\n}\n'),
        [opt]: (t) => t.replaceAll('EntitledServices.entitled', 'Objects.nonNull'),
    }));
    assert.equal(p.length, 2, p.join('\n'));
    assert.match(p.join('\n'), /SpiSlot\.java: listed as open with 1 .* found 2/);
    assert.match(p.join('\n'), /OptionalSpi\.java: a fail-soft loader listed as filtered/);
});

test('an open entry whose file is gone is refused', () => {
    const p = run(fixture({ 'platform/inspecto-engine/src/main/java/com/gamma/job/ReportRowMasking.java': null }));
    assert.match(p.join('\n'), /ReportRowMasking\.java: listed in OPEN or UNFILTERED_CALLERS but not a tracked/);
});

test('a method reference or a static import of load is a site too', () => {
    const p = run(fixture({
        'x/src/main/java/a/Ref.java': 'class Ref { java.util.function.Function<Class<Runnable>, ?> f = java.util.ServiceLoader::load; }',
        'x/src/main/java/a/Imp.java': 'import static java.util.ServiceLoader.load;\nclass Imp { }',
    }));
    assert.equal(p.length, 2, p.join('\n'));
    assert.match(p.join('\n'), /Ref\.java: 1 raw/);
    assert.match(p.join('\n'), /Imp\.java: 1 raw/);
});

test('unfiltered OptionalSpi discovery outside the listed callers is refused', () => {
    const p = run(fixture({ 'x/src/main/java/a/Hooks.java': 'class Hooks { Object o = com.gamma.spi.OptionalSpi.allInstalled(Runnable.class); }' }));
    assert.equal(p.length, 1, p.join('\n'));
    assert.match(p[0], /Hooks\.java: 1 OptionalSpi\.allInstalled call/);
});

test('codeOf strips comments and strings', () => {
    assert.equal(codeOf('a /* ServiceLoader.load( */ "ServiceLoader.load(" // x').includes('ServiceLoader'), false);
});
