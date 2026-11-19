// Tests for tools/check-handbook.mjs. Run: node --test tools/check-handbook.test.mjs
//
// Each check is proven to fire on a small manifest and to stay quiet on its near miss.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CHAPTER_IDS } from './render-handbook.mjs';
import { findings } from './check-handbook.mjs';

const page = (id, file, extra = {}) => ({ id, chapter: 'start', file, type: 'reference', tracks: ['builder'], audience: 'external', ...extra });
const book = (pages) => ({ title: 'H', chapters: CHAPTER_IDS.map((id) => ({ id, title: id, group: 'G', summary: '', pages: id === 'start' ? pages : [] })) });
const READ = {
    'docs/A.md': '# A\ntext',
    'docs/okf/x/user-guide-x.md': '# X',
    'docs/okf/x/admin-guide-x.md': '# Y\nsee [A](../../A.md)',
    'docs/H.md': '# H\n## Goal\n## Before you start\n## Steps\n## Check it worked\n## What can go wrong\n## Next',
};
const TRACKED = Object.keys(READ);
const run = (pages, tracked = TRACKED) => findings(book(pages), tracked, (f) => READ[f]);
const ALL = [page('a', 'docs/A.md'), page('ux', 'docs/okf/x/user-guide-x.md', { audience: 'internal' }), page('ax', 'docs/okf/x/admin-guide-x.md', { audience: 'internal' })];

test('a well-formed manifest has no findings', () => {
    assert.deepEqual(run(ALL), []);
});

test('chapters: an id out of the contract fails', () => {
    const b = book(ALL);
    b.chapters[1].id = 'concepts';
    assert.match(findings(b, TRACKED, (f) => READ[f])[0], /^chapters:/);
});

test('pages: duplicate id, untracked file, a file twice, bad type / track / audience', () => {
    assert.ok(run([...ALL, page('a', 'docs/H.md')]).some((f) => /id 'a' is used twice/.test(f)));
    assert.ok(run([...ALL, page('n', 'docs/none.md')]).some((f) => /not a tracked file/.test(f)));
    assert.ok(run([...ALL, page('b', 'docs/A.md')]).some((f) => /listed twice/.test(f)));
    assert.ok(run([...ALL, page('t', 'docs/H.md', { type: 'guide' })]).some((f) => /type 'guide'/.test(f)));
    assert.ok(run([...ALL, page('t', 'docs/H.md', { tracks: ['admins'] })]).some((f) => /tracks/.test(f)));
    assert.ok(run([...ALL, page('t', 'docs/H.md', { audience: 'public' })]).some((f) => /audience 'public'/.test(f)));
});

test('coverage: a hub guide missing from the manifest fails', () => {
    assert.ok(run(ALL.slice(0, 2)).some((f) => /coverage: docs\/okf\/x\/admin-guide-x\.md/.test(f)));
});

test('how-to: the six template headings are required, and only on how-to pages', () => {
    assert.deepEqual(run([...ALL, page('h', 'docs/H.md', { type: 'howto' })]), []);
    assert.ok(run([...ALL, page('h', 'docs/A.md', { id: 'h2', type: 'howto' })].filter((p, i) => i !== 0)).some((f) => /how-to: docs\/A\.md lacks/.test(f)));
});

test('leak: an internal page never reaches the external render, and its links there are plain text', () => {
    // the admin guide (internal) links to A (external); A is rendered, the guide is not — no finding either way
    assert.deepEqual(run(ALL), []);
    // making the guide external renders it and its link to A resolves
    assert.deepEqual(run([ALL[0], ALL[1], { ...ALL[2], audience: 'external' }]), []);
});
