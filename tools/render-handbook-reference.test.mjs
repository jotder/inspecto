// Tests for tools/render-handbook-reference.mjs. Run: node --test tools/render-handbook-reference.test.mjs
//
// Each page builder on small inputs (grouping, the edition column, the matrix cells, the glossary's first sentences),
// the banner docs-health reads, and the committed pages being current.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { GENERATED_BANNER } from './docs-health.mjs';
import { editionsReference, firstSentence, httpReference, renderAll, vocabularyReference } from './render-handbook-reference.mjs';
import { routeModules } from './render-api-routes.mjs';

const ED = { core: 'Basic', processor: 'Basic', cases: 'Standard', demo: null };

test('http: every operation once, grouped by first tag, with the registering module\'s editions; unregistered → the host', () => {
    const openapi = { paths: {
        '/cases': { get: { tags: ['cases'], summary: 'List | Cases' }, post: { tags: ['cases'], summary: 'Open' } },
        '/health': { get: { tags: ['health'], summary: 'Liveness' } },
        '/demo': { get: { summary: 'Demo only' } },
    } };
    const owner = { 'GET /cases': 'cases', 'POST /cases': 'cases', 'GET /demo': 'demo' };
    const md = httpReference(openapi, (m, p) => owner[`${m} ${p}`] ?? null, (id) => ED[id]);
    assert.ok(md.includes('— 4 in 3 areas'));
    assert.ok(md.includes('| GET | `/cases` | List \\| Cases | Standard · Advanced |'));
    assert.ok(md.indexOf('| GET | `/cases`') < md.indexOf('| POST | `/cases`'), 'GET before POST on one path');
    assert.ok(md.includes('| GET | `/health` | Liveness | Basic · Standard · Advanced |'), 'no registering module → the control-plane host');
    assert.ok(md.includes('## other') && md.includes('| no sold edition |'));
    assert.ok(GENERATED_BANNER.test(md.split('\n').slice(0, 15).join('\n')), 'docs-health reads the banner');
});

test('editions: one table per kind, ✅ from the first edition on, — everywhere for an item in no sold edition', () => {
    const items = { 'addon/b': { kind: 'addon', title: 'Beta' }, 'addon/a': { kind: 'addon', title: 'Alpha' },
        'module/x': { kind: 'module', title: 'Ex' }, 'module/d': { kind: 'module', title: 'Demo' }, 'industry/i': { kind: 'industry', title: 'Ind' } };
    const md = editionsReference(items, (k) => ({ 'addon/a': 'Basic', 'addon/b': 'Advanced', 'module/x': 'Standard', 'module/d': null })[k] ?? null);
    assert.ok(md.includes('| Add-ons | Basic | Standard | Advanced |'));
    assert.ok(md.indexOf('| Alpha |') < md.indexOf('| Beta |'), 'sorted by title');
    assert.ok(md.includes('| Alpha | ✅ | ✅ | ✅ |') && md.includes('| Beta | — | — | ✅ |'));
    assert.ok(md.includes('| Ex | — | ✅ | ✅ |') && md.includes('| Demo | — | — | — |'));
    assert.ok(!md.includes('Ind') && !md.includes('## Products'), 'kinds with no rows and unlisted kinds are left out');
});

test('firstSentence: stops at the first sentence end outside parentheses and code, keeps abbreviations', () => {
    assert.equal(firstSentence('A Space. Owns things.'), 'A Space.');
    assert.equal(firstSentence('A grant (e.g. Admin. Power) held. More.'), 'A grant (e.g. Admin. Power) held.');
    assert.equal(firstSentence('Named, e.g. `a.b`, here. Next.'), 'Named, e.g. `a.b`, here.');
    assert.equal(firstSentence('Term *(added 2026-11-15, `X-1` finding 3)* — x. Y.'), 'Term — x.');
    assert.equal(firstSentence('See [the guide](a/b.md) for more. Next.'), 'See the guide for more.');
    assert.equal(firstSentence('A thing (`LA-ONBOARD-1`, shipped) that works. Next.'), 'A thing that works.');
    assert.equal(firstSentence('no end at all'), 'no end at all');
});

test('vocabulary: **Term** — definition paragraphs under their sections, multi-line bodies joined, tables and lists skipped', () => {
    const md = vocabularyReference(['# Title', '## 1. Tenancy', '', '**Space** — A fully isolated', 'project. Owns more.', '',
        '**Pod** *(added 2026-09-13, `POD-1`)* — One process. More.', '| **Not** — a table | x |', '## 13. Rename map', '| a | b |', '## 1-A. Personas',
        '**Lens** — A view.', '- **Listed** — not a term'].join('\n'));
    assert.ok(md.includes('The 3 words'));
    assert.ok(md.includes('## Tenancy\n\n- **Space** — A fully isolated project.\n- **Pod** — One process.'));
    assert.ok(md.includes('## Personas\n\n- **Lens** — A view.'));
    assert.ok(!md.includes('Rename map') && !md.includes('Not**') && !md.includes('Listed'));
});

test('routeModules: an operation maps to the module whose src/main registers it; parameters match by position', () => {
    const world = {
        manifests: { 'features/x': 'cases', 'features/x-sub': 'sub' },
        routes: [{ method: 'GET', pattern: '/cases/([^/]+)', file: 'features\\x\\src\\main\\java\\R.java' },
            { method: 'POST', pattern: '/sub', file: 'features/x-sub/src/main/java/S.java' },
            { method: 'GET', pattern: '/test', file: 'features/x/src/test/java/T.java' }],
        openapi: { paths: { '/cases/{id}': { get: {} }, '/sub': { post: {} }, '/test': { get: {} }, '/none': { get: {} } } },
    };
    const m = routeModules(world);
    assert.equal(m.get('GET /cases/{id}'), 'cases');
    assert.equal(m.get('POST /sub'), 'sub', 'the longest module folder wins');
    assert.equal(m.has('GET /test'), false, 'test sources do not register');
    assert.equal(m.has('GET /none'), false);
});

test('the committed pages are current (run --write after changing the contract, the glossary or the Product Map)', () => {
    for (const [f, text] of Object.entries(renderAll('.'))) assert.equal(readFileSync(f, 'utf8').replace(/\r\n/g, '\n'), text, `${f} is stale`);
});
