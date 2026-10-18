#!/usr/bin/env node
// product-map-import — brings edits made in the generated CSV or Excel workbook (tools/render-product-map.mjs) back into the Product Map
// (docs/superpower/product-map-plan.md, PRODUCT-MAP-1 slice S4). Only COMMERCIAL columns are imported; prices go to the gitignored
// product-map/private/prices.csv (D-PM3), never into a committed file.
//
//   node tools/product-map-import.mjs <file.csv|file.xlsx> [--sheet "Items"] [--write] [--root .]
//
// DRY RUN by default: prints every change. --write applies them, and only when the changed tree still passes check-product-map.
// ROWS        matched by `id`; a sheet without an id column (the Price list) is matched by `orderCode`. Rows left out change nothing.
// IMPORTED    title, billable, orderCode, billingModel, supportTier, supportOwner, status (an empty cell removes the field, except title),
//             listPrice + currency (to private/prices.csv, keyed by the row's Order Code after the import).
// REFUSED     a changed kind, parent, link, enforcement or docs (engineering fields: edit the item file) and an unknown id or Order Code;
//             any refusal stops the whole import.
// IGNORED     the derived columns editions and modules (noted when edited).
// XLSX        read with node:zlib (Excel saves deflated entries and shared strings); no npm dependency.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { inflateRawSync } from 'node:zlib';
import { parseToon } from './check-offerings.mjs';
import { check, gather } from './check-product-map.mjs';
import { readPrices, rows } from './render-product-map.mjs';

const STRUCTURAL = ['kind', 'parent', 'link', 'enforcement', 'docs'];
const DERIVED = ['editions', 'modules'];
const COMMERCIAL = { title: ['title'], billable: ['billable'], orderCode: ['orderCode'], billingModel: ['billing', 'model'],
    supportTier: ['support', 'tier'], supportOwner: ['support', 'owner'], status: ['status'] };
const ORDER = ['id', 'kind', 'title', 'parent', 'module', 'offering', 'offeringAddon', 'template', 'industryPack', 'billable', 'orderCode',
    'enforcement', 'billing', 'support', 'status', 'docs'];

// ---------- readers ----------
/** RFC-4180 CSV (quotes, doubled quotes, CRLF, a leading BOM) -> array of row objects keyed by header. */
export function parseCsv(text) {
    const recs = []; let row = [], cell = '', q = false;
    const s = text.replace(/^﻿/, '');
    for (let i = 0; i < s.length; i++) {
        const ch = s[i];
        if (q) { if (ch === '"') { if (s[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += ch; }
        else if (ch === '"') q = true;
        else if (ch === ',') { row.push(cell); cell = ''; }
        else if (ch === '\n' || ch === '\r') { if (ch === '\r' && s[i + 1] === '\n') i++; row.push(cell); recs.push(row); row = []; cell = ''; }
        else cell += ch;
    }
    if (cell !== '' || row.length) { row.push(cell); recs.push(row); }
    const [head, ...body] = recs.filter((r) => r.some((c) => c.trim() !== ''));
    return body.map((r) => Object.fromEntries(head.map((h, i) => [h.trim(), (r[i] ?? '').trim()])));
}

/** zip bytes -> { name: Buffer } (stored or deflated entries, read from the central directory). */
export function unzip(buf) {
    let end = buf.length - 22;
    while (end >= 0 && buf.readUInt32LE(end) !== 0x06054b50) end--;
    if (end < 0) throw new Error('not a zip file');
    const n = buf.readUInt16LE(end + 10); let p = buf.readUInt32LE(end + 16); const out = {};
    for (let i = 0; i < n; i++) {
        const method = buf.readUInt16LE(p + 10), size = buf.readUInt32LE(p + 20), nl = buf.readUInt16LE(p + 28), el = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32);
        const name = buf.subarray(p + 46, p + 46 + nl).toString('utf8'); const lo = buf.readUInt32LE(p + 42);
        const start = lo + 30 + buf.readUInt16LE(lo + 26) + buf.readUInt16LE(lo + 28); const data = buf.subarray(start, start + size);
        out[name] = method === 0 ? data : method === 8 ? inflateRawSync(data) : (() => { throw new Error(`${name}: zip method ${method} unsupported`); })();
        p += 46 + nl + el + cl;
    }
    return out;
}

const unxml = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d)).replace(/&amp;/g, '&');
const textOf = (x) => [...x.matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g)].map((m) => unxml(m[1])).join('');
const colIndex = (ref) => [...ref.replace(/\d+$/, '')].reduce((n, c) => n * 26 + c.charCodeAt(0) - 64, 0) - 1;

/** .xlsx bytes + sheet name -> array of row objects keyed by the header row. */
export function parseXlsx(buf, sheet = 'Items') {
    const z = unzip(buf); const str = (n) => z[n]?.toString('utf8');
    const wb = str('xl/workbook.xml'); const rels = str('xl/_rels/workbook.xml.rels');
    const tag = [...wb.matchAll(/<sheet\b[^>]*>/g)].map((m) => m[0]).find((t) => unxml(/name="([^"]*)"/.exec(t)[1]) === sheet);
    if (!tag) throw new Error(`sheet '${sheet}' not found`);
    const rid = /r:id="([^"]*)"/.exec(tag)[1];
    const target = [...rels.matchAll(/<Relationship\b[^>]*>/g)].map((m) => m[0]).find((t) => t.includes(`Id="${rid}"`)).match(/Target="([^"]*)"/)[1];
    const path = target.startsWith('/') ? target.slice(1) : `xl/${target}`;
    const shared = [...(str('xl/sharedStrings.xml') ?? '').matchAll(/<si>([\s\S]*?)<\/si>/g)].map((m) => textOf(m[1]));
    const grid = [...str(path).matchAll(/<row\b[^>]*>([\s\S]*?)<\/row>/g)].map((rm) => {
        const r = [];
        for (const cm of rm[1].matchAll(/<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
            const attrs = cm[1]; const inner = cm[2] ?? ''; const ref = /r="([A-Z]+\d+)"/.exec(attrs)?.[1]; const t = /t="([^"]*)"/.exec(attrs)?.[1];
            const v = /<v>([\s\S]*?)<\/v>/.exec(inner)?.[1];
            r[ref ? colIndex(ref) : r.length] = t === 's' ? shared[+v] : t === 'inlineStr' ? textOf(inner) : t === 'b' ? (v === '1' ? 'TRUE' : 'FALSE') : unxml(v ?? '');
        }
        return Array.from(r, (c) => c ?? '');
    });
    const [head, ...body] = grid.filter((r) => r.some((c) => String(c).trim() !== ''));
    return body.map((r) => Object.fromEntries(head.map((h, i) => [String(h).trim(), String(r[i] ?? '').trim()])));
}

// ---------- the import ----------
const norm = (k, v) => (k === 'billable' ? String(v).toLowerCase() : v); // Excel shows true/false as TRUE/FALSE

/** files: { 'kind/name': toon }, sheetRows, current prices -> { files, prices, changes[], refusals[], notes[] }. Pure. */
export function plan(files, world, sheetRows, prices = {}) {
    const current = rows(files, world, prices);
    const byId = Object.fromEntries(current.map((r) => [r.id, r]));
    const byCode = Object.fromEntries(current.filter((r) => r.orderCode).map((r) => [r.orderCode, r]));
    const relOf = Object.fromEntries(Object.entries(files).map(([rel, t]) => [parseToon(t).id, rel]));
    const items = Object.fromEntries(Object.entries(files).map(([rel, t]) => [rel, parseToon(t)]));
    const changes = [], refusals = [], notes = [];
    const newPrices = { ...prices };
    sheetRows.forEach((row, i) => {
        const line = `row ${i + 2}`;
        const cur = 'id' in row ? byId[row.id] : byCode[row.orderCode];
        if (!cur) { refusals.push(`${line}: ${'id' in row ? `id '${row.id}'` : `orderCode '${row.orderCode}'`} is not in the Product Map`); return; }
        for (const k of STRUCTURAL) if (k in row && row[k] !== cur[k]) refusals.push(`${line} ${cur.id}: '${k}' changed ('${cur[k]}' -> '${row[k]}'); edit product-map/${relOf[cur.id]}.toon instead`);
        for (const k of DERIVED) if (k in row && row[k] !== cur[k]) notes.push(`${line} ${cur.id}: '${k}' is derived from offerings/*.toon; the edit is ignored`);
        const it = items[relOf[cur.id]];
        for (const [col, path] of Object.entries(COMMERCIAL)) {
            if (!(col in row) || (col === 'orderCode' && !('id' in row))) continue;
            const v = norm(col, row[col]);
            if (v === cur[col]) continue;
            if (col === 'title' && v === '') { refusals.push(`${line} ${cur.id}: title cannot be empty`); continue; }
            const [a, b] = path;
            if (b) { if (v === '') { if (it[a]) delete it[a][b]; if (it[a] && !Object.keys(it[a]).length) delete it[a]; } else (it[a] ??= {})[b] = v; }
            else if (v === '') delete it[a]; else it[a] = v;
            changes.push(`${cur.id}: ${col} '${cur[col]}' -> '${v}'`);
        }
        const code = it.orderCode ?? '';
        if (('listPrice' in row || 'currency' in row) && (row.listPrice !== cur.listPrice || row.currency !== cur.currency)) {
            if (!code) { if (row.listPrice || row.currency) refusals.push(`${line} ${cur.id}: a price needs an Order Code`); return; }
            if (row.listPrice !== '' && Number.isNaN(Number(row.listPrice))) { refusals.push(`${line} ${cur.id}: listPrice '${row.listPrice}' is not a number`); return; }
            if (row.listPrice === '' && row.currency === '') delete newPrices[code];
            else newPrices[code] = { orderCode: code, listPrice: row.listPrice ?? '', currency: row.currency ?? '' };
            changes.push(`${code}: price '${cur.listPrice} ${cur.currency}' -> '${row.listPrice ?? ''} ${row.currency ?? ''}' (private/prices.csv)`);
        }
    });
    const out = {};
    for (const [rel, it] of Object.entries(items)) out[rel] = serialize(it);
    return { files: out, prices: newPrices, changes, refusals, notes };
}

/** An item -> TOON in the canonical key order (the seed's), so an import changes only the lines it edits. */
export function serialize(it) {
    const keys = [...ORDER.filter((k) => k in it), ...Object.keys(it).filter((k) => !ORDER.includes(k))];
    return keys.map((k) => (it[k] && typeof it[k] === 'object' ? `${k}:\n${Object.entries(it[k]).map(([ck, cv]) => `  ${ck}: ${cv}`).join('\n')}` : `${k}: ${it[k]}`)).join('\n') + '\n';
}
export const pricesCsv = (p) => ['orderCode,listPrice,currency', ...Object.values(p).sort((a, b) => a.orderCode.localeCompare(b.orderCode)).map((r) => `${r.orderCode},${r.listPrice},${r.currency}`)].join('\r\n') + '\r\n';

function main() {
    const args = process.argv.slice(2);
    const arg = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
    const root = arg('--root', '.'); const input = args.find((a, i) => !a.startsWith('--') && !['--sheet', '--root'].includes(args[i - 1]));
    if (!input) { console.error('usage: node tools/product-map-import.mjs <file.csv|file.xlsx> [--sheet Items] [--write]'); process.exit(2); }
    const sheetRows = input.toLowerCase().endsWith('.xlsx') ? parseXlsx(readFileSync(input), arg('--sheet', 'Items')) : parseCsv(readFileSync(input, 'utf8'));
    const [files, world] = gather(root);
    const pf = join(root, 'product-map', 'private', 'prices.csv');
    const prices = existsSync(pf) ? readPrices(readFileSync(pf, 'utf8')) : {};
    const r = plan(files, world, sheetRows, prices);
    for (const n of r.notes) console.log(`note  ${n}`);
    for (const c of r.changes) console.log(`change ${c}`);
    for (const f of r.refusals) console.error(`✗ ${f}`);
    if (r.refusals.length) { console.error(`product-map-import: REFUSED (${r.refusals.length}); nothing written`); process.exit(1); }
    const findings = check(r.files, world);
    if (findings.length) { for (const f of findings) console.error(`✗ check-product-map: ${f}`); console.error('product-map-import: the result fails check-product-map; nothing written'); process.exit(1); }
    if (!args.includes('--write')) { console.log(`product-map-import: ${r.changes.length} change(s); dry run, add --write to apply`); return; }
    let n = 0;
    for (const [rel, text] of Object.entries(r.files)) if (text !== files[rel]) { writeFileSync(join(root, 'product-map', `${rel}.toon`), text); n++; }
    if (pricesCsv(r.prices) !== pricesCsv(prices)) { mkdirSync(join(root, 'product-map', 'private'), { recursive: true }); writeFileSync(pf, pricesCsv(r.prices)); }
    console.log(`product-map-import: ${r.changes.length} change(s) written (${n} item file(s)${pricesCsv(r.prices) !== pricesCsv(prices) ? ', private/prices.csv' : ''})`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
