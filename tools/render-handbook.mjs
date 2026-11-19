#!/usr/bin/env node
// render-handbook — the Handbook: one manifest over the hub pages, rendered to a static site (USER-HANDBOOK-1 P1;
// docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md §3.2, §4).
//
//   node tools/render-handbook.mjs --out <dir> [--internal]
//
// Reads docs/okf/product/layer/product/handbook.toon and writes a self-contained site: <dir>/index.html (home),
// <dir>/<chapter-id>.html (one landing page per chapter), <dir>/<page-id>.html (one per page), <dir>/search.json.
// CSS and JS are inline and every link is relative, so the site works under any base path (the bundle serves it at
// /help/). CONTRACT with the console (lane B): the chapter ids are fixed — CHAPTER_IDS below; check-handbook.mjs fails
// if the manifest drifts from them.
//
// AUDIENCE: the default render holds `audience: external` pages only — what a customer may read (BUNDLE-LAYOUT-1: the
// bundle ships only operator-facing documents, and a hub page becomes external by a deliberate per-page decision).
// `--internal` adds the internal pages for Inspecto's own team and must never feed a bundle. A link to a page that is
// not in this render becomes plain text, so an external render can neither leak nor dangle.
//
// Markdown: a small converter for what these docs use — headings, paragraphs, lists, tables, fenced code,
// blockquotes, links, emphasis, inline code. A ```check / ```check-ui block (docs as tests, tools/handbook-checks.mjs)
// renders as one quiet folded line naming what is checked, the step under it. Raw HTML is escaped; HTML comments and frontmatter are dropped; images
// render as their alt text (hub assets do not ship). No dependency (the repo's dependency set is locked).
//
// Tests: tools/render-handbook.test.mjs. Checked by tools/check-handbook.mjs.

import { mkdirSync, readFileSync, writeFileSync, existsSync, readdirSync, rmSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseToon } from './toon-subset.mjs';
import { FENCE_API, FENCE_UI, describe, parseBlock } from './handbook-checks.mjs';

export const MANIFEST = 'docs/okf/product/layer/product/handbook.toon';
export const CHAPTER_IDS = ['start', 'understand', 'build_bring_data_in', 'build_shape', 'build_trust', 'build_analyse',
    'build_detect', 'build_respond', 'build_present', 'ship', 'manage_app', 'manage_platform', 'develop', 'reference',
    'troubleshooting'];
export const TYPES = ['tutorial', 'howto', 'reference', 'concept', 'unsorted'];
export const TRACKS = ['builder', 'partner', 'operator', 'business'];
export const AUDIENCES = ['external', 'internal'];
export const HOWTO_HEADINGS = ['Goal', 'Before you start', 'Steps', 'Check it worked', 'What can go wrong', 'Next'];
const P = path.posix;

/** Parse the manifest into ordered chapters and pages. Throws on a structural error. */
export function loadManifest(text) {
    const t = parseToon(text);
    if (!t.handbook || !t.chapters) throw new Error('handbook.toon: needs `handbook:` and `chapters:`');
    const chapters = Object.entries(t.chapters).map(([id, c]) => ({
        id, title: c.title, group: c.group, summary: c.summary || '',
        pages: Object.entries(c.pages || {}).map(([pid, p]) => ({
            id: pid, chapter: id, file: p.file, title: p.title || null, type: p.type,
            tracks: Array.isArray(p.tracks) ? p.tracks : p.tracks ? [p.tracks] : [], audience: p.audience,
        })),
    }));
    return { title: t.handbook.title || 'Handbook', chapters };
}

export const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
export const slug = (s) => s.toLowerCase().replace(/<[^>]+>/g, '').replace(/[`*_]/g, '').replace(/[^\w\s-]/g, '').trim().replace(/\s+/g, '-');

/** Inline markdown → HTML. `link(href)` returns a rewritten href or null (render as text). */
export function inline(text, link) {
    const codes = [], opens = [];
    let s = text.replace(/`([^`]+)`/g, (_, c) => `\u0000${codes.push(c) - 1}\u0000`);
    // an HTML anchor target (`<a id="x"></a>`, `<a name="x">`) keeps working as an anchor; all other raw HTML is escaped
    s = s.replace(/<a\s+(?:id|name)="([\w-]+)"\s*>\s*<\/a>/g, (_, id) => `\u0002${opens.push(`<span id="${esc(id)}"></span>`) - 1}\u0002`);
    s = esc(s);
    s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt) => alt);
    // a link's opening tag is set aside so emphasis below can never reach into its href
    s = s.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;[^&]*&quot;)?\)/g, (_, label, href) => {
        const h = link(href.replace(/&amp;/g, '&'));
        const open = h ? `<a href="${esc(h)}">` : '<span class="nolink">';
        return `\u0002${opens.push(open) - 1}\u0002${label}${h ? '</a>' : '</span>'}`;
    });
    s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/(^|[^\w*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>')
        .replace(/(^|[^\w])_([^_\s][^_]*)_(?=[^\w]|$)/g, '$1<em>$2</em>');
    s = s.replace(/\u0002(\d+)\u0002/g, (_, i) => opens[Number(i)]);
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${esc(codes[Number(i)])}</code>`);
}

/** Markdown → { html, title, headings }. */
export function markdownToHtml(md, link = () => null) {
    let text = md.replace(/\r\n/g, '\n');
    if (text.startsWith('---\n')) { const e = text.indexOf('\n---', 4); if (e > 0) text = text.slice(text.indexOf('\n', e + 1) + 1); }
    text = text.replace(/<!--[\s\S]*?-->/g, '');
    const lines = text.split('\n');
    const out = [], headings = [];
    let title = null, i = 0;
    const para = [];
    const flush = () => { if (para.length) { out.push(`<p>${inline(para.join(' '), link)}</p>`); para.length = 0; } };
    while (i < lines.length) {
        const l = lines[i];
        const fence = l.match(/^\s*(```|~~~)(.*)$/);
        if (fence) {
            flush();
            const body = [];
            i++;
            while (i < lines.length && !lines[i].trimStart().startsWith(fence[1])) body.push(lines[i++]);
            i++;
            const info = fence[2].trim().split(/\s+/)[0];
            if (info === FENCE_API || info === FENCE_UI) {
                // a docs-as-tests step (tools/handbook-checks.mjs): one quiet line, the step itself folded under it
                const step = parseBlock({ kind: info === FENCE_API ? 'api' : 'ui', line: 0, lines: body.map((text) => ({ text, line: 0 })) });
                out.push(`<details class="check"><summary>Checked on every release · ${esc(describe(step))}</summary><pre><code>${esc(body.join('\n'))}</code></pre></details>`);
                continue;
            }
            out.push(`<pre><code>${esc(body.join('\n'))}</code></pre>`);
            continue;
        }
        const h = l.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
        if (h) {
            flush();
            const level = h[1].length, content = inline(h[2], link), id = slug(h[2]);
            if (level === 1 && !title) title = h[2].replace(/[`*_]/g, '');
            else headings.push({ level, text: h[2].replace(/[`*_]/g, ''), id });
            out.push(`<h${level} id="${id}">${content}</h${level}>`);
            i++;
            continue;
        }
        if (/^\s*\|.*\|\s*$/.test(l) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
            flush();
            const cells = (r) => r.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map((c) => inline(c.trim().replace(/\\\|/g, '|'), link));
            const head = cells(l);
            i += 2;
            const rows = [];
            while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(cells(lines[i++]));
            out.push('<div class="table"><table><thead><tr>' + head.map((c) => `<th>${c}</th>`).join('') + '</tr></thead><tbody>' +
                rows.map((r) => '<tr>' + r.map((c) => `<td>${c}</td>`).join('') + '</tr>').join('') + '</tbody></table></div>');
            continue;
        }
        if (/^\s*>/.test(l)) {
            flush();
            const body = [];
            while (i < lines.length && /^\s*>/.test(lines[i])) body.push(lines[i++].replace(/^\s*>\s?/, ''));
            out.push(`<blockquote>${markdownToHtml(body.join('\n'), link).html}</blockquote>`);
            continue;
        }
        const li = l.match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
        if (li) {
            flush();
            const ordered = /\d/.test(li[2]);
            const items = [];
            while (i < lines.length) {
                const m = lines[i].match(/^(\s*)([-*+]|\d+[.)])\s+(.*)$/);
                if (m) { items.push({ depth: Math.floor(m[1].length / 2), ordered: /\d/.test(m[2]), text: m[3] }); i++; continue; }
                if (lines[i].trim() && /^\s{2,}\S/.test(lines[i]) && items.length) { items[items.length - 1].text += ' ' + lines[i].trim(); i++; continue; }
                break;
            }
            out.push(renderList(items, ordered, link));
            continue;
        }
        if (/^\s*([-*_])\s*\1\s*\1[\s\1]*$/.test(l)) { flush(); out.push('<hr>'); i++; continue; }
        if (!l.trim()) { flush(); i++; continue; }
        para.push(l.trim());
        i++;
    }
    flush();
    return { html: out.join('\n'), title, headings };
}

/** Nested lists; each level takes its list type from the first item at that level. */
function renderList(items, ordered, link) {
    const tags = [];
    let html = '', depth = -1;
    for (const it of items) {
        const d = Math.min(it.depth, depth + 1);
        if (d > depth) {
            const tag = it.ordered === undefined ? (ordered ? 'ol' : 'ul') : it.ordered ? 'ol' : 'ul';
            tags[d] = tag;
            html += `<${tag}>`;
            depth = d;
        } else { html += '</li>'; while (depth > d) { html += `</${tags[depth]}></li>`; depth--; } }
        html += `<li>${inline(it.text, link)}`;
    }
    html += '</li>';
    while (depth > 0) { html += `</${tags[depth]}></li>`; depth--; }
    return html + `</${tags[0]}>`;
}

const CSS = `:root{--bg:#fff;--fg:#1c2128;--muted:#59636e;--line:#d8dee4;--accent:#0b5cad;--soft:#f3f5f7;--code:#f0f2f4}
@media (prefers-color-scheme:dark){:root{--bg:#14171b;--fg:#e4e7eb;--muted:#9aa4ae;--line:#30363d;--accent:#6cb2ff;--soft:#1b1f24;--code:#22272e}}
*{box-sizing:border-box}body{margin:0;font:15px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
a{color:var(--accent)}header{display:flex;gap:16px;align-items:center;padding:10px 20px;border-bottom:1px solid var(--line)}
header a.home{font-weight:600;text-decoration:none;color:var(--fg)}header input{margin-left:auto;padding:6px 10px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);width:260px;max-width:45vw}
.wrap{display:flex}nav{width:260px;flex:none;padding:16px 20px;border-right:1px solid var(--line);font-size:14px}
nav h4{margin:14px 0 4px;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}nav a{display:block;padding:2px 0;text-decoration:none}
nav a.on{font-weight:600}main{padding:20px 32px;max-width:900px;min-width:0}code{background:var(--code);padding:1px 4px;border-radius:4px;font-size:.92em}
pre{background:var(--code);padding:12px;border-radius:6px;overflow:auto}pre code{background:none;padding:0}
.table{overflow-x:auto}table{border-collapse:collapse;margin:12px 0}th,td{border:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}
th{background:var(--soft)}blockquote{margin:12px 0;padding:4px 14px;border-left:3px solid var(--line);color:var(--muted)}
.badges span{display:inline-block;font-size:12px;padding:1px 8px;margin:0 6px 6px 0;border-radius:10px;background:var(--soft);border:1px solid var(--line)}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}.card{border:1px solid var(--line);border-radius:8px;padding:12px 14px}
.card a{font-weight:600;text-decoration:none}.muted{color:var(--muted)}#results a{display:block}
details.check{margin:6px 0 12px;font-size:13px;color:var(--muted)}details.check summary{cursor:pointer}details.check pre{font-size:12px}
@media (max-width:760px){.wrap{display:block}nav{width:auto;border-right:0;border-bottom:1px solid var(--line)}main{padding:16px}}`;

const SEARCH_JS = `const q=document.getElementById('q');let idx=null;q&&q.addEventListener('input',async()=>{const r=document.getElementById('results');
if(!idx){try{idx=await (await fetch('search.json')).json()}catch(e){idx=[]}}const v=q.value.trim().toLowerCase();if(!v){r.innerHTML='';return}
const hits=idx.filter(p=>(p.title+' '+p.headings.join(' ')).toLowerCase().includes(v)).slice(0,30);
r.innerHTML='<h3>Search</h3>'+(hits.length?hits.map(p=>'<a href="'+p.id+'.html">'+p.title.replace(/</g,'&lt;')+'</a>').join(''):'<p class=muted>No page matches.</p>');});`;

function shell(book, chapters, current, title, body) {
    const groups = [];
    for (const c of chapters) { const g = groups.find((x) => x.name === c.group); if (g) g.items.push(c); else groups.push({ name: c.group, items: [c] }); }
    const nav = groups.map((g) => `<h4>${esc(g.name)}</h4>` + g.items.map((c) => `<a href="${c.id}.html"${c.id === current ? ' class="on"' : ''}>${esc(c.title)}</a>`).join('')).join('');
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
        `<title>${esc(title)} · ${esc(book.title)}</title><style>${CSS}</style></head><body><header><a class="home" href="index.html">${esc(book.title)}</a>` +
        `<input id="q" type="search" placeholder="Search the Handbook" aria-label="Search the Handbook"></header>` +
        `<div class="wrap"><nav aria-label="Chapters">${nav}</nav><main><div id="results"></div>${body}</main></div><script>${SEARCH_JS}</script></body></html>`;
}

/**
 * Render the site in memory. `read(file)` returns a repository file's text. Returns { files: Map<name, text>, pages }.
 * Pure apart from `read`, so check-handbook.mjs and the tests render without writing.
 */
export function render(book, read, { internal = false } = {}) {
    const visible = (p) => internal || p.audience === 'external';
    const chapters = book.chapters.map((c) => ({ ...c, pages: c.pages.filter(visible) }));
    const byFile = new Map();
    for (const c of chapters) for (const p of c.pages) byFile.set(p.file, p);
    const files = new Map(), index = [];
    for (const c of chapters) {
        for (const p of c.pages) {
            const dir = P.dirname(p.file);
            const link = (href) => {
                if (/^(https?:|mailto:)/i.test(href)) return href;
                if (href.startsWith('#')) return href;
                const [target, frag] = href.split('#');
                let decoded = target;
                try { decoded = decodeURIComponent(target); } catch { /* a malformed escape is read as written */ }
                const t = byFile.get(P.normalize(P.join(dir, decoded)));
                return t ? `${t.id}.html${frag ? '#' + slug(frag) : ''}` : null;
            };
            const r = markdownToHtml(read(p.file), link);
            const title = p.title || r.title || p.id;
            const badges = `<div class="badges"><span>${esc(p.type)}</span>${p.tracks.map((t) => `<span>${esc(t)}</span>`).join('')}` +
                (internal ? `<span>${esc(p.audience)}</span>` : '') + '</div>';
            const crumb = `<p class="muted"><a href="${c.id}.html">${esc(c.title)}</a></p>`;
            files.set(`${p.id}.html`, shell(book, chapters, c.id, title, crumb + badges + r.html));
            index.push({ id: p.id, title, chapter: c.id, headings: r.headings.map((h) => h.text).slice(0, 40) });
        }
        const list = c.pages.length
            ? '<div class="cards">' + c.pages.map((p) => `<div class="card"><a href="${p.id}.html">${esc(index.find((x) => x.id === p.id).title)}</a><div class="muted">${esc(p.type)} · ${esc(p.tracks.join(', '))}</div></div>`).join('') + '</div>'
            : '<p class="muted">The pages for this chapter are being written.</p>';
        files.set(`${c.id}.html`, shell(book, chapters, c.id, c.title, `<h1>${esc(c.title)}</h1><p>${esc(c.summary)}</p>${list}`));
        index.push({ id: c.id, title: c.title, chapter: c.id, headings: [] });
    }
    const groups = [...new Set(chapters.map((c) => c.group))];
    const home = `<h1>${esc(book.title)}</h1><p>How to build, ship and manage business Apps on Inspecto. Pick a chapter, or search.</p>` +
        groups.map((g) => `<h2>${esc(g)}</h2><div class="cards">` + chapters.filter((c) => c.group === g)
            .map((c) => `<div class="card"><a href="${c.id}.html">${esc(c.title)}</a><div class="muted">${esc(c.summary)}</div></div>`).join('') + '</div>').join('');
    files.set('index.html', shell(book, chapters, null, book.title, home));
    files.set('search.json', JSON.stringify(index));
    return { files, pages: chapters.flatMap((c) => c.pages) };
}

function main() {
    const args = process.argv.slice(2);
    const at = args.indexOf('--out');
    const out = at >= 0 ? args[at + 1] : null;
    if (!out) { console.error('usage: node tools/render-handbook.mjs --out <dir> [--internal]'); process.exit(2); }
    if (!existsSync(MANIFEST)) { console.error(`✗ render-handbook: ${MANIFEST} not found — run from the repository root`); process.exit(2); }
    const internal = args.includes('--internal');
    const book = loadManifest(readFileSync(MANIFEST, 'utf8'));
    const { files, pages } = render(book, (f) => readFileSync(f, 'utf8'), { internal });
    mkdirSync(out, { recursive: true });
    const marker = path.join(out, '.handbook');
    if (existsSync(marker)) for (const f of readdirSync(out)) if (/\.(html|json)$/.test(f)) rmSync(path.join(out, f));
    writeFileSync(marker, internal ? 'internal\n' : 'external\n');
    for (const [name, text] of files) writeFileSync(path.join(out, name), text);
    console.log(`render-handbook: ${internal ? 'INTERNAL' : 'external'} render, ${pages.length} page(s), ${files.size} file(s) → ${out}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
