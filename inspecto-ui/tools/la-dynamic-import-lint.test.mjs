// Falsifies the Link Analysis / Geo separation lint for DYNAMIC import() (eslint.config.mjs,
// `laDynamicImportSelectors`): `no-restricted-imports` ignores ImportExpression, so a lazy route reaching a
// host module would otherwise pass. Run from inspecto-ui/: node --test tools/la-dynamic-import-lint.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: process.cwd() });
const LA_FILE = 'src/app/modules/admin/studio/link-analysis/probe.ts';

async function lint(code, filePath = LA_FILE) {
    const [r] = await eslint.lintText(code, { filePath });
    return r.messages.filter((m) => m.ruleId === 'no-restricted-syntax').length;
}

for (const spec of [
    '../../modules/admin/foo',
    '..',
    'app/modules/x',
    'src/app/modules/x',
    'app/inspecto/tags',
    'app/inspecto/transfer/x',
    'app/inspecto/ai-assist',
    'app/inspecto/api/objects.service',
    'app/inspecto/api/session.service',
    'src/app/inspecto/api/session.service',
]) {
    test(`flags import('${spec}')`, async () => {
        assert.equal(await lint(`export const f = () => import('${spec}');`), 1);
    });
}

test('flags a computed specifier', async () => {
    assert.equal(await lint('export const f = (n: string) => import(n);'), 1);
});

for (const spec of [
    './link-view-widget.component',
    'app/inspecto/api',
    'app/inspecto/tagsx',
    'app/inspecto/api/session.servicex',
]) {
    test(`allows import('${spec}')`, async () => {
        assert.equal(await lint(`export const f = () => import('${spec}');`), 0);
    });
}

test('does not apply to specs or to host code', async () => {
    const code = `export const f = () => import('app/modules/x');`;
    assert.equal(await lint(code, LA_FILE.replace('probe.ts', 'probe.spec.ts')), 0);
    assert.equal(await lint(code, 'src/app/modules/admin/other/probe.ts'), 0);
});

test('applies to the library folder step 3 moves LA into (D-5 step 2: projects/ must not be an unlinted exemption)', async () => {
    const code = `export const f = () => import('app/modules/x');`;
    assert.equal(await lint(code, 'projects/link-analysis/src/probe.ts'), 1);
});
