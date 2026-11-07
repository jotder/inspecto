// Determinism and planted facts of tools/gen-la-police-data.py. Run: node --test tools/gen-la-police-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-police/data and tools/la-police-ground-truth.json) and asserts the
// bytes of every file are IDENTICAL to what is committed, then checks the stories and look-alikes on the raw files against the
// ground truth. The link Job's output is pinned by LaPoliceLinksJobTest. Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-police', 'data');
const TRUTH = join(ROOT, 'tools', 'la-police-ground-truth.json');
const FEEDS = ['incident_report', 'person', 'incident_person', 'vehicle', 'plate_read', 'call_record', 'handset', 'custody',
  'dealer_sale', 'property_stolen', 'address'];
const STAGED = ['incident_report', 'incident_person', 'plate_read', 'call_record', 'custody', 'dealer_sale', 'property_stolen'];
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) snapshot(p, out);
    else out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = { sha: sha(p), bytes: statSync(p).size };
  }
  return out;
}
const sha = (p) => createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-police-data.py')], { encoding: 'utf8' }).status, 0);

/** A feed's rows as objects; no field holds a comma or a quote, so a split is exact. */
function rows(part, feed) {
  const f = join(DATA, part, feed, `${feed.toUpperCase()}_${part === 'inbox' ? '20261031' : '20261130'}.csv`);
  const [head, ...lines] = readFileSync(f, 'utf8').trimEnd().split('\n');
  const cols = head.split(',');
  return lines.map((l) => Object.fromEntries(l.split(',').map((v, i) => [cols[i], v])));
}

test('generator twice = the committed bytes; 11 feeds in the inbox, 7 staged; under 8 MB', { skip: !python }, () => {
  const before = snapshot();
  const truth = sha(TRUTH);
  assert.deepEqual(Object.keys(before).filter((k) => k.startsWith('inbox/')).map((k) => k.split('/')[1]).sort(), [...FEEDS].sort());
  assert.deepEqual(Object.keys(before).filter((k) => k.startsWith('staged/')).map((k) => k.split('/')[1]).sort(), [...STAGED].sort());
  assert.ok(Object.values(before).reduce((n, f) => n + f.bytes, 0) < 8_000_000, 'the corpus is under 8 MB');
  assert.ok(!existsSync(join(DATA, 'ground-truth.json')), 'the ground truth is not shipped with the data');
  run();
  assert.deepEqual(snapshot(), before, 'first run changed bytes');
  assert.equal(sha(TRUTH), truth, 'first run changed the ground truth');
  run();
  assert.deepEqual(snapshot(), before, 'second run changed bytes');
});

test('the planted facts hold in the files', () => {
  const t = JSON.parse(readFileSync(TRUTH, 'utf8'));
  const inc = new Map(rows('inbox', 'incident_report').map((r) => [r.incident_id, r]));
  const s = t.burglary_series;
  assert.equal(s.incidents.length, 18);
  for (const id of s.incidents) {
    const r = inc.get(id);
    assert.equal(r.offence_type, 'burglary', id);
    assert.ok(['Leeds North', 'Leeds East'].includes(r.district), id);
    assert.ok(r.start_at >= '2026-02-01' && r.start_at < '2026-05-01', id);
  }
  const stolen = new Map(rows('inbox', 'property_stolen').filter((r) => r.serial).map((r) => [r.serial, r.incident_id]));
  const sales = rows('inbox', 'dealer_sale');
  for (const m of s.matches) {
    const sale = sales.find((x) => x.sale_id === m.sale);
    assert.equal(sale.serial, m.serial);
    assert.equal(stolen.get(m.serial), m.incident);
    assert.ok(s.sellers.includes(sale.seller_person_id) && s.dealers.includes(sale.dealer_id), m.sale);
  }
  const late = rows('staged', 'dealer_sale').find((x) => x.sale_id === s.staged_match.sale);
  assert.equal(late.serial, s.staged_match.serial, 'the next month brings one more serial match');
  assert.equal(stolen.get(late.serial), s.staged_match.incident);
  const person = new Map(rows('inbox', 'person').map((r) => [r.person_id, r]));
  const vehicle = rows('inbox', 'vehicle').find((v) => v.plate === s.plate);
  assert.equal(vehicle.keeper_person_id, s.keeper);
  assert.equal(person.get(s.keeper).address_id, s.home);
  assert.ok(s.sellers.some((p) => person.get(p).address_id === s.home), 'the keeper is a seller\'s partner at the same address');

  // the controller rotates; customers carry over; the look-alike numbers never touch the network
  const d = t.drug_supply;
  const calls = rows('inbox', 'call_record');
  const network = new Set([...d.controllers, ...d.runners, ...d.customers, ...t.drug_bases.numbers]);
  const served = d.controllers.map((c) => new Set(calls.filter((x) => x.caller === c || x.callee === c)
    .map((x) => (x.caller === c ? x.callee : x.caller)).filter((n) => d.customers.includes(n))));
  for (let i = 0; i + 1 < served.length; i++) {
    const kept = [...served[i]].filter((n) => served[i + 1].has(n)).length;
    assert.ok(kept >= 0.7 * served[i].size, `carry-over ${i}: ${kept}/${served[i].size}`);
  }
  assert.equal(new Set(served.flatMap((x) => [...x])).size, 60);
  const lookalikes = new Set([t.lookalike_driver.number, ...t.lookalike_shared_house.numbers]);
  for (const x of calls) {
    if (lookalikes.has(x.caller) || lookalikes.has(x.callee)) assert.ok(!network.has(x.caller) && !network.has(x.callee), JSON.stringify(x));
  }
  for (const r of t.drug_bases.residents) assert.equal(person.get(r).role_flags, 'vulnerable', r);

  // the witness and the shopkeeper
  const roles = rows('inbox', 'incident_person');
  const w = t.witness;
  assert.deepEqual(roles.filter((r) => r.person_id === w.witness).map((r) => `${r.incident_id}:${r.role}`).sort(),
    w.incidents.map((i) => `${i}:witness`).sort());
  assert.ok(roles.some((r) => r.incident_id === w.suspect_incident && r.person_id === w.suspect && r.role === 'suspect'));
  const custody = rows('inbox', 'custody');
  assert.deepEqual(custody.filter((c) => c.co_arrest_group === w.custody_group).map((c) => c.person_id).sort(), [w.suspect, w.witness].sort());
  assert.equal(roles.filter((r) => r.person_id === t.lookalike_shopkeeper.person && r.role === 'witness').length, 6);
  assert.ok(!custody.some((c) => c.person_id === t.lookalike_shopkeeper.person), 'the shopkeeper has no custody record');
});
