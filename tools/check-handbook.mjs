#!/usr/bin/env node
// check-handbook — the Handbook manifest holds together, and the external render can neither leak nor dangle
// (USER-HANDBOOK-1 P1; docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md §3.2, §4.3).
//
//   node tools/check-handbook.mjs
//
// CHECKS (exit 1 on any):
//   1 chapters  the manifest's chapter ids are exactly CHAPTER_IDS, in order — the console's help links (lane B) and the
//               bundle depend on them; change both together or neither.
//   2 pages     every page id is unique; every `file` is a tracked repository file and appears once; `type`, `tracks`
//               and `audience` hold allowed values.
//   3 coverage  every tracked hub user guide and admin guide (docs/okf/**/user-guide-*.md, admin-guide-*.md) is placed
//               in the manifest — a new guide is placed (internal by default) in the change that adds it.
//   4 how-to    a page of type `howto` carries the six template headings (HOWTO_HEADINGS).
//   5 leak      the EXTERNAL render, built in memory, holds no internal page, and every relative link in it reaches a
//               file of that render (a link to a page outside the render must already be plain text).
// A floor fails a manifest that has almost nothing in it. Tests: tools/check-handbook.test.mjs.

import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { AUDIENCES, CHAPTER_IDS, HOWTO_HEADINGS, MANIFEST, TRACKS, TYPES, loadManifest, render } from './render-handbook.mjs';

const MIN_PAGES = 50;
/** Page ids the render writes itself: a page named so would be overwritten by the home page or the search index. */
const RESERVED = ['index', 'search'];

/** All findings for a manifest. `tracked` is the list of tracked paths; `read(file)` returns text. Pure, for the tests. */
export function findings(book, tracked, read, renderSite = render) {
    const out = [];
    const ids = book.chapters.map((c) => c.id);
    if (ids.join(',') !== CHAPTER_IDS.join(',')) out.push(`chapters: [${ids.join(', ')}] must be exactly [${CHAPTER_IDS.join(', ')}]`);
    const trackedSet = new Set(tracked);
    const seenId = new Set(ids), seenFile = new Map();
    const pages = book.chapters.flatMap((c) => c.pages);
    for (const p of pages) {
        const at = `${p.chapter}/${p.id}`;
        if (seenId.has(p.id)) out.push(`pages: id '${p.id}' is used twice`);
        if (RESERVED.includes(p.id)) out.push(`pages: id '${p.id}' is reserved (the render writes ${p.id}.html / ${p.id}.json itself)`);
        seenId.add(p.id);
        if (!p.file || !trackedSet.has(p.file)) out.push(`pages: ${at} file '${p.file}' is not a tracked file`);
        else if (seenFile.has(p.file)) out.push(`pages: ${p.file} is listed twice (${seenFile.get(p.file)}, ${at})`);
        else seenFile.set(p.file, at);
        if (!TYPES.includes(p.type)) out.push(`pages: ${at} type '${p.type}' is not one of ${TYPES.join(', ')}`);
        if (!AUDIENCES.includes(p.audience)) out.push(`pages: ${at} audience '${p.audience}' is not external or internal`);
        if (!p.tracks.length || p.tracks.some((t) => !TRACKS.includes(t))) out.push(`pages: ${at} tracks [${p.tracks}] must be from ${TRACKS.join(', ')}`);
        if (p.type === 'howto' && trackedSet.has(p.file)) {
            const heads = [...read(p.file).matchAll(/^#{2,3}\s+(.*?)\s*$/gm)].map((m) => m[1].replace(/[`*_]/g, ''));
            const missing = HOWTO_HEADINGS.filter((h) => !heads.includes(h));
            if (missing.length) out.push(`how-to: ${p.file} lacks the template heading(s): ${missing.join(', ')}`);
        }
    }
    for (const f of tracked) {
        if (/^docs\/okf\/.*\/(user|admin)-guide-[^/]+\.md$/.test(f) && !seenFile.has(f)) out.push(`coverage: ${f} is not placed in the Handbook manifest`);
    }
    if (!out.some((o) => o.startsWith('pages:'))) {
        const { files } = renderSite(book, read, { internal: false });
        const internalIds = new Set(pages.filter((p) => p.audience !== 'external').map((p) => p.id));
        for (const [name, html] of files) {
            if (internalIds.has(name.replace(/\.html$/, ''))) out.push(`leak: the external render holds internal page ${name}`);
            if (!name.endsWith('.html')) continue;
            // The inline search script builds its links from search.json, whose ids are this render's own pages
            // (checked below); its template string is not a link.
            for (const m of html.replace(/<script>[\s\S]*?<\/script>/g, '').matchAll(/href="([^"#]*)(#[^"]*)?"/g)) {
                const href = m[1];
                if (!href || /^(https?:|mailto:)/i.test(href)) continue;
                if (!files.has(href)) out.push(`leak: ${name} links to '${href}', which is not in the external render`);
            }
        }
        for (const e of JSON.parse(files.get('search.json'))) {
            if (!files.has(`${e.id}.html`)) out.push(`leak: search.json lists '${e.id}', which is not in the external render`);
            if (internalIds.has(e.id)) out.push(`leak: search.json lists internal page '${e.id}'`);
        }
    }
    return out;
}

function main() {
    if (!existsSync(MANIFEST)) { console.error(`✗ check-handbook: ${MANIFEST} not found — run from the repository root`); process.exit(2); }
    const tracked = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8', maxBuffer: 1 << 28 }).split('\0').filter(Boolean);
    let book;
    try { book = loadManifest(readFileSync(MANIFEST, 'utf8')); } catch (e) { console.error(`✗ check-handbook: ${MANIFEST}: ${e.message}`); process.exit(1); }
    const pages = book.chapters.flatMap((c) => c.pages);
    if (pages.length < MIN_PAGES) { console.error(`✗ check-handbook: only ${pages.length} page(s) in the manifest, below the floor of ${MIN_PAGES}`); process.exit(1); }
    const f = findings(book, tracked, (p) => readFileSync(p, 'utf8'));
    if (f.length) { console.error(`✗ check-handbook: ${f.length} finding(s)\n` + f.map((x) => '  ' + x).join('\n')); process.exit(1); }
    const ext = pages.filter((p) => p.audience === 'external').length;
    const byType = Object.fromEntries(['tutorial', 'howto', 'reference', 'concept', 'unsorted'].map((t) => [t, pages.filter((p) => p.type === t).length]));
    console.log(`✓ check-handbook: ${book.chapters.length} chapters, ${pages.length} pages (${ext} external, ${pages.length - ext} internal; ` +
        `${Object.entries(byType).map(([k, v]) => `${v} ${k}`).join(', ')}); every hub guide placed; the external render neither leaks nor dangles.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
