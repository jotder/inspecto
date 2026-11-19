// Tests for tools/roll-backlog-ledger.mjs. Run: node --test tools/roll-backlog-ledger.test.mjs
//
// The roll moves exactly the entries before the cut-off, continuation lines with them, verbatim and newest first, to the
// top of the archive; it keeps the count line, the `> ⚠` lines and the board below byte-for-byte; it re-points links for
// the archive folder; and a second roll with the same cut-off changes nothing.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { keepFrom, newArchive, repoint, roll } from './roll-backlog-ledger.mjs';

const ARCH = 'archived-documents/backlog-ledger-2026.md';
const BOARD = [
    '# Backlog',
    '',
    '> **3<!--count:backlog-rows--> rows: 0<!--count:backlog-p1--> × P1** —',
    '> ⬆ **2 → 3 on 2026-11-19**: filed `A-1` — see [plan](superpower/a.md).',
    '> ↔ **2 on 2026-11-18**: narrowed `B-1`',
    '> continued text of the 18th',
    '> ⬇ **3 → 2 on 2026-11-10**: closed `C-1` — [hub](okf/product/x/spec-x.md#part)',
    '> continued text of the 10th',
    '> ↔ **2026-10-18**: undated-style entry',
    '> ⚠ **Report the 0<!--count:backlog-p1--> P1 rows** — stays',
    '',
    '## Area index',
    '- **P2** · `A-1` — row',
].join('\n');

test('keepFrom: today and the days before it', () => {
    assert.equal(keepFrom('2026-11-19', 3), '2026-11-17');
    assert.equal(keepFrom('2026-03-01', 2), '2026-02-28');
    assert.equal(keepFrom('2026-11-19', 1), '2026-11-19');
});

test('repoint: docs-relative links resolve from the archive folder; external and anchors untouched', () => {
    const out = repoint('[a](superpower/a.md) [b](okf/x.md#h) [c](archived-documents/) [d](https://x) [e](#top)', 'docs', 'docs/archived-documents');
    assert.equal(out, '[a](../superpower/a.md) [b](../okf/x.md#h) [c](./) [d](https://x) [e](#top)');
});

test('roll: moves the old entries with their continuation lines, verbatim, newest first, re-pointed', () => {
    const r = roll(BOARD, newArchive('2026'), '2026-11-17', ARCH);
    assert.equal(r.moved, 2);
    assert.equal(r.kept, 2);
    assert.ok(r.board.includes('> ↔ **2 on 2026-11-18**: narrowed `B-1`\n> continued text of the 18th'));
    assert.ok(!r.board.includes('closed `C-1`'));
    assert.ok(!r.board.includes('undated-style entry'));
    assert.ok(r.archive.includes('> ⬇ **3 → 2 on 2026-11-10**: closed `C-1` — [hub](../okf/product/x/spec-x.md#part)\n> continued text of the 10th\n> ↔ **2026-10-18**'));
});

test('roll: the count line, the ⚠ lines and the board below are byte-for-byte; the pointer names the cut-off', () => {
    const r = roll(BOARD, newArchive('2026'), '2026-11-17', ARCH);
    const lines = r.board.split('\n');
    assert.equal(lines[2], BOARD.split('\n')[2]);
    assert.match(lines[3], /^> Ledger entries dated before 2026-11-17 are kept verbatim in \[`archived-documents\/backlog-ledger-2026\.md`\]/);
    assert.ok(r.board.includes('> ⚠ **Report the 0<!--count:backlog-p1--> P1 rows** — stays'));
    assert.ok(r.board.endsWith('## Area index\n- **P2** · `A-1` — row'));
});

test('roll: later rolls add at the top of an existing archive; a repeat roll changes nothing', () => {
    const first = roll(BOARD, newArchive('2026'), '2026-11-17', ARCH);
    const second = roll(first.board, first.archive, '2026-11-19', ARCH);
    assert.equal(second.moved, 1);
    const a = second.archive.split('\n');
    const firstEntry = a.findIndex((l) => l.startsWith('> ↔') || l.startsWith('> ⬆') || l.startsWith('> ⬇'));
    assert.match(a[firstEntry], /on 2026-11-18/);
    const again = roll(second.board, second.archive, '2026-11-19', ARCH);
    assert.equal(again.moved, 0);
    assert.equal(again.board, second.board);
    assert.equal(again.archive, second.archive);
});

test('roll: a board without its anchors is refused', () => {
    assert.throws(() => roll('# nothing here', newArchive('2026'), '2026-11-17', ARCH), /count line/);
});
