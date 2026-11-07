// Golden test of tools/gen-la-ownership-data.py. Run: node --test tools/gen-la-ownership-data.test.mjs
// Re-runs the generator (it writes under spaces/_templates/la-ownership/data and tools/gen-la-ownership-data.ground-truth.json)
// and asserts every byte is IDENTICAL to what is committed, then reads the planted facts straight from the CSV files: the
// 24 % chain, the name match, the nominee's 120 companies at one address, the circle of stakes and payments, and that no
// look-alike shares a record with its story. The link Dataset itself is pinned by OwnershipLinksJobGoldenTest.
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-ownership', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-ownership-data.ground-truth.json');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function snapshot(dir = DATA, out = {}) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) snapshot(p, out);
    else out[p.slice(DATA.length + 1).replaceAll('\\', '/')] = sha(p);
  }
  return out;
}
const sha = (p) => createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
const run = () => assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-ownership-data.py')], { encoding: 'utf8' }).status, 0);

function csv(rel) {
  const rows = [];
  for (const line of readFileSync(join(DATA, rel), 'utf8').replace(/\r\n/g, '\n').split('\n').filter(Boolean)) {
    const cells = [];
    let cur = '', q = false;
    for (const ch of line) {
      if (ch === '"') q = !q;
      else if (ch === ',' && !q) { cells.push(cur); cur = ''; } else cur += ch;
    }
    cells.push(cur);
    rows.push(cells);
  }
  const [head, ...body] = rows;
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h, r[i]])));
}
const feed = (name, where = 'inbox') => readdirSync(join(DATA, where, name)).flatMap((f) => csv(`${where}/${name}/${f}`));

// the name_match rule of the ownership_links Job, re-implemented: Jaro-Winkler (prefix 0.1, up to 4) on lower-case letters
function jaro(s, t) {
  if (s === t) return 1;
  const win = Math.max(Math.floor(Math.max(s.length, t.length) / 2) - 1, 0);
  const sm = Array(s.length).fill(false), tm = Array(t.length).fill(false);
  let m = 0;
  for (let i = 0; i < s.length; i++)
    for (let j = Math.max(0, i - win); j < Math.min(t.length, i + win + 1); j++)
      if (!tm[j] && s[i] === t[j]) { sm[i] = tm[j] = true; m++; break; }
  if (!m) return 0;
  let k = 0, tr = 0;
  for (let i = 0; i < s.length; i++) if (sm[i]) { while (!tm[k]) k++; if (s[i] !== t[k]) tr++; k++; }
  return (m / s.length + m / t.length + (m - tr / 2) / m) / 3;
}
const jw = (s, t) => { const j = jaro(s, t); if (j <= 0.7) return j; let p = 0; while (p < Math.min(4, s.length, t.length) && s[p] === t[p]) p++; return j + p * 0.1 * (1 - j); };
const norm = (n) => n.toLowerCase().replace(/[^a-z ]/g, '').split(/\s+/).filter(Boolean).join(' ');

test('generator twice = the committed bytes; seven feeds; under 5 MB', { skip: !python }, () => {
  const before = snapshot();
  const truth = sha(TRUTH);
  assert.deepEqual([...new Set(Object.keys(before).map((k) => k.split('/')[1]))].sort(),
    ['company', 'customer', 'officer', 'ownership', 'payment', 'person', 'sanctions_list']);
  assert.equal(Object.keys(before).filter((k) => k.startsWith('inbox/payment/')).length, 12, '12 landed months');
  assert.deepEqual(Object.keys(before).filter((k) => k.startsWith('staged/')).sort(), ['staged/customer/CUSTOMER_20261031.csv', 'staged/payment/PAYMENT_202610.csv']);
  assert.ok(Object.keys(before).reduce((n, k) => n + statSync(join(DATA, k)).size, 0) < 5_000_000, 'the corpus is under 5 MB');
  run();
  assert.deepEqual(snapshot(), before, 'first run changed bytes');
  assert.equal(sha(TRUTH), truth, 'first run changed the ground truth');
  run();
  assert.deepEqual(snapshot(), before, 'second run changed bytes');
});

const T = JSON.parse(readFileSync(TRUTH, 'utf8'));
const S = T.stories;
const own = feed('ownership'), off = feed('officer'), cust = feed('customer'), pay = feed('payment'), pers = feed('person');
const co = Object.fromEntries(feed('company').map((c) => [c.company_id, c]));
const list = feed('sanctions_list');
const custCo = Object.fromEntries(cust.map((c) => [c.customer_id, c.company_id]));

test('plan scale: 6 000 companies, 5 000 people, 1 500 customers, 400 list entries, ~9 000 stakes, ~8 000 officers, ~40 000 payments', () => {
  assert.equal(Object.keys(co).length, 6000);
  assert.equal(pers.length, 5000);
  assert.equal(cust.length, 1500);
  assert.equal(list.length, 400);
  assert.ok(own.length > 8000 && own.length < 10000, `${own.length} stakes`);
  assert.ok(off.length > 7000 && off.length < 9000, `${off.length} officers`);
  assert.ok(pay.length > 36000 && pay.length < 44000, `${pay.length} payments`);
});

test('hidden owner: 5 layers of 24 % across CY, VG and AE, then 24 + 24 + 13 = 61 % of four customers', () => {
  const s = S.hidden_owner;
  let at = [s.hub];
  for (let layer = 1; layer <= 5; layer++) {
    const down = own.filter((o) => at.includes(o.owner_id));
    assert.ok(down.length && down.every((o) => o.percent === '24.00'), `layer ${layer}`);
    at = [...new Set(down.map((o) => o.owned_id))];
  }
  assert.deepEqual([...new Set(own.filter((o) => s.entities.includes(o.owned_id) && co[o.owned_id].jurisdiction !== 'GB')
    .map((o) => co[o.owned_id].jurisdiction))].sort(), ['AE', 'CY', 'VG']);
  for (const c of s.facts.customers) {
    assert.equal(own.filter((o) => o.owned_id === c).reduce((n, o) => n + Number(o.percent), 0), 61, c);
    assert.ok(cust.some((k) => k.company_id === c), `${c} is a customer`);
  }
});

test('sanctions two hops: customer <- 50 % owner <- 60 % by a person who name-matches a listed person; the near-namesake does not', () => {
  const s = S.sanctions_two_hops, f = s.facts;
  const listed = list.find((l) => l.list_id === s.hub);
  const p = pers.find((x) => x.name === f.matched_person_name);
  const q = pers.find((x) => x.name === f.near_namesake);
  const near = list.find((l) => l.list_id === f.near_namesake_list_id);
  assert.equal(listed.name, f.listed_name);
  assert.equal(p.birth_year, listed.birth_year);
  assert.equal(p.nationality, listed.nationality);
  assert.ok(jw(norm(p.name), norm(listed.name)) >= T.min_name_score);
  assert.ok(jw(norm(q.name), norm(near.name)) >= T.min_name_score, 'the near-namesake is as close by name alone');
  assert.ok(q.birth_year !== near.birth_year && q.nationality !== near.nationality);
  const owner = own.find((o) => o.owner_id === p.person_id);
  assert.equal(owner.percent, '60.00');
  const held = own.find((o) => o.owner_id === owner.owned_id);
  assert.equal(held.percent, '50.00');
  assert.equal(custCo[f.customer], held.owned_id);
  // the Job's rule over the whole register: only the planted pair matches
  const hits = [];
  for (const x of pers) for (const l of list) {
    if (!x.birth_year || !x.nationality || x.birth_year !== l.birth_year || x.nationality !== l.nationality) continue;
    if ([l.name, ...l.aliases.split('|').filter(Boolean)].some((c) => jw(norm(x.name), norm(c)) >= T.min_name_score)) hits.push(`${x.person_id}~${l.list_id}`);
  }
  assert.deepEqual(hits, [`${p.person_id}~${s.hub}`]);
});

test('the sanctions list is invented: no listed name is the name of anyone in the register', () => {
  const names = new Set(pers.map((x) => norm(x.name)));
  assert.equal(list.filter((l) => names.has(norm(l.name))).length, 0);
});

test('nominee director: one person directs 120 companies, all at the formation-agent address (three spellings); nobody else directs more than 12', () => {
  const s = S.nominee_director;
  const cos = off.filter((o) => o.person_id === s.hub && o.role === 'director').map((o) => o.company_id);
  assert.equal(cos.length, 120);
  assert.ok(cos.every((c) => s.facts.address_variants.includes(co[c].reg_address)));
  assert.equal(new Set(cos.map((c) => co[c].reg_address)).size, 3);
  const per = {};
  off.filter((o) => o.role === 'director').forEach((o) => { per[o.person_id] = (per[o.person_id] ?? 0) + 1; });
  assert.ok(Object.entries(per).filter(([p]) => p !== s.hub).every(([, n]) => n <= 12));
  const acct = s.lookalikes.find((x) => x.startsWith('PER-'));
  assert.equal(per[acct], 4, 'the accountant directs 4 family firms');
  assert.equal(new Set(off.filter((o) => o.person_id === acct).map((o) => co[o.company_id].reg_address)).size, 4);
});

test('circular ownership: A owns B owns C owns A, and they pay each other round the circle every landed month', () => {
  const [a, b, c] = S.circular_ownership.facts.cycle;
  for (const [x, y] of [[a, b], [b, c], [c, a]]) {
    assert.ok(own.some((o) => o.owner_id === x && o.owned_id === y), `${x} owns ${y}`);
    assert.equal(pay.filter((p) => custCo[p.from_customer] === x && p.to_company === y).length, 12, `${x} pays ${y}`);
  }
});

test('no look-alike shares a record with its story', () => {
  const edges = [...own.map((o) => [o.owner_id, o.owned_id]), ...off.map((o) => [o.person_id, o.company_id]),
    ...cust.map((c) => [c.customer_id, c.company_id]), ...pay.map((p) => [p.from_customer, p.to_company])];
  for (const [name, s] of Object.entries(S)) {
    const m = new Set(s.entities), l = new Set(s.lookalikes);
    assert.ok(l.size > 0, name);
    for (const x of l) assert.ok(edges.some(([u, v]) => u === x || v === x), `${name}: look-alike ${x} is in the data`);
    const bad = edges.filter(([u, v]) => (l.has(u) && m.has(v)) || (l.has(v) && m.has(u)));
    assert.deepEqual(bad, [], `${name}: a look-alike is linked to its story`);
  }
});
