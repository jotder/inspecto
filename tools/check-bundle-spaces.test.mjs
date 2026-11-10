// node --test tools/check-bundle-spaces.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bundleSpacesVerdict } from './bundle-spaces-rule.mjs';

const T = '_templates';

test('a plain bundle with a Space fails, one with only the template gallery passes', () => {
  assert.equal(bundleSpacesVerdict([T]).ok, true);
  assert.equal(bundleSpacesVerdict([]).ok, true);
  assert.equal(bundleSpacesVerdict([T, 'la-showcase']).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'default']).ok, false);
});

test('a demo bundle built with a seedSpace carries exactly that Space', () => {
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { seedSpace: 'la-showcase' }).ok, true);
  assert.equal(bundleSpacesVerdict(['la-showcase'], { seedSpace: 'la-showcase' }).ok, true);
  // PRODUCT-ASSEMBLY-1 PA-2: the Space is the plan's, not a hard-coded la-showcase.
  assert.equal(bundleSpacesVerdict([T, 'telco-demo'], { seedSpace: 'telco-demo' }).ok, true);
});

test('a seedSpace bundle with an extra or another Space fails', () => {
  const f = { seedSpace: 'la-showcase' };
  assert.equal(bundleSpacesVerdict([T, 'la-showcase', 'la-showcase-off'], f).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'la-showcase', 'telco-assurance'], f).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'default'], f).ok, false);
});

test('a Space in a bundle built with no seedSpace fails', () => {
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { seedSpace: null }).ok, false);
  assert.match(bundleSpacesVerdict([T, 'la-showcase'], {}).reason, /allowed: none \(bundles ship no Spaces\)/);
});
