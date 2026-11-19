// Tests for tools/render-handbook.mjs. Run: node --test tools/render-handbook.test.mjs
//
// The Markdown converter on each construct the docs use; link rewriting (a page in the render becomes its .html, any
// other target plain text); the audience filter; and a smoke render of the real manifest whose output is well formed.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { CHAPTER_IDS, MANIFEST, inline, loadManifest, markdownToHtml, render } from './render-handbook.mjs';

test('markdown: frontmatter and comments dropped, title and headings collected', () => {
    const r = markdownToHtml('---\ntitle: x\n---\n<!-- hidden -->\n# The Title\n\n## Part `one`\ntext');
    assert.equal(r.title, 'The Title');
    assert.deepEqual(r.headings.map((h) => h.text), ['Part one']);
    assert.ok(!r.html.includes('hidden'));
    assert.ok(r.html.includes('<h2 id="part-one">'));
});

test('markdown: tables, fenced code, blockquotes, lists and rules', () => {
    const r = markdownToHtml('| a | b \\| c |\n|---|---|\n| 1 | **2** |\n\n```\n<raw> & `x`\n```\n\n> quoted **bold**\n\n- one\n  - nested\n- two\n\n1. first\n2. second\n\n---');
    assert.ok(r.html.includes('<th>b | c</th>'));
    assert.ok(r.html.includes('<td><strong>2</strong></td>'));
    assert.ok(r.html.includes('<pre><code>&lt;raw&gt; &amp; `x`</code></pre>'));
    assert.ok(r.html.includes('<blockquote><p>quoted <strong>bold</strong></p></blockquote>'));
    assert.ok(r.html.includes('<ul><li>one<ul><li>nested</li></ul></li><li>two</li></ul>'));
    assert.ok(r.html.includes('<ol><li>first</li><li>second</li></ol>'));
    assert.ok(r.html.includes('<hr>'));
});

test('markdown: a check block is one quiet folded line naming what is checked, the step escaped under it', () => {
    const r = markdownToHtml('## Check it worked\n```check\nGET /spaces/{space}/datasets\nas analyst\nexpect data.items[*].id has "<o2c>"\n```\n```check-ui\n{"open":"/data"}\n```');
    assert.ok(r.html.includes('<details class="check"><summary>Checked on every release · GET /spaces/{space}/datasets as analyst answers 2xx, 1 more expectation</summary>'), r.html);
    assert.ok(r.html.includes('has &quot;&lt;o2c&gt;&quot;'));
    assert.ok(r.html.includes('<summary>Checked on every release · In the console: open “/data”</summary>'));
    assert.ok(!/<\/h2>\s*<pre>/.test(r.html), 'not shown as a plain code block');
    assert.equal(r.html.match(/<pre>/g).length, r.html.match(/<details class="check">/g).length, 'every step sits inside its fold');
});

test('inline: an HTML anchor stays an anchor, bold may hold emphasis, emphasis never reaches an href', () => {
    const link = (h) => h;
    assert.equal(inline('<a id="menus"></a>Menus', link), '<span id="menus"></span>Menus');
    assert.equal(inline('**text *and* color**', link), '<strong>text <em>and</em> color</strong>');
    assert.equal(inline('[x](a_b_c.md)', link), '<a href="a_b_c.md">x</a>');
});

test('markdown: a nested level keeps its own list type; a malformed link escape does not throw', () => {
    assert.ok(markdownToHtml('1. one\n   - sub\n2. two').html.includes('<ol><li>one<ul><li>sub</li></ul></li><li>two</li></ol>'));
    const { files } = render({ title: 'H', chapters: [{ id: 'start', title: 'S', group: 'G', summary: '', pages: [
        { id: 'p', chapter: 'start', file: 'docs/A.md', type: 'reference', tracks: ['builder'], audience: 'external' }] }] },
    () => '# A\n[bad](x%E0.md)');
    assert.ok(files.get('p.html').includes('<span class="nolink">bad</span>'));
});

test('inline: raw HTML escaped, code kept literal, images become alt text, links rewritten or dropped', () => {
    const link = (h) => (h === 'ok.md' ? 'ok.html' : h.startsWith('https:') ? h : null);
    assert.equal(inline('<b>x</b> `<i>`', link), '&lt;b&gt;x&lt;/b&gt; <code>&lt;i&gt;</code>');
    assert.equal(inline('![a diagram](x.svg)', link), 'a diagram');
    assert.equal(inline('[in](ok.md) [out](secret.md) [web](https://x.org)', link),
        '<a href="ok.html">in</a> <span class="nolink">out</span> <a href="https://x.org">web</a>');
});

const BOOK = {
    title: 'H', chapters: [
        { id: 'start', title: 'Start', group: 'S', summary: 's', pages: [
            { id: 'pub', chapter: 'start', file: 'docs/A.md', type: 'reference', tracks: ['builder'], audience: 'external' },
            { id: 'team', chapter: 'start', file: 'docs/okf/B.md', type: 'unsorted', tracks: ['builder'], audience: 'internal' },
        ] },
    ],
};
const READ = { 'docs/A.md': '# Public\nSee [team page](okf/B.md) and [self](A.md#top).', 'docs/okf/B.md': '# Team only\nsecret' };

test('render: the external render holds only external pages, and links to others become text', () => {
    const { files } = render(BOOK, (f) => READ[f]);
    assert.ok(files.has('pub.html') && files.has('start.html') && files.has('index.html') && files.has('search.json'));
    assert.ok(!files.has('team.html'));
    const pub = files.get('pub.html');
    assert.ok(pub.includes('<span class="nolink">team page</span>'));
    assert.ok(pub.includes('<a href="pub.html#top">self</a>'));
    assert.ok(!JSON.stringify([...files.values()]).includes('secret'));
});

test('render: --internal adds the internal pages and links to them', () => {
    const { files } = render(BOOK, (f) => READ[f], { internal: true });
    assert.ok(files.has('team.html'));
    assert.ok(files.get('pub.html').includes('<a href="team.html">team page</a>'));
});

test('the real manifest loads with the contract chapters and renders well-formed pages', () => {
    const book = loadManifest(readFileSync(MANIFEST, 'utf8'));
    assert.deepEqual(book.chapters.map((c) => c.id), CHAPTER_IDS);
    const { files } = render(book, (f) => readFileSync(f, 'utf8'));
    for (const [name, html] of files) {
        if (!name.endsWith('.html')) continue;
        for (const tag of ['ul', 'ol', 'table', 'blockquote', 'pre', 'main', 'nav']) {
            const open = (html.match(new RegExp(`<${tag}[ >]`, 'g')) || []).length;
            const close = (html.match(new RegExp(`</${tag}>`, 'g')) || []).length;
            assert.equal(open, close, `${name}: <${tag}> ${open} open, ${close} closed`);
        }
    }
});
