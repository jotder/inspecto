#!/usr/bin/env node
// check-backlog-items — every open row on docs/BACKLOG.md names the Product Map Item(s) it belongs to.
//
//   node tools/check-backlog-items.mjs      exit 1 on any finding
//
// The board is the ONLY list of open work; each hub's "Open work" section is generated from it by
// tools/render-product-docs.mjs (operator 2026-10-19: one board, tagged rows, never a second hand-kept list).
// A ranked row in §3–§5 ends its first line with `[items: <id>, <id> · kind: <kind> · hold: <hold>]`
// (kind and hold optional).
// CHECKS  1 every ranked row in §3–§5 carries the tag with ≥1 item
//         2 every item id is a Product Map Item (product-map/<kind>/<name>.toon)
//         3 kind and hold, when given, are from KINDS and HOLDS; no other field
// A floor on the parsed row count keeps a grammar change from turning this into a guard that cannot fire.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

export const KINDS = ['feature', 'defect', 'chore'];
/** Why a row is not startable now: an operator call, the outside world, or a customer / consumer that must ask first. */
export const HOLDS = ['operator', 'external', 'demand'];
const ROW = /^- \*\*(P[123])\*\* · (.*)$/;
const TAG = /\s\[(items:[^\]]*)\]\s*$/;
const ID = /^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\d+$/;
const MIN_ROWS = 10;

const plain = (s) => s.replace(/\*\*|`|~~/g, '').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').trim();

/** Parse the board's §3–§5 ranked rows: { line, priority, name, title, items, kind, hold, tagged, extra }. */
export function parseBoard(text) {
    const lines = text.split(/\r?\n/);
    const from = lines.findIndex((l) => /^## 3\. /.test(l)), to = lines.findIndex((l) => /^## 6\. /.test(l));
    if (from < 0 || to < from) throw new Error('docs/BACKLOG.md: no "## 3." … "## 6." span — re-anchor this parse');
    const rows = [];
    for (let i = from + 1; i < to; i++) {
        const m = ROW.exec(lines[i]);
        if (!m) continue;
        const t = TAG.exec(m[2]);
        const head = t ? m[2].slice(0, t.index) : m[2];
        const lead = /^(?:`([^`]+)`|\*\*([^*]+)\*\*)/.exec(head);
        const name = lead ? (lead[1] ?? lead[2]).trim() : '';
        const rest = lead ? head.slice(lead[0].length) : head;
        // A backticked lead (or an id-shaped bold one) is the row's name; any other bold lead is its title.
        const coined = Boolean(lead?.[1]) || ID.test(name);
        const bold = /^\s*—\s*\*\*(.+?)\*\*/.exec(rest)?.[1];
        let title = plain(coined ? bold ?? rest.replace(/^\s*—\s*/, '').split(/\. |; /)[0] : name);
        if (title.length > 140) title = `${title.slice(0, 137).trimEnd()}…`;
        const fields = {};
        for (const part of t ? t[1].split('·') : []) {
            const kv = /^\s*([a-z]+):\s*(.*?)\s*$/.exec(part);
            fields[kv ? kv[1] : part.trim()] = kv ? kv[2] : '';
        }
        const { items = '', kind = '', hold = '', ...extra } = fields;
        rows.push({
            line: i + 1, priority: m[1], name: coined ? name : null, title, tagged: Boolean(t), extra: Object.keys(extra),
            items: items.split(',').map((s) => s.trim()).filter(Boolean), kind: kind || null, hold: hold || null,
        });
    }
    return rows;
}

/** rows: parseBoard output; known: Set of Product Map Item ids. Returns findings. */
export function checkRows(rows, known) {
    const findings = [];
    for (const r of rows) {
        const at = `line ${r.line} (${r.name ?? r.title.slice(0, 40)})`;
        if (!r.tagged) { findings.push(`${at}: no [items: … · kind: … · hold: …] tag at the end of the row's first line`); continue; }
        if (!r.items.length) findings.push(`${at}: the tag names no item`);
        for (const it of r.items) if (!known.has(it)) findings.push(`${at}: '${it}' is not a Product Map Item (product-map/<kind>/<name>.toon)`);
        if (r.kind !== null && !KINDS.includes(r.kind)) findings.push(`${at}: kind '${r.kind}' is not one of ${KINDS.join(' | ')}`);
        if (r.hold !== null && !HOLDS.includes(r.hold)) findings.push(`${at}: hold '${r.hold}' is not one of ${HOLDS.join(' | ')}`);
        for (const x of r.extra) findings.push(`${at}: unknown tag field '${x}' (items, kind, hold)`);
    }
    return findings;
}

/** Every Product Map Item id under root/product-map (the `private` overlay excluded). */
export function productMapIds(root = '.') {
    const map = join(root, 'product-map'), ids = new Set();
    for (const k of readdirSync(map, { withFileTypes: true }).filter((d) => d.isDirectory() && d.name !== 'private'))
        for (const f of readdirSync(join(map, k.name)).filter((f) => f.endsWith('.toon'))) ids.add(`${k.name}.${f.slice(0, -5)}`);
    return ids;
}

function main() {
    const file = 'docs/BACKLOG.md';
    if (!existsSync(file)) { console.error(`✗ ${file} not found — run from the repository root.`); process.exit(1); }
    const rows = parseBoard(readFileSync(file, 'utf8'));
    const findings = rows.length < MIN_ROWS ? [`only ${rows.length} ranked row(s) parsed in §3–§5, below the floor of ${MIN_ROWS}`] : [];
    findings.push(...checkRows(rows, productMapIds()));
    if (findings.length) {
        console.error(`✗ Backlog items guard: ${findings.length} finding(s) in ${file}:\n  - ${findings.join('\n  - ')}`);
        process.exit(1);
    }
    const n = (k, v) => rows.filter((r) => r[k] === v).length;
    console.log(`✓ Backlog items guard: ${rows.length} row(s) tagged with Product Map Items; `
        + `${KINDS.map((k) => `${k} ${n('kind', k)}`).join(' · ')}; held: ${HOLDS.map((h) => `${h} ${n('hold', h)}`).join(' · ')}.`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
