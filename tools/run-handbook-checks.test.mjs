// Tests for tools/run-handbook-checks.mjs. Run: node --test tools/run-handbook-checks.test.mjs
//
// End to end: a tiny stub of the Control API (demo sign-in, a Space-scoped list, a Job trigger, a Run that finishes
// on its third read) is served on a free port inside the test, and the CLI runs a temporary manifest of pages against
// it — passing pages exit 0, a failing assertion exits 1 naming the page, step, file:line, expectation and the answer,
// usage errors exit 2. In process: sign-in order (env token, credentials file, demo exchange, Basic's 503), saves
// feeding later steps, retry, a page stopping at its first failure, and UI steps reported NOT RUN, never passed.

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkedPages, runChecks, signIn } from './run-handbook-checks.mjs';
import { loadManifest } from './render-handbook.mjs';

const RUNNER = fileURLToPath(new URL('./run-handbook-checks.mjs', import.meta.url));
const env = (data) => JSON.stringify({ data, metadata: {}, diagnostics: [] });

/** The stub: Basic-like when `authFree`, else it hands out `tok-<user>` on the demo exchange and checks it. */
function stub({ authFree = false } = {}) {
    const seen = [];
    let reads = 0;
    const server = http.createServer((req, res) => {
        let body = '';
        req.on('data', (d) => { body += d; });
        req.on('end', () => {
            seen.push({ method: req.method, url: req.url, auth: req.headers.authorization || null, body });
            const send = (code, text) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(text); };
            if (req.url === '/api/v1/auth/exchange') {
                if (authFree) return send(503, '{"error":{"code":"UNAVAILABLE"}}');
                const code = JSON.parse(body).code;
                return code === 'demo:ghost' ? send(401, '{}') : send(200, env({ accessToken: `tok-${code.slice(5)}` }));
            }
            const who = (req.headers.authorization || '').replace('Bearer tok-', '');
            if (!authFree && !who) return send(401, '{"error":{"code":"UNAUTHENTICATED"}}');
            if (req.method === 'GET' && req.url === '/api/v1/spaces/o2c/datasets') return send(200, env({ items: [{ id: 'o2c_orders' }, { id: 'o2c_invoices' }], who }));
            if (req.method === 'POST' && req.url === '/api/v1/spaces/o2c/jobs/o2c_detect/trigger') return send(202, env({ runId: 'r-7', reason: JSON.parse(body).reason }));
            if (req.method === 'GET' && req.url === '/api/v1/spaces/o2c/runs/r-7') return send(200, env({ state: ++reads >= 3 ? 'SUCCEEDED' : 'RUNNING' }));
            return send(404, '{"error":{"code":"NOT_FOUND"}}');
        });
    });
    return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, seen, base: `http://127.0.0.1:${server.address().port}`, reset: () => { reads = 0; seen.length = 0; } })));
}

const PASSING = `# Run the detection
## Steps
\`\`\`check
GET /spaces/{space}/datasets
as analyst
expect status 200
expect data.items[*].id has o2c_orders
expect data.items.length >= 2
\`\`\`
\`\`\`check
POST /spaces/{space}/jobs/o2c_detect/trigger
as analyst
body {"reason": "tutorial"}
expect status 202
expect data.reason = "tutorial"
save data.runId as run
\`\`\`
## Check it worked
\`\`\`check
GET /spaces/{space}/runs/{run}
as analyst
expect data.state = "SUCCEEDED"
retry 5 every 10ms
\`\`\`
`;
const FAILING = '# Broken\n```check\nGET /spaces/{space}/datasets\nas analyst\nexpect data.items[*].id has o2c_payments\n```\n```check\nGET /spaces/{space}/datasets\n```\n';
const UI = '# UI\n```check-ui\n{"open":"/data"}\n{"waitText":"o2c_orders"}\n```\n';

let s, dir;
before(async () => {
    s = await stub();
    dir = mkdtempSync(path.join(tmpdir(), 'handbook-run-'));
    mkdirSync(path.join(dir, 'docs'));
    writeFileSync(path.join(dir, 'docs/pass.md'), PASSING);
    writeFileSync(path.join(dir, 'docs/fail.md'), FAILING);
    writeFileSync(path.join(dir, 'docs/ui.md'), UI);
    writeFileSync(path.join(dir, 'docs/none.md'), '# No checks here');
    const page = (id, file) => `      ${id}:\n        file: ${file}\n        type: tutorial\n        tracks[1]: builder\n        audience: internal\n`;
    writeFileSync(path.join(dir, 'handbook.toon'), 'handbook:\n  title: T\nchapters:\n  start:\n    title: Start\n    group: G\n    pages:\n' +
        page('pass', 'docs/pass.md') + page('fail', 'docs/fail.md') + page('ui', 'docs/ui.md') + page('none', 'docs/none.md'));
});
after(() => { s.server.close(); rmSync(dir, { recursive: true, force: true }); });

/** Run the CLI in the temp folder (async: the stub answers from this same process). */
const cli = (args, extraEnv = {}) => new Promise((resolve) => {
    execFile(process.execPath, [RUNNER, '--manifest', 'handbook.toon', ...args], { cwd: dir, env: { ...process.env, INSPECTO_HANDBOOK_TOKEN: '', ...extraEnv } },
        (err, stdout, stderr) => resolve({ code: err ? err.code : 0, out: stdout + stderr }));
});

test('CLI: a passing page exits 0, signs in as the persona, feeds a save into the next step and retries until done', async () => {
    s.reset();
    const r = await cli(['--base-url', s.base + '/', '--page', 'pass', '--space', 'o2c']);
    assert.equal(r.code, 0, r.out);
    assert.match(r.out, /✓ run-handbook-checks: 1 page\(s\) passed/);
    assert.match(r.out, /after 3 tries/);
    assert.deepEqual(s.seen.filter((x) => x.url === '/api/v1/auth/exchange').length, 1, 'one exchange per persona, cached');
    assert.ok(s.seen.filter((x) => x.url.includes('/spaces/')).every((x) => x.auth === 'Bearer tok-analyst'));
    assert.ok(s.seen.some((x) => x.url === '/api/v1/spaces/o2c/runs/r-7'), 'the saved run id reached the path');
});

test('CLI: a failing assertion exits 1 naming page, step, file:line, the expectation and the answer; the page stops there', async () => {
    s.reset();
    const r = await cli(['--base-url', s.base, '--page', 'fail', '--space', 'o2c']);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, /fail: step 1 \(docs\/fail\.md:2\) GET \/spaces\/\{space\}\/datasets: expect data\.items\[\*\]\.id has o2c_payments \(line 5\) — got \["o2c_orders","o2c_invoices"\]/);
    assert.equal(s.seen.filter((x) => x.url.includes('/datasets')).length, 1, 'step 2 never ran');
});

test('CLI: usage errors exit 2 — no base URL, unknown page, a page with no check', async () => {
    assert.equal((await cli([])).code, 2);
    assert.equal((await cli(['--base-url', s.base, '--timeout', 'abc'])).code, 2);
    assert.match((await cli(['--base-url', s.base, '--page', 'nope'])).out, /no page 'nope'/);
    const none = await cli(['--base-url', s.base, '--page', 'none']);
    assert.equal(none.code, 2);
    assert.match(none.out, /carries no check block/);
});

test('CLI: the whole manifest — one failure fails the run, UI steps are NOT RUN and counted, never passed', async () => {
    s.reset();
    const r = await cli(['--base-url', s.base, '--space', 'o2c']);
    assert.equal(r.code, 1, r.out);
    assert.match(r.out, /1 of 3 page\(s\) failed; 1 UI step\(s\) NOT RUN/);
    assert.match(r.out, /UI: NOT RUN \(needs --ui/);
});

const pagesOf = (files) => {
    const book = loadManifest('handbook:\n  title: T\nchapters:\n  start:\n    title: S\n    group: G\n    pages:\n' + Object.keys(files).map((f, i) =>
        `      p${i}:\n        file: ${f}\n        type: howto\n        tracks[1]: builder\n        audience: external\n`).join(''));
    return checkedPages(book, (f) => files[f]);
};

test('checkedPages: a block that does not parse stops the run before any request, pointing at check-handbook', () => {
    assert.throws(() => pagesOf({ 'a.md': '```check\nGET /x\nexpect nonsense\n```' }), /a\.md:3: .*run node tools\/check-handbook\.mjs/);
});

test('signIn: per-persona env token, then the credentials file, then the demo exchange; no persona → generic token or none', async () => {
    const calls = [];
    const fetchFn = async (url, o) => { calls.push(JSON.parse(o.body).code); return new Response(env({ accessToken: 'demo-tok' }), { status: 200 }); };
    const auth = signIn({ base: 'http://x', env: { INSPECTO_HANDBOOK_TOKEN_DATA_STEWARD: 'env-tok', INSPECTO_HANDBOOK_TOKEN: 'gen' }, creds: { tokens: { analyst: 'file-tok' } }, fetchFn });
    assert.equal(await auth('data-steward'), 'Bearer env-tok');
    assert.equal(await auth('analyst'), 'Bearer file-tok');
    assert.equal(await auth('manager'), 'Bearer demo-tok');
    assert.equal(await auth(null), 'Bearer gen');
    assert.deepEqual(calls, ['demo:manager'], 'the generic token never stands in for a named persona');
    assert.equal(await signIn({ base: 'http://x', env: {}, fetchFn })(null), null);
    assert.equal(await signIn({ base: 'http://x', env: {}, creds: { tokens: {} }, fetchFn })('constructor'), 'Bearer demo-tok', 'an inherited key is not a token');
});

test('signIn: an exchange refused is a clear error naming the env variable; Basic (503) sends no header', async () => {
    const refused = signIn({ base: 'http://x', env: {}, fetchFn: async () => new Response('{}', { status: 401 }) });
    await assert.rejects(refused('ghost'), /no credential for persona 'ghost': set INSPECTO_HANDBOOK_TOKEN_GHOST/);
    const basic = signIn({ base: 'http://x', env: {}, fetchFn: async () => new Response('{}', { status: 503 }) });
    assert.equal(await basic('analyst'), null);
});

test('runChecks: against an auth-free (Basic-like) stub, steps go without a header and pass', async () => {
    const b = await stub({ authFree: true });
    try {
        const r = await runChecks(pagesOf({ 'p.md': PASSING }), { base: b.base, vars: { space: 'o2c' }, env: {}, sleep: async () => {} });
        assert.equal(r.failed, 0, JSON.stringify(r.pages));
        assert.ok(b.seen.filter((x) => x.url.includes('/spaces/')).every((x) => x.auth === null));
    } finally { b.server.close(); }
});

test('runChecks: a retry that never succeeds reports the last answer and the tries; an unset variable fails cleanly', async () => {
    s.reset();
    const never = '```check\nGET /spaces/{space}/runs/r-7\nas analyst\nexpect data.state = "FAILED"\nretry 2 every 1ms\n```';
    let r = await runChecks(pagesOf({ 'p.md': never }), { base: s.base, vars: { space: 'o2c' }, env: {}, sleep: async () => {} });
    assert.match(r.pages[0].failure, /expect data\.state = "FAILED" \(line 4\) — got "RUNNING" \(after 2 tries\)/);
    r = await runChecks(pagesOf({ 'p.md': never }), { base: s.base, vars: {}, env: {}, sleep: async () => {} });
    assert.match(r.pages[0].failure, /variable \{space\} is not set/);
});

test('runChecks: a status other than the expected one shows the answer body', async () => {
    const r = await runChecks(pagesOf({ 'p.md': '```check\nGET /spaces/o2c/nothing\nas analyst\n```' }), { base: s.base, env: {}, sleep: async () => {} });
    assert.match(r.pages[0].failure, /expect status 2xx — got 404 \{"error":\{"code":"NOT_FOUND"\}\}/);
});
