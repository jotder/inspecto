// Determinism of tools/gen-la-showcase-data.py. Run: node --test tools/gen-la-showcase-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-showcase/data) and asserts the bytes of every landed file are
// IDENTICAL to what is committed - so a second run is the same bytes as the first, the existing mule transfer corpus never
// moves when the GEO scene changes, and the committed GEO files are current. Skipped when python3 is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-showcase', 'data');
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
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-showcase-data.py')], { encoding: 'utf8' }).status, 0);

test('generator twice = the committed bytes, GEO scene files included', { skip: !python }, () => {
    const before = snapshot();
    assert.ok(before['inbox/mule_account_locations/MULE_ACCOUNT_LOCATIONS_20260901.csv'], 'locations file is present');
    assert.ok(before['inbox/mule_geo_legs/MULE_GEO_LEGS_20260901.csv'], 'legs file is present');
    run();
    assert.deepEqual(snapshot(), before, 'first run changed bytes');
    run();
    assert.deepEqual(snapshot(), before, 'second run changed bytes');
});
