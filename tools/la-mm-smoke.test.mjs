// node --test tools/la-mm-smoke.test.mjs  -  the pure verdicts of the mobile-money smoke runner, plus a dry run (no server).
import test from 'node:test';
import assert from 'node:assert/strict';
import { structuringVerdict, passThroughVerdict, allMasked, pathVerdict, flowVerdict, cycleVerdict, alertVerdict, run } from './la-mm-smoke.mjs';

test('structuring needs exactly one entity with 25 legs from 25 payers', () => {
  assert.equal(structuringVerdict({ count: 1, entities: [{ legs: 25, payers: 25 }] }), null);
  assert.match(structuringVerdict({ count: 2, entities: [{}, {}] }), /exactly 1/);
  assert.match(structuringVerdict({ count: 1, entities: [{ legs: 24, payers: 25 }] }), /25 legs/);
});
test('passThrough needs a row at 0.98', () => {
  assert.equal(passThroughVerdict({ entities: [{ ratio: 1.0 }, { ratio: 0.98 }] }), null);
  assert.match(passThroughVerdict({ entities: [{ ratio: 1.0 }] }), /0\.98/);
});
test('masking, path, flow and cycle verdicts', () => {
  assert.equal(allMasked(['masked:a', 'masked:b']), null);
  assert.match(allMasked(['masked:a', 'HUB-01']), /unmasked/);
  assert.equal(pathVerdict({ result: { selection: { nodeIds: [1, 2, 3, 4] } } }, 3), null);
  assert.match(pathVerdict({ result: {} }, 3), /got -1/);
  assert.equal(flowVerdict({ result: { value: 25 } }, 25), null);
  assert.match(flowVerdict({ result: { value: 0 } }, 25), /got 0/);
  assert.equal(cycleVerdict({ result: { selections: [{ nodeIds: [1, 2, 3, 4] }] } }), null);
  assert.match(cycleVerdict({ result: { selections: [] } }), /exactly 1/);
});
test('the Alert must exist and name no wallet', () => {
  assert.equal(alertVerdict([{ rule: 'mm-structuring', message: 'structuring: 1 entity' }]), null);
  assert.match(alertVerdict([]), /no Alert/);
  assert.match(alertVerdict([{ rule: 'mm-structuring', message: 'HUB-01' }]), /aggregate-only/);
});
test('dry run sends nothing', async () => {
  const lines = [];
  const r = await run({ fetchFn: async () => { throw new Error('no request expected'); }, dryRun: true, log: (l) => lines.push(l) });
  assert.deepEqual(r, []);
  assert.equal(lines.length, 1);
});
