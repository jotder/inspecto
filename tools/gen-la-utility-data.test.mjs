// Determinism and planted facts of tools/gen-la-utility-data.py. Run: node --test tools/gen-la-utility-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-utility/data and the ground truth beside itself) and asserts every file is
// byte-identical to what is committed, then checks each story's facts in the files against the ground truth: the drop after a C-14
// visit, consumption while disconnected, churn with arrears onto one bank account, a flat 3-5x load, over-credited night tokens - and
// that each look-alike has the innocent shape. That the stories LINK (and the look-alikes do not) over the real link Dataset is
// UtilityLinksJobGoldenTest (inspecto-engine). Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-utility', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-utility-data.ground-truth.json');
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
/** Every row of a feed (landed and staged) as objects. No value in this corpus holds a comma. */
function feed(name) {
  const rows = [];
  for (const p of files().filter((f) => f.replaceAll('\\', '/').includes(`/${name}/`)).sort()) {
    const [head, ...lines] = readFileSync(p, 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) rows.push(Object.fromEntries(l.split(',').map((v, i) => [cols[i], v])));
  }
  return rows;
}
const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
const story = (id) => truth.stories[id];
const day = (s) => s.slice(0, 10);
const addDays = (d, n) => new Date(Date.parse(d) + n * 86_400_000).toISOString().slice(0, 10);
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const cv = (xs) => Math.sqrt(mean(xs.map((x) => (x - mean(xs)) ** 2))) / mean(xs);

const reads = {};
for (const r of feed('meter_read')) (reads[r.premise_id] ??= {})[r.read_date] = Number(r.kwh);
const window = (pid, from, to) => Object.entries(reads[pid]).filter(([d]) => d >= from && d <= to).map(([, k]) => k);
const premise = Object.fromEntries(feed('premise').map((p) => [p.premise_id, p]));
const accounts = feed('customer_account');
const visits = feed('field_visit');
const orders = feed('service_order');
const payments = feed('payment');
const vends = feed('prepaid_vend');

test('generator twice = the committed bytes; eight feeds; under 14 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['customer_account', 'field_visit', 'meter_read', 'payment', 'premise', 'prepaid_vend', 'service_order', 'transformer_read']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 14_000_000, 'the corpus is under 14 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-utility-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus is 1 000 premises, 12 months landed and one month staged', () => {
  assert.equal(Object.keys(premise).length, 1000);
  const landed = files().filter((f) => f.replaceAll('\\', '/').includes('/inbox/meter_read/')).length;
  assert.equal(landed, 12, 'twelve monthly meter_read files');
  assert.deepEqual(Object.keys(reads['PRM-0001']).sort().at(-1), '2026-10-31', 'the staged month carries on to October 2026');
  assert.equal(new Set(feed('transformer_read').map((r) => r.transformer_id)).size, 12, '30 % of 40 transformers are metered');
});

test('meter tampering: 22 premises under TX-007/TX-019 drop 40-70 % within two weeks of a C-14 visit; the holiday home has no visit', () => {
  const s = story('meter_tampering');
  const prem = s.entities.filter((e) => e.startsWith('PRM-'));
  assert.equal(prem.length, 22);
  for (const pid of prem) {
    assert.ok(['TX-007', 'TX-019'].includes(premise[pid].transformer_id), pid);
    const v = truth.tamper_visit[pid];
    assert.ok(visits.some((x) => x.premise_id === pid && x.contractor_id === 'C-14' && day(x.visit_at) === v), `${pid} visited by C-14`);
    const ratio = mean(window(pid, addDays(v, 18), addDays(v, 45))) / mean(window(pid, addDays(v, -28), addDays(v, -1)));
    assert.ok(ratio > 0.25 && ratio < 0.68, `${pid}: after/before ${ratio.toFixed(2)}`);
  }
  assert.ok(truth.metered_transformers.includes('TX-007') && truth.metered_transformers.includes('TX-019'));
  const [home] = s.lookalikes;
  assert.equal(visits.filter((x) => x.premise_id === home).length, 0, 'the holiday home has no visit');
  assert.ok(mean(window(home, '2025-12-01', '2026-01-31')) < 0.3 * mean(window(home, '2026-04-01', '2026-09-30')), 'empty in winter');
});

test('illegal reconnection: 15 premises keep consuming while disconnected; C-22 closes the reconnect with no payment', () => {
  const s = story('illegal_reconnection');
  const prem = s.entities.filter((e) => e.startsWith('PRM-'));
  assert.equal(prem.length, 15);
  const reconnect = (pid) => orders.find((o) => o.premise_id === pid && o.order_type === 'reconnect');
  const paidBetween = (pid, from, to) => {
    const ids = new Set(accounts.filter((a) => a.premise_id === pid).map((a) => a.account_id));
    return payments.some((p) => ids.has(p.account_id) && day(p.paid_at) >= from && day(p.paid_at) <= to);
  };
  for (const pid of prem) {
    const d = truth.disconnected[pid], r = reconnect(pid);
    assert.equal(r.closed_by, 'C-22', pid);
    assert.ok(mean(window(pid, addDays(d, 1), day(r.closed_at))) > 2, `${pid} consumes while off`);
    assert.ok(!paidBetween(pid, d, day(r.closed_at)), `${pid}: no payment before the reconnect`);
  }
  assert.equal(s.lookalikes.length, 10);
  for (const pid of s.lookalikes) {
    const d = truth.disconnected[pid], r = reconnect(pid);
    assert.notEqual(r.closed_by, 'C-22', pid);
    assert.equal(Math.max(...window(pid, addDays(d, 1), addDays(day(r.closed_at), -1))), 0, `${pid} reads zero while off`);
    assert.ok(paidBetween(pid, d, day(r.closed_at)), `${pid}: paid before the reconnect`);
  }
});

test('account churn: two accounts per premise closed with arrears, reopened within 30 days under another name, one bank account', () => {
  const s = story('account_churn');
  const prem = s.entities.filter((e) => e.startsWith('PRM-'));
  assert.equal(prem.length, 6);
  const banks = new Set();
  let closed = 0;
  for (const pid of prem) {
    const acc = accounts.filter((a) => a.premise_id === pid).sort((a, b) => a.opened_at.localeCompare(b.opened_at));
    assert.equal(acc.length, 3, pid);
    for (let i = 0; i < 2; i++) {
      assert.ok(Number(acc[i].arrears) > 0, `${acc[i].account_id} closed with arrears`);
      const gap = (Date.parse(acc[i + 1].opened_at) - Date.parse(acc[i].closed_at)) / 86_400_000;
      assert.ok(gap >= 0 && gap <= 30, `${pid}: reopened after ${gap} days`);
      assert.notEqual(acc[i + 1].holder_name, acc[i].holder_name);
      closed++;
    }
    acc.forEach((a) => banks.add(a.bank_account));
  }
  assert.equal(closed, 12);
  assert.deepEqual([...banks], [s.hub], 'all 18 accounts pay from one bank account');
  for (const pid of s.lookalikes.filter((e) => e.startsWith('PRM-'))) {
    const [old, now] = accounts.filter((a) => a.premise_id === pid).sort((a, b) => a.opened_at.localeCompare(b.opened_at));
    assert.equal(Number(old.arrears), 0, `${pid}: the leaver settled`);
    assert.notEqual(now.bank_account, old.bank_account, `${pid}: the new tenant has a new bank account`);
  }
});

test('commercial load on a residential tariff: six flat loads at 3-5x the street median, one payer; the heat-pump home is cyclical', () => {
  const s = story('commercial_load');
  const prem = s.entities.filter((e) => e.startsWith('PRM-'));
  assert.equal(prem.length, 6);
  const street = (pid) => premise[pid].address.replace(/^\d+ /, '');
  const all = (pid) => window(pid, '2025-10-01', '2026-09-30');
  for (const pid of prem) {
    assert.equal(premise[pid].tariff, 'residential');
    assert.ok(cv(all(pid)) < 0.06, `${pid} is flat (cv ${cv(all(pid)).toFixed(3)})`);
    const peers = Object.keys(premise).filter((p) => p !== pid && street(p) === street(pid) && premise[p].tariff === 'residential').map((p) => mean(all(p))).sort((a, b) => a - b);
    const ratio = mean(all(pid)) / peers[Math.floor(peers.length / 2)];
    assert.ok(ratio >= 3 && ratio <= 5.5, `${pid}: ${ratio.toFixed(2)}x the street median`);
  }
  assert.deepEqual([...new Set(accounts.filter((a) => prem.includes(a.premise_id)).map((a) => a.bank_account))], [s.hub]);
  const hp = s.lookalikes.find((e) => e.startsWith('PRM-'));
  assert.ok(cv(all(hp)) > 0.25, 'the heat-pump home varies');
  assert.ok(mean(window(hp, '2026-01-01', '2026-01-31')) > 2 * mean(window(hp, '2026-07-01', '2026-07-31')), 'winter heating');
});

test('prepaid token fraud: V-08 credits 40 premises 2.5-4x their money, mostly at night; V-01 sells at the list price by day', () => {
  const s = story('prepaid_token_fraud');
  const prem = new Set(s.entities.filter((e) => e.startsWith('PRM-')));
  assert.equal(prem.size, 40);
  const night = (v) => Number(v.vend_at.slice(11, 13)) >= 22 || Number(v.vend_at.slice(11, 13)) < 5;
  const fraud = vends.filter((v) => prem.has(v.premise_id) && v.vend_at >= '2026-01-05');
  assert.ok(fraud.every((v) => v.vendor_id === 'V-08' && Number(v.kwh) / Number(v.amount) >= 10), 'over-credited by V-08');
  assert.ok(fraud.filter(night).length / fraud.length > 0.6, 'mostly at night');
  const v01 = vends.filter((v) => v.vendor_id === 'V-01');
  const busiest = Math.max(...['V-02', 'V-03', 'V-04', 'V-05', 'V-06', 'V-07', 'V-08'].map((id) => vends.filter((v) => v.vendor_id === id).length));
  assert.ok(v01.length > busiest, 'the supermarket sells the most');
  assert.ok(v01.every((v) => Number(v.kwh) === Number(v.amount) * 4 && !night(v)), 'list price, daytime');
  assert.ok(!v01.some((v) => prem.has(v.premise_id)), 'no fraud premise buys from V-01');
});
