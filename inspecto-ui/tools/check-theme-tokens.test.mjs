// Falsifies tools/check-theme-tokens.mjs on synthetic built CSS: a stylesheet that gives every theme/scheme/palette a filled
// fill passes, and the 2026-10-22 shape (token never declared, only an empty `color-mix(in srgb, , transparent)` ripple),
// an empty value, a later empty re-declaration and a missing stylesheet are each flagged by name.
// Run from inspecto-ui/: node --test tools/check-theme-tokens.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { checkCss, runGuard, SCHEMES, VARIANTS } from './check-theme-tokens.mjs';

const block = (theme, scheme, variant, body) =>
    `.${theme} .${scheme} .mat-mdc-unelevated-button.mat-${variant},.${theme}.${scheme} .mat-mdc-unelevated-button.mat-${variant}{${body}}`;
const fill = '--mat-button-filled-container-color: #4f46e5;--mat-button-filled-label-text-color: #FFFFFF';
const sheet = (body = () => fill, themes = ['theme-default', 'theme-teal']) =>
    themes.flatMap((t) => SCHEMES.flatMap((s) => VARIANTS.map((v) => block(t, s, v, body(t, s, v))))).join('');

test('every theme, scheme and palette filled passes', () => assert.deepEqual(checkCss('la-app', sheet()), []));

test('the 2026-10-22 shape - only an empty ripple, no fill - is flagged per theme, scheme, palette and token', () => {
    const bad = checkCss('la-app', sheet(() => '--mat-button-filled-ripple-color: color-mix(in srgb, , transparent)'));
    assert.equal(bad.length, 2 * SCHEMES.length * VARIANTS.length * 2 * 2);
    assert.ok(bad.some((b) => b.includes('theme-default dark mat-flat-button color="primary" - --mat-button-filled-container-color is never declared')));
});

test('one dark primary fill missing is flagged alone', () => {
    const bad = checkCss('gamma', sheet((t, s, v) => (t === 'theme-default' && s === 'dark' && v === 'primary' ? '--mat-button-filled-label-text-color: #fff' : fill)));
    assert.equal(bad.length, 2);
    assert.ok(bad.every((b) => b.startsWith('gamma: theme-default dark mat-flat-button color="primary" - --mat-button-filled-container-color')));
});

test('an empty value is not a colour', () => {
    const bad = checkCss('la-app', sheet((t, s, v) => (s === 'dark' && v === 'warn' ? '--mat-button-filled-container-color: ;--mat-button-filled-label-text-color: rgba(255, 255, 255, .87)' : fill)));
    assert.equal(bad.length, 2 * 2);
    assert.ok(bad.every((b) => b.includes('dark mat-flat-button color="warn" - --mat-button-filled-container-color is ``')));
});

test('a later empty re-declaration wins, as in the browser', () => {
    const css = sheet() + block('theme-teal', 'light', 'accent', '--mat-button-filled-container-color: color-mix(in srgb, 12%, transparent)');
    const bad = checkCss('la-app', css);
    assert.equal(bad.length, 2);
    assert.ok(bad.every((b) => b.includes('theme-teal light mat-flat-button color="accent"')));
});

test('a stylesheet with no theme block is refused, not passed', () => {
    assert.deepEqual(checkCss('la-app', '.x{color:red}'), ['la-app: no .theme-* light/dark block found - is this the global styles-*.css?']);
});

test('runGuard reads dist/<app>/browser/styles-*.css and flags a missing build', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'theme-tokens-'));
    try {
        fs.mkdirSync(path.join(root, 'dist', 'gamma', 'browser'), { recursive: true });
        fs.writeFileSync(path.join(root, 'dist', 'gamma', 'browser', 'styles-ABC.css'), sheet());
        const lines = [];
        assert.equal(runGuard(root, (l) => lines.push(l)), 1);
        assert.ok(lines.includes('ok   gamma (styles-ABC.css)'));
        assert.ok(lines.some((l) => l.startsWith('FAIL la-app: dist/la-app/browser/styles-*.css not found')));
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
