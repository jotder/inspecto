// Fixture test for tools/render-feature-db.mjs. Run: node --test tools/render-feature-db.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { hubs, inventoryRows, rows, toCsv, toMarkdown } from './render-feature-db.mjs';

const world = () => ({
    manifests: {
        engine: { id: 'engine', offeringRole: 'base', provides: { features: ['events'] } },
        ops: { id: 'ops', offeringRole: 'optional', provides: { features: ['ops'], routes: ['GET /objects'], contracts: ['X'] } },
        asn: { id: 'asn', offeringRole: 'optional' },
    },
    offerings: {
        basic: { id: 'basic', modules: [] },
        standard: { id: 'standard', includes: ['basic'], modules: ['ops', 'asn'], addons: { incidents: { status: 'built', modules: ['ops'] } } },
    },
    catalog: { processors: [
        { id: 'parser.asn', label: 'ASN.1', status: 'delivered', requires: 'inspecto-asn' },
        { id: 'sink.x', label: 'Sink', status: 'partial' },
    ] },
    inventory: '# T\n## 1. Feature inventory\n### A — x\n| Feature | TOON | Doc |\n|---|---|---|\n| Pipe `a\\|b` | `k: v` | `[LIVE]` [d](okf/product/module/ops/spec.md) |\n| Unmapped | x | y |\n## 2. Other\n| Not §1 | x | okf/product/module/ops/a.md |\n',
    manifestPaths: { ops: 'features/ops/module.toon' },
});
const files = () => ({
    'module/ops': 'id: module.ops\nkind: module\ntitle: Ops\nmodule: ops\nstatus: ga\n',
    'module/engine': 'id: module.engine\nkind: module\ntitle: Engine\nmodule: engine\nstatus: ga\n',
    'module/asn': 'id: module.asn\nkind: module\ntitle: ASN\nmodule: asn\nstatus: preview\n',
    'addon/incidents': 'id: addon.incidents\nkind: addon\ntitle: Incidents\nofferingAddon: incidents\nstatus: ga\n',
    'edition/basic': 'id: edition.basic\nkind: edition\ntitle: Basic\noffering: basic\n',
});
const find = (rs, feature) => rs.find((r) => r.feature === feature);

test('manifest provides become rows; contracts are skipped', () => {
    const rs = rows(files(), world());
    assert.deepEqual(find(rs, 'GET /objects'), { module: 'ops', editions: 'standard', addon: 'incidents', status: 'ga', feature: 'GET /objects',
        type: 'route', key: 'GET /objects', source: 'features/ops/module.toon' });
    assert.equal(find(rs, 'X'), undefined);
});
test('a base module is in every Edition; includes are resolved', () => {
    const rs = rows(files(), world());
    assert.equal(find(rs, 'events').editions, 'basic;standard');
    assert.equal(find(rs, 'ops').editions, 'standard');
});
test('a processor maps by requires, else to engine', () => {
    const rs = rows(files(), world());
    assert.equal(find(rs, 'ASN.1').module, 'asn');
    assert.equal(find(rs, 'ASN.1').status, 'delivered');
    assert.equal(find(rs, 'Sink').module, 'engine');
});
test('only §1 inventory rows linking a module hub are taken; the [LIVE] tag is the status', () => {
    assert.equal(inventoryRows(world().inventory).length, 2);
    const rs = rows(files(), world()).filter((r) => r.type === 'inventory');
    assert.equal(rs.length, 1);
    assert.equal(rs[0].status, 'live');
    assert.equal(rs[0].source, 'docs/FEATURE_INVENTORY.md §1A');
});
test('hubs: a module item gets its rows, an add-on item its modules\' rows, an edition none', () => {
    const w = world(), rs = rows(files(), w), h = hubs(files(), w, rs);
    assert.deepEqual(Object.keys(h).sort(), ['addon/incidents', 'module/asn', 'module/engine', 'module/ops']);
    assert.deepEqual(h['addon/incidents'].rows, h['module/ops'].rows);
});
test('markdown keeps an escaped pipe; csv unescapes it and quotes commas', () => {
    const w = world(), rs = rows(files(), w), h = hubs(files(), w, rs);
    const text = toMarkdown(h['module/ops']);
    assert.match(text, /^item: module\.ops$/m);
    assert.match(text, /\| Pipe `a\\\|b` \|/);
    const csv = toCsv(rs);
    assert.match(csv, /Pipe `a\|b`/);
    assert.match(csv, /"basic;standard"|basic;standard/);
    assert.equal(csv.split('\r\n')[0], 'feature,type,module,editions,addon,status,key,source');
});
test('the real tree is not stale', () => {
    execFileSync(process.execPath, ['tools/render-feature-db.mjs', '--check'], { stdio: 'pipe' });
});
