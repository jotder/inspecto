import { test } from 'node:test';
import assert from 'node:assert/strict';
import { render } from './render-api-routes.mjs';

const world = () => ({
    manifests: { inspecto: 'processor', 'features/inspecto-ops': 'ops' },
    items: { processor: 'processor', ops: 'ops' },
    routes: [
        { method: 'GET', pattern: '/runs/([^/]+)', capability: null, file: 'features\\inspecto-ops\\src\\main\\java\\OpsRoutes.java', line: 9 },
        { method: 'POST', pattern: '/runs', capability: 'canRun', file: 'features/inspecto-ops/src/main/java/OpsRoutes.java', line: 12 },
        { method: 'DELETE', pattern: '/notes/(?!all$)([^/]+)', capability: 'canAdminister', file: 'inspecto/src/main/java/NoteRoutes.java', line: 3 },
        { method: 'GET', pattern: '/stray', capability: null, file: 'inspecto/src/main/java/Stray.java', line: 4 },
        { method: 'GET', pattern: '/x', capability: null, file: 'other/src/main/java/X.java', line: 1 },
    ],
    openapi: { paths: {
        '/runs/{runId}': { get: { summary: 'One run', tags: ['Runs'] } },
        '/runs': { post: { summary: 'Start | a run', tags: ['Runs'] } },
        '/notes/{id}': { delete: { summary: 'Delete note' } },
        '/health': { get: { summary: 'Liveness' } },
    } },
});

test('each route lands on its owning module page, joined with its OpenAPI operation', () => {
    const { pages } = render(world());
    const ops = pages['docs/okf/product/module/ops/api-routes.md'];
    assert.match(ops, /GENERATED/);
    assert.match(ops, /\(\.\.\/\.\.\/\.\.\/\.\.\/api\/openapi-v1\.json\)/);
    assert.match(ops, /\| GET \| `\/runs\/\{runId\}` \| One run \| Runs \| — \| `features\/inspecto-ops\/src\/main\/java\/OpsRoutes\.java` \|/);
    assert.match(ops, /\| POST \| `\/runs` \| Start \\\| a run \| Runs \| `canRun` \|/);
    assert.match(ops, /None — every route here is in the contract/);
});

test('a lookahead-guarded regex route still matches its contract path', () => {
    const page = render(world()).pages['docs/okf/product/module/processor/api-routes.md'];
    assert.match(page, /\| DELETE \| `\/notes\/\{id\}` \| Delete note \|/);
});

test('drift both ways is listed, not hidden', () => {
    const page = render(world()).pages['docs/okf/product/module/processor/api-routes.md'];
    assert.match(page, /missing from the OpenAPI contract[\s\S]*\| GET \| `\/stray` \|/);
    assert.match(page, /registered by no module[\s\S]*\| GET \| `\/health` \| Liveness \|/);
});

test('a route outside every manifested module is reported as unowned', () => {
    const { pages, unowned } = render(world());
    assert.deepEqual(unowned.map((r) => r.pattern), ['/x']);
    assert.deepEqual(Object.keys(pages).sort(), ['docs/okf/product/module/ops/api-routes.md', 'docs/okf/product/module/processor/api-routes.md']);
});
