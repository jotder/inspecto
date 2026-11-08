// Determinism of tools/gen-la-claims-data.py. Run: node --test tools/gen-la-claims-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-claims/data) and asserts the bytes of every file are IDENTICAL to what is
// committed - a second run is the same bytes as the first - and that the corpus stays under 1 MB. Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-claims', 'data');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) snapshot(p, out);
    else if (p.endsWith('.csv')) out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = { sha: createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex'), bytes: statSync(p).size };
  }
  return out;
}
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-claims-data.py')], { encoding: 'utf8' }).status, 0);

test('generator twice = the committed bytes; seven feeds; under 1 MB', { skip: !python }, () => {
  const before = snapshot();
  assert.equal(Object.keys(before).length, 7, 'seven feeds');
  assert.ok(Object.values(before).reduce((n, f) => n + f.bytes, 0) < 1_000_000, 'the corpus is under 1 MB');
  run();
  assert.deepEqual(snapshot(), before, 'first run changed bytes');
  run();
  assert.deepEqual(snapshot(), before, 'second run changed bytes');
});

// ---- the stories added by docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 2 (on the committed files) ----
function rows(feed) {
  const dir = join(DATA, 'inbox', feed);
  const text = readFileSync(join(dir, readdirSync(dir).find((f) => f.endsWith('.csv'))), 'utf8').replace(/\r\n/g, '\n').trimEnd();
  const parse = (line) => {
    const out = []; let cur = ''; let q = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (q) { if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (ch === '"') q = false; else cur += ch; }
      else if (ch === '"') q = true; else if (ch === ',') { out.push(cur); cur = ''; } else cur += ch;
    }
    out.push(cur);
    return out;
  };
  const [head, ...body] = text.split('\n').map(parse);
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h, r[i]])));
}
const street = (a) => a.trim().toLowerCase().replace(/^[0-9]+ +/, '');

test('parts-supplier ring: SUP-07 invoices 30 claims across all 9 garages, none fitted; look-alike SUP-01 reaches all 9, all fitted', () => {
  const inv = rows('repair_invoice');
  const garages = new Set(rows('claim').map((c) => c.garage_id).filter(Boolean));
  const of = (s) => inv.filter((r) => r.supplier_id === s);
  assert.equal(garages.size, 9);
  assert.equal(new Set(of('SUP-07').map((r) => r.garage_id)).size, 9, 'ring supplier on all nine garages');
  assert.equal(new Set(of('SUP-07').map((r) => r.claim_id)).size, 30, 'thirty claims');
  assert.ok(of('SUP-07').every((r) => r.fitted === 'N'), 'none of its parts fitted');
  assert.equal(new Set(of('SUP-01').map((r) => r.garage_id)).size, 9, 'look-alike on all nine garages');
  assert.ok(of('SUP-01').every((r) => r.fitted === 'Y'), 'every look-alike part fitted');
  const otherUnfitted = new Set(inv.filter((r) => r.fitted === 'N' && r.supplier_id !== 'SUP-07').map((r) => r.claim_id));
  assert.ok(otherUnfitted.size <= 3, `ordinary returns only: ${otherUnfitted.size}`);
});

test('ghost broker: 25 BRK-07 policies on streets found nowhere else, paid from 2 accounts; look-alike: the family pays 4 from one account', () => {
  const pol = rows('policy');
  const parties = rows('claim_party');
  const ghost = pol.filter((p) => p.broker_id === 'BRK-07');
  assert.equal(ghost.length, 25);
  assert.deepEqual([...new Set(ghost.map((p) => p.payer_account))].sort(), ['BA-93000017', 'BA-93000029']);
  const real = new Set([...parties.map((p) => p.address), ...pol.filter((p) => p.broker_id !== 'BRK-07').map((p) => p.holder_address)]
    .filter((a) => a && a !== 'UNKNOWN').map(street));
  assert.ok(ghost.every((p) => !real.has(street(p.holder_address))), 'every ghost address is on a street found on no other record');
  const holders = new Set(ghost.map((p) => p.holder_id));
  assert.ok(parties.every((p) => !holders.has(p.party_id)), 'no ghost policyholder is on a claim');
  const perAccount = {};
  for (const p of pol) if (p.payer_account) perAccount[p.payer_account] = (perAccount[p.payer_account] ?? 0) + 1;
  const shared = Object.fromEntries(Object.entries(perAccount).filter(([, n]) => n >= 2));
  assert.deepEqual(shared, { 'BA-71000001': 4, 'BA-93000017': 13, 'BA-93000029': 12 });
  const family = pol.filter((p) => p.payer_account === 'BA-71000001');
  assert.equal(new Set(family.map((p) => p.holder_address)).size, 1, 'the family: one address');
  assert.ok(parties.some((p) => p.address === family[0].holder_address), 'a real address, the one on their claims');
  assert.ok(family.every((p) => p.broker_id !== 'BRK-07'));
});
