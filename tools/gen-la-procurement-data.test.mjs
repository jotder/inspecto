// Determinism and planted facts of tools/gen-la-procurement-data.py. Run: node --test tools/gen-la-procurement-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-procurement/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: four firms
// winning in turn with losing bids 5-12 % higher, cover bids from the winner's IP address within minutes, nine changes approved
// for a relative's firm, a debarred firm reborn under its old directors - and that each look-alike has the innocent shape. That
// the stories LINK (and the look-alikes do not) over the real link Datasets is ProcurementLinksJobGoldenTest (inspecto-engine).
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-procurement', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-procurement-data.ground-truth.json');
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
function feed(name, box = '') {
  const rows = [];
  for (const p of files().filter((f) => f.replaceAll('\\', '/').includes(`${box}/${name}/`)).sort()) {
    const [head, ...lines] = readFileSync(p, 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) rows.push(Object.fromEntries(l.split(',').map((v, i) => [cols[i], v])));
  }
  return rows;
}
const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
const minutes = (a, b) => (Date.parse(b.replace(' ', 'T')) - Date.parse(a.replace(' ', 'T'))) / 60_000;
const days = (a, b) => (Date.parse(b) - Date.parse(a)) / 86_400_000;

const tenders = feed('tender');
const tender = Object.fromEntries(tenders.map((t) => [t.tender_id, t]));
const bids = feed('bid');
const bidsOn = (tid) => bids.filter((b) => b.tender_id === tid);
const company = Object.fromEntries(feed('company').map((c) => [c.company_id, c]));
const person = Object.fromEntries(feed('person').map((p) => [p.person_id, p]));
const directors = feed('director');
const changes = feed('contract_change');

test('generator twice = the committed bytes; seven feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['bid', 'company', 'contract_change', 'director', 'official', 'person', 'tender']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-procurement-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 900 companies, 2 000 people, 12 months landed and October 2026 staged, synthetic identifiers', () => {
  assert.equal(Object.keys(company).length, 900);
  assert.equal(Object.keys(person).length, 2000);
  assert.equal(feed('official').length, 300);
  assert.equal(feed('tender', '/inbox').length, truth.counts.tender_landed);
  assert.equal(feed('bid', '/inbox').length, truth.counts.bid_landed);
  assert.equal(files().filter((f) => f.replaceAll('\\', '/').includes('/inbox/tender/')).length, 12, 'twelve monthly tender files');
  assert.ok(feed('tender', '/staged').every((t) => t.published_at.startsWith('2026-10')), 'the staged tenders are October 2026');
  assert.ok(feed('contract_change', '/staged').every((c) => c.approved_at.startsWith('2026-10')), 'the staged changes are October 2026');
  assert.ok(feed('tender', '/inbox').every((t) => t.published_at <= '2026-09-30'));
  assert.ok(bids.every((b) => tender[b.tender_id]), 'every bid names a tender');
  assert.ok(tenders.every((t) => !t.awarded_to || bidsOn(t.tender_id).some((b) => b.bidder_id === t.awarded_to)), 'every winner bid');
  assert.ok(Object.values(company).every((c) => !c.phone || /^(07700 900|01632 960|01\d{2} 496 0)/.test(c.phone)), 'drama-range phones');
  assert.ok(Object.values(company).every((c) => !c.bank_account || /^SYN-\d{8}$/.test(c.bank_account)), 'invented bank accounts');
  assert.ok(bids.every((b) => !b.ip || /^(100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|198\.51\.100\.)/.test(b.ip)), 'reserved IP ranges only');
  const blankAuthor = bids.filter((b) => !b.doc_author || b.doc_author === 'Administrator').length / bids.length;
  assert.ok(blankAuthor > 0.03 && blankAuthor < 0.12, `blank or placeholder authors are realistic noise (${blankAuthor})`);
});

test('bid rotation: four firms win the 24 in turn, only they bid, losers 5-12 % above and never identical; two share a director', () => {
  const r = truth.bid_rotation;
  assert.equal(r.tenders_in_order.length, 24);
  r.tenders_in_order.forEach((tid, i) => {
    const tb = bidsOn(tid).sort((a, b) => a.amount - b.amount);
    assert.equal(tender[tid].buyer_unit, 'Highways North');
    assert.deepEqual(tb.map((b) => b.bidder_id).sort(), [...r.firms].sort(), `${tid}: only the four bid`);
    assert.equal(tender[tid].awarded_to, r.firms[i % 4], `${tid}: the next firm in turn wins`);
    assert.equal(new Set(tb.map((b) => b.amount)).size, 4, `${tid}: no two bids are identical`);
    for (const b of tb.slice(1)) {
      const pct = (b.amount / tb[0].amount - 1) * 100;
      assert.ok(pct >= 5 && pct <= 12, `${tid}: a losing bid ${pct.toFixed(1)} % above`);
    }
    assert.ok(+tender[tid].award_value > +tender[tid].estimate, `${tid}: the rigged price is above the estimate`);
  });
  const shared = directors.filter((d) => d.person_id === r.shared_director).map((d) => d.company_id).sort();
  assert.deepEqual(shared, [...r.shared_by].sort());
});

test('the big three win most Highways South road tenders but bid independently and never touch the rotation', () => {
  const r = truth.bid_rotation;
  const south = tenders.filter((t) => t.buyer_unit === 'Highways South' && t.awarded_to);
  const share = south.filter((t) => r.lookalike_firms.includes(t.awarded_to)).length / south.length;
  assert.ok(share >= 0.5, `they win ${share}`);
  for (const f of r.lookalike_firms) {
    const theirs = bids.filter((b) => b.bidder_id === f);
    assert.ok(theirs.every((b) => !r.tenders_in_order.includes(b.tender_id)), `${f} never bids on a rotation tender`);
    assert.ok(theirs.length < south.length, `${f} skips some tenders`);
  }
  for (const t of south) {
    const tb = bidsOn(t.tender_id);
    assert.equal(t.awarded_to, tb.sort((a, b) => a.amount - b.amount)[0].bidder_id, `${t.tender_id}: the lowest bid wins`);
  }
});

test('cover bids: 60 % from the winner\'s IP within minutes, most with its estimator as author; the kiosk is hours apart', () => {
  const c = truth.cover_bids;
  let total = 0, fromIp = 0, byAuthor = 0;
  for (const tid of c.tenders) {
    const tb = bidsOn(tid);
    const win = tb.find((b) => b.bidder_id === c.winner);
    assert.equal(tender[tid].awarded_to, c.winner);
    for (const b of tb.filter((x) => x.bidder_id !== c.winner)) {
      assert.ok(c.covers.includes(b.bidder_id), `${tid}: only the covers bid against the winner`);
      const pct = (b.amount / win.amount - 1) * 100;
      assert.ok(pct >= 5 && pct <= 12, `${tid}: cover ${pct.toFixed(1)} % above`);
      total++;
      if (b.ip === c.winner_ip) {
        fromIp++;
        const gap = minutes(win.submitted_at, b.submitted_at);
        assert.ok(gap >= 2 && gap <= 25, `${b.bid_id}: ${gap} minutes after the winner`);
      }
      if (b.doc_author === c.winner_author) byAuthor++;
    }
  }
  assert.equal(total, c.cover_bids);
  assert.ok(Math.abs(fromIp / total - 0.6) < 0.05, `the IP match covers 60 % of cover bids (${fromIp}/${total})`);
  assert.ok(byAuthor / total >= 0.7, `most carry the winner's author (${byAuthor}/${total})`);
  const kiosk = bids.filter((b) => b.ip === c.kiosk_ip);
  assert.deepEqual([...new Set(kiosk.map((b) => b.bidder_id))].sort(), [...c.kiosk_firms].sort());
  assert.equal(c.kiosk_firms.length, 14);
  for (const a of kiosk) for (const b of kiosk)
    if (a !== b && a.tender_id === b.tender_id) assert.ok(Math.abs(minutes(a.submitted_at, b.submitted_at)) >= 60, 'kiosk bids are hours apart');
  assert.ok(kiosk.every((b) => b.doc_author !== c.winner_author));
});

test('conflict of interest: nine changes for the relative\'s firm, at the official\'s address; the busy manager spreads thin', () => {
  const c = truth.conflict_of_interest;
  const theirs = changes.filter((x) => x.approved_by === c.official && tender[x.tender_id].awarded_to === c.firm);
  assert.equal(theirs.length, 9);
  assert.equal(person[c.director].address_id, person[c.official].address_id);
  assert.equal(person[c.director].address_id, c.home);
  assert.ok(directors.some((d) => d.company_id === c.firm && d.person_id === c.director));
  assert.notEqual(person[c.director].name, person[c.official].name);
  const busy = changes.filter((x) => x.approved_by === c.lookalike_official);
  assert.equal(busy.length, c.lookalike_changes);
  const perFirm = {};
  for (const x of busy) perFirm[tender[x.tender_id].awarded_to] = (perFirm[tender[x.tender_id].awarded_to] ?? 0) + 1;
  assert.ok(Math.max(...Object.values(perFirm)) <= 2 && Object.keys(perFirm).length >= 17, 'many suppliers, two changes each at most');
  const directorsHome = new Set(directors.map((d) => person[d.person_id].address_id));
  assert.ok(!directorsHome.has(person[c.lookalike_official].address_id), 'the busy manager lives with no director');
});

test('phoenix: the old directors resign days after the debarment and their new firm wins inside 3 months', () => {
  const p = truth.phoenix_firm;
  assert.equal(company[p.old].debarred_at, p.debarred_at);
  assert.equal(company[p.new].incorporated_at, p.incorporated_at);
  for (const d of p.directors) {
    const old = directors.find((x) => x.company_id === p.old && x.person_id === d);
    assert.ok(old && days(p.debarred_at, old.to_date) >= 0 && days(p.debarred_at, old.to_date) <= 14, `${d} resigns from the old firm`);
    assert.ok(directors.some((x) => x.company_id === p.new && x.person_id === d && x.from_date === p.incorporated_at), `${d} runs the new one`);
  }
  assert.equal(tender[p.won].awarded_to, p.new);
  assert.ok(days(p.debarred_at, tender[p.won].awarded_at) <= 90);
  assert.ok(bids.every((b) => b.bidder_id !== p.old || b.submitted_at < p.debarred_at), 'no bid after the debarment');
  assert.equal(company[p.new].phone, company[p.old].phone, 'the new firm keeps the old phone');
  const lx = directors.find((x) => x.company_id === p.lookalike_new_home && x.person_id === p.lookalike_director);
  assert.ok(days(company[p.lookalike_old].debarred_at, lx.from_date) > 3 * 365, 'the look-alike moves on years later');
  for (const b of p.background_debarred) assert.ok(company[b].debarred_at < '2025-01-01' && !bids.some((x) => x.bidder_id === b));
});
