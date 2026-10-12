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

test('a demo-auth la-app bundle with exactly la-showcase passes', () => {
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { demoAuth: true, ui: 'la-app' }).ok, true);
  assert.equal(bundleSpacesVerdict(['la-showcase'], { demoAuth: true, ui: 'la-app' }).ok, true);
});

test('a demo-auth la-app bundle with an extra or another Space fails', () => {
  const f = { demoAuth: true, ui: 'la-app' };
  assert.equal(bundleSpacesVerdict([T, 'la-showcase', 'la-showcase-off'], f).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'la-showcase', 'telco-assurance'], f).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'default'], f).ok, false);
});

test('la-showcase in any other flavor fails', () => {
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { demoAuth: true, ui: 'gamma' }).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { demoAuth: false, ui: 'la-app' }).ok, false);
  assert.equal(bundleSpacesVerdict([T, 'la-showcase'], { demoAuth: false, ui: 'gamma' }).ok, false);
});
