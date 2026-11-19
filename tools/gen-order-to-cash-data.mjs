#!/usr/bin/env node
// Regenerate the `order-to-cash` Space Template's sample feeds - SYNTHETIC, deterministic (one seeded generator).
//
// Writes spaces/_templates/order-to-cash/data/samples/orders/ORDERS_<yyyyMMdd>.csv (ten daily order files, 2026-07-01..10)
// and data/samples/payments/PAYMENTS_<yyyyMMdd>.csv (one file per day a payment arrives, all in July 2026), and
// tools/gen-order-to-cash-data.ground-truth.json: the exact planted defects the Handbook's tutorials tell the reader to find,
// and what the shipped Expectations, Reconciliation, Alert Rule and Case Rule must report over them.
//
// 120 orders (O-1001..O-1120, twelve a day, customers C-001..C-020, USD, payment terms 14 days). Every other order is paid
// in full, once, 1 to 12 days after its order date. Planted (fixed ids and amounts; the random draw never touches them):
//   negative_amount   O-1017  240.00 ordered, one payment keyed as -240.00
//   orphan_payment    O-9001  310.00 and O-9002 55.00 paid against order ids that do not exist
//   duplicate_order   O-1050  180.00, the same row sent again in the next day's file; paid 180.00 once
//   partial_payment   O-1022  500.00 ordered, 300.00 paid;  O-1064 99.50 ordered, 95.00 paid (under the alert threshold)
//   missing_payment   O-1041  420.00 and O-1088 135.00, never paid
//   late_payment      O-1012  275.00 paid in full 15 days after its due date (a Break-free row: only the open items show it)
// Look-alikes that must stay clean: O-1030 paid in two instalments, O-1075 in three (each sums exactly); O-1099 paid on its
// due date (not late). Customer C-007 holds two alerting orders (O-1022, O-1041), so its collections Case groups two Incidents.
//
// Run: node tools/gen-order-to-cash-data.mjs   (pinned by tools/gen-order-to-cash-data.test.mjs and OrderToCashGoldenTest)
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const SAMPLES = join(ROOT, 'spaces', '_templates', 'order-to-cash', 'data', 'samples');
const TRUTH = join(ROOT, 'tools', 'gen-order-to-cash-data.ground-truth.json');
const TERMS_DAYS = 14;
const ALERT_THRESHOLD = 100;   // o2c_unmatched_value: one Incident per order whose unmatched value is at least this

/** mulberry32: a small seeded generator, so the corpus is byte-identical on every machine. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = rng(20261119);
const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1));
const day = (d) => `2026-07-${String(d).padStart(2, '0')}`;
const addDays = (iso, n) => day(Number(iso.slice(8)) + n);
const money = (cents) => (cents / 100).toFixed(2);
const METHODS = ['card', 'transfer', 'cheque'];

const PLANTED = {
  'O-1012': { cents: 27500, customer: 'C-003' },
  'O-1017': { cents: 24000 },
  'O-1022': { cents: 50000, customer: 'C-007' },
  'O-1030': { cents: 30000 },
  'O-1041': { cents: 42000, customer: 'C-007' },
  'O-1050': { cents: 18000 },
  'O-1064': { cents: 9950 },
  'O-1075': { cents: 60000 },
  'O-1088': { cents: 13500 },
  'O-1099': { cents: 41000 },
};

// ---- orders: 12 a day over ten days; the random draw runs for every order so a planted value never shifts the others
const orders = [];
for (let i = 0; i < 120; i++) {
  const id = `O-${1001 + i}`;
  const date = day(1 + Math.floor(i / 12));
  let customer = `C-${String(int(1, 20)).padStart(3, '0')}`;
  let cents = int(40, 900) * 100 + int(0, 99);
  const lag = int(1, 12);
  const method = METHODS[int(0, 2)];
  const p = PLANTED[id];
  if (p) {
    cents = p.cents;
    if (p.customer) customer = p.customer;
  } else if (customer === 'C-007') customer = 'C-017';   // C-007 holds exactly the two planted alerting orders
  orders.push({ id, date, customer, cents, lag, method });
}
const byId = Object.fromEntries(orders.map((o) => [o.id, o]));

// ---- payments
const payments = [];
const pay = (order, date, cents, method, customer = order?.customer) =>
  payments.push({ date, order: typeof order === 'string' ? order : order.id, customer, cents, method });
for (const o of orders) {
  switch (o.id) {
    case 'O-1012': pay(o, addDays(o.date, TERMS_DAYS + 15), o.cents, o.method); break;          // late
    case 'O-1017': pay(o, addDays(o.date, o.lag), -o.cents, o.method); break;                     // negative
    case 'O-1022': pay(o, addDays(o.date, o.lag), 30000, o.method); break;                        // partial
    case 'O-1030': pay(o, addDays(o.date, 3), 15000, 'card'); pay(o, addDays(o.date, 10), 15000, 'card'); break;
    case 'O-1041': case 'O-1088': break;                                                          // missing
    case 'O-1064': pay(o, addDays(o.date, o.lag), 9500, o.method); break;                         // partial, small
    case 'O-1075': for (const d of [2, 6, 11]) pay(o, addDays(o.date, d), 20000, 'transfer'); break;
    case 'O-1099': pay(o, addDays(o.date, TERMS_DAYS), o.cents, o.method); break;                  // on the due date
    default: pay(o, addDays(o.date, o.lag), o.cents, o.method);
  }
}
pay('O-9001', day(9), 31000, 'transfer', 'C-011');   // orphan
pay('O-9002', day(12), 5500, 'card', 'C-015');       // orphan, under the threshold
payments.sort((a, b) => a.date.localeCompare(b.date) || a.order.localeCompare(b.order) || a.cents - b.cents);
payments.forEach((p, i) => { p.id = `P-${50001 + i}`; });

// ---- files
rmSync(SAMPLES, { recursive: true, force: true });
function writeFeed(feed, prefix, header, rowsByDay) {
  mkdirSync(join(SAMPLES, feed), { recursive: true });
  for (const [d, rows] of Object.entries(rowsByDay).sort()) {
    writeFileSync(join(SAMPLES, feed, `${prefix}_${d.replaceAll('-', '')}.csv`), [header, ...rows].join('\n') + '\n');
  }
}
const orderRows = {};
const orderLine = (o) => [o.id, o.date, o.customer, 'USD', money(o.cents)].join(',');
for (const o of orders) (orderRows[o.date] ??= []).push(orderLine(o));
orderRows[day(6)].push(orderLine(byId['O-1050']));   // the re-sent row, in the next day's file
writeFeed('orders', 'ORDERS', 'ORDER_ID,ORDER_DATE,CUSTOMER_ID,CURRENCY,AMOUNT', orderRows);
const paymentRows = {};
for (const p of payments) (paymentRows[p.date] ??= []).push([p.id, p.date, p.order, p.customer, money(p.cents), p.method].join(','));
writeFeed('payments', 'PAYMENTS', 'PAYMENT_ID,PAYMENT_DATE,ORDER_ID,CUSTOMER_ID,AMOUNT,METHOD', paymentRows);

// ---- ground truth: the planted defects and what each shipped control must report
const items = {};
for (const o of orders) items[o.id] = { customer: o.customer, ordered: (items[o.id]?.ordered ?? 0) + o.cents, copies: 1, paid: 0 };
items['O-1050'].ordered += byId['O-1050'].cents;
items['O-1050'].copies = 2;
for (const p of payments) {
  items[p.order] ??= { customer: p.customer, ordered: 0, copies: 0, paid: 0 };
  items[p.order].paid += p.cents;
}
const incidents = Object.entries(items)
  .filter(([, v]) => Math.abs(v.ordered - v.paid) >= ALERT_THRESHOLD * 100)
  .map(([id, v]) => ({ customer_id: v.customer, order_id: id, unmatched_value: money(Math.abs(v.ordered - v.paid)) }))
  .sort((a, b) => a.order_id.localeCompare(b.order_id));
const cases = {};
for (const i of incidents) (cases[i.customer_id] ??= []).push(i.order_id);
const dueOf = (id) => addDays(byId[id].date, TERMS_DAYS);

const truth = {
  generator: 'tools/gen-order-to-cash-data.mjs',
  terms_days: TERMS_DAYS,
  alert_threshold: ALERT_THRESHOLD,
  counts: {
    order_files: Object.keys(orderRows).length,
    order_rows: orders.length + 1,
    distinct_orders: orders.length,
    payment_files: Object.keys(paymentRows).length,
    payment_rows: payments.length,
    open_items: Object.keys(items).length,
  },
  planted: {
    negative_amount: [{ order_id: 'O-1017', payment_id: payments.find((p) => p.cents < 0).id, amount: '-240.00' }],
    orphan_payment: payments.filter((p) => !byId[p.order]).map((p) => ({ order_id: p.order, payment_id: p.id, amount: money(p.cents) })),
    duplicate_order: [{ order_id: 'O-1050', copies: 2, amount: '180.00' }],
    partial_payment: [
      { order_id: 'O-1022', ordered: '500.00', paid: '300.00', short: '200.00' },
      { order_id: 'O-1064', ordered: '99.50', paid: '95.00', short: '4.50' },
    ],
    missing_payment: [{ order_id: 'O-1041', ordered: '420.00' }, { order_id: 'O-1088', ordered: '135.00' }],
    late_payment: [{ order_id: 'O-1012', due_date: dueOf('O-1012'), paid_on: addDays(byId['O-1012'].date, TERMS_DAYS + 15), days_late: 15 }],
  },
  look_alikes: {
    instalments: [{ order_id: 'O-1030', payments: 2 }, { order_id: 'O-1075', payments: 3 }],
    paid_on_due_date: [{ order_id: 'O-1099', due_date: dueOf('O-1099') }],
  },
  expectations: {
    orders_amount_non_negative: 0,
    payments_amount_non_negative: 1,
    payments_order_known: 2,
    orders_id_unique: 1,
  },
  reconciliation: {
    name: 'o2c_orders_vs_payments',
    day: '2026-07-01',
    missing_right: ['O-1041', 'O-1088'],
    missing_left: ['O-9001', 'O-9002'],
    value_break: ['O-1017', 'O-1022', 'O-1050', 'O-1064'],
    cardinality_break: ['O-1050'],
  },
  open_items_status: {
    orphan_payment: ['O-9001', 'O-9002'],
    unpaid: ['O-1041', 'O-1088'],
    duplicate_order: ['O-1050'],
    short_paid: ['O-1017', 'O-1022', 'O-1064'],
    paid_late: ['O-1012'],
  },
  incidents,
  cases: Object.fromEntries(Object.entries(cases).sort()),
};
writeFileSync(TRUTH, JSON.stringify(truth, null, 1) + '\n');
console.log(`order-to-cash: ${truth.counts.order_rows} order rows, ${truth.counts.payment_rows} payments, ${incidents.length} Incidents expected`);
