// Negative fixtures for tools/check-family-count.mjs. Run: node --test tools/check-family-count.test.mjs
// The guard is run (as a child process, FAMILY_COUNT_ROOT re-rooted) against a COPY of the files it reads; each
// test mutates one of them and asserts the guard goes red and names it. The roster is OPEN since
// MODULE-REORG-P1-FAMILY: core = the OperationalDb.Family enum, contributed = every module.toon's storeFamilies.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const GUARD = join(REPO, 'tools', 'check-family-count.mjs');

const FILES = [
    'inspecto/src/main/java/com/gamma/service/OperationalDb.java',
    'docs/okf/backend/engine/db-layer.md',
    'docs/okf/capabilities/data-plane/data-plane.md',
    'docs/superpower/enterprise-scale-out-plan.md',
    'inspecto/src/test/java/com/gamma/service/OperationalDbTest.java',
    'inspecto/src/test/java/com/gamma/control/ControlApiSystemRoutesTest.java',
    'features/inspecto-ops/src/test/java/com/gamma/service/ObjectFamiliesOperationalDbTest.java',
    'features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon',
];

/** A fresh fixture tree (copies of the real files), optionally mutated: edit(relPath, (text) => text). */
function fixture(edit = () => {}) {
    const root = mkdtempSync(join(tmpdir(), 'family-count-'));
    for (const rel of FILES) {
        mkdirSync(dirname(join(root, rel)), { recursive: true });
        cpSync(join(REPO, rel), join(root, rel));
    }
    edit((rel, fn) => {
        const f = join(root, rel);
        const before = readFileSync(f, 'utf8');
        const after = fn(before);
        assert.notEqual(after, before, `fixture anchor missing from ${rel}`);
        writeFileSync(f, after);
    });
    return root;
}

function run(root) {
    const r = spawnSync(process.execPath, [GUARD], { env: { ...process.env, FAMILY_COUNT_ROOT: root }, encoding: 'utf8' });
    return { code: r.status, out: r.stdout + r.stderr };
}

test('the real tree: GREEN, 12 core + 4 contributed = 16', () => {
    const r = run(fixture());
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /16 entries \(12 core \+ 4 contributed: OBJECTS, LINKS, NOTES, TAGS\)/);
});

test('RED: prose states a stale total', () => {
    const r = run(fixture((m) => m('docs/okf/backend/engine/db-layer.md', (t) => t.replace('all sixteen families', 'all fifteen families'))));
    assert.equal(r.code, 1);
    assert.match(r.out, /db-layer\.md:\d+ says "fifteen families" but the roster has 16/);
});

test('RED: a module contributes a fifth family and nothing else moved (the stale count the guard exists for)', () => {
    const r = run(fixture((m) => m('features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon',
        (t) => t.replace('storeFamilies[4]: OBJECTS,LINKS,NOTES,TAGS', 'storeFamilies[5]: OBJECTS,LINKS,NOTES,TAGS,EXTRA'))));
    assert.equal(r.code, 1);
    assert.match(r.out, /17 entries \(seventeen: 12 core \+ 5 contributed\)/);
    assert.match(r.out, /says "sixteen families" but the roster has 17/);
    assert.match(r.out, /asserts 4 for the ops module's contributed family count, but the contributed count is 5/);
});

test('RED: a manifest whose declared length disagrees with its list', () => {
    const r = run(fixture((m) => m('features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon',
        (t) => t.replace('storeFamilies[4]', 'storeFamilies[3]'))));
    assert.equal(r.code, 1);
    assert.match(r.out, /storeFamilies\[3\] declares 3 but lists 4/);
});

test('RED: a contributed family reuses a core name', () => {
    const r = run(fixture((m) => m('features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon',
        (t) => t.replace('OBJECTS,LINKS,NOTES,TAGS', 'OBJECTS,LINKS,NOTES,STATUS'))));
    assert.equal(r.code, 1);
    assert.match(r.out, /store family 'STATUS' duplicates a name already on the roster/);
});

test('RED: the processor tripwire is stale (a core family added to the enum, the count test untouched)', () => {
    const r = run(fixture((m) => m('inspecto/src/main/java/com/gamma/service/OperationalDb.java',
        (t) => t.replace('        JOB_RUNS(', '        EXTRA_CORE("Extra", "x.backend", "none", Mode.URL_OR_ENGINE,\n                "x.db.url", null, null, SpaceRoot::jobRunDbUrl),\n        JOB_RUNS('))));
    assert.equal(r.code, 1);
    assert.match(r.out, /OperationalDbTest\.java asserts 12 for the core roster size, but the core count is 13/);
    assert.match(r.out, /ControlApiSystemRoutesTest\.java asserts 12/);
});

test('RED: a removed tripwire is refused, not skipped', () => {
    const r = run(fixture((m) => m('features/inspecto-ops/src/test/java/com/gamma/service/ObjectFamiliesOperationalDbTest.java',
        (t) => t.replace('OperationalDb.all().size()', 'OperationalDb.all().stream().count()'))));
    assert.equal(r.code, 1);
    assert.match(r.out, /could not find the assertion for the whole roster on an Enterprise classpath/);
});

test('RED: an enum whose shape the parse no longer matches (emptiness floor)', () => {
    const r = run(fixture((m) => m('inspecto/src/main/java/com/gamma/service/OperationalDb.java',
        (t) => t.replace('enum Family implements StoreFamily', 'enum Family implements StoreFamily /* parse breaker */').replace(/^ {8}([A-Z_]+)\(/gm, '        // $1('))));
    assert.equal(r.code, 1);
    assert.match(r.out, /parsed only \d+ families/);
});
