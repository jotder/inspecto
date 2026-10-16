// Determinism of tools/gen-la-mm-data.py. Run: node --test tools/gen-la-mm-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-mobile-money/data) and asserts every CSV is byte-identical to what is
// committed, twice - so a regeneration never moves the corpus the golden test pins - and that the corpus stays under 1 MB.
// Skipped when python3 is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-mobile-money', 'data');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
    for (const e of readdirSync(dir)) {
        const p = join(dir, e);
        if (statSync(p).isDirectory()) snapshot(p, out);
        else if (p.endsWith('.csv'))
            out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = { sha: createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex'), bytes: statSync(p).size };
    }
    return out;
}
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-mm-data.py')], { encoding: 'utf8' }).status, 0);

test('generator twice = the committed bytes, and the corpus is under 1 MB', { skip: !python }, () => {
    const before = snapshot();
    assert.equal(Object.keys(before).filter((k) => k.startsWith('inbox/wallet_txn/')).length, 14, 'fourteen landed days');
    assert.ok(before['inbox/wallet_master/WALLET_MASTER_20261014.csv'] && before['inbox/agent_master/AGENT_MASTER_20261014.csv']);
    assert.ok('staged/wallet_txn/WALLET_TXN_20261015.csv' in before && 'staged/wallet_txn/WALLET_TXN_20261017.csv' in before, 'staged next-day and gap-day files');
    assert.ok(Object.values(before).reduce((n, f) => n + f.bytes, 0) < 1_000_000, 'under 1 MB of CSV');
    run();
    assert.deepEqual(snapshot(), before, 'first run changed bytes');
    run();
    assert.deepEqual(snapshot(), before, 'second run changed bytes');
});
