// Fixture test for tools/render-product-map.mjs. Run: node --test tools/render-product-map.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inflateRawSync } from 'node:zlib';
import { gather } from './check-product-map.mjs';
import { COLUMNS, readPrices, rows, toCsv, toHtml, toXlsx, workbook, zip } from './render-product-map.mjs';

const world = () => ({
    manifests: { engine: { offeringRole: 'base' }, ops: { offeringRole: 'optional' }, la: { offeringRole: 'optional' } },
    offerings: {
        basic: { id: 'basic', modules: ['la'], addons: { linkAnalysis: { status: 'built', modules: ['la'] } }, contentPacks: ['starter'] },
        standard: { id: 'standard', includes: ['basic'], modules: ['ops'], addons: { incidents: { status: 'built', modules: ['ops'] } }, contentPacks: ['aml'] },
    },
});
const files = () => ({
    'module/engine': 'id: module.engine\nkind: module\ntitle: Engine\nmodule: engine\nbillable: false\nstatus: ga\n',
    'module/ops': 'id: module.ops\nkind: module\ntitle: Ops\nmodule: ops\nbillable: false\nstatus: ga\n',
    'addon/linkAnalysis': 'id: addon.linkAnalysis\nkind: addon\ntitle: Link Analysis\nofferingAddon: linkAnalysis\nbillable: true\norderCode: INS-AO-LA\nenforcement: build\nbilling:\n  model: flat\nstatus: ga\n',
    'addon/incidents': 'id: addon.incidents\nkind: addon\ntitle: Incidents, cases\nofferingAddon: incidents\nbillable: true\norderCode: INS-AO-INC\nenforcement: build\nbilling:\n  model: per-user\nstatus: ga\n',
    'solution-pack/aml': 'id: solution-pack.aml\nkind: solution-pack\ntitle: AML\ntemplate: aml\nbillable: false\nstatus: ga\n',
    'solution-pack/showcase': 'id: solution-pack.showcase\nkind: solution-pack\ntitle: Showcase\ntemplate: showcase\nbillable: false\nstatus: preview\n',
    'solution-pack/industry-telecom': 'id: solution-pack.industry-telecom\nkind: solution-pack\ntitle: Telecom\nindustryPack: telecom\nbillable: false\nstatus: ga\n',
    'edition/standard': 'id: edition.standard\nkind: edition\ntitle: Standard\noffering: standard\nbillable: false\nstatus: ga\n',
});
const byId = (rs) => Object.fromEntries(rs.map((r) => [r.id, r]));

test('Edition membership is derived: includes resolved, base everywhere, packs and add-ons by Offering', () => {
    const r = byId(rows(files(), world()));
    assert.equal(r['module.engine'].editions, 'basic;standard');
    assert.equal(r['module.ops'].editions, 'standard');
    assert.equal(r['addon.linkAnalysis'].editions, 'basic;standard'); // via includes
    assert.equal(r['addon.incidents'].editions, 'standard');
    assert.equal(r['addon.incidents'].modules, 'ops');
    assert.equal(r['solution-pack.aml'].editions, 'standard');
    assert.equal(r['solution-pack.showcase'].editions, '');
    assert.equal(r['solution-pack.industry-telecom'].editions, 'basic;standard');
    assert.equal(r['edition.standard'].editions, 'standard');
});
test('prices come only from private/prices.csv, matched by Order Code', () => {
    assert.equal(byId(rows(files(), world()))['addon.incidents'].listPrice, '');
    const p = readPrices('orderCode,listPrice,currency\r\nINS-AO-INC, 1200 ,USD\r\n');
    const r = byId(rows(files(), world(), p));
    assert.equal(r['addon.incidents'].listPrice, '1200');
    assert.equal(r['addon.incidents'].currency, 'USD');
    assert.equal(r['addon.linkAnalysis'].listPrice, '');
});
test('CSV has every column and quotes a comma', () => {
    const csv = toCsv(rows(files(), world()));
    assert.equal(csv.split('\r\n')[0], COLUMNS.join(','));
    assert.match(csv, /,"Incidents, cases",/);
});
test('the zip is well formed: entries round-trip with their CRC-32', () => {
    const z = zip([['a.txt', 'abc'], ['b/c.xml', '<x/>']]);
    assert.equal(z.readUInt32LE(0), 0x04034b50);
    assert.equal(z.readUInt32LE(14), 0x352441c2); // crc32("abc")
    const end = z.length - 22;
    assert.equal(z.readUInt32LE(end), 0x06054b50);
    assert.equal(z.readUInt16LE(end + 10), 2);
    const second = 30 + 'a.txt'.length + 3;
    assert.equal(z.subarray(second + 30 + 'b/c.xml'.length, second + 30 + 'b/c.xml'.length + 4).toString(), '<x/>');
    assert.throws(() => inflateRawSync(Buffer.from('not deflate'))); // sanity: stored entries are not deflated
});
test('the workbook has the three sheets and escapes XML', () => {
    const rs = rows(files(), world());
    const sheets = workbook(rs, ['basic', 'standard']);
    assert.deepEqual(sheets.map(([n]) => n), ['Items', 'Price list', 'Edition matrix']);
    assert.equal(sheets[1][1].length, 3); // header + 2 billable
    assert.deepEqual(sheets[2][1].find((r) => r[0] === 'INS-AO-INC').slice(3), ['', '✓']);
    const x = toXlsx([['S', [['h'], ['a<b&c']]]]).toString('utf8');
    assert.match(x, /a&lt;b&amp;c/);
    assert.match(x, /\[Content_Types\]\.xml/);
});
test('the mind map nests by parent and shows Order Codes', () => {
    const f = files();
    f['functionality/sla'] = 'id: functionality.sla\nkind: functionality\ntitle: SLA <timers>\nparent: module.ops\nmodule: ops\nbillable: true\norderCode: INS-F-SLA\nenforcement: contract\nbilling:\n  model: flat\nstatus: ga\n';
    assert.match(toHtml(rows(f, world()), ['basic']), /Modules with no layer.*SLA &lt;timers&gt;/s); // parentless items are shown, not dropped
    f['layer/platform'] = 'id: layer.platform\nkind: layer\ntitle: Platform\nbillable: false\nstatus: ga\n';
    for (const m of ['engine', 'ops']) f[`module/${m}`] = f[`module/${m}`].replace('kind: module\n', 'kind: module\nparent: layer.platform\n');
    const html = toHtml(rows(f, world()), ['basic', 'standard']);
    assert.doesNotMatch(html, /Modules with no layer/);
    assert.match(html, /module\.ops<\/span>.*<ul><li><div class="leaf"><span class="t">SLA &lt;timers&gt;/s);
    assert.match(html, /INS-AO-INC/);
    assert.match(html, /<title>Product Map<\/title>/);
});
test('the real tree renders', () => {
    const [f, w] = gather('.');
    const rs = rows(f, w);
    assert.equal(rs.length, Object.keys(f).length);
    assert.ok(rs.every((r) => r.kind && r.id));
});
