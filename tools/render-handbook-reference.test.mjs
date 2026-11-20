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

test('http: every operation once, grouped by first tag; only an optional module\'s route claims a first edition', () => {
    const openapi = { paths: {
        '/cases': { get: { tags: ['cases'], summary: 'List | Cases' }, post: { tags: ['cases'], summary: 'Open' } },
        '/health': { get: { tags: ['health'], summary: 'Liveness' } },
        '/demo': { get: { summary: 'Demo only' } },
    } };
    const owner = { 'GET /cases': 'cases', 'POST /cases': 'cases', 'GET /demo': 'demo', 'GET /health': 'core' };
    const md = httpReference(openapi, (m, p) => owner[`${m} ${p}`] ?? null, (id) => ED[id]);
    assert.ok(md.includes('— 4 in 3 areas'));
    assert.ok(md.includes('| GET | `/cases` | List \\| Cases | Standard and up |'));
    assert.ok(md.indexOf('| GET | `/cases`') < md.indexOf('| POST | `/cases`'), 'GET before POST on one path');
    assert.ok(md.includes('| GET | `/health` | Liveness |  |'), 'a base module\'s route makes no edition claim');
    assert.ok(md.includes('## other') && md.includes('| GET | `/demo` | Demo only | no sold edition |'));
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
    assert.equal(firstSentence('One shared set (`com.gamma.objects.Kinds`), never a list. X.'), 'One shared set, never a list.');
    assert.equal(firstSentence('A gate (e.g. `canX` — `LensService`). X.'), 'A gate.');
    assert.equal(firstSentence('A trail (exports; `okf/a/plan.md` §3). X.'), 'A trail.');
    assert.equal(firstSentence('Rules incl. transforms. X.'), 'Rules incl. transforms.');
    assert.equal(firstSentence('**One inner file of an Archive.** ⛔ Never a member.'), '**One inner file of an Archive.**');
    // two spans in one sentence: the prose between them stays (verification of b1487d2ed)
    assert.equal(firstSentence('Subject = `id`, `dataScopes`, plus claims allowlisted in `roles.toon` `identity.attributeClaims` here. X.'),
        'Subject = `id`, `dataScopes`, plus claims allowlisted in `roles.toon` `identity.attributeClaims` here.', 'a config key stays');
    assert.equal(firstSentence('The Studio (`/studio/link-analysis`) for graphs, saved as `link-analysis-view`. X.'),
        'The Studio (`/studio/link-analysis`) for graphs, saved as `link-analysis-view`.');
    assert.equal(firstSentence('The `/api/v1` contract, its envelope, ETag concurrency, `GET /bootstrap`, and `Api.java` policy. X.'),
        'The `/api/v1` contract, its envelope, ETag concurrency, `GET /bootstrap`, and policy.');
    assert.equal(firstSentence('A playbook (the Type, `pack.Playbooks`, run by `pack.Investigator`) for one Incident. X.'), 'A playbook for one Incident.');
    assert.equal(firstSentence('Points as `GeoData {points, routes}` on a map. X.'), 'Points as on a map.');
    assert.equal(firstSentence('`inspecto-agent`\'s `EscalationPolicy`: a retry ladder. X.'), 'a retry ladder.');
    assert.equal(firstSentence('Kinds `MSISDN`, `ACCOUNT` and `link-analysis-view`. X.'), 'Kinds `MSISDN`, `ACCOUNT` and `link-analysis-view`.', 'values stay');
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

test('vocabulary: wrapped notes and plain-parenthesis names are terms; a bold word inside a paragraph and a repeat are not', () => {
    const md = vocabularyReference(['## 4. Rules', '', '**Load** *(added 2026-08-13 — as built:', '[x](a.md))* — The third stage. More.', '',
        '**Submission** (of a Report) — Writing the bytes. More.', '', '**Triage** / **Triage Run** *(renamed)* —', 'A playbook. More.', '',
        '**Annotation** — The umbrella for metadata: a Note. Every Annotation addresses its subject as an',
        '**Annotation Target** — a pair. More.', '', '**Load** — Again. More.'].join('\n'));
    assert.ok(md.includes('- **Load** — The third stage.'));
    assert.ok(md.includes('- **Submission (of a Report)** — Writing the bytes.'));
    assert.ok(md.includes('- **Triage / Triage Run** — A playbook.'));
    assert.ok(!md.includes('**Annotation Target**'), 'a bold word wrapped to a line start is not a term');
    assert.ok(!md.includes('Again'), 'a term is kept once');
    assert.ok(md.includes('The 4 words'));
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
