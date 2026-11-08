// Determinism and planted facts of tools/gen-la-benefits-data.py. Run: node --test tools/gen-la-benefits-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-benefits/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: 14 claimants
// declaring households of one at one address, nine pensions still paid after the deaths, twenty jobseekers on two payrolls, a
// preparer's eighty inflated refunds into six accounts - and that each look-alike has the innocent shape. That the stories LINK
// (and the look-alikes do not) over the real link Datasets is BenefitsLinksJobGoldenTest (inspecto-engine).
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-benefits', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-benefits-data.ground-truth.json');
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
/** Every row of a feed (landed and staged) as objects. Only the address street can hold a comma, and it is quoted. */
function feed(name, box = '') {
  const rows = [];
  for (const p of files().filter((f) => f.replaceAll('\\', '/').includes(`${box}/${name}/`)).sort()) {
    const [head, ...lines] = readFileSync(p, 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) {
      const cells = l.match(/("[^"]*"|[^,]*)(,|$)/g).map((c) => c.replace(/,$/, '').replace(/^"|"$/g, ''));
      rows.push(Object.fromEntries(cols.map((c, i) => [c, cells[i] ?? ''])));
    }
  }
  return rows;
}
const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
const days = (a, b) => (Date.parse(b) - Date.parse(a)) / 86_400_000;
const people = feed('person');
const person = Object.fromEntries(people.map((p) => [p.person_id, p]));
const claims = feed('claim');
const claimsOf = (pid) => claims.filter((c) => c.person_id === pid);
const deaths = feed('death_register');
const payroll = feed('payroll');
const returns = feed('tax_return');
const households = feed('household');

test('generator twice = the committed bytes; seven feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['address', 'claim', 'death_register', 'household', 'payroll', 'person', 'tax_return']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-benefits-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 12 000 people, 6 500 addresses, 300 deaths, 8 000 returns, October 2026 staged, synthetic identifiers', () => {
  assert.equal(people.length, 12000);
  assert.equal(feed('address').length, 6500);
  assert.equal(deaths.length, 300);
  assert.equal(returns.length, 8000);
  assert.equal(feed('claim', '/inbox').length, truth.counts.claim_landed);
  assert.equal(feed('payroll', '/inbox').length, truth.counts.payroll_landed);
  assert.equal(files().filter((f) => f.replaceAll('\\', '/').includes('/inbox/payroll/')).length, 12, 'twelve monthly payroll files');
  assert.ok(feed('payroll', '/staged').every((r) => r.period === '2026-10-01'), 'the staged payroll is October 2026');
  assert.ok(feed('claim', '/staged').every((c) => c.start_date.startsWith('2026-10')), 'the staged claims start in October 2026');
  assert.ok(feed('tax_return', '/inbox').every((r) => r.filed_at <= '2026-09-30'));
  assert.ok(claims.every((c) => person[c.person_id]), 'every claim names a person');
  assert.ok(people.every((p) => !p.phone || /^(07700 900|01632 960|01\d{2} 496 0|0191 498 0|020 7946 0|029 2018 0)\d{3}$/.test(p.phone)), 'drama-range phones');
  assert.ok(new Set(people.filter((p) => p.phone).map((p) => p.phone)).size === people.filter((p) => p.phone).length, 'no two people share a phone');
  assert.ok([...claims, ...returns].every((r) => !r.bank_account || /^SYN-\d{8}$/.test(r.bank_account)), 'invented bank accounts');
  const blankPhone = people.filter((p) => !p.phone).length / people.length;
  assert.ok(blankPhone > 0.03 && blankPhone < 0.5, `missing phones are realistic (${blankPhone})`);
});

test('address farm: 14 claimants, 5 surnames, 14 households of one, 3 accounts nobody else uses', () => {
  const f = truth.address_farm;
  const residents = people.filter((p) => p.address_id === f.address);
  assert.deepEqual(residents.map((p) => p.person_id).sort(), [...f.claimants].sort());
  assert.equal(new Set(residents.map((p) => p.name.split(' ').at(-1))).size, 5, 'five surnames');
  const decl = households.filter((h) => h.address_id === f.address);
  assert.equal(decl.length, 14);
  assert.ok(decl.every((h) => h.declared_members === '1' && f.claimants.includes(h.declared_by)), 'each declares a household of one');
  const accts = new Set(f.claimants.flatMap((p) => claimsOf(p).map((c) => c.bank_account)));
  assert.deepEqual([...accts].sort(), [...f.accounts].sort());
  assert.ok(claims.filter((c) => f.accounts.includes(c.bank_account)).every((c) => f.claimants.includes(c.person_id)));
  for (const [home, n] of Object.entries(f.care_homes)) {
    const res = people.filter((p) => p.address_id === home);
    assert.equal(res.length, n);
    assert.ok(n >= 20 && n <= 35);
    assert.equal(households.filter((h) => h.address_id === home).length, 1, `${home}: one declaration`);
    const own = res.map((p) => new Set(claimsOf(p.person_id).map((c) => c.bank_account)));
    assert.ok(own.every((s) => s.size === 1), `${home}: each resident has one account`);
    assert.equal(new Set(own.map((s) => [...s][0])).size, n, `${home}: nobody shares one`);
  }
});

test('payments to the deceased: nine pensions never stopped; the late notice is stopped and recovered', () => {
  const d = truth.payments_to_the_deceased;
  const died = Object.fromEntries(deaths.map((r) => [r.person_id, r]));
  for (const pid of d.deceased) {
    assert.ok(died[pid], `${pid} is on the death register`);
    assert.ok(died[pid].died_on >= '2025-11-01' && died[pid].died_on <= '2026-04-30');
    assert.ok(days(died[pid].died_on, died[pid].notified_on) <= 14, `${pid}: the death was notified promptly`);
    const c = claimsOf(pid);
    assert.equal(c.length, 1);
    assert.equal(c[0].benefit_type, 'Pension');
    assert.equal(c[0].end_date, '', `${pid}: the pension is never stopped`);
    assert.ok(d.accounts.includes(c[0].bank_account));
  }
  const ctl = claimsOf(d.controller);
  assert.deepEqual(ctl.map((c) => c.bank_account).sort(), [...d.accounts].sort(), 'the living person has one claim into each account');
  const others = deaths.filter((r) => !d.deceased.includes(r.person_id));
  for (const r of others)
    for (const c of claimsOf(r.person_id).filter((c) => c.start_date <= r.died_on))
      assert.ok(c.end_date && c.end_date <= '2026-09-30' && days(r.died_on, c.end_date) <= 65, `${c.claim_id}: stopped after the notice`);
  const lags = deaths.map((r) => days(r.died_on, r.notified_on));
  assert.ok(lags.every((n) => n >= 0 && n <= 60) && lags.filter((n) => n <= 7).length > lags.length / 2, 'most notices arrive within a week, none after 60 days');
  const la = died[d.lookalike];
  assert.equal(days(la.died_on, la.notified_on), 40);
  const lc = claimsOf(d.lookalike)[0];
  assert.equal(days(la.died_on, lc.end_date), 42);
  assert.ok(+lc.overpaid_recovered > 0.9 * (+lc.monthly_amount * 42) / 30.4, 'the overpayment is recovered in full');
});

test('undeclared work: twenty jobseekers paid full wages by two employers; the look-alike earns under the limit', () => {
  const w = truth.undeclared_work;
  const limit = truth.permitted_earnings_limit;
  const jobseekerOf = (pid) => claimsOf(pid).find((c) => c.benefit_type === 'Jobseeker');
  const whileClaiming = (pid) => {
    const c = jobseekerOf(pid);
    return c ? payroll.filter((r) => r.person_id === pid && r.period >= c.start_date.slice(0, 7) + '-01' && (!c.end_date || r.period <= c.end_date)) : [];
  };
  for (const pid of w.claimants) {
    const rows = whileClaiming(pid).filter((r) => r.period <= '2026-09-30');
    assert.ok(new Set(rows.map((r) => r.period)).size >= 6, `${pid}: six months or more while claiming`);
    assert.ok(rows.every((r) => w.employers.includes(r.employer_id) && +r.wages >= 1000 && +r.wages <= 2000), `${pid}: full wages from the two`);
  }
  for (const pid of w.on_both) assert.equal(new Set(whileClaiming(pid).map((r) => r.employer_id)).size, 2, `${pid} is on both payrolls`);
  const la = whileClaiming(w.lookalike);
  assert.ok(la.length >= 4 && la.every((r) => +r.wages < limit), 'the look-alike earns under the limit');
  for (const pid of w.permitted_earners) assert.ok(whileClaiming(pid).every((r) => +r.wages < limit), pid);
  const overLong = new Set();
  for (const p of people) {
    const months = new Set(whileClaiming(p.person_id).filter((r) => +r.wages > limit).map((r) => r.period));
    if (months.size > 1) overLong.add(p.person_id);
  }
  assert.deepEqual([...overLong].sort(), [...w.claimants].sort(), 'nobody else is over the limit for more than a month');
});

test('tax-preparer ring: P-12 files 80 inflated refunds into 6 accounts; P-03 refunds 250 clients into their own', () => {
  const t = truth.tax_preparer_ring;
  const ring = returns.filter((r) => r.preparer_id === t.preparer);
  assert.equal(ring.length, 80);
  assert.deepEqual(ring.map((r) => r.taxpayer_id).sort(), [...t.taxpayers].sort());
  assert.deepEqual([...new Set(ring.map((r) => r.bank_account))].sort(), [...t.accounts].sort());
  assert.ok(ring.every((r) => +r.refund >= 1800 && +r.refund <= 3400 && r.filed_at >= '2026-01-01' && r.filed_at <= '2026-02-28'));
  const refunds = returns.filter((r) => +r.refund > 0).map((r) => +r.refund).sort((a, b) => a - b);
  const median = refunds[Math.floor(refunds.length / 2)];
  assert.ok(median > 200 && median < 500, `the usual refund is a few hundred (${median})`);
  const big = returns.filter((r) => r.preparer_id === t.lookalike_preparer);
  assert.equal(big.length, t.lookalike_clients);
  const claimAcct = Object.fromEntries(claims.map((c) => [c.person_id, c.bank_account]));
  const users = {};
  for (const r of returns.filter((r) => r.bank_account)) (users[r.bank_account] ??= new Set()).add(r.taxpayer_id);
  assert.ok(big.filter((r) => r.bank_account).every((r) => users[r.bank_account].size <= 2), 'P-03 clients are refunded into their own (or a joint) account');
  assert.deepEqual(Object.keys(users).filter((a) => users[a].size > 2).sort(), [...t.accounts].sort(), 'only the ring shares refund accounts');
  assert.ok(ring.every((r) => claimAcct[r.taxpayer_id] !== r.bank_account), 'the ring accounts are not the taxpayers\' own');
  const perPreparer = {};
  for (const r of returns.filter((r) => r.preparer_id)) perPreparer[r.preparer_id] = (perPreparer[r.preparer_id] ?? 0) + 1;
  assert.ok(Object.entries(perPreparer).every(([p, n]) => [t.preparer, t.lookalike_preparer].includes(p) || n <= 55), 'other preparers are small');
});
