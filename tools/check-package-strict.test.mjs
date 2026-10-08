// Run: node --test tools/check-package-strict.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyze } from './check-package-strict.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const fixture = body => { const f = join(mkdtempSync(join(tmpdir(), 'strict-')), 'x.ps1'); writeFileSync(f, body); return f; };

test('the real package.ps1 is GREEN', () => {
    const r = analyze(join(ROOT, 'inspecto', 'package.ps1'));
    assert.deepEqual(r.unset, []);
});

test('RED: a variable assigned only in another branch then read', () => {
    const r = analyze(fixture("Set-StrictMode -Version Latest\n$e = 'Personal'\nif ($e -ne 'Personal') { $kafkaJarSrc = 'a' }\nif ($kafkaJarSrc) { Write-Host x }\n"));
    assert.equal(r.ok, false);
    assert.match(r.unset.join('\n'), /\$kafkajarsrc/);
});

test('GREEN: pre-initialised, assigned-before-read and try-body assignments pass', () => {
    const r = analyze(fixture("Set-StrictMode -Version Latest\n$a = $null\nif ($true) { $a = 1 }\nif ($a) { }\ntry { $b = 2 } finally { }\nWrite-Host $b\n"));
    assert.deepEqual(r.unset, []);
});
