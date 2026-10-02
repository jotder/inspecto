// Falsifies the STATIC-import half of the Link Analysis / Geo separation lint on the NEW library paths (eslint.config.mjs,
// the `projects/link-analysis/**` block, D-5 step 3): a host feature, a `../` escape, a tokenised host edge or the
// barrel-re-exported `ObjectsService` / `SessionService` must be flagged under `projects/link-analysis/src/**`, in
// every spelling a library file can use (`app/inspecto/...` legacy, `@inspecto/core/...` since step 3).
// Run from inspecto-ui/: node --test tools/la-static-import-lint.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: process.cwd() });
const LIB = 'projects/link-analysis/src/link-analysis/probe.ts';

async function flags(code, filePath = LIB) {
    const [r] = await eslint.lintText(code, { filePath });
    return r.messages.filter((m) => m.ruleId === 'no-restricted-imports').length;
}
const imp = (spec) => `import { X } from '${spec}';\nexport const y = X;`;
const named = (name, spec) => `import { ${name} } from '${spec}';\nexport const y = ${name};`;

for (const spec of [
    'app/modules/admin/studio/datasets/datasets.service',
    'src/app/modules/x',
    '../geo/x',
    '../../src/app/modules/x',
    'app/inspecto/tags/tag-assignment.dialog',
    'app/inspecto/transfer',
    'app/inspecto/ai-assist/ai-assist.component',
    'app/inspecto/api/session.service',
    '@inspecto/core/tags/tag-assignment.dialog',
    '@inspecto/core/transfer',
    '@inspecto/core/ai-assist/ai-assist.component',
    '@inspecto/core/api/objects.service',
    '@inspecto/core/api/session.service',
]) {
    test(`library file: flags import '${spec}'`, async () => {
        assert.equal(await flags(imp(spec)), 1);
    });
}

for (const [name, spec] of [
    ['ObjectsService', 'app/inspecto/api'],
    ['SessionService', 'app/inspecto/api'],
    ['ObjectsService', '@inspecto/core/api'],
    ['SessionService', '@inspecto/core/api'],
]) {
    test(`library file: flags { ${name} } from '${spec}'`, async () => {
        assert.equal(await flags(named(name, spec)), 1);
    });
}

for (const spec of [
    '@inspecto/core/api',
    '@inspecto/core/api/api-base',
    '@inspecto/core/components/alert.component',
    '@inspecto/core/graph/graph-types',
    '@inspecto/link-analysis/graph/graph-analysis',
    './sibling',
]) {
    test(`library file: allows import '${spec}'`, async () => {
        assert.equal(await flags(imp(spec)), 0);
    });
}

test('applies to every folder of the library and to the legacy studio homes; specs and host code are exempt', async () => {
    const bad = imp('app/modules/x');
    for (const dir of ['', 'graph/', 'geo/', 'geo-map/', 'la-host/', 'api/', 'investigation/']) {
        assert.equal(await flags(bad, `projects/link-analysis/src/${dir}probe.ts`), 1, dir);
    }
    assert.equal(await flags(bad, 'src/app/modules/admin/studio/link-analysis/probe.ts'), 1);
    assert.equal(await flags(bad, 'src/app/modules/admin/studio/geo-map/probe.ts'), 1);
    assert.equal(await flags(bad, 'projects/link-analysis/src/graph/probe.spec.ts'), 0);
    assert.equal(await flags(bad, 'src/app/modules/admin/catalog/probe.ts'), 0);
});
