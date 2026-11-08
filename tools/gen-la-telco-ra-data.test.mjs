// Determinism and planted facts of tools/gen-la-telco-ra-data.py. Run: node --test tools/gen-la-telco-ra-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-telco-ra/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: 300 SIMs of
// one dealer, device and day used and never billed; 400 SIMs of one dealer topped up once at its own shop and then dormant; four
// trunks calling premium-rate ranges in three countries at night and at weekends - and that each look-alike has the innocent
// shape. That the stories LINK (and the look-alikes do not) over the real link Datasets is TelcoRaLinksJobGoldenTest
// (inspecto-engine). Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-telco-ra', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-telco-ra-data.ground-truth.json');
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
const subs = feed('subscriber');
const sub = Object.fromEntries(subs.map((s) => [s.msisdn, s]));
const usage = feed('network_usage', '/inbox');
const billing = feed('billing_record', '/inbox');
const topups = feed('topup');
const calls = feed('trunk_call');
const trunks = Object.fromEntries(feed('enterprise_trunk').map((t) => [t.trunk_id, t.customer_id]));
const billed = new Set(billing.map((b) => `${b.msisdn}|${b.period}`));
const usageOf = {};
for (const u of usage) (usageOf[u.msisdn] ??= []).push(u);
const unbilledMonths = (m) => (usageOf[m] ?? []).filter((u) => !billed.has(`${u.msisdn}|${u.period}`)).length;
const topupsOf = {};
for (const t of topups) (topupsOf[t.msisdn] ??= []).push(t);
const weekOf = (ts) => {
  const d = new Date(ts.slice(0, 10) + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
};
const offHours = (c) => +c.start_hour < 7 || +c.start_hour >= 21 || [0, 6].includes(new Date(c.call_date + 'T00:00:00Z').getUTCDay());

test('generator twice = the committed bytes; seven feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['billing_record', 'dealer', 'enterprise_trunk', 'network_usage', 'subscriber', 'topup', 'trunk_call']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-telco-ra-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 7 500 subscribers, 200 dealers, 150 trunks, October 2026 staged, synthetic identifiers', () => {
  assert.equal(subs.length, 7500);
  assert.equal(feed('subscriber', '/inbox').length, truth.counts.subscriber_landed);
  assert.equal(feed('dealer').length, 200);
  assert.equal(Object.keys(trunks).length, 150);
  assert.equal(usage.length, truth.counts.network_usage_landed);
  assert.equal(billing.length, truth.counts.billing_record_landed);
  assert.equal(feed('topup', '/inbox').length, truth.counts.topup_landed);
  assert.equal(feed('trunk_call', '/inbox').length, truth.counts.trunk_call_landed);
  for (const f of ['network_usage', 'billing_record', 'topup', 'trunk_call'])
    assert.equal(files().filter((p) => p.replaceAll('\\', '/').includes(`/inbox/${f}/`)).length, 12, `twelve monthly ${f} files`);
  assert.ok(feed('subscriber', '/staged').every((s) => s.activated_at.startsWith('2026-10')), 'the staged subscribers are October activations');
  assert.ok(feed('network_usage', '/staged').every((u) => u.period === '2026-10-01'));
  assert.ok(feed('trunk_call', '/inbox').every((c) => c.call_date <= LANDED_END));
  assert.ok(subs.every((s) => /^9996\d{7}$/.test(s.msisdn) && /^00101\d{10}$/.test(s.imsi)), '+999 MSISDNs and test-network IMSIs');
  assert.equal(new Set(subs.map((s) => s.msisdn)).size, subs.length);
  assert.ok([...usage, ...billing, ...topups].every((r) => sub[r.msisdn]), 'every record names a subscriber');
  const blank = subs.filter((s) => !s.activation_device).length / subs.length;
  assert.ok(blank > 0.02 && blank < 0.08, `missing activation devices are realistic (${blank})`);
});

test('unbilled usage: 300 SIMs of one dealer, device and day, used every month, never billed or topped up', () => {
  const u = truth.unbilled_usage;
  for (const m of u.subscribers) {
    const s = sub[m];
    assert.equal(s.dealer_id, u.dealer);
    assert.equal(s.activation_device, u.device);
    assert.ok(s.activated_at.startsWith(u.day), `${m} activated on ${u.day}`);
    assert.equal(usageOf[m].length, 7, `${m}: used every month from March`);
    assert.equal(unbilledMonths(m), 7, `${m}: never billed`);
    assert.ok(!topupsOf[m], `${m}: never topped up`);
  }
  assert.equal(subs.filter((s) => s.dealer_id === u.dealer && s.activated_at.startsWith(u.day)).length, 300, 'nothing else that day');
  for (const m of u.test_sims) {
    assert.equal(sub[m].plan, 'Internal test', `${m} is on the known list`);
    assert.equal(sub[m].dealer_id, u.test_dealer);
    assert.ok(u.test_devices.includes(sub[m].activation_device));
    assert.equal(unbilledMonths(m), 12, `${m}: used and never billed`);
  }
  assert.deepEqual(subs.filter((s) => s.plan === 'Internal test').map((s) => s.msisdn).sort(), [...u.test_sims].sort());
  const over = subs.filter((s) => unbilledMonths(s.msisdn) >= 2).map((s) => s.msisdn).sort();
  assert.deepEqual(over, [...u.subscribers, ...u.test_sims, ...u.late_billing].sort(), 'nobody else is unbilled in two months');
  for (const m of u.late_billing) assert.equal(unbilledMonths(m), 2, `${m}: its first two months only`);
  for (const m of u.dropped_months) assert.equal(unbilledMonths(m), 1, `${m}: one dropped month`);
  assert.ok(![...u.late_billing, ...u.dropped_months].some((m) => [u.dealer, u.test_dealer].includes(sub[m].dealer_id)));
});

test('dealer commission fraud: 400 SIMs on 3 devices, one 5.00 top-up at the dealer\'s shop, then dormant; the university SIMs stay active', () => {
  const d = truth.dealer_commission_fraud;
  for (const m of d.subscribers) {
    const s = sub[m];
    assert.equal(s.dealer_id, d.dealer);
    assert.ok(d.devices.includes(s.activation_device));
    assert.ok(d.weeks.includes(weekOf(s.activated_at)), `${m}: activated in one of the six weeks`);
    assert.equal(topupsOf[m].length, 1, `${m}: one top-up`);
    const t = topupsOf[m][0];
    assert.ok(t.amount === '5.00' && t.channel === 'retail' && t.pos_id === d.pos, `${m}: 5.00 at the dealer's shop`);
    assert.ok((Date.parse(t.topped_up_at.slice(0, 10)) - Date.parse(s.activated_at.slice(0, 10))) / 86_400_000 <= 2);
    assert.equal(usageOf[m].length, 1, `${m}: used in its first month only`);
    assert.ok(+usageOf[m][0].minutes <= 4, `${m}: a test call`);
  }
  assert.equal(new Set(d.subscribers.map((m) => sub[m].activation_device)).size, 3, 'on all three devices');
  for (const m of d.lookalike_subscribers) {
    assert.equal(sub[m].dealer_id, d.lookalike_dealer);
    assert.ok(sub[m].activated_at >= '2025-10-01' && sub[m].activated_at < '2025-10-13', `${m}: start of term`);
    assert.ok(topupsOf[m].filter((t) => t.topped_up_at <= LANDED_END + ' 23:59:59').length >= 6, `${m}: keeps topping up`);
    assert.equal(usageOf[m].length, 12, `${m}: used every month`);
    assert.ok(topupsOf[m].every((t) => !t.pos_id || d.campus_pos.includes(t.pos_id)), `${m}: tops up near campus`);
  }
  assert.ok(!d.campus_pos.includes(d.pos));
  // the commission screen: dealer-weeks with 50 or more SIMs that were activated and topped up
  const earned = {};
  for (const s of feed('subscriber', '/inbox').filter((s) => topupsOf[s.msisdn])) (earned[`${s.dealer_id}|${weekOf(s.activated_at)}`] ??= []).push(s.msisdn);
  const spikes = Object.entries(earned).filter(([, v]) => v.length >= 50);
  assert.deepEqual([...new Set(spikes.map(([k]) => k.split('|')[0]))].sort(), [d.dealer, d.lookalike_dealer].sort(), 'only the two dealers spike');
  assert.deepEqual(spikes.flatMap(([, v]) => v).sort(), [...d.subscribers, ...d.lookalike_subscribers].sort());
});

test('PBX hacking: four trunks call premium ranges in three countries off hours; the look-alike calls its Singapore office', () => {
  const p = truth.pbx_hacking;
  const off = {};
  for (const c of calls.filter((c) => c.call_date <= LANDED_END && offHours(c))) {
    const k = `${c.trunk_id}|${c.destination_country}|${c.number_type}`;
    off[k] = (off[k] ?? 0) + +c.minutes;
  }
  const flagged = Object.keys(off).filter((k) => off[k] >= truth.off_hours_minutes_flag).sort();
  const prn = flagged.filter((k) => k.endsWith('|premium'));
  assert.deepEqual([...new Set(prn.map((k) => k.split('|')[0]))].sort(), [...p.trunks].sort(), 'only the four call premium ranges off hours');
  assert.deepEqual([...new Set(prn.map((k) => k.split('|')[1]))].sort(), [...p.countries].sort(), 'in three countries');
  assert.ok(p.trunks.every((t) => prn.filter((k) => k.startsWith(t + '|')).length >= 2), 'each hacked trunk calls two or three of them');
  assert.equal(new Set(p.trunks.map((t) => trunks[t])).size, 4, 'four different firms');
  const attack = calls.filter((c) => p.trunks.includes(c.trunk_id) && c.number_type === 'premium');
  assert.ok(attack.every((c) => offHours(c)), 'every premium call starts at night or at a weekend');
  assert.ok(attack.every((c) => c.call_date >= '2026-04-03' && c.call_date <= '2026-06-22'), 'over six weekends, April to June');
  assert.ok(calls.filter((c) => !p.trunks.includes(c.trunk_id) && c.number_type === 'premium').every((c) => !p.countries.includes(c.destination_country)),
    'nobody else calls the three countries\' premium ranges');
  const la = calls.filter((c) => c.trunk_id === p.lookalike_trunk && c.destination_country === 'SG');
  assert.ok(la.length >= 200 && la.every((c) => c.number_type === 'geographic' && ![0, 6].includes(new Date(c.call_date + 'T00:00:00Z').getUTCDay())),
    'the look-alike calls a geographic number on working days');
  assert.ok(la.filter((c) => +c.start_hour < 7).length > la.length / 2, 'mostly at night here, the Singapore working day');
  assert.deepEqual(flagged, [...prn, `${p.lookalike_trunk}|SG|geographic`, ...p.evening_trunks.map((t) => `${t}|US|geographic`)].sort());
});
