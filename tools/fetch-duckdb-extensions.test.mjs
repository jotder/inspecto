// Offline tests for tools/fetch-duckdb-extensions.mjs's --only / --platform selection (D-8, 2026-09-26).
// Run: node --test tools/fetch-duckdb-extensions.test.mjs
//
// No network: the positive case pre-seeds the one selected file in --out, so the script takes its
// "already cached" branch; the negative cases exit before any fetch.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'fetch-duckdb-extensions.mjs');

function run(...args) {
    const r = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
    return { status: r.status, output: r.stdout + r.stderr };
}

test('--only + --platform select exactly one file, and nothing else is touched', () => {
    const out = mkdtempSync(join(tmpdir(), 'duckdb-ext-'));
    try {
        const dest = join(out, pomAbi(), 'linux_amd64', 'excel.duckdb_extension');
        mkdirSync(dirname(dest), { recursive: true });
        writeFileSync(dest, 'seeded');
        const r = run('--out', out, '--only', 'excel', '--platform', 'linux_amd64');
        assert.equal(r.status, 0, r.output);
        assert.match(r.output, /DuckDB extensions: excel\r?\n/);
        assert.match(r.output, /Platforms\s+: linux_amd64\r?\n/);
        assert.match(r.output, /= linux_amd64\/excel \(already cached\)/);
        assert.match(r.output, /0 fetched, 1 total/, 'the other nine were not selected');
    } finally {
        rmSync(out, { recursive: true, force: true });
    }
});

test('an unknown extension exits 2 naming what package.ps1 stages — never a green empty run', () => {
    const r = run('--check', '--only', 'exel');
    assert.equal(r.status, 2, r.output);
    assert.match(r.output, /unknown extension exel .*package\.ps1 stages .*excel/);
});

test('an unknown platform exits 2 too', () => {
    const r = run('--check', '--platform', 'linux_arm64');
    assert.equal(r.status, 2, r.output);
    assert.match(r.output, /unknown platform linux_arm64/);
});

test('an empty selection exits 2', () => {
    const r = run('--check', '--only', ',');
    assert.equal(r.status, 2, r.output);
    assert.match(r.output, /empty/);
});

/** The ABI directory, derived as the script does: the root pom's duckdb.version, first three parts. */
function pomAbi() {
    const pom = readFileSync(join(dirname(SCRIPT), '..', 'pom.xml'), 'utf8');
    return 'v' + /<duckdb\.version>([^<]+)</.exec(pom)[1].split('.').slice(0, 3).join('.');
}
