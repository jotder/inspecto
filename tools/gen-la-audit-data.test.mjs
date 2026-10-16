// Determinism of tools/gen-la-audit-data.py. Run: node --test tools/gen-la-audit-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-audit/data) twice and asserts the bytes of every CSV are IDENTICAL
// to what is committed. Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-audit', 'data');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
    for (const e of readdirSync(dir)) {
        const p = join(dir, e);
        if (statSync(p).isDirectory()) snapshot(p, out);
        else if (p.endsWith('.csv'))
            out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
    }
    return out;
}
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-audit-data.py')], { encoding: 'utf8' }).status, 0);

test('generator twice = the committed bytes', { skip: !python }, () => {
    const before = snapshot();
    assert.equal(Object.keys(before).length, 7, 'five inbox feeds + two staged files');
    run();
    assert.deepEqual(snapshot(), before, 'first run changed bytes');
    run();
    assert.deepEqual(snapshot(), before, 'second run changed bytes');
});
