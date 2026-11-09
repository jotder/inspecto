// Determinism and planted facts of tools/gen-la-video7-data.mjs. Run: node --test tools/gen-la-video7-data.test.mjs
// Generates twice into temp folders and asserts the bytes match, then checks the story the video films: the lead
// 99979100042 calls the suspect 99979100001 and receives the suspect's money, written with spaces in transfers.csv,
// and the mess the video points at is present.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const GEN = join(dirname(fileURLToPath(import.meta.url)), 'gen-la-video7-data.mjs');
const generate = () => {
    const dir = mkdtempSync(join(tmpdir(), 'la-video7-'));
    const r = spawnSync(process.execPath, [GEN, dir], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    return { dir, calls: readFileSync(join(dir, 'calls.csv'), 'utf8'), transfers: readFileSync(join(dir, 'transfers.csv'), 'utf8') };
};
const rows = (text) => text.trim().split('\n').slice(1).map((l) => l.split(','));

test('the same bytes every run', () => {
    const a = generate(), b = generate();
    try {
        assert.equal(a.calls, b.calls);
        assert.equal(a.transfers, b.transfers);
    } finally { rmSync(a.dir, { recursive: true }); rmSync(b.dir, { recursive: true }); }
});

test('the lead calls the suspect and receives the suspect\'s money, written two ways', () => {
    const g = generate();
    try {
        const calls = rows(g.calls), transfers = rows(g.transfers);
        // Seven calls are planted; the mess blanks one callee and may write a caller with a leading + (the same number
        // to the product), so six calls from the lead reach the suspect.
        assert.equal(calls.filter(([a, b]) => a.replace(/^\+/, '') === '99979100042' && b === '99979100001').length, 6);
        const paid = transfers.filter(([a, b]) => a === '99979100001' && b === '999 7910 0042');
        assert.equal(paid.length, 4);
        assert.ok(paid.every(([, , , m]) => Number(m) >= 400), 'the lead\'s transfers are the large ones');
        assert.equal(transfers.filter(([, b]) => b === '99979100042').length, 0, 'in transfers.csv the lead is only ever spaced');
    } finally { rmSync(g.dir, { recursive: true }); }
});

test('the mess the video points at is there', () => {
    const g = generate();
    try {
        const lines = g.calls.trim().split('\n').slice(1), calls = rows(g.calls);
        assert.ok(calls.some(([a]) => a.startsWith('+')), 'a caller with a leading +');
        assert.ok(calls.some(([, b]) => b === ''), 'a blank callee');
        assert.ok(lines.some((l, i) => i > 0 && l === lines[i - 1]), 'a repeated row');
        assert.ok(calls.some(([, , t]) => /^\d\d\/\d\d\/\d{4} \d\d:\d\d$/.test(t)), 'a start time in the second format');
        assert.ok(rows(g.transfers).some(([, b]) => b === ''), 'a blank receiver');
    } finally { rmSync(g.dir, { recursive: true }); }
});
