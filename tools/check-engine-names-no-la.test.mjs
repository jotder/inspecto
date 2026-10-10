// Fixture test for tools/check-engine-names-no-la.mjs. Run: node --test tools/check-engine-names-no-la.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { check } from './check-engine-names-no-la.mjs';

function run(src) {
    const root = mkdtempSync(join(tmpdir(), 'engine-no-la-'));
    try {
        const d = join(root, 'inspecto', 'src', 'main', 'java', 'x');
        mkdirSync(d, { recursive: true });
        writeFileSync(join(d, 'A.java'), src);
        return check(root);
    } finally { rmSync(root, { recursive: true, force: true }); }
}

test('a clean engine source is GREEN', () => assert.equal(run('package x;\nimport com.gamma.config.Y;\nclass A {}\n').length, 0));
test('an import of com.gamma.la.* is RED', () => assert.equal(run('import com.gamma.la.core.InvestigationStore;\n').length, 1));
test('a fully qualified com.gamma.geolink use is RED', () => assert.equal(run('Object o = new com.gamma.geolink.Foo();\n').length, 1));
test('a lookalike package (com.gamma.layout) is GREEN', () => assert.equal(run('import com.gamma.layout.Z;\n').length, 0));
