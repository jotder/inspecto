// Tests for tools/handbook-checks.mjs. Run: node --test tools/handbook-checks.test.mjs
//
// The docs-as-tests step format: finding the blocks in a page, parsing each line kind (and refusing each malformed one
// with its line number), paths and the assertion operators, routes against an OpenAPI document (with the space seam),
// variables used before they are saved, the reader's one-line description, and the UI verb list kept in step with the
// LA recorder it delegates to.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { UI_VERBS, describe, evalPath, evaluate, extractBlocks, parseBlock, parseExpect, parsePath, routeKnown, substitute, validatePage } from './handbook-checks.mjs';

const OPENAPI = { paths: { '/datasets': { get: {} }, '/jobs/{jobId}/trigger': { post: {} }, '/runs/{runId}': { get: {} }, '/spaces/{spaceId}/detach': { post: {} } } };
const parse = (text, kind = 'api') => parseBlock({ kind, line: 1, lines: text.split('\n').map((t, i) => ({ text: t, line: i + 2 })) });

test('extractBlocks: check and check-ui blocks in order, with fence line numbers; other fences skipped whole', () => {
    const md = '# P\n```check\nGET /datasets\n```\ntext\n````markdown\n```check\nGET /hidden\n```\n````\n~~~check-ui\n{"open":"/data"}\n~~~\n```js\nx\n```';
    const b = extractBlocks(md);
    assert.deepEqual(b.map((x) => [x.kind, x.line]), [['api', 2], ['ui', 11]]);
    assert.deepEqual(b[0].lines.map((l) => [l.text, l.line]), [['GET /datasets', 3]]);
});

test('parseBlock: a full API step', () => {
    const s = parse('# a comment\nPOST /spaces/{space}/jobs/o2c_detect/trigger\nas analyst\nbody {"reason": "tutorial",\n  "n": 1}\nexpect status 200|202\nexpect data.runId exists\nsave data.runId as run\nretry 5 every 2s');
    assert.deepEqual(s.errors, []);
    assert.equal(s.method, 'POST');
    assert.equal(s.path, '/spaces/{space}/jobs/o2c_detect/trigger');
    assert.equal(s.as, 'analyst');
    assert.deepEqual(s.body, { reason: 'tutorial', n: 1 });
    assert.deepEqual(s.expects[0], { kind: 'status', codes: [200, 202], line: 7 });
    assert.equal(s.expects[1].op, 'exists');
    assert.deepEqual(s.saves.map((x) => [x.path, x.name]), [['data.runId', 'run']]);
    assert.deepEqual(s.retry, { tries: 5, everyMs: 2000 });
});

test('parseBlock: each malformed line is refused with its line number, and the rest still parses', () => {
    const bad = [
        ['FETCH /x', /first line is 'METHOD \/path'/],
        ['GET /x\nas two words', /as: one persona/],
        ['GET /x\nbody {"open": 1', /body: not valid JSON/],
        ['GET /x\nexpect data.x ~ 1', /is not 'status <code>'/],
        ['GET /x\nexpect data..x = 1', /is not a path/],
        ['GET /x\nexpect data.x exists 1', /takes no value/],
        ['GET /x\nexpect data.x =', /is not 'status|needs a value/],
        ['GET /x\nexpect data.n > many', /needs a number/],
        ['GET /x\nexpect data.s matches abc', /takes \/regex\/flags/],
        ['GET /x\nsave data.id', /save: '<path> as <name>'/],
        ['GET /x\nretry 0 every 1s', /retry:/],
        ['GET /x\nwait 5', /unknown line 'wait 5'/],
        ['', /block is empty/],
    ];
    for (const [text, re] of bad) {
        const s = parse(text);
        assert.ok(s.errors.some((e) => re.test(e.message)), `${JSON.stringify(text)} → ${JSON.stringify(s.errors)}`);
    }
    const s = parse('GET /x\nwait 5\nexpect status 200');
    assert.deepEqual(s.errors.map((e) => e.line), [3]);
    assert.equal(s.expects.length, 1);
});

test('parseBlock: a check-ui block is one JSON step per line, each with a recorder verb', () => {
    assert.deepEqual(parse('{"space":"o2c"}\n{"open":"/data"}\n{"waitText":"o2c_orders","soft":false}', 'ui').errors, []);
    assert.match(parse('{"open":"/data"', 'ui').errors[0].message, /one JSON step/);
    assert.match(parse('{"teleport":"/data"}', 'ui').errors[0].message, /no known verb/);
    assert.match(parse('["open"]', 'ui').errors[0].message, /JSON object/);
});

test('paths: names, indexes, [*] spreads, length', () => {
    const root = { data: { items: [{ id: 'a', n: 1 }, { id: 'b' }], name: 'abc', obj: { length: 9 } } };
    assert.deepEqual(parsePath('data.items[*].id'), ['data', 'items', '*', 'id']);
    assert.equal(evalPath(root, parsePath('data.items[1].id')), 'b');
    assert.deepEqual(evalPath(root, parsePath('data.items[*].id')), ['a', 'b']);
    assert.deepEqual(evalPath(root, parsePath('data.items[*].n')), [1]);
    assert.equal(evalPath(root, parsePath('data.items.length')), 2);
    assert.equal(evalPath(root, parsePath('data.name.length')), 3);
    assert.equal(evalPath(root, parsePath('data.obj.length')), 9, 'a real key named length wins');
    assert.equal(evalPath(root, parsePath('data.missing.deeper')), undefined);
    assert.throws(() => parsePath('data.[0]'));
});

test('evaluate: every operator, true and false', () => {
    const root = { data: { n: 3, s: 'Order-to-cash', items: [{ id: 'x' }], o: { k: 1 }, nul: null } };
    const ok = (line) => evaluate(parseExpect(line), root).ok;
    const yes = ['data.n = 3', 'data.s = "Order-to-cash"', 'data.o = {"k":1}', 'data.n != 4', 'data.n > 2', 'data.n >= 3', 'data.n < 4', 'data.n <= 3',
        'data.items[*].id has x', 'data.items has {"id":"x"}', 'data.s has cash', 'data.o has k', 'data.s matches /^order/i', 'data.n exists', 'data.nul absent', 'data.none absent'];
    const no = ['data.n = "3"', 'data.n != 3', 'data.n > 3', 'data.s > 1', 'data.items[*].id has y', 'data.s has Cash', 'data.o has z', 'data.n matches /3/', 'data.nul exists', 'data.n absent'];
    for (const l of yes) assert.equal(ok(l), true, l);
    for (const l of no) assert.equal(ok(l), false, l);
    assert.deepEqual(evaluate(parseExpect('data.items[*].id has y'), root).actual, ['x']);
});

test('substitute: {vars} in strings, deep; an unset one throws', () => {
    assert.deepEqual(substitute({ a: '/runs/{run}', b: ['{space}', 2] }, { run: 'r1', space: 's' }), { a: '/runs/r1', b: ['s', 2] });
    assert.throws(() => substitute('/runs/{nope}', {}), /\{nope\} is not set/);
});

test('routeKnown: templates match either side, query ignored, the /spaces/{id} seam strips', () => {
    assert.ok(routeKnown('GET', '/datasets?limit=5', OPENAPI));
    assert.ok(routeKnown('GET', '/spaces/{space}/datasets', OPENAPI));
    assert.ok(routeKnown('GET', '/spaces/o2c/runs/{run}', OPENAPI));
    assert.ok(routeKnown('POST', '/spaces/o2c/detach', OPENAPI));
    assert.ok(!routeKnown('POST', '/datasets', OPENAPI), 'wrong method');
    assert.ok(!routeKnown('GET', '/spaces/o2c/dataset', OPENAPI), 'no such path');
});

test('validatePage: syntax, unknown route and a variable before its save are findings; a clean page has none', () => {
    const clean = '```check\nPOST /spaces/{space}/jobs/d/trigger\nsave data.runId as run\n```\n```check\nGET /spaces/{space}/runs/{run}\nexpect data.state = "SUCCEEDED"\nretry 10 every 1s\n```';
    assert.deepEqual(validatePage(clean, () => OPENAPI), []);
    const f = validatePage('```check\nGET /spaces/{space}/runs/{run}\n```\n```check\nGET /nowhere\nexpect bad\n```', () => OPENAPI);
    assert.ok(f.some((x) => /\{run\} is used before a 'save … as run'/.test(x.message) && x.line === 1));
    assert.ok(f.some((x) => /GET \/nowhere is not an operation/.test(x.message)));
    assert.ok(f.some((x) => /expect: 'bad'/.test(x.message) && x.line === 6));
    let loaded = 0;
    assert.deepEqual(validatePage('# no checks', () => { loaded++; return OPENAPI; }), []);
    assert.equal(loaded, 0, 'the OpenAPI document is read only when a page has an API check');
});

test('describe: one quiet line for the reader', () => {
    assert.equal(describe(parse('GET /spaces/{space}/datasets\nas analyst\nexpect status 200\nexpect data.items.length >= 3')),
        'GET /spaces/{space}/datasets as analyst answers 200, 1 more expectation');
    assert.equal(describe(parse('DELETE /x')), 'DELETE /x answers 2xx');
    assert.equal(describe(parse('{"open":"/data"}\n{"wait":500}\n{"waitText":"o2c_orders"}\n{"escape":true}', 'ui')), 'In the console: open “/data” · waitText “o2c_orders” · escape');
});

test('UI_VERBS is exactly the verb set tools/record-la-live.mjs `episode` understands', () => {
    const src = readFileSync(new URL('./record-la-live.mjs', import.meta.url), 'utf8');
    const body = src.slice(src.indexOf('episode: async (h) =>'), src.indexOf("throw new Error('unknown episode step"));
    const verbs = new Set([...body.matchAll(/if \('(\w+)' in st\)/g), ...body.matchAll(/if \(st\.(\w+)\)/g)].map((m) => m[1]));
    assert.ok(verbs.size > 20, 'the recorder source moved — update this test');
    assert.deepEqual([...verbs].sort(), [...UI_VERBS].sort());
});
