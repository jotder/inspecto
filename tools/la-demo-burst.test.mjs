// node --test tools/la-demo-burst.test.mjs - runBurst against a fake server with the real limits (pool 18 slots, queue 5, bucket 20).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runBurst } from './la-demo-burst.mjs';

// `bucket` is the shared token bucket; sleep() in the test refills it fully, as the 3 s/token wait does in real life.
function fakeServer({ poolSlots = 18, queueSlots = 5, bucket = 20, inline = false } = {}) {
  let runs = 0, builds = 0, tokens = bucket;
  const fetchFn = async (url) => {
    const p = new URL(url).pathname.replace(/^\/api\/v1(\/spaces\/[^/]+)?/, '');
    const res = (status, body = {}) => ({ ok: status < 400, status, json: async () => body });
    if (p === '/auth/exchange') return res(200, { data: { accessToken: 't' } });
    if (tokens <= 0) return res(429);
    tokens--;
    if (p === '/inv/graph/runs') return inline ? res(200) : ++runs > poolSlots ? res(503) : res(202);
    if (p === '/inv/index/builds') return ++builds > queueSlots ? res(503, { error: { errorCode: 'STORE_BUSY' } }) : res(202);
    if (p === '/inv/projection') return res(200);
    return res(500);
  };
  return { fetchFn, refill: () => { tokens = bucket; } };
}
const run = (srv) => runBurst({ fetchFn: srv.fetchFn, base: 'http://x', space: 's', sleep: async () => srv.refill() });

test('a server with the documented limits passes every burst probe', async () => {
  const rs = await run(fakeServer());
  assert.equal(rs.length, 3);
  assert.ok(rs.every((r) => r.ok), JSON.stringify(rs));
});

test('a server with no pool bound, no queue bound or no rate limit fails the matching probe', async () => {
  const noPool = await run(fakeServer({ poolSlots: 1e9 }));
  assert.match(noPool.find((r) => r.id.includes('Graph Runs')).reason, /no 503/);
  const noQueue = await run(fakeServer({ queueSlots: 1e9 }));
  assert.match(noQueue.find((r) => r.id.includes('index builds')).reason, /no 503/);
  const noLimit = await run(fakeServer({ bucket: 1e9 }));
  assert.match(noLimit.find((r) => r.id.includes('429')).reason, /no 429/);
});

test('every run answered inline is a SKIP, not a pass or a fail', async () => {
  const rs = await run(fakeServer({ inline: true }));
  const pool = rs.find((r) => r.id.includes('Graph Runs'));
  assert.ok(pool.ok && pool.skipped);
});
