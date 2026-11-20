// Tests for tools/handbook-editions.mjs. Run: node --test tools/handbook-editions.test.mjs
//
// The derivation per Product Map kind on small facts, the badge text, the declared-edition rules, and a smoke run over
// the real repository (every Offering and Item parse, a known module resolves).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { editionIndex, editionLabel, editionName, gatherFacts, widens, withEditions } from './handbook-editions.mjs';

const FACTS = {
    offerings: {
        basic: { modules: ['lacore'], contentPacks: ['starter'], addons: { linkAnalysis: { modules: ['lacore'] } } },
        standard: { includes: ['basic'], modules: ['ops', 'needs'], contentPacks: ['o2c'], addons: { incidents: {} } },
        advanced: { includes: ['standard'], modules: ['policy'], addons: { telecomPack: {} } },
        preview: { includes: ['advanced'] },
    },
    manifests: {
        core: { id: 'core', offeringRole: 'base' }, lacore: { id: 'lacore', offeringRole: 'optional' },
        ops: { id: 'ops', offeringRole: 'optional' }, needs: { id: 'needs', offeringRole: 'optional', requires: { modules: ['dep'] } },
        dep: { id: 'dep', offeringRole: 'optional' }, policy: { id: 'policy', offeringRole: 'optional' },
        hosted: { id: 'hosted', offeringRole: 'provider' },
    },
    items: {
        'module/core': { id: 'module.core', kind: 'module', module: 'core', parent: 'layer.platform' },
        'module/ops': { id: 'module.ops', kind: 'module', module: 'ops', parent: 'layer.features' },
        'module/dep': { id: 'module.dep', kind: 'module', module: 'dep', parent: 'layer.features' },
        'module/policy': { id: 'module.policy', kind: 'module', module: 'policy', parent: 'layer.features' },
        'module/hosted': { id: 'module.hosted', kind: 'module', module: 'hosted', parent: 'layer.providers' },
        'layer/platform': { id: 'layer.platform', kind: 'layer' },
        'layer/features': { id: 'layer.features', kind: 'layer' },
        'layer/providers': { id: 'layer.providers', kind: 'layer' },
        'addon/incidents': { kind: 'addon', offeringAddon: 'incidents' },
        'addon/telecomPack': { kind: 'addon', offeringAddon: 'telecomPack' },
        'solution-pack/starter': { kind: 'solution-pack', template: 'starter' },
        'solution-pack/o2c': { kind: 'solution-pack', template: 'o2c' },
        'solution-pack/off': { kind: 'solution-pack', template: 'off' },
        'solution-pack/industry-tel': { kind: 'solution-pack', industryPack: 'tel' },
        'app/cases': { kind: 'app', app: 'cases' }, 'app/plain': { kind: 'app', app: 'plain' },
        'product/telco': { kind: 'product', product: 'telco' },
        'edition/advanced': { kind: 'edition', offering: 'advanced' },
        'industry/telecom': { kind: 'industry', industry: 'telecom' },
    },
    apps: { cases: { requires: { addons: ['incidents', 'telecomPack'] } }, plain: { requires: {} } },
    products: { telco: { editions: ['advanced', 'standard'] } },
    industryPacks: { tel: 'core' },
};
const idx = editionIndex(FACTS);

test('module: base → Basic; optional → the first Offering resolving it, through includes and requires; unlisted → null', () => {
    assert.equal(idx.module('core'), 'Basic');
    assert.equal(idx.module('lacore'), 'Basic');
    assert.equal(idx.module('ops'), 'Standard');
    assert.equal(idx.module('dep'), 'Standard', 'pulled in by a requires.modules closure');
    assert.equal(idx.module('policy'), 'Advanced');
    assert.equal(idx.module('hosted'), null);
    assert.equal(idx.module('nope'), null);
});

test('items: addon, solution pack, industry pack, app, product, edition, layer; industry resolves nothing', () => {
    assert.equal(idx.item('addon/incidents'), 'Standard');
    assert.equal(idx.item('addon/telecomPack'), 'Advanced');
    assert.equal(idx.item('solution-pack/starter'), 'Basic');
    assert.equal(idx.item('solution-pack/o2c'), 'Standard');
    assert.equal(idx.item('solution-pack/off'), null);
    assert.equal(idx.item('solution-pack/industry-tel'), 'Basic', 'the edition of the module that ships the pack');
    assert.equal(idx.item('app/cases'), 'Advanced', 'the latest of its required add-ons');
    assert.equal(idx.item('app/plain'), 'Basic');
    assert.equal(idx.item('product/telco'), 'Standard', 'the first of its editions');
    assert.equal(idx.item('edition/advanced'), 'Advanced');
    assert.equal(idx.item('layer/platform'), 'Basic');
    assert.equal(idx.item('layer/features'), 'Standard', 'the first edition of any child module');
    assert.equal(idx.item('layer/providers'), null);
    assert.equal(idx.item('industry/telecom'), null);
});

test('hub: a page in docs/okf/product/<kind>/<name>/ takes its Item\'s edition; any other path none', () => {
    assert.equal(idx.hub('docs/okf/product/module/ops/user-guide-ops.md'), 'Standard');
    assert.equal(idx.hub('docs/okf/product/addon/incidents/sub/x.md'), 'Standard');
    assert.equal(idx.hub('docs/okf/product/module/missing/x.md'), null);
    assert.equal(idx.hub('docs/USER_GUIDE.md'), null);
});

test('label: the sold editions from the first on; Preview alone; unknown → null', () => {
    assert.equal(editionLabel('Basic'), 'Basic · Standard · Advanced');
    assert.equal(editionLabel('Standard'), 'Standard · Advanced');
    assert.equal(editionLabel('Advanced'), 'Advanced');
    assert.equal(editionLabel('Preview'), 'Preview');
    assert.equal(editionLabel(null), null);
    assert.equal(editionName('STANDARD'), 'Standard');
    assert.equal(editionName('gold'), null);
});

test('withEditions: derived from the hub unless declared; widens tells a claim earlier than the derived one', () => {
    const p = (file, declaredEdition = null) => ({ file, declaredEdition });
    const book = { chapters: [{ pages: [p('docs/okf/product/module/ops/a.md'), p('docs/USER_GUIDE.md', 'basic'), p('docs/okf/product/module/ops/b.md', 'advanced')] }] };
    const [a, root, narrow] = withEditions(book, idx).chapters[0].pages;
    assert.deepEqual([a.edition, a.derivedEdition], ['Standard', 'Standard']);
    assert.deepEqual([root.edition, root.derivedEdition], ['Basic', null]);
    assert.deepEqual([narrow.edition, narrow.derivedEdition], ['Advanced', 'Standard']);
    assert.equal(widens('Basic', 'Standard'), true);
    assert.equal(widens('Advanced', 'Standard'), false);
});

test('smoke: the repository resolves — a base module is Basic, the incidents add-on Standard', () => {
    const real = editionIndex(gatherFacts('.'));
    assert.equal(real.item('module/elt'), 'Basic');
    assert.equal(real.item('addon/incidents'), 'Standard');
    assert.equal(real.item('solution-pack/industry-telecom'), 'Basic');
});
