// Fixture test for tools/product-map-import.mjs. Run: node --test tools/product-map-import.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deflateRawSync } from 'node:zlib';
import { parseCsv, parseXlsx, plan, pricesCsv, serialize, unzip } from './product-map-import.mjs';
import { readPrices, rows, toCsv, toXlsx, workbook } from './render-product-map.mjs';
import { parseToon } from './check-offerings.mjs';

const world = () => ({
    manifests: { ops: { id: 'ops', offeringRole: 'optional', provides: { features: ['ops'] } } },
    offerings: { basic: { id: 'basic', addons: { incidents: { status: 'built', modules: ['ops'] } } } },
    templates: [], industryPacks: [], docExists: () => true,
});
const files = () => ({
    'layer/features': 'id: layer.features\nkind: layer\ntitle: Features\nbillable: false\nstatus: ga\n',
    'module/ops': 'id: module.ops\nkind: module\ntitle: Ops\nparent: layer.features\nmodule: ops\nbillable: false\nstatus: ga\n',
    'addon/incidents': 'id: addon.incidents\nkind: addon\ntitle: Incidents\nofferingAddon: incidents\nbillable: true\norderCode: INS-AO-INC\nenforcement: build\nbilling:\n  model: flat\nsupport:\n  tier: standard\n  runbook: docs/x.md\nstatus: ga\n',
    'edition/basic': 'id: edition.basic\nkind: edition\ntitle: Basic\noffering: basic\nbillable: false\nstatus: ga\n',
});
/** The exported CSV rows, edited by `edit(rowsById)`, then planned. */
const run = (edit, prices = {}) => {
    const sheet = parseCsv(toCsv(rows(files(), world(), prices)));
    edit?.(Object.fromEntries(sheet.map((r) => [r.id, r])), sheet);
    return plan(files(), world(), sheet, prices);
};

test('an unedited export imports as ZERO changes', () => {
    const r = run();
    assert.deepEqual([r.changes, r.refusals, r.notes], [[], [], []]);
    assert.deepEqual(r.files, files());
});
test('CSV parsing: quotes, doubled quotes, embedded comma and newline, BOM, CRLF', () => {
    assert.deepEqual(parseCsv('﻿a,b\r\n"x, ""y""","1\n2"\r\n'), [{ a: 'x, "y"', b: '1\n2' }]);
});
test('commercial edits land in the item file and change only their own lines', () => {
    const r = run((by) => { by['addon.incidents'].billingModel = 'per-user'; by['addon.incidents'].supportOwner = 'ops-team'; by['addon.incidents'].title = 'Incidents, cases'; });
    assert.equal(r.refusals.length, 0);
    const t = r.files['addon/incidents'];
    assert.match(t, /\n {2}model: per-user\n/);
    assert.match(t, /\nsupport:\n {2}tier: standard\n {2}runbook: docs\/x.md\n {2}owner: ops-team\n/);
    assert.match(t, /\ntitle: Incidents, cases\n/);
    const before = files()['addon/incidents'].split('\n'); const after = t.split('\n');
    assert.deepEqual(after.filter((l) => !before.includes(l)), ['title: Incidents, cases', '  model: per-user', '  owner: ops-team']);
    assert.deepEqual(before.filter((l) => !after.includes(l)), ['title: Incidents', '  model: flat']);
});
test('Excel TRUE/FALSE is read as true/false, and an empty cell removes the field', () => {
    const r = run((by) => { by['module.ops'].billable = 'FALSE'; by['addon.incidents'].supportTier = ''; });
    assert.deepEqual(r.changes, ['addon.incidents: supportTier \'standard\' -> \'\'']);
    assert.doesNotMatch(r.files['addon/incidents'], /tier:/);
});
test('prices go to private/prices.csv, keyed by Order Code, never into an item file', () => {
    const r = run((by) => { by['addon.incidents'].listPrice = '1200'; by['addon.incidents'].currency = 'USD'; });
    assert.deepEqual(r.prices, { 'INS-AO-INC': { orderCode: 'INS-AO-INC', listPrice: '1200', currency: 'USD' } });
    assert.doesNotMatch(Object.values(r.files).join(''), /1200/);
    assert.equal(pricesCsv(r.prices), 'orderCode,listPrice,currency\r\nINS-AO-INC,1200,USD\r\n');
    const cleared = run((by) => { by['addon.incidents'].listPrice = ''; by['addon.incidents'].currency = ''; }, readPrices(pricesCsv(r.prices)));
    assert.deepEqual(cleared.prices, {});
});
test('the Price list sheet (no id column) matches rows by Order Code', () => {
    const sheet = [{ orderCode: 'INS-AO-INC', title: 'Incidents', listPrice: '50', currency: 'EUR' }];
    const r = plan(files(), world(), sheet);
    assert.equal(r.prices['INS-AO-INC'].listPrice, '50');
    assert.match(plan(files(), world(), [{ orderCode: 'INS-NOPE', listPrice: '1' }]).refusals[0], /orderCode 'INS-NOPE' is not in/);
});
test('structural edits, unknown ids, bad prices and an empty title are REFUSED', () => {
    assert.match(run((by) => { by['module.ops'].parent = 'layer.x'; }).refusals.join(), /'parent' changed/);
    assert.match(run((by) => { by['module.ops'].link = 'recon'; }).refusals.join(), /'link' changed/);
    assert.match(run((by) => { by['addon.incidents'].enforcement = 'contract'; }).refusals.join(), /'enforcement' changed/);
    assert.match(run((by) => { by['module.ops'].kind = 'addon'; }).refusals.join(), /'kind' changed/);
    assert.match(run((by, s) => { s.push({ ...by['module.ops'], id: 'module.ghost' }); }).refusals.join(), /id 'module.ghost' is not in/);
    assert.match(run((by) => { by['addon.incidents'].listPrice = 'ten'; }).refusals.join(), /not a number/);
    assert.match(run((by) => { by['module.ops'].listPrice = '5'; }).refusals.join(), /needs an Order Code/);
    assert.match(run((by) => { by['module.ops'].title = ''; }).refusals.join(), /title cannot be empty/);
});
test('derived columns are ignored with a note', () => {
    const r = run((by) => { by['addon.incidents'].editions = 'basic;premium'; });
    assert.deepEqual(r.changes, []);
    assert.match(r.notes.join(), /'editions' is derived/);
});
test('serialize is the canonical order: parse -> serialize is lossless', () => {
    for (const t of Object.values(files())) assert.equal(serialize(parseToon(t)), t);
});
test('XLSX: our own stored workbook reads back', () => {
    const rs = rows(files(), world());
    const back = parseXlsx(toXlsx(workbook(rs, ['basic'])), 'Items');
    assert.equal(back.find((r) => r.id === 'addon.incidents').orderCode, 'INS-AO-INC');
    assert.throws(() => parseXlsx(toXlsx(workbook(rs, ['basic'])), 'Nope'), /sheet 'Nope' not found/);
});
test('XLSX: an Excel-style workbook (deflated entries, shared strings, boolean cell) reads', () => {
    const entries = {
        'xl/workbook.xml': '<workbook xmlns:r="r"><sheets><sheet name="Items" sheetId="1" r:id="rId7"/></sheets></workbook>',
        'xl/_rels/workbook.xml.rels': '<Relationships><Relationship Id="rId7" Target="/xl/worksheets/s1.xml"/></Relationships>',
        'xl/sharedStrings.xml': '<sst><si><t>id</t></si><si><t>title</t></si><si><t>billable</t></si><si><r><t>addon.</t></r><r><t xml:space="preserve">incidents</t></r></si><si><t>A &amp; B</t></si></sst>',
        'xl/worksheets/s1.xml': '<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>'
            + '<row r="2"><c r="A2" t="s"><v>3</v></c><c r="C2" t="b"><v>1</v></c><c r="B2" t="s"><v>4</v></c></row></sheetData></worksheet>',
    };
    // a deflating zip writer, so this covers method 8 (what Excel writes)
    const parts = []; const cen = []; let off = 0;
    for (const [n, s] of Object.entries(entries)) {
        const raw = Buffer.from(s); const data = deflateRawSync(raw); const nm = Buffer.from(n);
        const l = Buffer.alloc(30); l.writeUInt32LE(0x04034b50, 0); l.writeUInt16LE(8, 8); l.writeUInt32LE(data.length, 18); l.writeUInt32LE(raw.length, 22); l.writeUInt16LE(nm.length, 26);
        const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(8, 10); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(raw.length, 24); c.writeUInt16LE(nm.length, 28); c.writeUInt32LE(off, 42);
        parts.push(l, nm, data); cen.push(c, nm); off += 30 + nm.length + data.length;
    }
    const cd = Buffer.concat(cen); const e = Buffer.alloc(22); e.writeUInt32LE(0x06054b50, 0); e.writeUInt16LE(4, 10); e.writeUInt32LE(cd.length, 12); e.writeUInt32LE(off, 16);
    const buf = Buffer.concat([...parts, cd, e]);
    assert.equal(unzip(buf)['xl/workbook.xml'].toString(), entries['xl/workbook.xml']);
    assert.deepEqual(parseXlsx(buf), [{ id: 'addon.incidents', title: 'A & B', billable: 'TRUE' }]);
});
