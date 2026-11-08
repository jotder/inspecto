// Unit tests for tools/package-la-showcase.mjs (LA-SHOWCASE-DIST-1): the Space / data split and the space.toon it writes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { egressToon, parseArgs, plan, spaceToon, toonValue } from './package-la-showcase.mjs';

test('the Space ships an empty egress allowlist, as a Space created from a template gets', () => {
  assert.match(egressToon(), /^allow\[0\]:\ncreatedAt: "\d{4}-/);
});

test('config goes to the Space zip, data to the data zip, gallery files to neither', () => {
  const p = plan('la-claims', ['template.toon', 'config/claims/claim_pipeline.toon', 'config/roles.toon',
    'data/inbox/claim/claim.csv', 'portal/partner-portal.html']);
  assert.deepEqual(p.space.map((f) => f.to), ['la-claims/config/claims/claim_pipeline.toon', 'la-claims/config/roles.toon']);
  assert.deepEqual(p.data.map((f) => f.to), ['la-claims/data/inbox/claim/claim.csv']);
  assert.ok(p.space.every((f) => f.toon));
});

test('the Space zip never carries data/ (a Space folder holding data/ is not booted beside a data root)', () => {
  const p = plan('x', ['config/a.toon', 'data/inbox/f/a.csv', 'data/staged/f/b.csv']);
  assert.ok(p.space.every((f) => !f.to.startsWith('x/data/')));
});

test('a template with no data or no config is refused', () => {
  assert.throws(() => plan('x', ['config/a.toon']), /no committed data/);
  assert.throws(() => plan('x', ['data/inbox/a.csv']), /no committed config/);
});

test('space.toon takes name and tagline as SpaceManager.createFromTemplate does, plus min_product_version', () => {
  const tpl = 'name: Link Analysis claims showcase\ntagline: "A seeded \\"claims\\" demo"\nminEdition: advanced\n';
  const out = spaceToon(tpl, 'la-claims', '4.0.0');
  assert.match(out, /^display_name: "Link Analysis claims showcase"$/m);
  assert.match(out, /^description: "A seeded \\"claims\\" demo"$/m);
  assert.match(out, /^min_product_version: "4.0.0"$/m);
  assert.match(out, /^created_at: "\d{4}-/m);
});

test('toonValue reads bare and quoted values and misses absent keys', () => {
  assert.equal(toonValue('name: Bare value\n', 'name'), 'Bare value');
  assert.equal(toonValue('tagline: "q"\n', 'tagline'), 'q');
  assert.equal(toonValue('name: x\n', 'tagline'), '');
});

test('arguments: id required and validated', () => {
  assert.equal(parseArgs(['la-claims', '--version', '4.0.0']).version, '4.0.0');
  assert.throws(() => parseArgs([]), /usage/);
  assert.throws(() => parseArgs(['Bad_Id']), /not a Space id/);
});

// LA-INDUSTRY-SPACES-1: industry Spaces composed from use-case templates.
import { composeSources, mergeEntityTypes, recipeUseCases, MERGED } from './package-la-showcase.mjs';

test('an industry recipe names its use-case templates', () => {
  assert.deepEqual(recipeUseCases('name: Banking\nuseCases[2]: la-card,la-trade\n'), ['la-card', 'la-trade']);
  assert.throws(() => recipeUseCases('name: Empty\n'), /names no useCases/);
});

test('composing takes each file from one use case and refuses a path two use cases ship', () => {
  const owner = composeSources([
    { tpl: 'la-card', files: ['template.toon', 'config/card/a.toon', MERGED, 'data/inbox/card/x.csv', 'portal/p.html'] },
    { tpl: 'la-trade', files: ['template.toon', 'config/trade/b.toon', MERGED] },
  ]);
  assert.equal(owner.get('config/card/a.toon'), 'la-card');
  assert.equal(owner.get('config/trade/b.toon'), 'la-trade');
  assert.deepEqual(owner.get(MERGED), ['la-card', 'la-trade']);
  assert.ok(!owner.has('template.toon') && !owner.has('portal/p.html'));
  assert.throws(() => composeSources([
    { tpl: 'la-card', files: ['template.toon', 'config/company/c.toon'] }, { tpl: 'la-trade', files: ['template.toon', 'config/company/c.toon'] },
  ]), /shipped by both la-card and la-trade/);
});

test('entity types merge by id; the same id declared differently is refused', () => {
  const a = 'entity_types[1]:\n  - id: party\n    label: Party\n';
  const b = 'entity_types[2]:\n  - id: party\n    label: Party\n  - id: box\n    label: Container\n';
  assert.equal(mergeEntityTypes([a, b]), 'entity_types[2]:\n  - id: party\n    label: Party\n  - id: box\n    label: Container\n');
  assert.throws(() => mergeEntityTypes([a, 'entity_types[1]:\n  - id: party\n    label: Other\n']), /declared differently/);
});

test('a recipe naming a template that does not exist is refused, not skipped', () => {
  assert.throws(() => composeSources([{ tpl: 'la-utility', files: ['template.toon', 'config/a.toon'] }, { tpl: 'la-utlity', files: [] }]),
    /'la-utlity' has no committed template.toon/);
});

test('a path two use cases ship with the same bytes is one file; different bytes are refused', () => {
  const t = [{ tpl: 'a', files: ['template.toon', 'config/shared/x.toon'] }, { tpl: 'b', files: ['template.toon', 'config/shared/x.toon'] }];
  assert.equal(composeSources(t, () => true).get('config/shared/x.toon'), 'a');
  assert.throws(() => composeSources(t, () => false), /shipped by both a and b/);
});

test('every use case may ship its own config/examples/*.md; several are joined, one ships as is', async () => {
  const { isJoined, joinExamples } = await import('./package-la-showcase.mjs');
  assert.ok(isJoined('config/examples/sla-escalation-examples.md') && isJoined(MERGED) && !isJoined('config/examples/x.toon') && !isJoined('config/shared/x.toon'));
  const owner = composeSources([
    { tpl: 'a', files: ['template.toon', 'config/examples/sla.md'] }, { tpl: 'b', files: ['template.toon', 'config/examples/sla.md'] },
  ]);
  assert.deepEqual(owner.get('config/examples/sla.md'), ['a', 'b']);
  assert.equal(joinExamples(['# A\nx\n', '# B\ny']), '# A\nx\n\n---\n\n# B\ny\n');
});
