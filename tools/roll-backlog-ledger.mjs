#!/usr/bin/env node
// roll-backlog-ledger — keep the board's ledger short: move older entries, verbatim, into the archive ledger
// (INFO-RESTRUCTURE-1 D-IR2; docs/okf/product/layer/platform/spec-docs-maintenance.md §4).
//
//   node tools/roll-backlog-ledger.mjs [--days 3] [--today YYYY-MM-DD] [--write]
//
// The ledger is the blockquote between docs/BACKLOG.md's count line and "## Area index": one entry per board change,
// starting `> ⬆`, `> ⬇` or `> ↔`, its continuation lines starting `> `. An entry dated before the kept window — today
// and the (days - 1) days before it — moves to the TOP of docs/archived-documents/backlog-ledger-<year of today>.md,
// newest first as on the board, with its relative links re-pointed for the archive folder. Nothing else changes:
// the count line, the `> ⚠` lines and the board below stay byte-for-byte; the pointer line under the count line
// names the new cut-off. Dry run unless --write. The `handoff` skill runs it at shift end; tools/docs-health.mjs
// fails a commit only when the ledger passes 60 KB.
//
// Tests: tools/roll-backlog-ledger.test.mjs.

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const P = path.posix;
const ENTRY = /^> (⬆|⬇|↔)/;
const POINTER = /^> Ledger entries dated before \d{4}-\d{2}-\d{2} /;

/** Re-point relative markdown link targets written for `fromDir` so they resolve from `toDir` (repo-relative). */
export function repoint(text, fromDir, toDir) {
    return text.replace(/\]\(([^)\s]+)\)/g, (m, target) => {
        if (/^(https?:|mailto:|#|\/)/.test(target)) return m;
        const hash = target.indexOf('#');
        const file = hash >= 0 ? target.slice(0, hash) : target;
        const frag = hash >= 0 ? target.slice(hash) : '';
        if (!file) return m;
        let rel = P.relative(toDir, P.normalize(P.join(fromDir, file)));
        if (rel === '') rel = './';
        else if (file.endsWith('/') && !rel.endsWith('/')) rel += '/';
        return '](' + rel + frag + ')';
    });
}

/** The first day kept: `today` minus (days - 1). */
export function keepFrom(today, days) {
    const d = new Date(today + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() - (days - 1));
    return d.toISOString().slice(0, 10);
}

/** A fresh archive ledger for `year`. */
export function newArchive(year) {
    return [
        `# Board ledger — ${year} (rolled from \`docs/BACKLOG.md\`)`,
        '',
        '> **History tier — not maintained.** Each line below is a ledger entry from the header of',
        '> [`../BACKLOG.md`](../BACKLOG.md), moved here verbatim (newest first, as on the board) by',
        '> `tools/roll-backlog-ledger.mjs`; relative links were re-pointed for this folder. Later rolls add their entries at the top.',
        '',
        '',
    ].join('\n');
}

/**
 * Roll the ledger. Pure: board text + archive text in, new texts out.
 * @returns {{ board: string, archive: string, moved: number, kept: number }}
 */
export function roll(boardText, archiveText, cutoff, archiveRel) {
    const eol = boardText.includes('\r\n') ? '\r\n' : '\n';
    const lines = boardText.split(/\r?\n/);
    const countLine = lines.findIndex((l) => l.includes('<!--count:backlog-rows-->'));
    const areaIndex = lines.findIndex((l) => /^## Area index/.test(l));
    if (countLine < 0 || areaIndex < 0 || areaIndex < countLine) throw new Error('docs/BACKLOG.md: count line or "## Area index" not found');
    const keep = [], moved = [];
    let cur = null;
    for (let i = countLine + 1; i < areaIndex; i++) {
        const l = lines[i];
        if (POINTER.test(l)) { cur = null; continue; } // rewritten below
        if (ENTRY.test(l)) {
            const date = (l.match(/\d{4}-\d{2}-\d{2}/) || [])[0];
            if (!date) throw new Error(`docs/BACKLOG.md:${i + 1}: a ledger entry without a date`);
            cur = { lines: [l] };
            (date < cutoff ? moved : keep).push(cur);
            continue;
        }
        if (cur && l.startsWith('> ') && !l.startsWith('> ⚠')) { cur.lines.push(l); continue; }
        cur = null;
        keep.push({ lines: [l], other: true });
    }
    const pointer = `> Ledger entries dated before ${cutoff} are kept verbatim in ` +
        `[\`${archiveRel}\`](${archiveRel}); git history has every change. The board keeps the last days of ledger ` +
        '(`tools/roll-backlog-ledger.mjs`, `INFO-RESTRUCTURE-1` D-IR2).';
    const board = [...lines.slice(0, countLine + 1), pointer, ...keep.flatMap((e) => e.lines), ...lines.slice(areaIndex)].join(eol);
    let archive = archiveText;
    if (moved.length) {
        const block = repoint(moved.flatMap((e) => e.lines).join('\n'), 'docs', P.dirname(P.join('docs', archiveRel)));
        const a = archiveText.split('\n');
        let at = a.findIndex((l) => ENTRY.test(l));
        if (at < 0) at = a.length;
        archive = [...a.slice(0, at), ...block.split('\n'), ...a.slice(at)].join('\n');
        if (!archive.endsWith('\n')) archive += '\n';
    }
    return { board, archive, moved: moved.length, kept: keep.filter((e) => !e.other).length };
}

function main() {
    const args = process.argv.slice(2);
    const opt = (name, dflt) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : dflt; };
    const days = Number(opt('--days', '3'));
    const today = opt('--today', new Date().toISOString().slice(0, 10));
    if (!Number.isInteger(days) || days < 1 || !/^\d{4}-\d{2}-\d{2}$/.test(today)) {
        console.error('usage: node tools/roll-backlog-ledger.mjs [--days N>=1] [--today YYYY-MM-DD] [--write]');
        process.exit(2);
    }
    const cutoff = keepFrom(today, days);
    const archiveRel = `archived-documents/backlog-ledger-${today.slice(0, 4)}.md`;
    const BOARD = 'docs/BACKLOG.md', ARCH = P.join('docs', archiveRel);
    if (!existsSync(BOARD)) { console.error('✗ run from the repository root'); process.exit(2); }
    const boardText = readFileSync(BOARD, 'utf8');
    const archiveText = existsSync(ARCH) ? readFileSync(ARCH, 'utf8') : newArchive(today.slice(0, 4));
    const r = roll(boardText, archiveText, cutoff, archiveRel);
    const kb = (s) => (Buffer.byteLength(s) / 1024).toFixed(0) + ' KB';
    if (!args.includes('--write')) {
        console.log(`roll-backlog-ledger (dry run): keep from ${cutoff}; would move ${r.moved}, keep ${r.kept}; board ${kb(boardText)} → ${kb(r.board)}. Add --write to apply.`);
        return;
    }
    if (r.moved === 0 && r.board === boardText) { console.log(`roll-backlog-ledger: nothing older than ${cutoff}; board unchanged.`); return; }
    writeFileSync(BOARD, r.board, 'utf8');
    if (r.moved) writeFileSync(ARCH, r.archive, 'utf8');
    console.log(`roll-backlog-ledger: moved ${r.moved} entr(y/ies) dated before ${cutoff} to ${ARCH}; kept ${r.kept}; board ${kb(boardText)} → ${kb(r.board)}.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
