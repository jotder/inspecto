#!/usr/bin/env node
// docs-health — the health of the information base, and the ratchets that keep it from getting worse
// (INFO-RESTRUCTURE-1 wave W2, docs/superpower/2026-11-19-information-restructure-and-maintenance-plan.md §2, §4).
//
//   node tools/docs-health.mjs [--json] [--ratchet] [--baseline-write]
//
// WHY: the plan's principles — a page an agent can read whole, current pages free of their own history, one home per
// fact, one header per page — only hold if something counts them. Today's debt is large (46 pages over 60 KB, hundreds
// of inline history notes), so every check is a RATCHET against tools/docs-health.baseline.json: old debt may stay and
// shrink, new debt fails. Nothing here makes a commit that does not touch the debt go red.
//
// SCOPE (stated, never inferred): TRACKED `*.md` under docs/ — `git ls-files docs` — minus docs/archived-documents/**
// (the history tier, never maintained). GENERATED pages (a hub `index.md`, `feature-db.md`, or a page whose opening
// lines carry a generator banner, GENERATED_BANNER — the tests inventories, api-routes pages …) are measured in the report but exempt from every ratchet: their content follows the board
// and the hubs, so ratcheting them would fail an unrelated edit.
//
// RATCHETS (--ratchet exits 1 on any):
//   size      a page over CAP (60 KB) that is not in the baseline, or one that grew more than GROW_ALLOWANCE (2 KB) past
//             its baseline size. The allowance lets a small fix land on a big page; it does not let the page grow.
//             Pages that grow by design are reported only: the board (its ledger has its own cap), the glossary (new
//             terms) and in-flight plans under docs/superpower/ (their as-built notes).
//   history   a page whose count of inline history notes (HISTORY) rose above its baseline. Exempt by decision: the
//             board and the glossary (logs and rename maps by nature) and the competitive landscape (its corrections
//             log is a deliberate lesson log, D-IR7). Plans under docs/superpower/ record decisions and are exempt.
//   header    a hub page (docs/okf/**) without the base header (`type`, `title`, `description`) that is not in the
//             baseline list; and, wherever a header carries them, an invalid `audience` (external | internal), `verified`
//             (starts YYYY-MM-DD) or `code` path (must be tracked). The full header (D-IR4: type, title, description,
//             item, audience, status, verified, code) is required of every hub page that is not in the baseline's
//             `partialHeader` list (pages W4 could not fill — a layer links no `code`) or its `headerless` list: a NEW
//             hub page needs it (`node tools/fill-doc-headers.mjs --write --hub <kind>/<name>` adds what it can), and a
//             page that has it may not lose it, so the full-header count only rises (INFO-RESTRUCTURE-1 W4).
//   editions  a page with more hand-written Basic | Standard | Advanced tables than its baseline. The homes of that fact
//             (EDITIONS.md and spec-editions.md) are exempt.
//   ledger    the board's ledger (BACKLOG.md, between the count line and "## Area index") over LEDGER_CAP (60 KB):
//             run `node tools/roll-backlog-ledger.mjs --write`. Capped by size, not age, so the calendar alone never
//             turns a commit red.
// A count BELOW its baseline passes with a request to run --baseline-write; a baseline entry for a page that no longer
// exists is reported as stale, never failed.
//
// REPORT (default, always exit 0): pages and KB per tier, pages over the 40 KB target and the 60 KB cap, history notes,
// headers (base / full), hand-written edition tables, the ledger, and the DRIFT LIST — pages whose header names `code`
// paths changed (git log) after the header's `verified` date. The drift list is empty until W4 fills headers.
//
// NOT detected (so nobody reads a 0 as "none"): history written in other words than HISTORY's four shapes ("until <date>" is left out on purpose);
// a moved page (git mv) reads as new — run --baseline-write in the move's commit; a restated
// fact other than edition placement; a page too long in tokens but under 60 KB of bytes.
//
// Tests: tools/docs-health.test.mjs.

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const CAP = 60 * 1024;
export const TARGET = 40 * 1024;
export const GROW_ALLOWANCE = 2 * 1024;
/** Pages that grow by design — new board rows, new terms, a plan's as-built notes — are reported, never size-ratcheted. */
export const SIZE_EXEMPT = new Set(['docs/BACKLOG.md', 'docs/GLOSSARY.md']);
const sizeScoped = (p) => !SIZE_EXEMPT.has(p.path) && !p.path.startsWith('docs/superpower/');
export const LEDGER_CAP = 60 * 1024;
// "until <date>" is deliberately NOT a shape: "valid until 2026-12-31" is a current fact, not history.
export const HISTORY = /corrected 20\d\d-\d\d-\d\d|\(was [^)\n]{0,40}\)|refreshed 20\d\d-\d\d-\d\d|re-verified 20\d\d-\d\d-\d\d/gi;
/** A generator's banner at the start of a line in a page's opening lines: `> **Generated** by \`tools/…\``,
 *  `> **GENERATED** by \`tools/…\``, `<!-- generated by tools/… -->`. Prose that merely names a generator is not one. */
export const GENERATED_BANNER = /^(?:>\s*|<!--\s*)?\**generated\**\s+by\s+`?tools\//im;
export const EDITION_TABLE = /^\s*\|.*\|\s*\**\s*Basic\s*\**\s*\|\s*\**\s*Standard\s*\**\s*\|\s*\**\s*Advanced\b/;
export const HISTORY_EXEMPT = new Set([
    'docs/BACKLOG.md',
    'docs/GLOSSARY.md',
    'docs/okf/product/layer/product/brochure-competitive-landscape.md',
]);
export const EDITION_HOMES = new Set(['docs/EDITIONS.md', 'docs/okf/product/layer/platform/spec-editions.md']);
const ARCHIVE = 'docs/archived-documents/';
const BASE_KEYS = ['type', 'title', 'description'];
const FULL_KEYS = ['type', 'title', 'description', 'item', 'audience', 'status', 'verified', 'code'];

/** Is this page generated (exempt from ratchets)? */
export function isGenerated(path, text) {
    if (/^docs\/okf\/.*\/index\.md$/.test(path) || path === 'docs/okf/product/index.md') return true;
    if (/\/feature-db\.md$/.test(path)) return true;
    return GENERATED_BANNER.test(text.split('\n').slice(0, 15).join('\n'));
}

/** A minimal frontmatter reader: `key: value`, `key: [a, b]` and `- item` lists. Null when the page has none. */
export function frontmatter(text) {
    const lines = text.split(/\r?\n/);
    if (lines[0] !== '---') return null;
    const end = lines.indexOf('---', 1);
    if (end < 0) return null;
    const out = {};
    let listKey = null;
    for (const l of lines.slice(1, end)) {
        const item = l.match(/^\s+-\s+(.*)$/);
        if (item && listKey) { out[listKey].push(unquote(item[1])); continue; }
        const kv = l.match(/^([A-Za-z_][\w-]*):\s*(.*)$/);
        if (!kv) continue;
        const [, k, v] = kv;
        if (v === '') { out[k] = []; listKey = k; continue; }
        listKey = null;
        out[k] = v.startsWith('[') && v.endsWith(']')
            ? v.slice(1, -1).split(',').map((s) => unquote(s.trim())).filter(Boolean)
            : unquote(v.trim());
    }
    return out;
}
const unquote = (s) => s.replace(/^["']|["']$/g, '');

/** Count matches outside fenced blocks. */
function countOutsideFences(text, test) {
    let inFence = false, n = 0;
    for (const l of text.split('\n')) {
        const t = l.trimStart();
        if (t.startsWith('```') || t.startsWith('~~~')) { inFence = !inFence; continue; }
        if (!inFence) n += test(l);
    }
    return n;
}
export const historyNotes = (text) => countOutsideFences(text, (l) => (l.match(HISTORY) || []).length);
export const editionTables = (text) => countOutsideFences(text, (l) => (EDITION_TABLE.test(l) ? 1 : 0));

/** Validate the header fields this plan introduced; returns messages. */
export function headerProblems(fm, isTracked) {
    const out = [];
    if (!fm) return out;
    if (fm.audience !== undefined && !['external', 'internal'].includes(fm.audience))
        out.push(`audience '${fm.audience}' is not external or internal`);
    if (fm.verified !== undefined && !/^\d{4}-\d{2}-\d{2}/.test(String(fm.verified)))
        out.push(`verified '${fm.verified}' does not start with a YYYY-MM-DD date`);
    if (fm.code !== undefined) {
        for (const p of [].concat(fm.code)) if (!isTracked(p)) out.push(`code path '${p}' is not tracked`);
    }
    return out;
}

/** The board's ledger span: lines between the count line and "## Area index". */
export function ledgerBytes(boardText) {
    const lines = boardText.split(/\r?\n/);
    const c = lines.findIndex((l) => l.includes('<!--count:backlog-rows-->'));
    const a = lines.findIndex((l) => /^## Area index/.test(l));
    if (c < 0 || a < 0 || a < c) return null;
    return Buffer.byteLength(lines.slice(c + 1, a).join('\n'));
}

/** Measure every page. `read(path)` returns the text; `isTracked(path)` answers for header `code` paths. */
export function measure(paths, read, isTracked) {
    const pages = [];
    for (const path of paths) {
        if (!path.startsWith('docs/') || !path.endsWith('.md') || path.startsWith(ARCHIVE)) continue;
        const text = read(path);
        const fm = frontmatter(text);
        pages.push({
            path,
            bytes: Buffer.byteLength(text),
            generated: isGenerated(path, text),
            hub: path.startsWith('docs/okf/'),
            history: historyNotes(text),
            editions: editionTables(text),
            baseHeader: !!fm && BASE_KEYS.every((k) => fm[k] !== undefined && fm[k] !== ''),
            fullHeader: !!fm && FULL_KEYS.every((k) => fm[k] !== undefined && fm[k] !== ''),
            missingFull: FULL_KEYS.filter((k) => !fm || fm[k] === undefined || fm[k] === ''),
            headerProblems: headerProblems(fm, isTracked),
            fm,
        });
    }
    const board = paths.includes('docs/BACKLOG.md') ? ledgerBytes(read('docs/BACKLOG.md')) : null;
    return { pages, ledgerBytes: board };
}

/** The numbers a baseline keeps: only the debt, so the file stays small. */
export function baselineOf(m) {
    const b = { oversize: {}, history: {}, headerless: [], partialHeader: [], editions: {} };
    for (const p of m.pages) {
        if (p.generated) continue;
        if (sizeScoped(p) && p.bytes > CAP) b.oversize[p.path] = p.bytes;
        if (historyScoped(p) && p.history > 0) b.history[p.path] = p.history;
        if (p.hub && !p.baseHeader) b.headerless.push(p.path);
        else if (p.hub && !p.fullHeader) b.partialHeader.push(p.path);
        if (!EDITION_HOMES.has(p.path) && p.editions > 0) b.editions[p.path] = p.editions;
    }
    b.headerless.sort();
    b.partialHeader.sort();
    return b;
}
const historyScoped = (p) => !HISTORY_EXEMPT.has(p.path) && !p.path.startsWith('docs/superpower/');

/** Compare a measurement with the baseline: { failures, improvements, stale }. Pure, for the tests. */
export function evaluate(m, base) {
    const failures = [], improvements = [], stale = [];
    const present = new Set(m.pages.map((p) => p.path));
    for (const p of m.pages) {
        if (p.generated) continue;
        const kb = (n) => (n / 1024).toFixed(1) + ' KB';
        if (sizeScoped(p) && p.bytes > CAP) {
            const was = base.oversize[p.path];
            if (was === undefined) failures.push(`size: ${p.path} is ${kb(p.bytes)}, over the 60 KB cap — split it by topic`);
            else if (p.bytes > was + GROW_ALLOWANCE) failures.push(`size: ${p.path} grew to ${kb(p.bytes)} from ${kb(was)} (allowance 2 KB) — move text out, do not add it`);
            else if (p.bytes < was - GROW_ALLOWANCE) improvements.push(`size: ${p.path} shrank to ${kb(p.bytes)}`);
        } else if (base.oversize[p.path] !== undefined) improvements.push(`size: ${p.path} is now under the cap`);
        if (historyScoped(p)) {
            const was = base.history[p.path] || 0;
            if (p.history > was) failures.push(`history: ${p.path} has ${p.history} inline history notes, baseline ${was} — put the change in git or a short Changes list, not in the current text`);
            else if (p.history < was) improvements.push(`history: ${p.path} ${was} → ${p.history}`);
        }
        if (p.hub && !p.baseHeader && !base.headerless.includes(p.path))
            failures.push(`header: ${p.path} has no type / title / description header`);
        if (p.hub && p.baseHeader && base.headerless.includes(p.path)) improvements.push(`header: ${p.path} gained its header`);
        const partial = base.partialHeader ?? [];
        if (p.hub && p.baseHeader && !p.fullHeader && !partial.includes(p.path) && !base.headerless.includes(p.path))
            failures.push(`header: ${p.path} lacks the full D-IR4 header (missing: ${p.missingFull.join(', ')}) — run node tools/fill-doc-headers.mjs --write --hub ${p.path.split('/').slice(3, 5).join('/')}`);
        if (p.hub && p.fullHeader && partial.includes(p.path)) improvements.push(`header: ${p.path} gained the full header`);
        for (const msg of p.headerProblems) failures.push(`header: ${p.path}: ${msg}`);
        if (!EDITION_HOMES.has(p.path)) {
            const was = base.editions[p.path] || 0;
            if (p.editions > was) failures.push(`editions: ${p.path} has ${p.editions} hand-written Basic | Standard | Advanced table(s), baseline ${was} — link to EDITIONS.md or generate it from offerings/*.toon`);
            else if (p.editions < was) improvements.push(`editions: ${p.path} ${was} → ${p.editions}`);
        }
    }
    if (m.ledgerBytes !== null && m.ledgerBytes > LEDGER_CAP)
        failures.push(`ledger: the board's ledger is ${(m.ledgerBytes / 1024).toFixed(1)} KB, over 60 KB — run node tools/roll-backlog-ledger.mjs --write`);
    const known = [...Object.keys(base.oversize), ...Object.keys(base.history), ...base.headerless, ...(base.partialHeader ?? []), ...Object.keys(base.editions)];
    for (const k of new Set(known)) if (!present.has(k)) stale.push(k);
    return { failures, improvements, stale };
}

/** Pages whose `code` paths changed after `verified`. `lastChange(path)` returns YYYY-MM-DD or null. */
export function drift(m, lastChange) {
    const out = [];
    for (const p of m.pages) {
        if (!p.fm || !p.fm.code || !p.fm.verified) continue;
        const verified = String(p.fm.verified).slice(0, 10);
        for (const c of [].concat(p.fm.code)) {
            const d = lastChange(c);
            if (d && d > verified) out.push({ page: p.path, code: c, changed: d, verified });
        }
    }
    return out;
}

function report(m, d) {
    const tiers = new Map();
    for (const p of m.pages) {
        const tier = p.path.startsWith('docs/okf/') ? 'hubs' : p.path.startsWith('docs/superpower/') ? 'plans'
            : p.path.startsWith('docs/api/') ? 'api' : /^docs\/[^/]+\.md$/.test(p.path) ? 'root canon' : 'other docs';
        const t = tiers.get(tier) || { n: 0, bytes: 0 };
        t.n++; t.bytes += p.bytes; tiers.set(tier, t);
    }
    const lines = ['Docs health (tracked docs/**/*.md minus docs/archived-documents/**)'];
    for (const [k, t] of tiers) lines.push(`  ${k.padEnd(11)} ${String(t.n).padStart(4)} pages  ${String(Math.round(t.bytes / 1024)).padStart(6)} KB`);
    const hand = m.pages.filter((p) => !p.generated);
    const over = hand.filter((p) => p.bytes > CAP).sort((a, b) => b.bytes - a.bytes);
    lines.push(`  pages over the 40 KB target: ${hand.filter((p) => p.bytes > TARGET).length}; over the 60 KB cap: ${over.length}`);
    for (const p of over.slice(0, 10)) lines.push(`    ${Math.round(p.bytes / 1024)} KB  ${p.path}`);
    lines.push(`  inline history notes (ratcheted pages): ${hand.filter(historyScoped).reduce((s, p) => s + p.history, 0)}`);
    const hubs = hand.filter((p) => p.hub);
    lines.push(`  hub headers: ${hubs.filter((p) => p.baseHeader).length} of ${hubs.length} base, ${hubs.filter((p) => p.fullHeader).length} full (D-IR4)`);
    lines.push(`  hand-written edition tables outside their homes: ${hand.filter((p) => !EDITION_HOMES.has(p.path)).reduce((s, p) => s + p.editions, 0)}`);
    if (m.ledgerBytes !== null) lines.push(`  board ledger: ${(m.ledgerBytes / 1024).toFixed(1)} KB (cap 60 KB)`);
    lines.push(`  drift list (code changed after verified): ${d.length}`);
    for (const x of d.slice(0, 20)) lines.push(`    ${x.page}  ${x.code} changed ${x.changed}, verified ${x.verified}`);
    return lines.join('\n');
}

function main() {
    const args = new Set(process.argv.slice(2));
    const root = process.cwd();
    const BASELINE = join(dirname(fileURLToPath(import.meta.url)), 'docs-health.baseline.json');
    const tracked = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 }).split('\0').filter(Boolean);
    const trackedSet = new Set(tracked);
    const isTracked = (p) => trackedSet.has(p) || tracked.some((t) => t.startsWith(p.replace(/\/$/, '') + '/'));
    // A tracked doc deleted in this working tree but not yet staged is skipped, not a crash (a shared checkout often
    // holds someone's unstaged deletion); the count is printed so the skip is never silent.
    const listed = tracked.filter((p) => p.startsWith('docs/') && p.endsWith('.md'));
    const docs = listed.filter((p) => existsSync(join(root, p)));
    if (docs.length < listed.length) console.log(`docs-health: ${listed.length - docs.length} tracked doc(s) missing on disk, skipped`);
    const m = measure(docs, (p) => readFileSync(join(root, p), 'utf8'), isTracked);
    if (m.pages.length < 300) { console.error(`✗ docs-health: only ${m.pages.length} page(s) measured — run from the repository root`); process.exit(2); }
    const lastChange = (p) => {
        try { return execFileSync('git', ['log', '-1', '--format=%cs', '--', p], { cwd: root, encoding: 'utf8' }).trim() || null; } catch { return null; }
    };
    if (args.has('--baseline-write')) {
        writeFileSync(BASELINE, JSON.stringify({ _note: 'tools/docs-health.mjs --baseline-write; only debt is kept, and it may only shrink (INFO-RESTRUCTURE-1 W2)', ...baselineOf(m) }, null, 1) + '\n');
        console.log(`docs-health: baseline written to ${BASELINE}`);
    } else if (args.has('--ratchet')) {
        if (!existsSync(BASELINE)) { console.error('✗ docs-health: no baseline — run with --baseline-write first'); process.exit(2); }
        const base = JSON.parse(readFileSync(BASELINE, 'utf8'));
        const r = evaluate(m, base);
        if (r.stale.length) console.log(`docs-health: ${r.stale.length} baseline entr(y/ies) name pages that no longer exist — refresh with --baseline-write`);
        if (r.improvements.length) console.log(`docs-health: ${r.improvements.length} improvement(s) below the baseline — lock them in with --baseline-write`);
        if (r.failures.length) {
            console.error(`✗ docs-health: ${r.failures.length} new debt item(s)\n` + r.failures.map((f) => '  ' + f).join('\n'));
            process.exit(1);
        }
        console.log(`✓ docs-health: no new debt — ${m.pages.length} pages measured (the archive excluded, generated pages exempt).`);
    } else if (args.has('--json')) {
        console.log(JSON.stringify({ baseline: baselineOf(m), ledgerBytes: m.ledgerBytes, drift: drift(m, lastChange) }, null, 1));
    } else {
        console.log(report(m, drift(m, lastChange)));
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
