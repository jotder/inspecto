// Determinism and planted facts of tools/gen-order-to-cash-data.mjs. Run: node --test tools/gen-order-to-cash-data.test.mjs
// Runs the generator (it writes the order-to-cash template's sample feeds and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each planted defect in the files against the ground truth. That the
// shipped controls REPORT them (Expectation breaches, Breaks, Incident keys) is OrderToCashGoldenTest (inspecto-reconciliation);
// that the Incidents group into collections Cases is OrderToCashCaseTest (inspecto-case-management).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SAMPLES = join(ROOT, 'spaces', '_templates', 'order-to-cash', 'data', 'samples');
const TRUTH = join(ROOT, 'tools', 'gen-order-to-cash-data.ground-truth.json');

function snapshot() {
  const out = {};
  for (const feed of ['orders', 'payments'])
    for (const f of readdirSync(join(SAMPLES, feed))) out[`${feed}/${f}`] = hash(join(SAMPLES, feed, f));
  out.truth = hash(TRUTH);
  return out;
}
const hash = (p) => createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
function feed(name) {
  const rows = [];
  for (const f of readdirSync(join(SAMPLES, name)).sort()) {
    const [head, ...lines] = readFileSync(join(SAMPLES, name, f), 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) rows.push({ file: f, ...Object.fromEntries(l.split(',').map((v, i) => [cols[i], v])) });
  }
  return rows;
}

test('generator twice = the committed bytes', () => {
  const before = snapshot();
  for (let i = 0; i < 2; i++) {
    const r = spawnSync(process.execPath, [join(ROOT, 'tools', 'gen-order-to-cash-data.mjs')], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    assert.deepEqual(snapshot(), before, 'the generator must reproduce the committed samples and ground truth byte for byte');
  }
});

test('the files hold exactly the planted defects of the ground truth', () => {
  const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
  const orders = feed('orders');
  const payments = feed('payments');
  assert.equal(orders.length, truth.counts.order_rows);
  assert.equal(payments.length, truth.counts.payment_rows);
  assert.equal(new Set(orders.map((o) => o.ORDER_ID)).size, truth.counts.distinct_orders);
  const order = Object.fromEntries(orders.map((o) => [o.ORDER_ID, o]));
  const paid = (id) => payments.filter((p) => p.ORDER_ID === id);
  const sum = (rows) => rows.reduce((s, r) => s + Math.round(Number(r.AMOUNT) * 100), 0);

  // negative amounts: exactly one, a payment
  assert.deepEqual(orders.filter((o) => Number(o.AMOUNT) < 0), []);
  assert.deepEqual(payments.filter((p) => Number(p.AMOUNT) < 0).map((p) => [p.ORDER_ID, p.PAYMENT_ID, p.AMOUNT]),
    truth.planted.negative_amount.map((n) => [n.order_id, n.payment_id, n.amount]));
  // orphans: exactly the payments whose order id the orders feed does not know
  assert.deepEqual(payments.filter((p) => !order[p.ORDER_ID]).map((p) => p.ORDER_ID), truth.planted.orphan_payment.map((o) => o.order_id));
  // duplicates: exactly one order id twice, in two files, identical rows
  const dup = orders.filter((o) => o.ORDER_ID === 'O-1050');
  assert.deepEqual(Object.entries(orders.reduce((m, o) => ({ ...m, [o.ORDER_ID]: (m[o.ORDER_ID] ?? 0) + 1 }), {})).filter(([, n]) => n > 1),
    [['O-1050', 2]]);
  assert.notEqual(dup[0].file, dup[1].file, 're-sent in the next day file');
  // partial and missing payments
  for (const p of truth.planted.partial_payment) assert.equal(sum(paid(p.order_id)), Math.round(Number(p.paid) * 100), p.order_id);
  for (const m of truth.planted.missing_payment) assert.deepEqual(paid(m.order_id), [], m.order_id);
  // every order not planted is paid in full, on or before its due date
  const planted = new Set(['O-1012', 'O-1017', 'O-1022', 'O-1041', 'O-1050', 'O-1064', 'O-1088']);
  const due = (id) => new Date(Date.parse(order[id].ORDER_DATE) + truth.terms_days * 86_400_000).toISOString().slice(0, 10);
  for (const id of Object.keys(order).filter((x) => !planted.has(x))) {
    assert.equal(sum(paid(id)), Math.round(Number(order[id].AMOUNT) * 100), `${id} is paid in full`);
    for (const p of paid(id)) assert.ok(p.PAYMENT_DATE <= due(id), `${id} is paid by ${due(id)}`);
  }
  // the late payment: in full, after its due date by the planted days
  const late = truth.planted.late_payment[0];
  assert.equal(due(late.order_id), late.due_date);
  assert.deepEqual(paid(late.order_id).map((p) => p.PAYMENT_DATE), [late.paid_on]);
  assert.equal((Date.parse(late.paid_on) - Date.parse(late.due_date)) / 86_400_000, late.days_late);
  // the look-alikes
  for (const i of truth.look_alikes.instalments) assert.equal(paid(i.order_id).length, i.payments, i.order_id);
  assert.equal(paid('O-1099')[0].PAYMENT_DATE, due('O-1099'), 'paid on its due date');
  // every payment arrives in July, so the monthly Reconciliation reads one period
  assert.ok(payments.every((p) => p.PAYMENT_DATE.startsWith('2026-07-')));
  assert.ok(orders.every((o) => o.ORDER_DATE.startsWith('2026-07-')));
  // the Incidents: every order id (or orphan) whose |ordered - paid| reaches the threshold, and the Cases group them by customer
  assert.deepEqual(truth.incidents.map((i) => i.order_id), ['O-1017', 'O-1022', 'O-1041', 'O-1050', 'O-1088', 'O-9001']);
  assert.deepEqual(truth.cases['C-007'], ['O-1022', 'O-1041'], 'one customer holds two alerting orders');
  assert.equal(Object.keys(truth.cases).length, 5);
});
