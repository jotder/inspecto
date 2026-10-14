// node --test tools/seed-telco-fraud-demo.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run, shiftDates, ROSTER } from './seed-telco-fraud-demo.mjs';

test('shiftDates moves day 1 to yesterday, keeps the offset of later days, and renames files', () => {
  assert.equal(shiftDates('a 2026-07-01 10:00 b 2026-07-02 01:00 CDR_20260701.csv', '2026-10-13'),
    'a 2026-10-13 10:00 b 2026-10-14 01:00 CDR_20261013.csv');
});

test('run bootstraps first, then seeds the created Space', () => {
  const spaces = fs.mkdtempSync(path.join(os.tmpdir(), 'tf-'));
  assert.equal(run(spaces, '2026-10-14').step, 'bootstrap');
  assert.equal(fs.readFileSync(path.join(spaces, 'telco-demo', 'config', 'demo-users.toon'), 'utf8'), ROSTER);
  fs.mkdirSync(path.join(spaces, 'telco-fraud', 'config'), { recursive: true });
  const r = run(spaces, '2026-10-14');
  assert.equal(r.step, 'seeded');
  assert.deepEqual(r.files.sort(), ['CDR_20261013.csv', 'PAYMENTS_20261013.csv', 'SUBSCRIBER_EVENTS_20261013.csv']);
  const cdr = fs.readFileSync(path.join(spaces, 'telco-fraud', 'data', 'inbox', 'cdr', 'CDR_20261013.csv'), 'utf8');
  assert.match(cdr, /2026-10-13 /);
  assert.doesNotMatch(cdr, /2026-07/);
  assert.ok(fs.existsSync(path.join(spaces, 'telco-fraud', 'config', 'demo-users.toon')));
});
