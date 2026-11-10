// node --test tools/check-product-build.test.mjs - the Product matrix check (PRODUCT-ASSEMBLY-1 PA-5d).
import test from 'node:test';
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkPlan, checkProductBuild } from './check-product-build.mjs';
import { gatherModel } from './products.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');

test('the Telco matrix holds: Standard, Advanced, and the Advanced demo', () => {
    for (const [edition, distribution] of [['standard', 'production'], ['advanced', 'production'], ['advanced', 'demo']]) {
        const { plan, findings } = checkProductBuild({ product: 'telco', edition, distribution });
        assert.deepEqual(findings, [], `${edition} ${distribution}`);
        assert.deepEqual(plan.descriptor.apps.map((a) => a.id), ['telco-ra', 'case-management', 'telco-fraud', 'link-analysis']);
        assert.equal(plan.seedSpace, distribution === 'demo' ? 'telco' : null);
    }
});

test('Inspecto LA still holds, with no composed Space', () => {
    const { plan, findings } = checkProductBuild({ product: 'la', edition: 'advanced', distribution: 'demo' });
    assert.deepEqual(findings, []);
    assert.equal(plan.space, null);
});

test('a plan that does not hold together is named, finding by finding', () => {
    const { plan } = checkProductBuild({ product: 'telco', edition: 'advanced', distribution: 'demo' });
    const p = gatherModel(repo).products.telco;
    const bad = structuredClone(plan);
    bad.apps = bad.apps.filter((a) => a.id !== 'link-analysis');
    bad.descriptor.apps[1].nav.push({ ...bad.descriptor.apps[0].nav[0] });
    bad.descriptor.apps[2].nav = [];
    bad.modules = bad.modules.filter((m) => m !== 'inspecto-reconciliation.jar');
    bad.seedTool = 'tools/seed-nowhere.mjs';
    const f = checkPlan(bad, p);
    for (const re of [/the plan's Apps are telco-ra, case-management, telco-fraud, the Product names .*link-analysis/,
        /the descriptor's Apps .* are not the plan's/, /nav path 'reconciliation' is drawn by both 'telco-ra' and 'case-management'/,
        /App 'telco-fraud' draws no nav entry/, /App 'telco-ra' needs module 'reconciliation'/, /tools\/seed-nowhere\.mjs is missing/])
        assert.ok(f.some((x) => re.test(x)), `${re}\n${f.join('\n')}`);
    assert.deepEqual(checkPlan({ ...plan, seedSpace: null }, p).filter((x) => /bakes nothing/.test(x)).length, 1);
});
