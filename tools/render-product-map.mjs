#!/usr/bin/env node
// render-product-map — the generated views of the Product Map (docs/superpower/product-map-plan.md, PRODUCT-MAP-1 slice S3):
// a CSV, an Excel workbook and a standalone collapsible mind-map page, written to target/product-map/ (gitignored, never committed).
//
//   node tools/render-product-map.mjs [--root .] [--out target/product-map]
//
// DERIVED, never hand-kept: the Editions an item is in (from offerings/*.toon, `includes` resolved; a base/internal module is in
// every Edition), an add-on's modules, a module's offering role. PRICES come only from product-map/private/prices.csv
// (`orderCode,listPrice,currency`, gitignored, D-PM3); without that file the price columns are empty.
// XLSX is written with a built-in zip writer (stored entries + CRC-32) so the guards job needs no npm dependency.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { gather } from './check-product-map.mjs';
import { parseToon } from './check-offerings.mjs';

const TIERS = ['basic', 'standard', 'advanced', 'preview'];
const byTier = (x, y) => TIERS.indexOf(x) - TIERS.indexOf(y);

export const COLUMNS = ['id', 'kind', 'title', 'parent', 'link', 'billable', 'orderCode', 'enforcement', 'billingModel', 'supportTier',
    'supportOwner', 'status', 'editions', 'modules', 'listPrice', 'currency', 'docs'];

/** Parsed items + world + prices -> rows (one per item, COLUMNS keys), sorted by kind order then id. */
export function rows(files, world, prices = {}) {
    const { manifests, offerings } = world;
    const items = Object.values(files).map((t) => parseToon(t));
    const resolved = (id, seen = new Set()) => {
        if (seen.has(id)) return { modules: new Set(), addons: new Set(), packs: new Set() };
        seen.add(id);
        const o = offerings[id];
        const acc = { modules: new Set(o.modules ?? []), addons: new Set(Object.keys(o.addons ?? {})), packs: new Set(o.contentPacks ?? []) };
        for (const inc of o.includes ?? []) { const r = resolved(inc, seen); for (const k of Object.keys(acc)) r[k].forEach((x) => acc[k].add(x)); }
        return acc;
    };
    const ed = Object.fromEntries(Object.keys(offerings).map((id) => [id, resolved(id)]));
    const addonModules = {};
    for (const o of Object.values(offerings)) for (const [n, a] of Object.entries(o.addons ?? {})) addonModules[n] = a.modules ?? a.hostedBy ?? [];
    const editionsOf = (it) => Object.keys(offerings).sort(byTier).filter((e) => {
        const r = ed[e];
        if (it.offering) return it.offering === e;
        if (it.offeringAddon) return r.addons.has(it.offeringAddon);
        if (it.template) return r.packs.has(it.template);
        if (it.industryPack) return true; // Industry packs ship inside every edition
        if (it.module) { const role = manifests[it.module]?.offeringRole; return role === 'base' || role === 'internal' || r.modules.has(it.module); }
        return false;
    });
    const order = ['layer', 'module', 'functionality', 'addon', 'solution-pack', 'edition'];
    return items.map((it) => ({
        id: it.id, kind: it.kind, title: it.title ?? '', parent: it.parent ?? '',
        link: it.module ?? it.offeringAddon ?? it.template ?? it.industryPack ?? it.offering ?? '',
        billable: it.billable ?? '', orderCode: it.orderCode ?? '', enforcement: it.enforcement ?? '', billingModel: it.billing?.model ?? '',
        supportTier: it.support?.tier ?? '', supportOwner: it.support?.owner ?? '', status: it.status ?? '',
        editions: editionsOf(it).join(';'), modules: it.offeringAddon ? (addonModules[it.offeringAddon] ?? []).join(';') : '',
        listPrice: prices[it.orderCode]?.listPrice ?? '', currency: prices[it.orderCode]?.currency ?? '', docs: it.docs ?? '',
    })).sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind) || a.id.localeCompare(b.id));
}

/** product-map/private/prices.csv -> { orderCode: { listPrice, currency } }. */
export function readPrices(text) {
    const [head, ...lines] = text.split(/\r?\n/).filter((l) => l.trim());
    const cols = head.split(',').map((s) => s.trim());
    return Object.fromEntries(lines.map((l) => { const v = l.split(',').map((s) => s.trim()); const r = Object.fromEntries(cols.map((c, i) => [c, v[i] ?? ''])); return [r.orderCode, r]; }));
}

const csvCell = (v) => (/[",\r\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
export const toCsv = (rs) => [COLUMNS.join(','), ...rs.map((r) => COLUMNS.map((c) => csvCell(r[c])).join(','))].join('\r\n') + '\r\n';

// ---------- XLSX (SpreadsheetML in a stored zip) ----------
const xml = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const colName = (i) => { let s = ''; for (i++; i; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s; return s; };
function sheetXml(table) {
    const body = table.map((row, r) => `<row r="${r + 1}">${row.map((v, c) => {
        const ref = `${colName(c)}${r + 1}`;
        if (typeof v === 'number') return `<c r="${ref}"><v>${v}</v></c>`;
        return `<c r="${ref}" t="inlineStr"${r === 0 ? ' s="1"' : ''}><is><t>${xml(v)}</t></is></c>`;
    }).join('')}</row>`).join('');
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>${body}</sheetData>${table.length > 1 ? `<autoFilter ref="A1:${colName(table[0].length - 1)}${table.length}"/>` : ''}</worksheet>`;
}
const CRC = new Uint32Array(256).map((_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc32 = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
/** Minimal zip: stored (uncompressed) entries, which every XLSX reader accepts. */
export function zip(entries) {
    const parts = []; const central = []; let off = 0;
    for (const [name, text] of entries) {
        const data = Buffer.from(text, 'utf8'); const nm = Buffer.from(name, 'utf8'); const crc = crc32(data);
        const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0800, 6);
        local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(nm.length, 26);
        const cen = Buffer.alloc(46); cen.writeUInt32LE(0x02014b50, 0); cen.writeUInt16LE(20, 4); cen.writeUInt16LE(20, 6); cen.writeUInt16LE(0x0800, 8);
        cen.writeUInt32LE(crc, 16); cen.writeUInt32LE(data.length, 20); cen.writeUInt32LE(data.length, 24); cen.writeUInt16LE(nm.length, 28); cen.writeUInt32LE(off, 42);
        parts.push(local, nm, data); central.push(cen, nm); off += 30 + nm.length + data.length;
    }
    const cd = Buffer.concat(central); const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
    return Buffer.concat([...parts, cd, end]);
}
/** sheets: [[name, table(rows of cells)]] -> .xlsx bytes. */
export function toXlsx(sheets) {
    const ns = 'http://schemas.openxmlformats.org';
    const files = [
        ['[Content_Types].xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="${ns}/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`],
        ['_rels/.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${ns}/package/2006/relationships"><Relationship Id="rId1" Type="${ns}/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
        ['xl/workbook.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="${ns}/spreadsheetml/2006/main" xmlns:r="${ns}/officeDocument/2006/relationships"><sheets>${sheets.map(([n], i) => `<sheet name="${xml(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets>${sheets.map(([, t], i) => (t.length > 1 ? `<definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="${i}" hidden="1">'${xml(sheets[i][0])}'!$A$1:$${colName(t[0].length - 1)}$${t.length}</definedName></definedNames>` : '')).join('').replace(/<\/definedNames><definedNames>/g, '')}</workbook>`],
        ['xl/_rels/workbook.xml.rels', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${ns}/package/2006/relationships">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${ns}/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId${sheets.length + 1}" Type="${ns}/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
        ['xl/styles.xml', `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="${ns}/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf xfId="0"/><xf fontId="1" applyFont="1" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`],
        ...sheets.map(([, t], i) => [`xl/worksheets/sheet${i + 1}.xml`, sheetXml(t)]),
    ];
    return zip(files);
}
export function workbook(rs, editions) {
    const num = (v) => (v === '' || Number.isNaN(Number(v)) ? v : Number(v));
    const billable = rs.filter((r) => r.billable === 'true');
    return [
        ['Items', [COLUMNS, ...rs.map((r) => COLUMNS.map((c) => (c === 'listPrice' ? num(r[c]) : r[c])))]],
        ['Price list', [['orderCode', 'title', 'kind', 'billingModel', 'enforcement', 'supportTier', 'listPrice', 'currency'],
            ...billable.map((r) => [r.orderCode, r.title, r.kind, r.billingModel, r.enforcement, r.supportTier, num(r.listPrice), r.currency])]],
        ['Edition matrix', [['orderCode', 'title', 'kind', ...editions],
            ...billable.map((r) => [r.orderCode, r.title, r.kind, ...editions.map((e) => (r.editions.split(';').includes(e) ? '✓' : ''))])]],
    ];
}

// ---------- Mind map (standalone HTML, collapsible) ----------
const KIND_TITLE = { module: 'Modules with no layer', functionality: 'Functionalities with no parent', layer: 'Layers and modules', addon: 'Add-ons', 'solution-pack': 'Solution packs', edition: 'Editions' };
export function toHtml(rs, editions) {
    const kids = {};
    for (const r of rs) (kids[r.parent || `kind:${r.kind}`] ??= []).push(r);
    const badge = (t, cls = '') => `<span class="b ${cls}">${xml(t)}</span>`;
    const node = (r) => {
        const meta = [r.billable === 'true' ? badge(r.orderCode, 'code') : badge('not billable', 'muted'),
            r.enforcement && badge(r.enforcement), r.billingModel && badge(r.billingModel),
            r.status !== 'ga' && badge(r.status, 'warn'), r.editions && badge(r.editions.split(';').join(' · '), 'ed'),
            r.listPrice !== '' && badge(`${r.listPrice} ${r.currency}`, 'price')].filter(Boolean).join('');
        const label = `<span class="t">${xml(r.title)}</span> <span class="id">${xml(r.id)}</span>${meta}`;
        const ch = kids[r.id] ?? [];
        return ch.length ? `<li><details><summary>${label} <span class="n">${ch.length}</span></summary><ul>${ch.map(node).join('')}</ul></details></li>` : `<li><div class="leaf">${label}</div></li>`;
    };
    // The four usual roots always show; any other kind shows only when it has parentless items, so nothing is silently dropped.
    const kinds = ['layer', 'addon', 'solution-pack', 'edition', ...['module', 'functionality'].filter((k) => kids[`kind:${k}`])];
    const roots = kinds.map((k) => {
        const ch = kids[`kind:${k}`] ?? [];
        return `<li><details open><summary><span class="t k">${KIND_TITLE[k]}</span> <span class="n">${ch.length}</span></summary><ul>${ch.map(node).join('')}</ul></details></li>`;
    }).join('');
    const nBill = rs.filter((r) => r.billable === 'true').length;
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Product Map</title><style>
:root{--bg:#fbfbfa;--fg:#1d1d1b;--mut:#6b6a66;--line:#d9d8d3;--chip:#efeee9;--acc:#2f5f8a;--warn:#8a5a00;--ok:#2e6b3f}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#1a1a19;--fg:#ecebe6;--mut:#a3a29c;--line:#3a3936;--chip:#2a2a28;--acc:#8db8e0;--warn:#e0b35c;--ok:#8fcf9f}}
:root[data-theme="dark"]{--bg:#1a1a19;--fg:#ecebe6;--mut:#a3a29c;--line:#3a3936;--chip:#2a2a28;--acc:#8db8e0;--warn:#e0b35c;--ok:#8fcf9f}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,sans-serif}
main{max-width:1100px;margin:0 auto;padding:24px 16px}
h1{font-size:22px;margin:0 0 4px}.sub{color:var(--mut);margin:0 0 16px}
.bar{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}
input{flex:1;min-width:200px;padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
button{padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:var(--chip);color:var(--fg);cursor:pointer}
ul{list-style:none;margin:0;padding-left:18px;border-left:1px solid var(--line)}main>ul{border:0;padding:0}
li{margin:2px 0}summary{cursor:pointer}.leaf{padding-left:14px}
.t{font-weight:600}.k{font-size:16px}.id{color:var(--mut);font-size:12px;font-family:ui-monospace,monospace}
.n{color:var(--mut);font-size:12px}
.b{display:inline-block;margin-left:6px;padding:0 6px;border-radius:4px;background:var(--chip);font-size:12px;white-space:nowrap}
.code{color:var(--acc);font-family:ui-monospace,monospace}.muted{color:var(--mut)}.warn{color:var(--warn)}.ed{color:var(--ok)}.price{font-weight:600}
.hide{display:none}
</style></head><body><main>
<h1>Product Map</h1><p class="sub">${rs.length} items · ${nBill} billable · Editions: ${editions.join(', ')} · generated by tools/render-product-map.mjs, do not edit</p>
<div class="bar"><input id="q" type="search" placeholder="Filter by title, id or Order Code" aria-label="Filter"><button id="ex">Expand all</button><button id="co">Collapse all</button></div>
<ul>${roots}</ul></main><script>
const all=[...document.querySelectorAll('details')];
document.getElementById('ex').onclick=()=>all.forEach(d=>d.open=true);
document.getElementById('co').onclick=()=>all.forEach(d=>d.open=false);
document.getElementById('q').oninput=e=>{const q=e.target.value.toLowerCase();
document.querySelectorAll('main li').forEach(li=>{const hit=!q||li.textContent.toLowerCase().includes(q);li.classList.toggle('hide',!hit);if(q&&hit){const d=li.querySelector(':scope>details');if(d)d.open=true}})};
</script></body></html>
`;
}

function main() {
    const args = process.argv.slice(2);
    const arg = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
    const root = arg('--root', '.');
    const out = join(root, arg('--out', join('target', 'product-map')));
    const [files, world] = gather(root);
    const pf = join(root, 'product-map', 'private', 'prices.csv');
    const prices = existsSync(pf) ? readPrices(readFileSync(pf, 'utf8')) : {};
    const rs = rows(files, world, prices);
    const editions = Object.keys(world.offerings).sort(byTier);
    mkdirSync(out, { recursive: true });
    writeFileSync(join(out, 'product-map.csv'), '﻿' + toCsv(rs)); // BOM so Excel reads UTF-8
    writeFileSync(join(out, 'product-map.xlsx'), toXlsx(workbook(rs, editions)));
    writeFileSync(join(out, 'product-map.html'), toHtml(rs, editions));
    console.log(`render-product-map: ${rs.length} items -> ${out} (csv, xlsx, html); prices ${existsSync(pf) ? 'merged from private/prices.csv' : 'absent (no private/prices.csv)'}`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
