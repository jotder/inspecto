import { test } from 'node:test';
import assert from 'node:assert/strict';
import { check } from './check-doc-routes.mjs';

const contract = ['/runs/{runId}/trigger', '/pipelines', '/health'];
const code = ['/notes/(?!all$)([^/]+)', '/inv/graph/runs/([^/]+)/cancel'];
const run = (doc) => check({ docs: { 'd.md': doc }, contract, code });

test('an unserved route fails with its file:line — the probe a served route would pass', () => {
    assert.deepEqual(run('curl /api/v1/runs/etl/trigger').failures, []);
    assert.deepEqual(run('x\ncurl /api/v1/pipelines/etl/trigger').failures, ['d.md:2  /api/v1/pipelines/etl/trigger']);
});

test('placeholder spellings, concrete ids, space prefix, query and punctuation all normalise', () => {
    const { failures, counts } = run([
        '`/api/v1/runs/{id}/trigger`, `/api/v1/runs/:id/trigger` and `/api/v1/runs/<id>/trigger`.',
        'GET /api/v1/health?verbose=1',
        'POST /api/v1/inv/graph/runs/r-42/cancel',       // code-only regex route
        'DELETE /api/v1/notes/n1',                       // lookahead-guarded code route
        '/api/v1/spaces/acme/pipelines',                 // ControlApi.bindSpace
        '`/api/v1/<path>` → `/api/v1/spaces/<id>/<path>`', // templates
        'the `/api/v1/inv/graph/*` family',              // prefix
        '`/api/v1/legacy/thing` was removed',            // marked
    ].join('\n'));
    assert.deepEqual(failures, []);
    assert.deepEqual(counts, { contract: 5, code: 2, prefix: 1, marked: 1, template: 2, total: 9 });
});

test('a prefix no known route starts with fails', () => {
    assert.deepEqual(run('/api/v1/nothing/*').failures, ['d.md:1  /api/v1/nothing/*']);
});
