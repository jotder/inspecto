// Falsifies the form-idiom guard (tools/check-form-idiom.mjs, UI-FORM-ROWS-1): a boxed field in a new file fails, a
// listed file passes, a converted file left on the list fails (the list only shrinks), the `form-allow` escape and spec
// files pass, and both roots (src/app, projects) are scanned.
// Run from inspecto-ui/: node --test tools/check-form-idiom.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const guard = join(dirname(fileURLToPath(import.meta.url)), 'check-form-idiom.mjs');
const BOXED = '<mat-form-field><input matInput /></mat-form-field>\n';
const ROWS = '<inspecto-pen-field label="Name"></inspecto-pen-field>\n';

function run(files, allow) {
    const root = mkdtempSync(join(tmpdir(), 'form-guard-'));
    try {
        for (const [rel, body] of Object.entries(files)) {
            mkdirSync(dirname(join(root, rel)), { recursive: true });
            writeFileSync(join(root, rel), body);
        }
        const list = join(root, 'allow.txt');
        writeFileSync(list, ['# header', ...allow].join('\n'));
        return spawnSync(process.execPath, [guard], {
            env: { ...process.env, FORM_IDIOM_ROOT: root, FORM_IDIOM_ALLOWLIST: list },
            encoding: 'utf8',
        });
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

test('rows only: passes', () => {
    assert.equal(run({ 'src/app/a.html': ROWS }, []).status, 0);
});

test('a boxed field in a new file fails, under either root', () => {
    for (const rel of ['src/app/x/a.html', 'projects/lib/src/b.component.ts']) {
        const r = run({ [rel]: BOXED }, []);
        assert.equal(r.status, 1, rel);
        assert.match(r.stderr, new RegExp(rel.replace(/\./g, '\\.')));
    }
    assert.equal(
        run({ 'src/app/c.html': '<mat-select formControlName="x"></mat-select>\n' }, []).status,
        1,
        'mat-select too',
    );
});

test('a listed file passes; a converted file left on the list fails', () => {
    assert.equal(run({ 'src/app/a.html': BOXED }, ['src/app/a.html']).status, 0);
    const r = run({ 'src/app/a.html': ROWS }, ['src/app/a.html']);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /remove it from/);
});

test('form-allow on the line, spec files and unscanned folders pass', () => {
    assert.equal(run({ 'src/app/a.html': '<mat-form-field> <!-- form-allow: SQL editor -->\n' }, []).status, 0);
    assert.equal(run({ 'src/app/a.component.spec.ts': BOXED }, []).status, 0);
    assert.equal(
        run({ 'src/app/c.ts': '/**\n * replaces a <mat-select> with the picker\n */\n// was <mat-form-field>\n' }, [])
            .status,
        0,
        'a tag named in a comment is not a field',
    );
    assert.equal(run({ 'src/@gamma/v.html': BOXED }, []).status, 0, 'vendored scaffolding is out of scope');
});
