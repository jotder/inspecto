// Falsifies the "core must not import Link Analysis / Geo library code" lint (eslint.config.mjs, `coreMustNotImportLa`,
// D-5 step 1). Every probe is linted as text under a REAL core path, so a `files:` glob that stops matching (the
// "lint scope is a silent exemption" trap) turns the flag cases red. Run from inspecto-ui/:
//   node --test tools/core-no-la-imports-lint.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: process.cwd() });
const CORE_API = 'src/app/inspecto/api/probe.ts';
const CORE_GRAPH = 'src/app/inspecto/graph/entity-key.ts'; // a core-slice file of the shared graph folder

async function flags(code, filePath) {
    const [r] = await eslint.lintText(code, { filePath });
    // a library import is reported by TWO patterns (the alias ban and the folder-name ban): count "flagged", not messages
    return Math.min(1, r.messages.filter((m) => m.ruleId === 'no-restricted-imports').length);
}
const imp = (spec) => `import { X } from '${spec}';\nexport const y = X;`;

for (const spec of [
    'app/inspecto/graph', // the barrel: pulls the whole library half
    'app/inspecto/graph/graph-analysis',
    '../graph/branching-pattern-engine',
    '../graph/index',
    'app/inspecto/geo',
    'app/inspecto/la-host',
    'app/inspecto/investigation/pivot.service',
    'app/modules/admin/studio/link-analysis/entity-projection',
    'app/modules/admin/studio/geo-map/geo-map.routes',
    '../../modules/admin/studio/link-analysis/x',
    // D-5 step 3: the library lives in projects/link-analysis and is reached through its alias
    '@inspecto/link-analysis',
    '@inspecto/link-analysis/graph/graph-analysis',
    '@inspecto/link-analysis/la-host',
]) {
    test(`core api file: flags import '${spec}'`, async () => {
        assert.equal(await flags(imp(spec), CORE_API), 1);
    });
}

for (const spec of [
    'app/inspecto/graph/graph-types',
    '../graph/branching-stage',
    '../graph/entity-key',
    '../graph/graph-view.component',
    'app/inspecto/investigation/unique-name',
    'app/inspecto/api',
    'app/inspecto/components',
]) {
    test(`core api file: allows import '${spec}'`, async () => {
        assert.equal(await flags(imp(spec), CORE_API), 0);
    });
}

test('core slice of graph/: a library sibling is flagged, a core sibling is not', async () => {
    assert.equal(await flags(imp('./graph-analysis'), CORE_GRAPH), 1);
    assert.equal(await flags(imp('./index'), CORE_GRAPH), 1);
    assert.equal(await flags(imp('./graph-types'), CORE_GRAPH), 0);
    assert.equal(await flags(imp('./graph-source'), CORE_GRAPH), 0);
    assert.equal(await flags(imp('../query/query-types'), CORE_GRAPH), 0);
});

test('core slice of graph/: the library alias is flagged too', async () => {
    assert.equal(await flags(imp('@inspecto/link-analysis/graph/graph-analysis'), CORE_GRAPH), 1);
    assert.equal(await flags(imp('@inspecto/link-analysis'), 'src/app/inspecto/viz/probe.ts'), 1);
});

test('does not apply to specs, to the library itself, or to host code', async () => {
    assert.equal(await flags(imp('app/inspecto/graph'), 'src/app/inspecto/api/probe.spec.ts'), 0);
    assert.equal(await flags(imp('@inspecto/link-analysis'), 'src/app/inspecto/api/probe.spec.ts'), 0);
    assert.equal(await flags(imp('./graph-analysis'), 'projects/link-analysis/src/graph/probe.ts'), 0);
    assert.equal(
        await flags(imp('@inspecto/link-analysis/graph/graph-analysis'), 'projects/link-analysis/src/geo/probe.ts'),
        0,
    );
    assert.equal(await flags(imp('app/inspecto/graph'), 'src/app/modules/admin/catalog/probe.ts'), 0);
    assert.equal(await flags(imp('@inspecto/link-analysis'), 'src/app/modules/admin/studio/probe.ts'), 0);
});

test('api/ must not import environments/* (D-5 step 2); host code and specs may', async () => {
    for (const spec of ['environments/environment', '../../../environments/environment']) {
        assert.equal(await flags(imp(spec), CORE_API), 1, spec);
    }
    assert.equal(await flags(imp('environments/environment'), 'src/app/inspecto/api/probe.spec.ts'), 0);
    assert.equal(await flags(imp('environments/environment'), 'src/app/layout/probe.ts'), 0);
});
