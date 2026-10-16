// node --test tools/la-audit-smoke.test.mjs  -  the pure helpers of the la-audit smoke runner (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import { asObjects, apChecks, glChecks, tamperManifest } from './la-audit-smoke.mjs';

test('asObjects reads arrays-with-columns and plain objects', () => {
  assert.deepEqual(asObjects({ columns: ['a', { name: 'b' }], rows: [[1, 2]] }), [{ a: 1, b: 2 }]);
  assert.deepEqual(asObjects({ rows: [{ a: 1 }] }), [{ a: 1 }]);
});

/** A minimal ap_links that satisfies every check. */
function good() {
  const rows = [];
  const add = (src, dst, kind, amount = 100, via = 'x') => rows.push({ src, dst, kind, amount, via, event_date: '2026-01-01' });
  for (let i = 0; i < 14; i++) add('E-15', 'V-117', 'approved');
  for (let i = 0; i < 30; i++) add('E-23', 'V-099', 'approved', 9800);
  for (let v = 21; v <= 40; v++) for (let i = 0; i < 10; i++) add(i < 7 ? 'E-07' : 'E-08', `V-0${v}`, 'approved');
  for (let i = 0; i < 40; i++) add('E-01', `V-0${50 + (i % 9)}`, 'approved');
  for (let i = 0; i < 1700; i++) add('E-10', 'V-060', 'approved');
  for (let i = 0; i < 100; i++) add('V-001', 'BA-1001', 'paid_to');
  add('E-31', 'V-117', 'shares_account', 0, 'BA-9031');
  add('E-31', 'V-117', 'shares_address', 0, 'AD-9031');
  const sh = ['V-131', 'V-132', 'V-133', 'V-134', 'V-135'];
  for (let a = 0; a < 5; a++) for (let b = a + 1; b < 5; b++) add(sh[a], sh[b], 'shares_account', 0, 'BA-7701');
  for (let i = 0; i < 59; i++) add('E-15', i === 0 ? 'E-31' : `E-${40 + i}`, 'reports_to', 0);
  return rows;
}

test('a faithful ap_links passes every check', () => {
  const failing = apChecks(good()).filter(([, r]) => r);
  assert.deepEqual(failing, []);
});

test('each planted fact is individually falsifiable', () => {
  const drop = (pred) => apChecks(good().filter((r) => !pred(r))).filter(([, r]) => r).length;
  assert.ok(drop((r) => r.kind === 'shares_address' && r.src === 'E-31') >= 1, 'conflict of interest');
  assert.ok(drop((r) => r.dst === 'V-099' && r.amount === 9800) >= 1, 'threshold splitting');
  assert.ok(drop((r) => r.src === 'E-01') >= 1, 'CFO');
  const leaked = good(); leaked.push({ src: 'V-009', dst: 'PENDING', kind: 'paid_to', amount: 1, via: 'x', event_date: '2026-01-01' });
  assert.ok(apChecks(leaked).some(([n, r]) => n.startsWith('placeholders') && r), 'a PENDING leak is caught');
  const sixth = good(); sixth.push({ src: 'V-131', dst: 'V-136', kind: 'shares_account', amount: 0, via: 'x', event_date: '2026-01-01' });
  assert.ok(apChecks(sixth).some(([n, r]) => n.includes('V-136') && r), 'the sixth vendor must stay unlinked');
});

test('glChecks pins the round trip and 40 journals', () => {
  const rows = [];
  const ring = ['LE-05', 'LE-06', 'LE-07', 'LE-08'];
  for (let q = 0; q < 3; q++) for (let k = 0; k < 4; k++) rows.push({ src: ring[k], dst: ring[(k + 1) % 4], amount: 2400000, via: `JNL-RT-${q}${k}/1900` });
  for (let j = 0; j < 28; j++) rows.push({ src: 'LE-01', dst: 'LE-05', amount: 5, via: `JNL-${j}/6100` });
  assert.deepEqual(glChecks(rows).filter(([, r]) => r), []);
  assert.ok(glChecks(rows.slice(1)).some(([, r]) => r));
});

test('tamperManifest changes a hash and refuses a manifest with none', () => {
  const h = 'a'.repeat(64);
  const t = tamperManifest({ files: [{ sha256: `sha256:${h}` }] });
  assert.notEqual(t.files[0].sha256, `sha256:${h}`);
  assert.throws(() => tamperManifest({ a: 'b' }));
});
