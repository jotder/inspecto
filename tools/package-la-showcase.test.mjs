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
