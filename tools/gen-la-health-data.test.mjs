// Determinism and planted facts of tools/gen-la-health-data.py. Run: node --test tools/gen-la-health-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-health/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: two
// practices sending 85 % of their referrals to an imaging centre whose owner shares a home with one practice's owner, a clinic
// billing 40 patients in hospital or after death, a pill mill's 70 travelling cash patients, three urgent-care clinics under one
// owner billing the top visit level far above their patients' ages - and that each look-alike has the innocent shape. That the
// stories LINK (and the look-alikes do not) over the real link Datasets is HealthLinksJobGoldenTest (inspecto-engine).
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-health', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-health-data.ground-truth.json');
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
/** Every row of a feed (landed, staged or both) as objects. No field holds a comma. */
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
const days = (a, b) => (Date.parse(b) - Date.parse(a)) / 86_400_000;
const address = Object.fromEntries(feed('address').map((a) => [a.address_id, a]));
const providers = feed('provider');
const provider = Object.fromEntries(providers.map((p) => [p.provider_id, p]));
const owners = feed('owner');
const owner = Object.fromEntries(owners.map((o) => [o.owner_id, o]));
const members = feed('member');
const member = Object.fromEntries(members.map((m) => [m.member_id, m]));
const lines = feed('claim_line', '/inbox');
const admissions = feed('admission');
const referrals = feed('referral', '/inbox');
const scripts = feed('prescription', '/inbox');
const ownership = feed('provider_owner');
function km(a, b) {
  const r = (x) => (x * Math.PI) / 180;
  const [la1, lo1, la2, lo2] = [+a.lat, +a.lon, +b.lat, +b.lon];
  const h = Math.sin(r(la2 - la1) / 2) ** 2 + Math.cos(r(la1)) * Math.cos(r(la2)) * Math.sin(r(lo2 - lo1) / 2) ** 2;
  return 2 * 6371 * Math.asin(Math.sqrt(h));
}
const reversed = new Set(lines.filter((l) => l.reverses).map((l) => l.reverses));
const stays = {};
for (const a of admissions) (stays[a.member_id] ??= []).push(a);
const inStay = (l) => (stays[l.member_id] ?? []).find((a) => a.from_date < l.service_date && l.service_date < a.to_date && a.hospital_id !== l.provider_id);
const afterDeath = (l) => member[l.member_id].died_on && l.service_date > member[l.member_id].died_on;

test('generator twice = the committed bytes; nine feeds; under 12 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['address', 'admission', 'claim_line', 'member', 'owner', 'prescription', 'provider', 'provider_owner', 'referral']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 12_000_000, 'the corpus is under 12 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-health-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 20 000 members, 1 500 providers, October 2026 staged, synthetic identifiers', () => {
  assert.equal(members.length, 20000);
  assert.equal(providers.length, 1500);
  assert.equal(ownership.length, truth.counts.provider_owner);
  assert.equal(owners.length, truth.counts.owner);
  assert.equal(lines.length, truth.counts.claim_line_landed);
  assert.equal(scripts.length, truth.counts.prescription_landed);
  assert.equal(files().filter((f) => f.replaceAll('\\', '/').includes('/inbox/claim_line/')).length, 12, 'twelve monthly claim-line files');
  assert.ok(feed('claim_line', '/staged').every((l) => l.service_date.startsWith('2026-10')), 'the staged claim lines are October 2026');
  assert.ok(lines.every((l) => l.service_date <= '2026-09-30') && scripts.every((s) => s.filled_on <= '2026-09-30'));
  assert.ok([...lines, ...feed('claim_line', '/staged')].every((l) => provider[l.provider_id] && member[l.member_id]), 'every line names a provider and a member');
  assert.ok(providers.every((p) => !p.bank_account || /^SYN-\d{8}$/.test(p.bank_account)), 'invented bank accounts');
  assert.deepEqual([...new Set(lines.map((l) => l.procedure_code))].sort(), ['IMG-CT', 'IMG-MR', 'IMG-XR', 'INP', 'LAB', 'OFV', 'PHY', 'SPV'], 'invented procedure codes');
  assert.ok(lines.every((l) => (l.procedure_code === 'OFV') === (l.level !== '')), 'only office visits carry a level');
  const homes = members.map((m) => address[m.address_id]);
  const blank = homes.filter((a) => !a.lat).length / homes.length;
  assert.ok(blank > 0.01 && blank < 0.06, `missing map positions are realistic (${blank})`);
  assert.ok(ownership.every((r) => provider[r.provider_id] && owner[r.owner_id]));
});

test('referral kickbacks: two practices send 85 % to the imaging centre of one owner\'s spouse; the seaside practice to its only hospital', () => {
  const k = truth.referral_kickbacks;
  const share = (from, to) => referrals.filter((r) => r.from_provider === from && r.to_provider === to).length / referrals.filter((r) => r.from_provider === from).length;
  for (const gp of k.practices) {
    assert.ok(share(gp, k.imaging_centre) >= 0.78 && share(gp, k.imaging_centre) <= 0.92, `${gp}: about 85 %`);
    assert.equal(provider[gp].specialty, 'General practice');
  }
  assert.equal(provider[k.imaging_centre].specialty, 'Imaging');
  const ownersOf = (p) => ownership.filter((r) => r.provider_id === p).map((r) => r.owner_id);
  assert.deepEqual(ownersOf(k.imaging_centre), [k.spouse]);
  assert.ok(ownersOf(k.practices[0]).concat(ownersOf(k.practices[1])).includes(k.practice_owner));
  assert.equal(owner[k.practice_owner].address_id, k.home);
  assert.equal(owner[k.spouse].address_id, k.home, 'the two owners share one home');
  assert.equal(owners.filter((o) => o.address_id === k.home).length, 2);
  assert.equal(provider[k.lookalike_practice].specialty, 'General practice');
  assert.equal(address[provider[k.lookalike_practice].address_id].city, 'Scarborough');
  assert.ok(share(k.lookalike_practice, k.lookalike_hospital) >= 0.78, 'the seaside practice sends most to the hospital');
  assert.deepEqual(providers.filter((p) => p.specialty === 'Hospital' && address[p.address_id].city === 'Scarborough').map((p) => p.provider_id), [k.lookalike_hospital], 'the only hospital nearby');
  assert.equal(owner[k.lookalike_hospital_owner].owner_type, 'organisation');
  assert.ok(!referrals.some((r) => k.scarborough_practices.includes(r.from_provider) && r.to_provider === k.imaging_centre));
});

test('phantom billing: one clinic bills 40 patients in hospital or after death; the look-alike reverses its errors within a week', () => {
  const p = truth.phantom_billing;
  const open = lines.filter((l) => !l.reverses && !reversed.has(l.claim_id) && (inStay(l) || afterDeath(l)));
  assert.deepEqual([...new Set(open.map((l) => l.provider_id))], [p.clinic], 'only the clinic has open lines in a stay or after a death');
  assert.deepEqual([...new Set(open.filter(inStay).map((l) => l.member_id))].sort(), [...p.in_hospital].sort());
  assert.deepEqual([...new Set(open.filter(afterDeath).map((l) => l.member_id))].sort(), [...p.after_death].sort());
  assert.equal(p.in_hospital.length + p.after_death.length, 40);
  for (const m of p.after_death) assert.ok(member[m].died_on >= '2026-01-01' && member[m].died_on <= '2026-05-31', `${m} died in 2026`);
  const fixed = lines.filter((l) => l.provider_id === p.lookalike_clinic && reversed.has(l.claim_id));
  assert.deepEqual([...new Set(fixed.map((l) => l.member_id))].sort(), [...p.lookalike_patients].sort());
  for (const l of fixed) {
    assert.ok(inStay(l), `${l.claim_id}: the error was dated inside a stay`);
    const rev = lines.find((r) => r.reverses === l.claim_id);
    assert.ok(days(l.received_on, rev.received_on) <= 7 && +rev.billed === -+l.billed, `${l.claim_id}: reversed in full within a week`);
  }
  assert.equal(new Set(lines.filter((l) => reversed.has(l.claim_id)).map((l) => l.provider_id)).size, 16, 'the look-alike plus 15 single errors elsewhere');
});

test('pill mill: 70 patients over 50 km away paying cash at two pharmacies; the oncology centre\'s patients are local and insured', () => {
  const m = truth.pill_mill;
  const strong = scripts.filter((s) => s.drug_class === 'Strong opioid');
  const perPrescriber = {};
  for (const s of strong) (perPrescriber[s.prescriber_id] ??= new Set()).add(s.member_id);
  assert.deepEqual(Object.keys(perPrescriber).filter((p) => perPrescriber[p].size >= 40).sort(), [m.prescriber, m.lookalike].sort());
  assert.deepEqual([...perPrescriber[m.prescriber]].sort(), [...m.patients].sort());
  const mill = strong.filter((s) => s.prescriber_id === m.prescriber);
  const where = address[provider[m.prescriber].address_id];
  for (const pid of m.patients) assert.ok(km(address[member[pid].address_id], where) > 50, `${pid} lives over 50 km away`);
  assert.ok(mill.every((s) => +s.qty >= 90 && m.pharmacies.includes(s.pharmacy_id)), 'large quantities at the two pharmacies');
  assert.ok(mill.filter((s) => s.paid_cash === 'Y').length >= 0.8 * mill.length, 'mostly cash');
  assert.ok(m.pharmacies.every((ph) => provider[ph].specialty === 'Pharmacy' && address[provider[ph].address_id].city === 'Sheffield'));
  const onc = strong.filter((s) => s.prescriber_id === m.lookalike);
  const oncAt = address[provider[m.lookalike].address_id];
  for (const pid of m.lookalike_patients) assert.ok(km(address[member[pid].address_id], oncAt) < 15, `${pid} is local`);
  assert.ok(onc.filter((s) => s.paid_cash === 'Y').length <= 0.05 * onc.length, 'insured');
  assert.ok(!onc.some((s) => m.pharmacies.includes(s.pharmacy_id)));
  assert.equal(provider[m.lookalike].specialty, 'Oncology');
});

test('upcoding: three clinics under one owner bill the top level 3-5 times their patients\' ages; the elderly-care clinic as its ages predict', () => {
  const u = truth.upcoding;
  const band = (mid) => { const age = 2026 - +member[mid].birth_year; return age < 40 ? 'a' : age < 65 ? 'b' : age < 80 ? 'c' : 'd'; };
  const visits = lines.filter((l) => l.procedure_code === 'OFV' && !reversed.has(l.claim_id));
  const rate = {};
  for (const b of 'abcd') { const xs = visits.filter((l) => band(l.member_id) === b); rate[b] = xs.filter((l) => l.level === '5').length / xs.length; }
  const all = visits.filter((l) => l.level === '5').length / visits.length;
  const profile = (p) => {
    const xs = visits.filter((l) => l.provider_id === p);
    const share = xs.filter((l) => l.level === '5').length / xs.length;
    return { n: xs.length, share, ratio: share / (xs.reduce((s, l) => s + rate[band(l.member_id)], 0) / xs.length) };
  };
  assert.deepEqual(ownership.filter((r) => r.owner_id === u.owner).map((r) => r.provider_id).sort(), [...u.clinics].sort(), 'one owner, the three clinics');
  assert.equal(new Set(u.clinics.map((c) => address[provider[c].address_id].city)).size, 3, 'in three cities');
  for (const c of u.clinics) {
    const p = profile(c);
    assert.ok(p.n >= 150 && p.share >= 1.75 * all && p.ratio >= 3 && p.ratio <= 5.5, `${c}: ${JSON.stringify(p)}`);
  }
  const g = profile(u.lookalike);
  assert.ok(g.n >= 150 && g.share >= 1.75 * all && g.ratio < 1.5, `the elderly-care clinic: ${JSON.stringify(g)}`);
  assert.equal(provider[u.lookalike].specialty, 'Geriatric medicine');
  assert.ok(visits.filter((l) => l.provider_id === u.lookalike).every((l) => band(l.member_id) === 'd'), 'all its patients are over 80');
});
