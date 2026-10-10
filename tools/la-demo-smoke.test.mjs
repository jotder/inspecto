// Unit test for the pure assertion helpers of tools/la-demo-smoke.mjs, plus a stub-server run. node --test tools/la-demo-smoke.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { expectStatus, countLaRoutes, tamperManifest, rateLimitVerdict, poolVerdict, buildProbes, run, EXPECTED_LA_ROUTES } from './la-demo-smoke.mjs';

test('expectStatus checks the status and, when asked, the error code', () => {
  assert.equal(expectStatus({ status: 404, body: { error: { errorCode: 'MODULE_DISABLED' } } }, { status: 404, code: 'MODULE_DISABLED' }), null);
  assert.match(expectStatus({ status: 404, body: { error: { errorCode: 'NOT_FOUND' } } }, { status: 404, code: 'MODULE_DISABLED' }), /MODULE_DISABLED/);
  assert.match(expectStatus({ status: 200, body: null }, 401), /401/);
  assert.equal(expectStatus({ status: 401, body: null }, 401), null);
});

test('countLaRoutes counts /inv and /geo rows only; a non-inventory is -1', () => {
  const routes = [{ pattern: '/inv/index' }, { pattern: '/geo/projection' }, { pattern: '/cases' }, { pattern: '/invoice' }];
  assert.equal(countLaRoutes({ routes }), 2);
  assert.equal(countLaRoutes({}), -1);
});

test('tamperManifest changes every 64-hex hash and keeps the rest', () => {
  const h = 'a'.repeat(64);
  const t = tamperManifest({ root: h, entries: [{ path: 'log.jsonl', sha256: h }], n: 3 });
  assert.notEqual(t.root, h);
  assert.notEqual(t.entries[0].sha256, h);
  assert.equal(t.entries[0].path, 'log.jsonl');
  assert.equal(t.n, 3);
});

test('tamperManifest flips the server form sha256:<64hex> and keeps the prefix', () => {
  const h = 'sha256:' + 'a'.repeat(64);
  const t = tamperManifest({ root: h, artefacts: [{ sha256: h }], content: { entities: h } });
  assert.equal(t.root, 'sha256:0' + 'a'.repeat(63));
  assert.notEqual(t.artefacts[0].sha256, h);
  assert.notEqual(t.content.entities, h);
});

test('tamperManifest refuses a manifest with no hash to tamper', () => {
  assert.throws(() => tamperManifest({ root: 'not-a-hash', n: 3 }), /no hash field/);
});

test('rateLimitVerdict needs a 429 and a bounded pass-through', () => {
  assert.equal(rateLimitVerdict([...Array(20).fill(200), ...Array(5).fill(429)]), null);
  assert.match(rateLimitVerdict(Array(25).fill(200)), /no 429/);
  assert.match(rateLimitVerdict([...Array(30).fill(200), 429]), /got through/);
});

test('poolVerdict needs at least one 503', () => {
  assert.equal(poolVerdict([...Array(18).fill(200), 503]), null);
  assert.match(poolVerdict(Array(19).fill(200)), /no 503/);
});

test('the probe table covers 401, 403, 404, 404 MODULE_DISABLED and 422', () => {
  const wants = buildProbes().map((p) => (typeof p.want === 'number' ? p.want : `${p.want.status}:${p.want.code}`));
  for (const w of [401, 403, 404, '404:MODULE_DISABLED', 422]) assert.ok(wants.includes(w), String(w));
});

test('run() against a stub server passes every probe, and fails a wrong status', async () => {
  const hash = 'b'.repeat(64);
  const stub = (wrong401) => async (url, init = {}) => {
    const u = new URL(url); const p = u.pathname.replace(/^\/api\/v1(\/spaces\/[^/]+)?/, ''); const space = (u.pathname.match(/spaces\/([^/]+)/) || [])[1];
    const who = (init.headers || {}).authorization;
    const res = (status, body) => ({ ok: status < 400, status, json: async () => body });
    if (p === '/auth/exchange') return res(200, { data: { accessToken: JSON.parse(init.body).code } });
    if (!who) return res(wrong401 ? 200 : 401, {});
    if (who === 'Bearer demo:ra.analyst') return res(403, {});
    if (who === 'Bearer demo:demo.manager') return res(404, {});
    if (space === 'la-showcase-off') return res(404, { error: { errorCode: 'MODULE_DISABLED' } });
    if (p === '/inv/investigations' && init.method === 'POST') return res(422, {});
    if (p.endsWith('/log')) return res(200, { data: {} });
    if (p === '/audit/route-inventory') return res(200, { data: { routes: Array.from({ length: 75 }, (_, i) => ({ pattern: `/inv/x${i}` })) } });
    if (p.endsWith('/dossier')) return res(200, { data: { manifest: { root: hash } } });
    if (p.endsWith('/dossier/verify')) return res(200, { data: { verified: JSON.parse(init.body).manifest.root === hash } });
    return res(500, {});
  };
  const ok = await run({ fetchFn: stub(false) });
  assert.ok(ok.every((r) => r.ok), JSON.stringify(ok.filter((r) => !r.ok)));
  assert.ok(ok.some((r) => r.id.includes(String(EXPECTED_LA_ROUTES))));
  const bad = await run({ fetchFn: stub(true) });
  assert.ok(bad.some((r) => !r.ok && r.id === '401 no token'));
});
