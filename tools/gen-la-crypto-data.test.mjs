// Determinism and planted facts of tools/gen-la-crypto-data.py. Run: node --test tools/gen-la-crypto-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-crypto/data and the ground truth beside itself) and asserts every file is
// byte-identical to what is committed, then checks each story's facts in the files against the ground truth: 412.5 SYC stolen from a hacked wallet
// into an 80-hop peel chain whose peels land at 6 exchange deposit addresses; three ransom lots that leave the mixer within 2 hours at
// 98-99 % to one exchange account; one cluster paying 40 accounts in 40 names that cash out to 3 bank accounts - and that each
// look-alike has the innocent shape. That the stories LINK (and the look-alikes do not) over the real link Datasets is
// CryptoLinksJobGoldenTest (inspecto-engine). Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-crypto', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-crypto-data.ground-truth.json');
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
const labelOf = Object.fromEntries(wallets.map((w) => [w.address, w.label]));
const transfers = feed('transfer');
const landed = transfers.filter((t) => t.tx_at <= LANDED_END);
const accounts = feed('exchange_account');
const accountOfDeposit = Object.fromEntries(accounts.map((a) => [a.deposit_address, a]));
const persons = Object.fromEntries(feed('kyc_person').map((p) => [p.person_id, p]));
const cashouts = feed('cashout');
const clusterOf = Object.fromEntries(feed('cluster_hint').map((c) => [c.address, c.cluster_id]));
const ms = (ts) => Date.parse(ts.replace(' ', 'T') + 'Z');
const MIN = 60_000;
const outOf = {};
const inOf = {};
for (const t of landed) {
  (outOf[t.from_address] ??= []).push(t);
  (inOf[t.to_address] ??= []).push(t);
}

test('generator twice = the committed bytes; six feeds; under 8 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['cashout', 'cluster_hint', 'exchange_account', 'kyc_person', 'transfer', 'wallet']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 8_000_000, 'the corpus is under 8 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-crypto-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: invented syn1 addresses, five invented exchanges, October 2026 staged, one asset per pair', () => {
  const c = truth.counts;
  assert.equal(feed('wallet', '/inbox').length, c.wallet_landed);
  assert.equal(feed('transfer', '/inbox').length, c.transfer_landed);
  assert.equal(feed('cluster_hint', '/inbox').length, c.cluster_hint_landed);
  assert.equal(feed('exchange_account', '/inbox').length, c.exchange_account_landed);
  assert.equal(feed('kyc_person', '/inbox').length, c.kyc_person_landed);
  assert.equal(feed('cashout', '/inbox').length, c.cashout_landed);
  for (const f of ['transfer', 'cashout'])
    assert.equal(files().filter((p) => p.replaceAll('\\', '/').includes(`/inbox/${f}/`)).length, 12, `twelve monthly ${f} files`);
  assert.ok(feed('transfer', '/staged').length > 0 && feed('transfer', '/staged').every((t) => t.tx_at.startsWith('2026-10')));
  assert.ok(feed('wallet', '/staged').every((w) => w.first_seen.startsWith('2026-10')));
  assert.ok(feed('transfer', '/inbox').every((t) => t.tx_at <= LANDED_END));
  assert.ok(wallets.every((w) => /^syn1[02-9ac-hj-np-z]{26}$/.test(w.address)), 'the invented syn1 format, never a real chain');
  assert.equal(new Set(wallets.map((w) => w.address)).size, wallets.length);
  assert.equal(new Set(transfers.map((t) => t.tx_id)).size, transfers.length);
  assert.ok(transfers.every((t) => labelOf[t.from_address] && labelOf[t.to_address]), 'every transfer end is a listed address');
  assert.deepEqual([...new Set(wallets.map((w) => w.label))].sort(), ['exchange', 'hacked', 'merchant', 'mixer', 'unknown']);
  assert.deepEqual([...new Set(transfers.map((t) => t.asset))].sort(), ['SUSD', 'SYC']);
  const pairAsset = {};
  for (const t of transfers) assert.equal((pairAsset[`${t.from_address}>${t.to_address}`] ??= t.asset), t.asset, 'one asset per address pair');
  assert.deepEqual([...new Set(accounts.map((a) => a.exchange))].sort(),
    ['Ashgrove Exchange', 'Fernhill Coin Exchange', 'Larkmoor Digital Exchange', 'Pebblebrook Exchange', 'Wrenfield Exchange']);
  assert.ok(accounts.every((a) => persons[a.kyc_person_id] && labelOf[a.deposit_address] === 'exchange'));
  assert.ok(cashouts.every((c) => /^BK-\d{8}$/.test(c.bank_account) && accounts.some((a) => a.account_id === c.account_id)));
  const blank = Object.values(persons).filter((p) => !p.birth_year).length / Object.keys(persons).length;
  assert.ok(blank > 0.02 && blank < 0.08, `a few birth years are blank (${blank})`);
});

test('peel chain: 412.5 SYC leaves the hacked wallet into 80 hops of one in, two out, peeling into 6 exchange deposit addresses', () => {
  const p = truth.peel_chain;
  assert.equal(labelOf[p.hacked], 'hacked');
  const theft = outOf[p.hacked].filter((t) => t.tx_at.startsWith('2026-03-14'));
  assert.equal(theft.length, 1);
  assert.equal(theft[0].to_address, p.hops[0]);
  assert.equal(+theft[0].amount, p.stolen);
  let peeled = 0;
  p.hops.forEach((h, i) => {
    const ins = inOf[h];
    const outs = outOf[h];
    assert.equal(ins.length, 1, `${h}: one transfer in`);
    assert.equal(outs.length, 2, `${h}: two out`);
    const next = i + 1 < p.hops.length ? p.hops[i + 1] : p.keeper;
    const change = outs.find((t) => t.to_address === next);
    const peel = outs.find((t) => t.to_address !== next);
    assert.ok(change && peel && p.deposits.includes(peel.to_address), `${h}: the change goes on, the peel to a deposit address`);
    assert.ok(+peel.amount < 5 && +peel.amount < +change.amount / 20, `${h}: a small peel`);
    assert.ok(outs.every((t) => ms(t.tx_at) >= ms(ins[0].tx_at) && ms(t.tx_at) <= ms(ins[0].tx_at) + 24 * 60 * MIN), `${h}: within 24 hours`);
    peeled += +peel.amount;
  });
  assert.ok(Math.abs(peeled - p.peeled) < 1e-4);
  assert.equal(outOf[p.keeper], undefined, 'the last change address keeps the rest');
  assert.deepEqual(p.deposits.map((d) => accountOfDeposit[d].account_id).sort(), [...p.accounts].sort());
  for (const d of p.deposits) assert.ok(inOf[d].every((t) => p.hops.includes(t.from_address)), `${d} receives peels only`);
  assert.ok(ms(p.last_hop_at) - ms(p.hacked_at) < 5 * 24 * 60 * MIN, 'the chain runs in under 5 days');
});

test('peel look-alike: a payroll wallet whose monthly run pays the same 25 staff wallets, change to a fresh address each time', () => {
  const p = truth.peel_chain;
  const runs = outOf[p.lookalike_wallet] ?? [];
  assert.equal(runs.length, 12, 'one run a month from the company wallet');
  const staff = new Set(p.lookalike_staff);
  let hops = 0;
  for (const run of runs) {   // walk each run: every hop pays one staff wallet and passes the change on, the last back to the company
    const paid = new Set();
    let h = run.to_address;
    while (h !== p.lookalike_wallet) {
      const outs = outOf[h];
      assert.ok(inOf[h].length === 1 && outs.length === 2, `${h}: one transfer in, two out`);
      const pay = outs.find((t) => staff.has(t.to_address));
      assert.ok(pay, `${h} pays a staff wallet`);
      paid.add(pay.to_address);
      h = outs.find((t) => t !== pay).to_address;
      hops++;
    }
    assert.deepEqual([...paid].sort(), [...staff].sort(), `the ${run.tx_at.slice(0, 7)} run pays every staff wallet once`);
  }
  assert.equal(hops, p.lookalike_hops_landed);
  assert.ok(!(inOf[p.lookalike_wallet] ?? []).some((t) => labelOf[t.from_address] === 'hacked'));
  assert.ok(![...staff].some((s) => accountOfDeposit[s]), 'staff wallets are personal, not exchange deposit addresses');
});

test('mixer hop: three lots out within 2 hours at 98-99 % of the input, all to one exchange account; the near misses are not', () => {
  const m = truth.mixer_hop;
  const ins = (outOf[m.input_address] ?? []).filter((t) => labelOf[t.to_address] === 'mixer');
  assert.deepEqual(ins.map((t) => +t.amount), m.inputs);
  m.exits.forEach((x, i) => {
    const out = inOf[x];
    assert.equal(out.length, 1);
    assert.equal(labelOf[out[0].from_address], 'mixer');
    const mins = (ms(out[0].tx_at) - ms(ins[i].tx_at)) / MIN;
    const ratio = +out[0].amount / +ins[i].amount;
    assert.ok(mins > 0 && mins <= 120 && ratio >= 0.98 && ratio <= 1, `${x}: ${mins} minutes, ratio ${ratio}`);
    assert.deepEqual(outOf[x].map((t) => t.to_address), [m.deposit], `${x}: deposited whole at one exchange`);
  });
  assert.equal(accountOfDeposit[m.deposit].account_id, m.account);
  assert.equal(accountOfDeposit[m.deposit].exchange, m.exchange);
  assert.equal(accountOfDeposit[m.deposit].kyc_person_id, m.person);
  const exits = landed.filter((t) => labelOf[t.from_address] === 'mixer' && labelOf[t.to_address] !== 'mixer');
  for (const la of m.lookalikes) {
    const own = (outOf[la.address] ?? []).filter((t) => labelOf[t.to_address] === 'mixer');
    assert.ok(own.length >= 1, `${la.address} uses the mixer`);
    for (const i of own) {
      const near = exits.filter((o) => ms(o.tx_at) > ms(i.tx_at) && ms(o.tx_at) <= ms(i.tx_at) + 120 * MIN && +o.amount >= 0.98 * +i.amount && +o.amount <= +i.amount);
      assert.equal(near.length, 0, `${la.address}: nothing leaves within 2 hours at 98 % or more`);
    }
    const other = inOf[la.other_exit][0];
    const hit = own.filter((i) => Math.abs((ms(other.tx_at) - ms(i.tx_at)) / MIN - la.minutes) <= 1 && Math.abs(+other.amount / +i.amount - la.ratio) < 0.001);
    assert.equal(hit.length, 1, `${la.address}: the near miss, ${la.minutes} minutes at ${la.ratio}`);
  }
});

test('converging cash-out: one cluster pays 40 accounts in 40 names at 2 exchanges, which cash out to 3 bank accounts', () => {
  const c = truth.converging_cash_out;
  assert.deepEqual(Object.keys(clusterOf).filter((a) => clusterOf[a] === c.cluster).sort(), [...c.addresses].sort());
  const paid = new Set(landed.filter((t) => c.addresses.includes(t.from_address) && accountOfDeposit[t.to_address]).map((t) => accountOfDeposit[t.to_address].account_id));
  assert.deepEqual([...paid].sort(), [...c.accounts].sort());
  const acc = accounts.filter((a) => c.accounts.includes(a.account_id));
  assert.equal(acc.filter((a) => a.exchange === 'Fernhill Coin Exchange').length, 22);
  assert.equal(acc.filter((a) => a.exchange === 'Pebblebrook Exchange').length, 18);
  assert.equal(new Set(acc.map((a) => persons[a.kyc_person_id].name)).size, 40, 'forty different names');
  for (const a of acc) assert.ok(inOf[a.deposit_address].every((t) => c.addresses.includes(t.from_address)), `${a.account_id} is fed by the cluster only`);
  assert.deepEqual([...new Set(cashouts.filter((x) => c.accounts.includes(x.account_id)).map((x) => x.bank_account))].sort(), [...c.banks].sort());
  const otc = Object.keys(clusterOf).filter((a) => clusterOf[a] === c.lookalike_cluster);
  assert.deepEqual(otc.sort(), [...c.lookalike_addresses].sort());
  const otcPaid = new Set(landed.filter((t) => otc.includes(t.from_address) && accountOfDeposit[t.to_address]).map((t) => accountOfDeposit[t.to_address].account_id));
  assert.deepEqual([...otcPaid].sort(), [...c.lookalike_accounts].sort(), 'the desk pays its 60 customers');
  const ownToo = c.lookalike_accounts.filter((a) => inOf[accounts.find((x) => x.account_id === a).deposit_address].some((t) => !otc.includes(t.from_address)));
  assert.ok(ownToo.length >= 45, `most customers also deposit from their own wallets (${ownToo.length} of 60)`);
  const otcBanks = new Set(cashouts.filter((x) => c.lookalike_accounts.includes(x.account_id)).map((x) => x.bank_account));
  assert.equal(otcBanks.size, c.lookalike_banks);
  assert.ok(otcBanks.size >= 50 && ![...otcBanks].some((b) => c.banks.includes(b)));
});
