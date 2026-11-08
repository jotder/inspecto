// Determinism and planted facts of tools/gen-la-card-data.py. Run: node --test tools/gen-la-card-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-card/data and the ground truth beside itself) and asserts every file is
// byte-identical to what is committed, then checks each story's facts in the files against the ground truth: a one-night burst of
// 1.00 authorisations, accounts moved onto drop addresses, refunds to cards never sold to, fraud weeks after one fuel station - and
// that each look-alike has the innocent shape. That the stories LINK (and the look-alikes do not) over the real link Dataset is
// CardLinksJobGoldenTest (inspecto-engine). Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-card', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-card-data.ground-truth.json');
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
const days = (a, b) => (Date.parse(day(b)) - Date.parse(day(a))) / 86_400_000;

const auths = feed('card_auth');
const authById = Object.fromEntries(auths.map((a) => [a.auth_id, a]));
const chargebacks = feed('chargeback').map((c) => ({ ...c, auth: authById[c.auth_id] }));
const online = feed('online_account');
const merchant = Object.fromEntries(feed('merchant').map((m) => [m.merchant_id, m]));
const cardsAt = (mid, pred = () => true) => new Set(auths.filter((a) => a.merchant_id === mid && pred(a)).map((a) => a.card_id));
const fraudCards = new Set(chargebacks.filter((c) => c.reason === 'fraud_card_not_present').map((c) => c.auth.card_id));

test('generator twice = the committed bytes; six feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['card', 'card_auth', 'cardholder', 'chargeback', 'merchant', 'online_account']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-card-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 6 500 invented cards, 900 merchants, 12 months landed and October 2026 staged', () => {
  assert.equal(feed('card').length, 6500);
  assert.ok(feed('card').every((c) => /^CRD-\d{6}$/.test(c.card_id)), 'card ids are invented, never card numbers');
  assert.equal(Object.keys(merchant).length, 900);
  const landed = files().filter((f) => f.replaceAll('\\', '/').includes('/inbox/card_auth/'));
  assert.equal(landed.length, 12, 'twelve monthly card_auth files');
  const staged = files().filter((f) => f.replaceAll('\\', '/').includes('/staged/'));
  for (const f of staged) for (const r of readFileSync(f, 'utf8').trim().split('\n').slice(1)) assert.match(r, /,2026-10-\d\d/, f);
  assert.ok(auths.every((a) => a.auth_id && authById[a.auth_id] === a), 'auth ids are unique');
  assert.ok(chargebacks.every((c) => c.auth && day(c.filed_at) >= day(c.auth.auth_at)), 'a chargeback follows its authorisation');
  assert.ok(feed('cardholder').every((h) => !h.phone || /^(07700 900|01632 960|0\d{2,3} 49[68] |020 7946 )/.test(h.phone)), 'drama-range phones');
  assert.ok(feed('cardholder').every((h) => !h.email || /@example\.(com|net|org)$/.test(h.email)), 'reserved e-mail domains');
});

test('card testing: one device and one /24 try 300 cards at 1.00 in one night, 85 % declined; the proxy is office-hours and clean', () => {
  const t = truth.card_testing;
  const burst = auths.filter((a) => a.device_id === t.device);
  assert.equal(new Set(burst.map((a) => a.card_id)).size, 300);
  assert.deepEqual([...new Set(burst.map((a) => a.card_id))].sort(), [...t.cards].sort());
  assert.ok(burst.every((a) => a.amount === '1.00' && t.shops.includes(a.merchant_id) && a.ip.startsWith('203.0.113.') && day(a.auth_at) === t.night));
  const declined = burst.filter((a) => a.result === 'declined').length / burst.length;
  assert.ok(declined > 0.8 && declined < 0.9, `declined ${declined.toFixed(2)}`);
  assert.ok(t.shops.every((m) => merchant[m].mcc === '5999'), 'three small online shops');
  const proxy = auths.filter((a) => a.ip === t.proxy_ip);
  const cards = new Set(proxy.map((a) => a.card_id));
  assert.ok(cards.size >= 150, `the proxy carries ${cards.size} cards`);
  assert.ok(new Set(proxy.map((a) => a.device_id)).size >= 100, 'each on its own device');
  assert.ok(proxy.filter((a) => a.result === 'declined').length / proxy.length < 0.06, 'few declines');
  assert.ok(proxy.every((a) => { const d = new Date(a.auth_at.replace(' ', 'T')); return d.getDay() >= 1 && d.getDay() <= 5 && d.getHours() >= 8 && d.getHours() < 19; }), 'office hours');
  assert.ok(!t.cards.some((c) => cards.has(c)), 'no tested card ever goes through the proxy');
});

test('account takeover: 8 devices move 140 accounts onto 5 drop addresses in seven weeks; the family tablet stays at home', () => {
  const t = truth.account_takeover;
  const moved = online.filter((r) => t.devices.includes(r.device_id));
  assert.deepEqual([...new Set(moved.map((r) => r.account_id))].sort(), [...t.accounts].sort());
  assert.equal(t.accounts.length, 140);
  assert.deepEqual([...new Set(moved.map((r) => r.ship_to_address))].sort(), [...t.drops].sort());
  assert.equal(new Set(moved.map((r) => r.device_id)).size, 8);
  assert.ok(moved.every((r) => day(r.changed_at) >= '2026-01-12' && day(r.changed_at) <= '2026-02-28'));
  for (const r of moved) {
    const buys = auths.filter((a) => a.card_id === r.card_id && a.device_id === r.device_id && a.auth_at > r.changed_at);
    assert.ok(buys.length >= 1 && buys.every((a) => days(r.changed_at, a.auth_at) <= 3), `${r.account_id} buys from the device that moved it`);
  }
  const tablet = online.filter((r) => r.device_id === t.tablet);
  const family = new Set(tablet.map((r) => r.account_id));
  assert.ok(family.size >= 2 && family.size <= 4, 'the tablet is shared inside the family');
  assert.ok(tablet.every((r) => r.ship_to_address === t.family_address), 'one home address');
  assert.equal(new Set(online.filter((r) => r.ship_to_address === t.family_address).map((r) => r.account_id)).size, 4, 'four family accounts');
  assert.ok(![...family].some((a) => t.accounts.includes(a)));
});

test('colluding merchant: M-221 refunds 12 cards it never sold to, chargebacks about 9x electronics; M-140 spikes in one strike month', () => {
  const t = truth.colluding_merchant;
  const refunds = auths.filter((a) => a.result === 'refund');
  assert.deepEqual([...new Set(refunds.map((a) => a.card_id))].sort(), [...t.refund_cards].sort());
  assert.ok(refunds.every((a) => a.merchant_id === 'M-221'));
  const sold = cardsAt('M-221', (a) => a.result === 'approved');
  assert.ok(!t.refund_cards.some((c) => sold.has(c)), 'never sold to');
  const rate = (mids) => chargebacks.filter((c) => mids.has(c.auth.merchant_id)).length / auths.filter((a) => mids.has(a.merchant_id) && a.result === 'approved').length;
  const peers = new Set(Object.values(merchant).filter((m) => m.mcc === '5732' && m.merchant_id !== 'M-221').map((m) => m.merchant_id));
  const ratio = rate(new Set(['M-221'])) / rate(peers);
  assert.ok(ratio >= 7 && ratio <= 12, `M-221 is ${ratio.toFixed(1)}x its category`);
  const cb221 = chargebacks.filter((c) => c.auth.merchant_id === 'M-221');
  assert.deepEqual([...new Set(cb221.map((c) => c.auth.card_id))].sort(), [...t.chargeback_cards].sort());
  assert.ok(cb221.every((c) => c.filed_at <= '2026-09-30'), 'every planted chargeback lands with the 12 months, not the staged one');
  const months221 = new Set(cb221.map((c) => c.filed_at.slice(0, 7)));
  assert.ok(months221.size >= 5, `M-221 chargebacks over ${months221.size} months`);
  const cb140 = chargebacks.filter((c) => c.auth.merchant_id === 'M-140');
  assert.ok(cb140.length >= 30);
  assert.ok(cb140.filter((c) => c.filed_at.startsWith(t.lookalike_strike_month)).length / cb140.length >= 0.8, 'one strike month');
  assert.equal(merchant['M-140'].mcc, '4722');
  const buyers140 = cardsAt('M-140');
  assert.ok(![...t.refund_cards, ...t.chargeback_cards].some((c) => buyers140.has(c)));
});

test('common point of purchase: 60 cards defrauded 1-6 weeks after M-317 in a 3-week window; the supermarket M-052 is clean', () => {
  const t = truth.common_point_of_purchase;
  const [from, to] = t.window;
  const inWindow = cardsAt('M-317', (a) => day(a.auth_at) >= from && day(a.auth_at) <= to);
  assert.equal(t.cards.length, 60);
  for (const c of t.cards) {
    assert.ok(inWindow.has(c), `${c} paid at M-317 in the window`);
    const visit = auths.find((a) => a.card_id === c && a.merchant_id === 'M-317' && day(a.auth_at) >= from && day(a.auth_at) <= to);
    const fraud = chargebacks.filter((x) => x.auth.card_id === c && x.reason === 'fraud_card_not_present').map((x) => x.auth.auth_at).sort()[0];
    assert.ok(fraud, `${c} is defrauded`);
    assert.ok(days(visit.auth_at, fraud) >= 7 && days(visit.auth_at, fraud) <= 42, `${c}: ${days(visit.auth_at, fraud)} days later`);
  }
  assert.equal(inWindow.size, 70, 'ten more cards used the pump then and were lucky');
  assert.equal(merchant['M-317'].mcc, '5541');
  const shoppers = cardsAt('M-052');
  assert.ok(shoppers.size >= 600, `the supermarket serves ${shoppers.size} cards`);
  assert.ok(![...shoppers].some((c) => fraudCards.has(c)), 'none of its customers is defrauded');
});
