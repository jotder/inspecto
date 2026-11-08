// Determinism and planted facts of tools/gen-la-logistics-data.py. Run: node --test tools/gen-la-logistics-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-logistics/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: 12 short
// deliveries of one two-driver team, 10 scanned out at night by one scanner; 9 high-value trucks robbed at one lay-by; a
// three-week-old carrier with a dismissed driver's phone whose 5 high-value loads never arrive - and that each look-alike has
// the innocent shape. That the stories LINK (and the look-alikes do not) over the real link Datasets is
// LogisticsLinksJobGoldenTest (inspecto-engine). Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-logistics', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-logistics-data.ground-truth.json');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function files(dir = DATA, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) files(p, out);
    else out.push(p);
  }
  return out;
}
function snapshot() {
  const out = {};
  for (const p of [...files(), TRUTH]) out[p.slice(ROOT.length + 1).replaceAll('\\', '/')] = createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
  return out;
}
/** Every row of a feed (landed, staged, or both) as objects. No field holds a comma. */
function feed(name, box = '') {
  const rows = [];
  for (const p of files().filter((f) => f.replaceAll('\\', '/').includes(`${box}/${name}/`)).sort()) {
    const [head, ...lines] = readFileSync(p, 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) {
      const cells = l.split(',');
      rows.push(Object.fromEntries(cols.map((c, i) => [c, cells[i] ?? ''])));
    }
  }
  return rows;
}
const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
const LANDED_END = '2026-09-30';
const trips = feed('trip', '/inbox');
const trip = Object.fromEntries(feed('trip').map((t) => [t.trip_id, t]));
const ship = Object.fromEntries(feed('shipment').map((s) => [s.shipment_id, s]));
const loss = Object.fromEntries(feed('loss_report', '/inbox').map((l) => [l.shipment_id, l]));
const lossOf = (tr) => loss[trip[tr].shipment_id];
const scans = feed('warehouse_scan', '/inbox');
const outbound = Object.fromEntries(scans.filter((s) => s.scan_type === 'outbound').map((s) => [s.shipment_id, s]));
const arrived = new Set(scans.filter((s) => s.scan_type === 'arrived').map((s) => s.shipment_id));
const stops = feed('stop', '/inbox');
const longAt = {};
for (const s of stops.filter((s) => +s.minutes >= truth.long_stop_minutes)) (longAt[s.place_id] ??= new Set()).add(s.trip_id);
const carrier = Object.fromEntries(feed('carrier').map((c) => [c.carrier_id, c]));
const driver = Object.fromEntries(feed('driver').map((d) => [d.driver_id, d]));
const digits = (p) => { const d = p.replace(/[^0-9]/g, ''); return d.startsWith('44') ? '0' + d.slice(2) : d; };
const hv = (tr) => +ship[trip[tr].shipment_id].value >= truth.high_value;
const night = (ts) => +ts.slice(11, 13) >= 22 || +ts.slice(11, 13) < 6;
const ageDays = (tr) => Math.floor((Date.parse(trip[tr].depart_at.replace(' ', 'T') + 'Z') - Date.parse(carrier[trip[tr].carrier_id].registered_at.replace(' ', 'T') + 'Z')) / 86_400_000);

test('generator twice = the committed bytes; eight feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['carrier', 'driver', 'loss_report', 'place', 'shipment', 'stop', 'trip', 'warehouse_scan']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-logistics-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 120 carriers, 900 drivers, one shipment per trip, October 2026 staged, synthetic identifiers', () => {
  assert.equal(Object.keys(carrier).length, 120);
  assert.equal(Object.keys(driver).length, 900);
  assert.equal(feed('place').length, truth.counts.place);
  assert.equal(trips.length, truth.counts.trip_landed);
  assert.equal(feed('shipment', '/inbox').length, truth.counts.shipment_landed);
  assert.equal(stops.length, truth.counts.stop_landed);
  assert.equal(scans.length, truth.counts.warehouse_scan_landed);
  assert.equal(Object.keys(loss).length, truth.counts.loss_report_landed);
  for (const f of ['shipment', 'trip', 'stop', 'warehouse_scan'])
    assert.equal(files().filter((p) => p.replaceAll('\\', '/').includes(`/inbox/${f}/`)).length, 12, `twelve monthly ${f} files`);
  assert.ok(feed('trip', '/staged').every((t) => t.depart_at.startsWith('2026-10')), 'the staged trips leave in October');
  assert.ok(trips.every((t) => t.depart_at <= LANDED_END + ' 23:59:59' && ship[t.shipment_id]), 'one shipment per landed trip');
  assert.ok(Object.values(loss).every((l) => l.reported_at <= LANDED_END + ' 23:59:59'));
  const phones = [...Object.values(driver), ...Object.values(carrier)].map((x) => x.phone).filter(Boolean);
  assert.ok(phones.every((p) => /^(07700 ?900\d{3}|\+44 7700 900\d{3}|0\d{2,3} \d{3,4} \d{3,4}|01632 960\d{3})$/.test(p)), 'phones in the reserved drama ranges');
  const blankVehicle = trips.filter((t) => !t.vehicle).length / trips.length;
  assert.ok(blankVehicle > 0.02 && blankVehicle < 0.08, `missing vehicles are realistic (${blankVehicle})`);
  assert.ok(trips.length / Object.keys(loss).length > 150, 'losses are rare');
});

test('inside job: 12 shortages of one team, 10 scanned out at night by one scanner; the busy driver has 2 in about 320 trips', () => {
  const j = truth.inside_job;
  for (const tr of j.trips) {
    assert.equal(trip[tr].driver_id, j.driver);
    assert.equal(trip[tr].co_driver_id, j.co_driver);
    assert.equal(lossOf(tr)?.loss_type, 'shortage', `${tr}: short-delivered`);
    assert.ok(arrived.has(trip[tr].shipment_id), `${tr}: it arrived`);
  }
  const onLoss = {};
  for (const t of trips.filter((t) => loss[t.shipment_id]))
    for (const p of [t.driver_id, t.co_driver_id, outbound[t.shipment_id]?.employee_id].filter(Boolean)) (onLoss[p] ??= []).push(t.trip_id);
  const drivers = Object.keys(onLoss).filter((p) => p.startsWith('DRV-') && onLoss[p].length >= 2).sort();
  assert.deepEqual(drivers, [j.driver, j.co_driver, j.lookalike_driver].sort(), 'nobody else drove two lost loads');
  assert.deepEqual(onLoss[j.driver].sort(), [...j.trips].sort());
  assert.deepEqual(onLoss[j.scanner].sort(), [...j.night_trips].sort(), 'the scanner is on ten of them and no other');
  for (const tr of j.night_trips) {
    const s = outbound[trip[tr].shipment_id];
    assert.ok(s.warehouse_id === j.scanner_warehouse && night(s.scanned_at), `${tr}: scanned out at night at ${j.scanner_warehouse}`);
  }
  const busy = trips.filter((t) => t.driver_id === j.lookalike_driver);
  assert.equal(busy.length, j.lookalike_trips_landed);
  assert.ok(busy.length >= 300 && busy.filter((t) => ['Southampton-Birmingham', 'Birmingham-Southampton'].includes(t.route)).length >= 280, 'the busiest driver on the busiest route');
  assert.deepEqual(onLoss[j.lookalike_driver].sort(), [...j.lookalike_trips].sort());
  assert.ok(j.lookalike_trips.every((tr) => !onLoss[j.scanner].includes(tr)));
});

test('stop hotspot: every high-value truck that stood long at the lay-by was robbed; the services see 3 among hundreds', () => {
  const h = truth.stop_hotspot;
  const lostLong = Object.fromEntries(Object.entries(longAt).map(([p, s]) => [p, [...s].filter((tr) => lossOf(tr))]));
  assert.deepEqual(Object.keys(lostLong).filter((p) => lostLong[p].length >= 3).sort(), [h.layby, h.lookalike_services].sort());
  assert.deepEqual([...longAt[h.layby]].filter(hv).sort(), [...h.trips].sort(), 'the high-value trucks that stood there');
  assert.deepEqual(lostLong[h.layby].sort(), [...h.trips].sort(), 'all nine robbed, nobody else');
  for (const tr of h.trips) {
    const own = stops.filter((s) => s.trip_id === tr).sort((a, b) => a.start_at.localeCompare(b.start_at));
    assert.equal(own.at(-1).place_id, h.layby, `${tr}: its last stop`);
    assert.ok(+own.at(-1).minutes >= 25 && +own.at(-1).minutes <= 70);
    assert.ok(lossOf(tr).loss_type === 'theft' && !trip[tr].arrive_at && !arrived.has(trip[tr].shipment_id), `${tr}: stolen`);
  }
  assert.equal(new Set(h.trips.map((tr) => trip[tr].driver_id)).size, 9, 'nine different drivers');
  assert.ok(longAt[h.lookalike_services].size >= 200, 'busy services');
  assert.equal([...longAt[h.lookalike_services]].filter(hv).length, h.lookalike_high_value_long_stops);
  assert.deepEqual(lostLong[h.lookalike_services].sort(), [...h.lookalike_lost_trips].sort());
  assert.ok(![...longAt[h.layby]].some((tr) => longAt[h.lookalike_services].has(tr)), 'no trip stood long at both');
});

test('fictitious carrier: three weeks old, a dismissed driver\'s phone, 5 high-value loads never arrive; the new owner-operator delivers', () => {
  const f = truth.fictitious_carrier;
  const c = carrier[f.carrier];
  assert.equal(c.insured, 'N');
  assert.deepEqual(trips.filter((t) => t.carrier_id === f.carrier).map((t) => t.trip_id).sort(), [...f.trips].sort());
  for (const tr of f.trips) {
    assert.ok(hv(tr) && ageDays(tr) >= 18 && ageDays(tr) <= 32, `${tr}: high value, ${ageDays(tr)} days after registering`);
    assert.ok(!trip[tr].arrive_at && !arrived.has(trip[tr].shipment_id) && lossOf(tr).loss_type === 'non_delivery', `${tr}: never arrived`);
    assert.ok(!stops.some((s) => s.trip_id === tr), `${tr}: no GPS`);
  }
  const sharing = Object.keys(driver).filter((d) => driver[d].phone && digits(driver[d].phone) === digits(c.phone));
  assert.deepEqual(sharing, [f.thief]);
  assert.notEqual(driver[f.thief].phone, c.phone, 'the same number, written another way');
  assert.equal(driver[f.thief].left_on, '2025-11-30');
  assert.equal(lossOf(f.thief_trip).loss_type, 'theft');
  assert.equal(trips.filter((t) => t.driver_id === f.thief && loss[t.shipment_id]).length, 1);
  const n = carrier[f.lookalike_carrier];
  assert.equal(n.insured, 'Y');
  assert.deepEqual(Object.keys(driver).filter((d) => driver[d].phone && digits(driver[d].phone) === digits(n.phone)), [f.lookalike_driver], 'an owner-operator');
  const newHv = trips.filter((t) => t.carrier_id === f.lookalike_carrier && hv(t.trip_id) && ageDays(t.trip_id) < 60);
  assert.deepEqual(newHv.map((t) => t.trip_id).sort(), [...f.lookalike_high_value_trips].sort());
  assert.ok(trips.filter((t) => t.carrier_id === f.lookalike_carrier).every((t) => t.arrive_at && arrived.has(t.shipment_id) && !loss[t.shipment_id]), 'delivers every load');
});
