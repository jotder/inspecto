// Tests for tools/check-bundle-handbook.mjs — the D-UE7 rule (which bundle's /help carries the training pages) and the
// verdict on a rendered folder. Run: node --test tools/check-bundle-handbook.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handbookAudience, bundleHandbookVerdict } from './check-bundle-handbook.mjs';

const book = { chapters: [{ id: 'start', pages: [
    { id: 'ext_a', audience: 'external' },
    { id: 'lab_one', audience: 'training' },
    { id: 'kit', audience: 'training' },
    { id: 'dev_only', audience: 'internal' },
] }] };
const ext = ['index.html', 'search.json', 'start.html', 'ext_a.html', '.handbook'];
const trn = [...ext, 'lab_one.html', 'kit.html'];

test('sold editions get the external render, in production and trial', () => {
    for (const edition of ['Basic', 'Standard', 'Advanced'])
        for (const distribution of ['production', 'trial'])
            assert.equal(handbookAudience({ edition, distribution }), 'external', `${edition} x ${distribution}`);
});

test('the Preview, and any demo build, get the training render', () => {
    for (const distribution of ['production', 'trial', 'demo']) assert.equal(handbookAudience({ edition: 'Preview', distribution }), 'training');
    assert.equal(handbookAudience({ edition: 'Advanced', distribution: 'demo' }), 'training');
});

test('a sold edition passes with the external render and fails with the training one', () => {
    const sold = { edition: 'Standard', distribution: 'production' };
    assert.deepEqual(bundleHandbookVerdict({ marker: 'external\n', files: ext }, book, sold), { ok: true });
    const v = bundleHandbookVerdict({ marker: 'training\n', files: trn }, book, sold);
    assert.equal(v.ok, false);
    assert.match(v.reason, /marker says 'training'/);
    assert.match(v.reason, /training page\(s\) lab_one, kit/);
});

test('a sold edition fails on one leaked training page even under an external marker', () => {
    const v = bundleHandbookVerdict({ marker: 'external', files: [...ext, 'kit.html'] }, book, { edition: 'Basic', distribution: 'production' });
    assert.equal(v.ok, false);
    assert.match(v.reason, /training page\(s\) kit,/);
});

test('the Preview passes with the training render and fails when a training page is missing', () => {
    const preview = { edition: 'Preview', distribution: 'production' };
    assert.deepEqual(bundleHandbookVerdict({ marker: 'training', files: trn }, book, preview), { ok: true });
    const v = bundleHandbookVerdict({ marker: 'external', files: ext }, book, preview);
    assert.equal(v.ok, false);
    assert.match(v.reason, /lacks training page\(s\) lab_one, kit/);
});

test('no flavor may carry an internal page', () => {
    for (const flavor of [{ edition: 'Advanced', distribution: 'production' }, { edition: 'Preview', distribution: 'demo' }]) {
        const marker = handbookAudience(flavor);
        const files = [...(marker === 'training' ? trn : ext), 'dev_only.html'];
        const v = bundleHandbookVerdict({ marker, files }, book, flavor);
        assert.equal(v.ok, false);
        assert.match(v.reason, /internal page\(s\) dev_only/);
    }
});
