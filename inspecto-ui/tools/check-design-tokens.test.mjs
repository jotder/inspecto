// Falsifies the SCOPE of the design-system guard (tools/check-design-tokens.mjs): a violation planted under each guarded
// root must be caught, a clean tree must pass, and an unguarded folder must NOT be (so the probe is not vacuous).
// The guard silently skips a root that does not exist, so "projects/ is listed in ROOTS" is only proven by this test.
// Run from inspecto-ui/: node --test tools/check-design-tokens.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const guard = join(dirname(fileURLToPath(import.meta.url)), 'check-design-tokens.mjs');
const BAD = "export const c = '#ff0000';\n";
const OK = "export const c = 'var(--gamma-primary)';\n";

function run(files) {
    const root = mkdtempSync(join(tmpdir(), 'ds-guard-'));
    try {
        for (const [rel, body] of Object.entries(files)) {
            mkdirSync(dirname(join(root, rel)), { recursive: true });
            writeFileSync(join(root, rel), body);
        }
        return spawnSync(process.execPath, [guard], { env: { ...process.env, DESIGN_TOKENS_ROOT: root }, encoding: 'utf8' });
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

test('a clean tree passes', () => {
    assert.equal(run({ 'src/app/a.ts': OK, 'projects/link-analysis/src/b.ts': OK }).status, 0);
});

for (const rel of ['src/app/inspecto/x.ts', 'projects/link-analysis/src/x.ts', 'projects/anything/src/deep/x.ts']) {
    test(`a hardcoded colour in ${rel} is caught`, () => {
        const r = run({ [rel]: BAD });
        assert.equal(r.status, 1, r.stdout + r.stderr);
        assert.match(r.stderr, /hardcoded-hex/);
    });
}

test('a hardcoded colour in the vendored src/@gamma tree is NOT caught (the guard is not vacuous)', () => {
    assert.equal(run({ 'src/@gamma/x.ts': BAD }).status, 0);
});

test('the ds-allow escape hatch still works under projects/', () => {
    assert.equal(run({ 'projects/link-analysis/src/x.ts': "export const c = '#ff0000'; // ds-allow\n" }).status, 0);
});
