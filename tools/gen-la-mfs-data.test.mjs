// Determinism and planted facts of tools/gen-la-mfs-data.py. Run: node --test tools/gen-la-mfs-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-mfs/data and the ground truth beside itself) and asserts every file is
// byte-identical to what is committed, then checks each story's facts in the files against the ground truth: 30 SIMs swapped by one
// call-centre login in 10 days and each wallet drained within 2 hours from a handset it never used to 5 mules that cash out at 2
// agents; 120 wallets registered by one agent whose deposits it splits into many small cash-ins; a 3-week-old merchant paid by 60
// new wallets minutes after a cash-in at 3 agents, settling daily - and that each look-alike has the innocent shape. That the stories
// LINK (and the look-alikes do not) over the real link Datasets is MfsLinksJobGoldenTest (inspecto-engine). Skipped when python is
// not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-mfs', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-mfs-data.ground-truth.json');
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
const LANDED_END = '2026-09-30 23:59:59';
const wallets = feed('wallet');
const wallet = Object.fromEntries(wallets.map((w) => [w.wallet_id, w]));
const byMsisdn = Object.fromEntries(wallets.map((w) => [w.msisdn, w]));
const txns = feed('wallet_txn');
const landed = txns.filter((t) => t.txn_at <= LANDED_END);
const sims = feed('sim_event');
const logins = feed('device_login');
const ms = (ts) => Date.parse(ts.replace(' ', 'T') + 'Z');
const MIN = 60_000;
const outOf = {};
const inOf = {};
for (const t of landed) {
  (outOf[t.from_id] ??= []).push(t);
  (inOf[t.to_id] ??= []).push(t);
}
const firstLogin = {};
for (const l of logins) {
  const k = `${l.wallet_id}|${l.device_id}`;
  if (!firstLogin[k] || l.login_at < firstLogin[k]) firstLogin[k] = l.login_at;
}

test('generator twice = the committed bytes; seven feeds; under 12 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['agent', 'agent_commission', 'device_login', 'merchant', 'sim_event', 'wallet', 'wallet_txn']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 12_000_000, 'the corpus is under 12 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-mfs-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 8 000 wallets, 300 agents, 400 merchants, October 2026 staged, synthetic identifiers', () => {
  const c = truth.counts;
  assert.equal(wallets.length, 8000);
  assert.equal(feed('wallet', '/inbox').length, c.wallet_landed);
  assert.equal(feed('agent').length, 300);
  assert.equal(feed('merchant').length, 400);
  assert.equal(feed('wallet_txn', '/inbox').length, c.wallet_txn_landed);
  assert.equal(feed('sim_event', '/inbox').length, c.sim_event_landed);
  assert.equal(feed('device_login', '/inbox').length, c.device_login_landed);
  assert.equal(feed('agent_commission', '/inbox').length, c.agent_commission_landed);
  for (const f of ['wallet_txn', 'sim_event', 'device_login', 'agent_commission'])
    assert.equal(files().filter((p) => p.replaceAll('\\', '/').includes(`/inbox/${f}/`)).length, 12, `twelve monthly ${f} files`);
  assert.ok(feed('wallet', '/staged').every((w) => w.opened_at.startsWith('2026-10')), 'the staged wallets are October openings');
  assert.ok(feed('wallet_txn', '/staged').every((t) => t.txn_at.startsWith('2026-10')));
  assert.ok(feed('wallet_txn', '/inbox').every((t) => t.txn_at <= LANDED_END));
  assert.ok(wallets.every((w) => /^9997\d{7}$/.test(w.msisdn)), '+999 MSISDNs');
  assert.equal(new Set(wallets.map((w) => w.msisdn)).size, wallets.length);
  assert.equal(new Set(txns.map((t) => t.txn_id)).size, txns.length);
  const types = new Set(txns.map((t) => t.type));
  assert.deepEqual([...types].sort(), ['cash_in', 'cash_out', 'merchant_pay', 'p2p', 'settlement']);
  assert.ok(sims.every((e) => byMsisdn[e.msisdn] && ['sim_swap', 'port_out'].includes(e.event)), 'every SIM event is on a wallet number');
  assert.ok(sims.filter((e) => e.channel === 'store').every((e) => +e.event_at.slice(11, 13) >= 9 && +e.event_at.slice(11, 13) <= 18), 'stores swap in opening hours');
  const blank = wallets.filter((w) => !w.registered_by_agent).length / wallets.length;
  assert.ok(blank > 0.25 && blank < 0.4, `a third of customers registered in the app (${blank})`);
});

test('SIM-swap takeover: 30 swaps by S-41 in 10 days, each wallet drained within 2 hours from a new handset to 5 mules', () => {
  const s = truth.sim_swap_takeover;
  const fraud = sims.filter((e) => e.staff_login === s.login && e.event_at >= s.days[0] && e.event_at <= s.days[1] + ' 23:59:59');
  assert.deepEqual(fraud.map((e) => byMsisdn[e.msisdn].wallet_id).sort(), [...s.victims].sort(), 'the 30 swaps in the 10 days are the victims');
  assert.ok(fraud.every((e) => e.channel === 'call_centre'));
  for (const e of fraud) {
    const w = byMsisdn[e.msisdn].wallet_id;
    const t0 = ms(e.event_at);
    const out = (outOf[w] ?? []).filter((t) => ms(t.txn_at) >= t0 && ms(t.txn_at) <= t0 + 120 * MIN);
    assert.ok(out.length >= 1 && out.every((t) => t.type === 'p2p' && s.mules.includes(t.to_id)), `${w}: drained to mules within 2 hours`);
    assert.ok(Math.abs(out.reduce((n, t) => n + +t.amount, 0) - s.drained[w]) < 0.05, `${w}: the drained total`);
    assert.ok(out.every((t) => s.attacker_devices.includes(t.device_id)), `${w}: from an attacker handset`);
    const dev = out[0].device_id;
    assert.ok(ms(firstLogin[`${w}|${dev}`]) >= t0 && ms(firstLogin[`${w}|${dev}`]) <= t0 + 60 * MIN, `${w}: a handset it never used, within the hour`);
    const back = sims.filter((x) => x.msisdn === e.msisdn && x.event_at > e.event_at && x.event === 'sim_swap');
    assert.ok(back.length === 1 && back[0].channel === 'store' && back[0].staff_login !== s.lookalike_login, `${w}: the number back at a store`);
  }
  assert.equal(new Set(fraud.map((e) => byMsisdn[e.msisdn].wallet_id)).size, 30);
  for (const m of s.mules) {
    const ins = (inOf[m] ?? []).filter((t) => t.type === 'p2p');
    assert.ok(ins.length >= 2 && ins.every((t) => s.victims.includes(t.from_id)), `${m}: paid only by victims`);
    const outs = outOf[m] ?? [];
    assert.ok(outs.length >= 1 && outs.every((t) => t.type === 'cash_out' && s.cash_out_agents.includes(t.to_id)), `${m}: cashes out at the two agents`);
    assert.ok(Math.abs(outs.reduce((n, t) => n + +t.amount, 0) - ins.reduce((n, t) => n + +t.amount, 0)) < 0.05, `${m}: cashes out all of it`);
  }
  // the look-alike: a store login's upgrade drive; every customer keeps the balance
  const store = sims.filter((e) => e.staff_login === s.lookalike_login && e.event_at <= LANDED_END);
  assert.deepEqual(store.map((e) => byMsisdn[e.msisdn].wallet_id).sort(), [...s.lookalike_customers].sort());
  assert.ok(store.length >= 150 && store.every((e) => e.channel === 'store'));
  const drained = store.filter((e) => (outOf[byMsisdn[e.msisdn].wallet_id] ?? []).some((t) => ms(t.txn_at) >= ms(e.event_at) && ms(t.txn_at) <= ms(e.event_at) + 120 * MIN && +t.amount > 100));
  assert.equal(drained.length, 0, 'no store customer moves money out after the swap');
  assert.ok(!s.lookalike_customers.some((w) => s.victims.includes(w)));
});

test('split deposits: A-052 registered 120 wallets and splits their deposits into small cash-ins minutes apart; the market traders do not', () => {
  const d = truth.split_deposits;
  for (const w of d.wallets) {
    assert.equal(wallet[w].registered_by_agent, d.agent);
    assert.equal(wallet[w].kyc_level, '1');
    const ins = (inOf[w] ?? []).filter((t) => t.type === 'cash_in');
    assert.ok(ins.length >= 12 && ins.every((t) => t.from_id === d.agent && +t.amount <= 25), `${w}: many small cash-ins at ${d.agent}`);
    const days = new Set(ins.map((t) => t.txn_at.slice(0, 10)));
    assert.ok(ins.length / days.size >= 3, `${w}: several a visit`);
    const other = (outOf[w] ?? []).filter((t) => t.type !== 'cash_out');
    assert.ok(other.length <= 1, `${w}: hardly used for anything else`);
  }
  const atAgent = landed.filter((t) => t.type === 'cash_in' && t.from_id === d.agent);
  assert.equal(atAgent.length, d.cash_ins);
  assert.ok(atAgent.every((t) => d.wallets.includes(t.to_id)), 'every cash-in there is one of the 120');
  const trader = landed.filter((t) => t.type === 'cash_in' && t.from_id === d.lookalike_agent);
  assert.equal(trader.length, d.lookalike_cash_ins);
  const traderDays = new Set(trader.map((t) => `${t.to_id}|${t.txn_at.slice(0, 10)}`));
  assert.ok(trader.length / traderDays.size < 1.3, 'a trader makes one deposit a visit');
  const active = d.lookalike_traders.filter((w) => (outOf[w] ?? []).some((t) => t.type === 'merchant_pay' || t.type === 'p2p'));
  assert.ok(active.length > d.lookalike_traders.length * 0.6, `traders also pay merchants and each other (${active.length})`);
  // the screen: agents with 1 000 or more cash-ins averaging under 25
  const per = {};
  for (const t of landed.filter((t) => t.type === 'cash_in')) (per[t.from_id] ??= []).push(+t.amount);
  const flagged = Object.entries(per).filter(([, v]) => v.length >= truth.thresholds.split_cash_ins && v.reduce((a, b) => a + b, 0) / v.length < truth.thresholds.split_average_below);
  assert.deepEqual(flagged.map(([a]) => a).sort(), [d.agent, d.lookalike_agent].sort());
  // commission: the agent earns far more a deposit than its peers
  const com = feed('agent_commission', '/inbox').filter((r) => r.agent_id === d.agent && r.month >= '2026-02-01');
  assert.ok(com.every((r) => +r.transactions > 400), 'hundreds of commissioned transactions a month');
});

test('merchant cash-out: MR-077, three weeks old, paid by 60 new wallets minutes after a cash-in, settling daily; the grocery does not', () => {
  const m = truth.merchant_cash_out;
  const merchant = Object.fromEntries(feed('merchant').map((r) => [r.merchant_id, r]));
  assert.equal(merchant[m.merchant].opened_at, m.opened);
  assert.equal(merchant[m.merchant].settlement_account, m.account);
  const pays = landed.filter((t) => t.type === 'merchant_pay' && t.to_id === m.merchant);
  assert.deepEqual([...new Set(pays.map((t) => t.from_id))].sort(), [...m.payers].sort(), 'paid only by the 60');
  for (const p of pays) {
    const ins = (inOf[p.from_id] ?? []).filter((t) => t.type === 'cash_in' && ms(t.txn_at) <= ms(p.txn_at) && ms(t.txn_at) > ms(p.txn_at) - 30 * MIN);
    assert.ok(ins.length === 1 && m.agents.includes(ins[0].from_id) && +p.amount >= 0.95 * +ins[0].amount - 0.01, `${p.txn_id}: minutes after a cash-in at one of the three`);
  }
  for (const w of m.payers) {
    assert.ok(wallet[w].opened_at >= '2026-08-20' && m.agents.includes(wallet[w].registered_by_agent), `${w}: new, registered at a colluding agent`);
    assert.ok((outOf[w] ?? []).every((t) => t.to_id === m.merchant), `${w}: pays nobody else`);
  }
  const sets = landed.filter((t) => t.type === 'settlement' && t.from_id === m.merchant);
  assert.ok(sets.every((t) => t.to_id === m.account));
  assert.equal(new Set(sets.map((t) => t.txn_at.slice(0, 10))).size, sets.length, 'one settlement a day');
  assert.ok(sets.length >= 20, 'every day');
  // the look-alike: a grocery with 200 regulars all year, weekly settlement
  const g = landed.filter((t) => t.type === 'merchant_pay' && t.to_id === m.lookalike_merchant);
  assert.deepEqual([...new Set(g.map((t) => t.from_id))].sort(), [...m.lookalike_payers].sort());
  assert.ok(g.some((t) => t.txn_at < '2025-11-01') && g.some((t) => t.txn_at > '2026-09-01'), 'all year');
  const gs = landed.filter((t) => t.type === 'settlement' && t.from_id === m.lookalike_merchant);
  assert.ok(gs.every((t) => new Date(t.txn_at.slice(0, 10) + 'T00:00:00Z').getUTCDay() === 1), 'settles on Mondays');
  assert.ok(!m.payers.some((w) => m.lookalike_payers.includes(w)));
  assert.ok(m.lookalike_quick_payers.length >= truth.thresholds.quick_payers && m.lookalike_quick_payers.length < m.lookalike_payers.length / 2,
    'many shoppers pay right after a cash-in, most do not');
});
