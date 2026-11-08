// Golden test of tools/gen-la-trade-data.py. Run: node --test tools/gen-la-trade-data.test.mjs
// Re-runs the generator (it writes under spaces/_templates/la-trade/data and tools/gen-la-trade-data.ground-truth.json)
// and asserts every byte is IDENTICAL to what is committed, then reads the planted facts straight from the CSV files: the
// over-priced shipments between two companies that share a director, the six boxes shipped round a circle, the bills of lading
// whose vessel was elsewhere on the B/L date (and the transhipments that only a trade-level check would flag), the shell
// importers at one address, and that no look-alike shares a record with its story. The link Dataset itself is pinned by
// TradeLinksJobGoldenTest. Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-trade', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-trade-data.ground-truth.json');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) snapshot(p, out);
    else out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = sha(p);
  }
  return out;
}
const sha = (p) => createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-trade-data.py')], { encoding: 'utf8' }).status, 0);

function csv(rel) {
  const rows = [];
  for (const line of readFileSync(join(DATA, rel), 'utf8').replace(/\r\n/g, '\n').split('\n').filter(Boolean)) {
    const cells = [];
    let cur = '', q = false;
    for (const ch of line) {
      if (ch === '"') q = !q;
      else if (ch === ',' && !q) { cells.push(cur); cur = ''; } else cur += ch;
    }
    cells.push(cur);
    rows.push(cells);
  }
  const [head, ...body] = rows;
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h, r[i]])));
}
const feed = (name, where = 'inbox') => readdirSync(join(DATA, where, name)).sort().flatMap((f) => csv(`${where}/${name}/${f}`));

test('generator twice = the committed bytes; seven feeds; 12 landed months; under 12 MB', { skip: !python }, () => {
  const before = snapshot();
  const truth = sha(TRUTH);
  assert.deepEqual([...new Set(Object.keys(before).map((k) => k.split('/')[1]))].sort(),
    ['bill_of_lading', 'company', 'director', 'payment', 'price_reference', 'trade', 'vessel_position']);
  for (const f of ['trade', 'bill_of_lading', 'payment', 'vessel_position', 'price_reference'])
    assert.equal(Object.keys(before).filter((k) => k.startsWith(`inbox/${f}/`)).length, 12, `${f}: 12 landed months`);
  assert.equal(Object.keys(before).filter((k) => k.startsWith('staged/')).length, 5, 'October 2026 staged for the five monthly feeds');
  assert.ok(Object.keys(before).reduce((n, k) => n + statSync(join(DATA, k)).size, 0) < 12_000_000, 'the corpus is under 12 MB');
  run();
  assert.deepEqual(snapshot(), before, 'first run changed bytes');
  assert.equal(sha(TRUTH), truth, 'first run changed the ground truth');
  run();
  assert.deepEqual(snapshot(), before, 'second run changed bytes');
});

const T = JSON.parse(readFileSync(TRUTH, 'utf8'));
const S = T.stories;
const co = Object.fromEntries(feed('company').map((c) => [c.company_id, c]));
const dir = feed('director'), trade = feed('trade'), bl = feed('bill_of_lading'), pay = feed('payment');
const pos = new Map(feed('vessel_position').map((p) => [`${p.vessel}|${p.pos_date}`, p.port_or_area]));
const ref = new Map(feed('price_reference').map((r) => [`${r.hs_code}|${r.month}`, Number(r.median_unit_price)]));
const ownerOf = Object.fromEntries(Object.values(co).map((c) => [c.account_id, c.company_id]));
const tradeById = Object.fromEntries(trade.map((t) => [t.trade_id, t]));
const xRef = (t) => Number(t.unit_price) / ref.get(`${t.hs_code}|${t.ship_date.slice(0, 7)}`);
const elsewhere = (b) => pos.has(`${b.vessel}|${b.bl_date}`) && pos.get(`${b.vessel}|${b.bl_date}`) !== b.port_from;

test('plan scale: 2 500 companies, ~3 500 directors, ~12 000 trades and B/Ls, 300 vessels every day, ~30 000 payments', () => {
  assert.equal(Object.keys(co).length, 2500);
  assert.ok(dir.length > 3200 && dir.length < 3800, `${dir.length} directors`);
  assert.equal(trade.length, T.landed_rows.trade);
  assert.ok(trade.length > 11000 && trade.length < 13000, `${trade.length} trades`);
  assert.ok(bl.length >= trade.length && bl.length < 13000, `${bl.length} B/Ls`);
  assert.equal(new Set(feed('vessel_position').map((p) => p.vessel)).size, 300);
  assert.equal(pos.size, 300 * 365, 'one position per vessel per landed day');
  assert.ok(pay.length > 27000 && pay.length < 32000, `${pay.length} payments`);
  assert.ok(trade.every((t) => /^\d{6}$/.test(t.hs_code) && ref.has(`${t.hs_code}|${t.ship_date.slice(0, 7)}`)), 'every trade has a reference price');
  assert.ok(pay.every((p) => ownerOf[p.from_account] && ownerOf[p.to_account]), 'every account belongs to a company');
});

test('over-invoicing: 14 shipments of one HS code at 3-8x the reference between two companies that share a director', () => {
  const f = S.over_invoicing.facts;
  const ts = trade.filter((t) => t.beneficiary === f.exporter && t.applicant === f.importer);
  assert.equal(ts.length, 14);
  assert.ok(ts.every((t) => t.hs_code === f.hs_code && xRef(t) > 3 && xRef(t) < 8), 'all 14 at 3-8x');
  const dirsOf = (c) => new Set(dir.filter((d) => d.company_id === c).map((d) => d.person_id));
  assert.ok(dirsOf(f.importer).has(f.shared_director) && dirsOf(f.exporter).has(f.shared_director));
  // the look-alike: steady 3.3-3.9x all year to 8 buyers, no director shared with any of them
  const ss = trade.filter((t) => t.beneficiary === f.specialty_supplier);
  assert.equal(new Set(ss.map((t) => t.applicant)).size, 8);
  assert.ok(ss.every((t) => xRef(t) > 3.2 && xRef(t) < 4), 'steady prices');
  assert.ok(new Set(ss.map((t) => t.ship_date.slice(0, 7))).size >= 9, 'all year round');
  for (const b of new Set(ss.map((t) => t.applicant))) assert.equal([...dirsOf(b)].filter((p) => dirsOf(f.specialty_supplier).has(p)).length, 0);
  // nothing else in the corpus is priced over 3x
  const over = trade.filter((t) => xRef(t) > T.price_ratio_alert).map((t) => t.beneficiary);
  assert.deepEqual([...new Set(over)].sort(), [f.exporter, f.specialty_supplier].sort());
});

test('circular trade: the same 6 boxes go A -> B -> C -> A twice within 4 months, the value rising every leg', () => {
  const f = S.circular_trade.facts;
  const [a, b, c] = f.cycle;
  const legs = f.trades.map((id) => tradeById[id]);
  assert.deepEqual(legs.map((t) => `${t.beneficiary}>${t.applicant}`), [`${a}>${b}`, `${b}>${c}`, `${c}>${a}`, `${a}>${b}`, `${b}>${c}`, `${c}>${a}`]);
  for (let i = 1; i < 6; i++) assert.ok(Number(legs[i].value) > Number(legs[i - 1].value), `leg ${i + 1} is worth more`);
  assert.ok((Date.parse(f.last_ship_date) - Date.parse(f.first_ship_date)) / 864e5 <= 122);
  for (const t of legs) assert.deepEqual(bl.filter((x) => x.trade_id === t.trade_id).map((x) => x.container).sort(), [...f.containers].sort());
  // the leasing boxes: 10 unrelated shippers each, none of them ever a consignee of the box
  for (const box of f.leasing_boxes) {
    const ls = bl.filter((x) => x.container === box);
    assert.equal(new Set(ls.map((x) => x.shipper)).size, 10, box);
    assert.ok(ls.every((x) => !ls.some((y) => y.consignee === x.shipper)), `${box} is never relayed`);
  }
});

test('phantom shipment: 5 paid letters of credit on B/Ls naming a vessel that was elsewhere; the transhipments pass per B/L', () => {
  const f = S.phantom_shipment.facts;
  const flagged = bl.filter(elsewhere).map((b) => b.bl_id).sort();
  assert.deepEqual(flagged, [...f.bills_of_lading].sort(), 'only the five phantom B/Ls fail the position check');
  for (const b of bl.filter((x) => f.bills_of_lading.includes(x.bl_id))) {
    assert.equal(b.vessel, f.vessel);
    assert.equal(b.shipper, f.exporter);
    assert.equal(b.port_from, f.port_of_loading);
    assert.ok(![...pos].some(([k, v]) => k.startsWith(`${f.vessel}|`) && v === f.port_of_loading), 'the vessel never called there');
    assert.equal(tradeById[b.trade_id].instrument, 'LC');
    assert.ok(pay.some((p) => p.trade_ref === b.trade_id && ownerOf[p.to_account] === f.exporter), `${b.trade_id} was paid`);
  }
  for (const id of f.transhipment_trades) {
    const t = tradeById[id], legs = bl.filter((x) => x.trade_id === id);
    assert.equal(new Set(legs.map((x) => x.vessel)).size, 2, `${id}: two vessels`);
    assert.notEqual(pos.get(`${t.vessel}|${t.ship_date}`), t.port_load, `${id}: a check on the trade would flag it`);
    assert.ok(legs.every((x) => !elsewhere(x)), `${id}: each leg matches its own vessel`);
  }
});

test('shell importers: 6 importers incorporated within 2 months at one address, 2 directors, paying one exporter far more than it shipped', () => {
  const f = S.shell_importers.facts;
  assert.ok(f.importers.every((c) => co[c].reg_address === f.agent_address));
  const inc = f.importers.map((c) => Date.parse(co[c].incorporated_at)).sort();
  assert.ok((inc.at(-1) - inc[0]) / 864e5 <= 61);
  assert.deepEqual([...new Set(dir.filter((d) => f.importers.includes(d.company_id)).map((d) => d.person_id))].sort(), [...f.nominee_directors].sort());
  const paid = pay.filter((p) => f.importers.includes(ownerOf[p.from_account]) && ownerOf[p.to_account] === f.exporter)
    .reduce((n, p) => n + Number(p.amount), 0);
  const shipped = trade.filter((t) => t.beneficiary === f.exporter && f.importers.includes(t.applicant)).reduce((n, t) => n + Number(t.value), 0);
  assert.ok(Math.abs(paid - f.paid_to_exporter) < 0.05);
  assert.ok(paid > 10 * shipped, `${paid} paid against ${shipped} shipped`);
  const biz = Object.values(co).filter((c) => c.reg_address === f.business_centre);
  assert.equal(biz.length, 14);
  assert.ok(biz.every((c) => Date.parse(c.incorporated_at) < Date.parse('2015-01-01')), 'long-standing');
  const bizDirs = dir.filter((d) => biz.some((c) => c.company_id === d.company_id));
  assert.equal(new Set(bizDirs.map((d) => d.person_id)).size, bizDirs.length, 'each business-centre company has its own directors');
  // no other address hosts 5 or more companies
  const n = {};
  Object.values(co).filter((c) => c.reg_address).forEach((c) => { n[c.reg_address] = (n[c.reg_address] ?? 0) + 1; });
  assert.deepEqual(Object.keys(n).filter((a) => n[a] >= 5).sort(), [f.agent_address, f.business_centre].sort());
});

test('no look-alike shares a record with its story', () => {
  const up = (a) => a.trim().toUpperCase();
  const edges = [...trade.map((t) => [t.beneficiary, t.applicant]), ...bl.map((b) => [b.shipper, b.vessel]),
    ...bl.map((b) => [b.shipper, b.container]), ...dir.map((d) => [d.company_id, d.person_id]),
    ...Object.values(co).filter((c) => c.reg_address).map((c) => [c.company_id, up(c.reg_address)]),
    ...pay.map((p) => [ownerOf[p.from_account], ownerOf[p.to_account]])];
  for (const [name, s] of Object.entries(S)) {
    const m = new Set(s.entities), l = new Set(s.lookalikes);
    assert.ok(l.size > 0, name);
    for (const x of l) assert.ok(edges.some(([u, v]) => u === x || v === x), `${name}: look-alike ${x} is in the data`);
    const bad = edges.filter(([u, v]) => (l.has(u) && m.has(v)) || (l.has(v) && m.has(u)));
    assert.deepEqual(bad, [], `${name}: a look-alike is linked to its story`);
  }
});

test('invented names: no real port, vessel or container format leaks in; every port carries its country', () => {
  assert.ok(bl.every((b) => /^MV [A-Z]+ [A-Z]+$/.test(b.vessel)));
  assert.ok(bl.every((b) => /^[A-Z]{4}\d{7}$/.test(b.container)));
  assert.ok(bl.every((b) => / \([A-Z]{2}\)$/.test(b.port_from) && / \([A-Z]{2}\)$/.test(b.port_to)));
});
