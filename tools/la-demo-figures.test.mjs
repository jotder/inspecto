// node --test tools/la-demo-figures.test.mjs - verdicts on fixtures shaped like GET /inv/value-measures, plus a fake-server run.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { passThroughVerdict, cashOutVerdict, runFigures } from './la-demo-figures.mjs';

const ent = (entity, ratio) => ({ entity, ratio });
const goodPass = () => ({ status: 200, data: { count: 13, entities: [ent('SHELL-A', 1.08), ent('RELAY-1', 0.985), ent('RELAY-2', 0.985), ent('MULE-HUB-01', 0.979),
  ...Array.from({ length: 9 }, (_, i) => ent(`ACC-${i}`, 0.9))] } });
const goodCash = () => ({ status: 200, data: { entities: [{ entity: 'TILL-06', share: 0.97, payers: 8 }] } });

test('the manual figures pass on the expected measure bodies', () => {
  assert.deepEqual(passThroughVerdict(goodPass()), []);
  assert.deepEqual(cashOutVerdict(goodCash()), []);
});

test('Pass-through names a moved count, a missing hub and a wrong 0.985 pair', () => {
  const moved = goodPass(); moved.data.count = 12;
  assert.match(passThroughVerdict(moved)[0], /flags 12.*13/);
  const noHub = goodPass(); noHub.data.entities = noHub.data.entities.filter((e) => e.entity !== 'MULE-HUB-01');
  assert.match(passThroughVerdict(noHub).join(), /MULE-HUB-01 is not flagged/);
  const three = goodPass(); three.data.entities.push(ent('ACC-X', 0.985));
  assert.match(passThroughVerdict(three).join(), /3 accounts sit at 0.985/);
  const wrong = goodPass(); wrong.data.entities[1] = ent('ACC-Y', 0.985);
  assert.match(passThroughVerdict(wrong).join(), /expected the RELAY-\* pair/);
  assert.match(passThroughVerdict({ status: 500, data: null })[0], /HTTP 500/);
});

test('Cash-out names a wrong leader, a moved share and a moved payer count', () => {
  assert.match(cashOutVerdict({ status: 200, data: { entities: [{ entity: 'TILL-01', share: 0.97, payers: 8 }] } })[0], /TILL-01/);
  assert.match(cashOutVerdict({ status: 200, data: { entities: [{ entity: 'TILL-06', share: 0.9, payers: 8 }] } })[0], /90.0 %/);
  assert.match(cashOutVerdict({ status: 200, data: { entities: [{ entity: 'TILL-06', share: 0.97, payers: 7 }] } })[0], /7 payers/);
  assert.match(cashOutVerdict({ status: 200, data: { entities: [] } })[0], /no cash-out/);
});

test('runFigures reads both measures through a fake server and is read-only (GET only)', async () => {
  const methods = [];
  const fetchFn = async (url, init = {}) => {
    const u = new URL(url);
    const res = (body) => ({ ok: true, status: 200, json: async () => body });
    if (u.pathname.endsWith('/auth/exchange')) return res({ data: { accessToken: 't' } });
    methods.push(init.method);
    return res({ data: u.searchParams.get('name') === 'passThrough' ? goodPass().data : goodCash().data });
  };
  const rs = await runFigures({ fetchFn, base: 'http://x', space: 's' });
  assert.equal(rs.length, 2);
  assert.ok(rs.every((r) => r.ok), JSON.stringify(rs));
  assert.deepEqual(methods, ['GET', 'GET']);
});
