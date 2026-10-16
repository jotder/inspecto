// node --test tools/la-claims-smoke.test.mjs  -  the claims smoke's pure verdicts and its dry run (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import { geoVerdict, GEO_VIEWS, registryVerdict, ringGrowthVerdict, maskedVerdict, runVerdict, run, ALGORITHMS, LINK_COLUMNS } from './la-claims-smoke.mjs';

test('registryVerdict names a missing link column', () => {
  const full = [{ name: 'claims_links_dataset', content: { columns: LINK_COLUMNS.map((name) => ({ name })) } }];
  assert.equal(registryVerdict(full), null);
  assert.match(registryVerdict([{ name: 'claims_links_dataset', content: { columns: [{ name: 'src' }] } }]), /lacks columns: dst/);
  assert.match(registryVerdict([]), /not listed/);
});

test('ringGrowthVerdict wants the 12 claimants and the 3 providers', () => {
  const ws = (n) => ({ status: 200, data: { rows: Array.from({ length: n }, (_, i) => ({ entityId: `e${i}` })) } });
  assert.equal(ringGrowthVerdict(ws(15)), null);
  assert.match(ringGrowthVerdict(ws(9)), /expected at least 15/);
});

test('maskedVerdict fails on a raw claimant id and passes on pseudonyms', () => {
  assert.equal(maskedVerdict({ status: 200, data: { rows: [{ entityId: 'party:masked:0a1b2c3d4e5f6a7b' }] } }), null);
  assert.match(maskedVerdict({ status: 200, data: { rows: [{ entityId: 'party:CL-0134' }] } }), /raw claimant id/);
});

test('runVerdict accepts 200/202 and refuses a failed or budget-exceeded run', () => {
  assert.equal(runVerdict({ status: 200, data: { status: 'SUCCEEDED' } }), null);
  assert.equal(runVerdict({ status: 202, data: {} }), null);
  assert.match(runVerdict({ status: 200, data: { status: 'BUDGET_EXCEEDED' } }), /ended/);
  assert.match(runVerdict({ status: 500, body: {} }), /HTTP 500/);
});

test('the nine claims algorithms are named and the dry run sends nothing', async () => {
  assert.equal(new Set(ALGORITHMS).size, 9);
  let sent = 0;
  const lines = [];
  await run({ fetchFn: async () => { sent++; return { ok: true, status: 200, json: async () => ({}) }; }, dryRun: true, log: (l) => lines.push(l) });
  assert.equal(sent, 0);
  assert.ok(lines.length >= 7);
});

test('geoVerdict wants at least one point', () => {
  assert.equal(geoVerdict({ status: 200, data: { points: [{ id: 'pt:0' }] } }), null);
  assert.match(geoVerdict({ status: 200, data: { points: [] } }), /no points/);
  assert.match(geoVerdict({ status: 422, body: {} }), /HTTP 422/);
  assert.equal(GEO_VIEWS.length, 3);
});
