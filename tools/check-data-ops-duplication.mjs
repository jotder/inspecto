#!/usr/bin/env node
// check-data-ops-duplication — the inventory of data-operations code that is COPIED into modules instead of living once in
// its shared home (ANALYTICS-CORE-1, docs/archived-documents/plans-archive/analytics-core-plan.md §1a, §4 M9/M10, §4a).
//
//   node tools/check-data-ops-duplication.mjs [--root .] [--json] [--ratchet] [--baseline-write]
//
// WHY: the plan's measure of success is how many copies are GONE, and its survey counts (~30 quoting helpers, ~29 files with
// a hand-built read_*) were a one-off grep. Without a maintained count a retired copy can quietly come back in another
// module, and nothing tells the next author the shared builder exists. This guard is that count — per category, per module.
//
// SCOPE: every TRACKED Java main source, `git ls-files '*/src/main/java/**/*.java'` (all module trees: inspecto/, platform/,
// features/, providers/, la/, spi/...). Tests, the UI and untracked files are never read. Comments are stripped first, so a
// javadoc that NAMES `read_parquet(` is not a finding. Categories:
//   a  private SQL identifier/literal quoting helpers — a method returning String named q, quote, quoteIdent, quoteIdentifier,
//      quoteLiteral, ident, lit, literal, sqlLiteral, sqlString, sqlStr, sqlPath, escapeSql, esc or escape, outside the
//      shared builders SqlBuilder and SqlIdent (inspecto-util; SqlBuilder.quoteIdent delegates to SqlIdent.q).
//      A wrapper that only delegates to SqlIdent/SqlBuilder still counts (it is a private helper M9 retires); the detail marks it.
//      Also escapeIdent, escapeIdentifier, escapeLiteral, sqlIdent, sqlQuote and quoteSql. AND (M9, inline): every
//      `.replace("'", "''")` (a string literal or a COPY ... TO '<path>' escaped in place - SqlIdent.sqlStr / pathStr) and
//      every `.replace("\"", "\"\"")` (an identifier quoted in place - SqlIdent.q), replaceAll and a char-literal first
//      argument included; one finding per call, its member the enclosing method. A call inside a helper already counted
//      above is not counted twice. RFC-4180 CSV quoting doubles `"` the same way - it is exempted per method, with a reason.
//   b  hand-built read_parquet( / read_csv( / read_json( / read_xlsx( (and _auto / ndjson variants) inside a Java string
//      literal, outside SqlViews (inspecto-sql) — one finding per literal.
//   c  private statistics / similarity / union-find — a method named median, lowerMedian, percentile*, quantile*, mad or
//      *Mad, stddev/stdDev, variance, zScore, robustZ*, jaroWinkler, levenshtein or jaccard*; and a union-find `find`
//      (its parameters name `parent`, or its file also declares `union`) — outside the Analytics Kernel
//      (platform/inspecto-analytics) and the graph library (la/inspecto-la-graph, its planned home platform/inspecto-graph).
//   d  JVM re-implementations of the Measure grammar — a switch with `case "countDistinct"` and `case "avg"` labels outside
//      query/MeasureCompiler.java and query/ConditionSql.java.
// NOT detected (stated so nobody reads a 0 as "none"): SQL quoting done some other way than the doubling above (a char
// loop, a regex, a String.format of a quote), statistics computed inline without a named method (a sort-and-index median, as VolumeBaseline had before ANALYTICS-CORE-1 M2), and a robust z named just `z` (RobustStats.z).
//
// EXEMPTIONS are explicit — category + file + member ('*' = the whole file) + reason, one JSON object per line in
// tools/data-ops-duplication.exemptions.json, never inferred. An HTML/XML/CSV/JSON escaper
// or a request-token validator named `esc`/`quote`/`ident` is exempted there after a human read it; so is an ingest reader.
//
// MODES: default = REPORT-ONLY, always exit 0. --baseline-write writes tools/data-ops-duplication.baseline.json (counts per
// category per module). --ratchet exits 1 when any category/module count is ABOVE the baseline, or an exemption matches
// nothing (stale); a count BELOW the baseline passes with a request to lower the baseline. Tests:
// tools/check-data-ops-duplication.test.mjs.

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { moduleLabel, reactorModuleDirs } from './reactor-modules.mjs';

export const BASELINE = 'tools/data-ops-duplication.baseline.json';
export const EXEMPTIONS = 'tools/data-ops-duplication.exemptions.json';
export const CATEGORIES = {
    a: 'private SQL quoting helpers and inline quote doubling outside SqlBuilder/SqlIdent',
    b: 'hand-built read_*( outside SqlViews',
    c: 'private statistics / similarity / union-find outside the Analytics Kernel and graph library',
    d: 'JVM re-implementation of the Measure grammar outside MeasureCompiler',
};

/** The shared homes: a file (path suffix) or module directory (path prefix) that IS the one place for the category. */
export const HOMES = {
    a: { suffixes: ['/com/gamma/util/SqlBuilder.java', '/com/gamma/util/SqlIdent.java'], prefixes: [] },
    b: { suffixes: ['/com/gamma/sql/SqlViews.java'], prefixes: [] },
    c: { suffixes: [], prefixes: ['platform/inspecto-analytics/', 'la/inspecto-la-graph/', 'platform/inspecto-graph/'] },
    d: { suffixes: ['/com/gamma/query/MeasureCompiler.java', '/com/gamma/query/ConditionSql.java'], prefixes: [] },
};

// ── Java lexing: comments out, string literals kept and listed ────────────────────────────────────────────────────────

/** text -> { code, strings: [{ value, offset }] }. `code` keeps the text (and every newline) with comments blanked. */
export function lex(text) {
    const out = text.split('');
    const strings = [];
    let i = 0;
    const n = text.length;
    const blank = (from, to) => { for (let k = from; k < to; k++) if (out[k] !== '\n' && out[k] !== '\r') out[k] = ' '; };
    while (i < n) {
        const c = text[i];
        if (c === '/' && text[i + 1] === '/') { const e = text.indexOf('\n', i); const end = e < 0 ? n : e; blank(i, end); i = end; }
        else if (c === '/' && text[i + 1] === '*') { const e = text.indexOf('*/', i + 2); const end = e < 0 ? n : e + 2; blank(i, end); i = end; }
        else if (c === '"' && text.startsWith('"""', i)) {
            const e = text.indexOf('"""', i + 3); const end = e < 0 ? n : e + 3;
            strings.push({ value: text.slice(i + 3, end - 3), offset: i }); i = end;
        } else if (c === '"' || c === '\'') {
            let j = i + 1;
            while (j < n && text[j] !== c && text[j] !== '\n') j += text[j] === '\\' ? 2 : 1;
            if (c === '"') strings.push({ value: text.slice(i + 1, j), offset: i });
            i = j + 1;
        } else i++;
    }
    return { code: out.join(''), strings };
}

const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'synchronized', 'try', 'else', 'do', 'return', 'new', 'super', 'this']);

/**
 * Methods declared in comment-free code: [{ name, params, prefix, offset, start, end }] — `prefix` is the text before the
 * name in the declaration header (modifiers, annotations, return type), `start..end` the body. Brace-depth based, so it
 * also sees methods of nested and anonymous classes; a lambda, initializer or control block is never a method.
 */
export function methods(code) {
    const found = [];
    const stack = [];
    let boundary = 0;
    for (let i = 0; i < code.length; i++) {
        const c = code[i];
        if (c === '"') { let j = i + 1; while (j < code.length && code[j] !== '"' && code[j] !== '\n') j += code[j] === '\\' ? 2 : 1; i = j; continue; }
        if (c === '\'') { let j = i + 1; while (j < code.length && code[j] !== '\'' && code[j] !== '\n') j += code[j] === '\\' ? 2 : 1; i = j; continue; }
        if (c === '{') {
            const header = code.slice(boundary, i);
            const m = headerMethod(header);
            const frame = m ? { ...m, offset: boundary + m.at, start: i } : null;
            stack.push(frame);
            boundary = i + 1;
        } else if (c === '}') {
            const f = stack.pop();
            if (f) { f.end = i; found.push(f); }
            boundary = i + 1;
        } else if (c === ';') boundary = i + 1;
    }
    return found.sort((a, b) => a.start - b.start);
}

function headerMethod(header) {
    let h = header.trimEnd();                                            // trimEnd, not /\s+$/: blanked javadoc makes long space runs (quadratic)
    const t = h.lastIndexOf('throws');
    if (t > 0 && /^throws\s+[\w$.,\s<>]+$/.test(h.slice(t)) && /\s/.test(h[t - 1])) h = h.slice(0, t).trimEnd();
    if (!h.endsWith(')')) return null;
    let depth = 0;
    let open = -1;
    for (let k = h.length - 1; k >= 0; k--) {
        if (h[k] === ')') depth++;
        else if (h[k] === '(' && --depth === 0) { open = k; break; }
    }
    if (open < 0) return null;
    const nm = /([A-Za-z_$][\w$]*)$/.exec(h.slice(0, open).trimEnd());
    if (!nm || KEYWORDS.has(nm[1])) return null;
    const prefix = h.slice(0, nm.index).trimEnd();
    if (/\bnew$/.test(prefix) || /[.=(,?:!&|+\-*/]$/.test(prefix) || /\b(record|class|interface|enum)$/.test(prefix)) return null;
    if (!prefix.trim()) return null;                                     // a bare call `foo(x) {` is no declaration
    return { name: nm[1], params: h.slice(open + 1, h.length - 1), prefix, at: nm.index };
}

const lineAt = (text, offset) => { let l = 1; for (let k = 0; k < offset; k++) if (text.charCodeAt(k) === 10) l++; return l; };

const QUOTING = /^(q|quote|quoteIdent|quoteIdentifier|quoteLiteral|ident|lit|literal|sqlLiteral|sqlString|sqlStr|sqlPath|escapeSql|esc|escape|escapeIdent|escapeIdentifier|escapeLiteral|sqlIdent|sqlQuote|quoteSql)$/;
/** Inline SQL quote doubling: .replace("'", "''") = a literal, .replace("\"", "\"\"") = an identifier; replaceAll and a char first argument too. */
const INLINE = /\.replace(?:All)?\(\s*(?:"(')"|'(\\')'|"\\""|'"')\s*,\s*(?:"''"|"\\"\\"")\s*\)/g;
const STATS = /^(median|lowerMedian|percentile\w*|quantile\w*|mad|\w*Mad|stddev|stdDev|variance|zScore|robustZ\w*|jaroWinkler|levenshtein|jaccard\w*)$/;
const READER = /\bread_(parquet|csv|csv_auto|json|json_auto|ndjson|ndjson_auto|xlsx)\s*\(/;

const inHome = (cat, file) => HOMES[cat].suffixes.some((s) => file.endsWith(s)) || HOMES[cat].prefixes.some((p) => file.startsWith(p));

/** Findings in ONE file: [{ category, file, line, member, note }] (exemptions not yet applied). */
export function scanFile(file, text) {
    const { code, strings } = lex(text);
    const ms = methods(code);
    const enclosing = (off) => { let best = null; for (const m of ms) if (m.start < off && off < m.end && (!best || m.start > best.start)) best = m; return best?.name ?? '<class>'; };
    const out = [];
    const hit = (category, offset, member, note = '') => out.push({ category, file, line: lineAt(text, offset), member, note });
    if (!inHome('a', file)) {
        const helpers = ms.filter((m) => QUOTING.test(m.name) && /\bString$/.test(m.prefix));
        for (const m of helpers) {
            hit('a', m.offset, m.name, /\b(SqlIdent|SqlBuilder)\.\w+\s*\(/.test(code.slice(m.start, m.end)) ? 'delegates to the shared builder' : 'reimplements');
        }
        for (const r of code.matchAll(INLINE)) {
            if (helpers.some((m) => m.start < r.index && r.index < m.end)) continue;     // counted once, as its helper
            hit('a', r.index, enclosing(r.index), r[1] || r[2] ? 'inline literal escape' : 'inline identifier escape');
        }
    }
    if (!inHome('b', file)) for (const s of strings) { const r = READER.exec(s.value); if (r) hit('b', s.offset, enclosing(s.offset), `read_${r[1]}(`); }
    if (!inHome('c', file)) {
        const hasUnion = ms.some((m) => m.name === 'union');
        for (const m of ms) {
            if (STATS.test(m.name)) hit('c', m.offset, m.name);
            else if (m.name === 'find' && (/\bparent\b/.test(m.params) || hasUnion)) hit('c', m.offset, m.name, 'union-find');
        }
    }
    if (!inHome('d', file)) {
        const cd = /\bcase\b[^\n:>]*"countDistinct"/.exec(code);
        if (cd && /\bcase\b[^\n:>]*"avg"/.test(code)) hit('d', cd.index, enclosing(cd.index), 'switch on aggregate names');
    }
    return out;
}

const exemptionOf = (f, exempt) => exempt.find((e) => e.category === f.category && e.file === f.file && (e.member === '*' || e.member === f.member));

/** files: [path], read(path) -> text, label(path) -> module. -> { scanned, modules, findings, exempted, stale }. */
export function scan(files, read, label, exempt = []) {
    const findings = [];
    const exempted = [];
    const used = new Set();
    for (const file of files) for (const f of scanFile(file, read(file))) {
        f.module = label(file);
        const e = exemptionOf(f, exempt);
        if (e) { used.add(e); exempted.push({ ...f, reason: e.reason }); } else findings.push(f);
    }
    return { scanned: files.length, modules: new Set(files.map(label)).size, findings, exempted, stale: exempt.filter((e) => !used.has(e)) };
}

/** findings -> { category: { module: n } }, every category present, keys sorted. */
export function counts(findings) {
    const c = Object.fromEntries(Object.keys(CATEGORIES).map((k) => [k, {}]));
    for (const f of findings) c[f.category][f.module] = (c[f.category][f.module] ?? 0) + 1;
    for (const k of Object.keys(c)) c[k] = Object.fromEntries(Object.entries(c[k]).sort(([a], [b]) => a.localeCompare(b)));
    return c;
}

/** { rose: ['a/mod: 3 > 2'], fell: ['a/mod: 1 < 2'] } — a module absent from either side counts as 0. */
export function ratchet(current, baseline) {
    const rose = [];
    const fell = [];
    for (const cat of Object.keys(CATEGORIES)) {
        const cur = current[cat] ?? {};
        const base = baseline[cat] ?? {};
        for (const mod of [...new Set([...Object.keys(cur), ...Object.keys(base)])].sort()) {
            const now = cur[mod] ?? 0;
            const was = base[mod] ?? 0;
            if (now > was) rose.push(`${cat}/${mod}: ${now} > baseline ${was}`);
            else if (now < was) fell.push(`${cat}/${mod}: ${now} < baseline ${was}`);
        }
    }
    return { rose, fell };
}

export function render(r) {
    const c = counts(r.findings);
    const cats = Object.keys(CATEGORIES);
    const mods = [...new Set(r.findings.map((f) => f.module))].sort();
    const L = [`data-ops duplication — scope: ${r.scanned} tracked main Java files in ${r.modules} modules (git ls-files '*/src/main/java/**/*.java'); comments stripped`, ''];
    for (const k of cats) L.push(`  ${k}  ${CATEGORIES[k]}`);
    L.push('', `| Module | ${cats.join(' | ')} | total |`, `|---|${cats.map(() => '---:').join('|')}|---:|`);
    for (const m of mods) { const row = cats.map((k) => c[k][m] ?? 0); L.push(`| ${m} | ${row.join(' | ')} | ${row.reduce((s, x) => s + x, 0)} |`); }
    const tot = cats.map((k) => Object.values(c[k]).reduce((s, x) => s + x, 0));
    L.push(`| **total** | ${tot.join(' | ')} | ${tot.reduce((s, x) => s + x, 0)} |`);
    for (const k of cats) {
        const fs = r.findings.filter((f) => f.category === k);
        L.push('', `## ${k} — ${CATEGORIES[k]}: ${fs.length}`);
        for (const f of fs) L.push(`- [${f.module}] ${f.file}:${f.line}  ${f.member}${f.note ? `  (${f.note})` : ''}`);
    }
    L.push('', `## Exempted (recorded in ${EXEMPTIONS}, each read at the site): ${r.exempted.length}`);
    for (const f of r.exempted) L.push(`- ${f.category} ${f.file}:${f.line}  ${f.member} — ${f.reason}`);
    if (r.stale.length) {
        L.push('', `## STALE exemptions (match nothing — remove them): ${r.stale.length}`);
        for (const e of r.stale) L.push(`- ${e.category} ${e.file} ${e.member}`);
    }
    return L.join('\n');
}

export function gather(root) {
    const files = execFileSync('git', ['ls-files', '--', '*/src/main/java/**/*.java'], { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20 })
        .split('\n').map((s) => s.trim()).filter(Boolean);
    const dirs = reactorModuleDirs((q) => (existsSync(join(root, q)) ? readFileSync(join(root, q), 'utf8') : null));
    const ep = join(root, EXEMPTIONS);
    const exempt = existsSync(ep) ? JSON.parse(readFileSync(ep, 'utf8')) : [];
    return scan(files, (f) => readFileSync(join(root, f), 'utf8'), (f) => moduleLabel(f, dirs) ?? '(root)', exempt);
}

function main() {
    const a = process.argv.slice(2);
    const i = a.indexOf('--root');
    const root = i >= 0 ? a[i + 1] : '.';
    const r = gather(root);
    const c = counts(r.findings);
    const bp = join(root, BASELINE);
    if (a.includes('--json')) console.log(JSON.stringify({ scanned: r.scanned, counts: c, exempted: r.exempted.length, stale: r.stale.length }));
    else console.log(render(r));
    if (a.includes('--baseline-write')) {
        mkdirSync(dirname(bp), { recursive: true });
        writeFileSync(bp, JSON.stringify(c, null, 2) + '\n');
        console.log(`\nwrote ${bp}`);
    }
    if (a.includes('--ratchet')) {
        if (!existsSync(bp)) { console.error(`RATCHET FAILED: no baseline at ${bp} — run --baseline-write once`); process.exit(1); }
        const { rose, fell } = ratchet(c, JSON.parse(readFileSync(bp, 'utf8')));
        const bad = [...rose.map((s) => `rose ${s}`), ...r.stale.map((e) => `stale exemption ${e.category} ${e.file} ${e.member}`)];
        if (bad.length) {
            console.error(`\nRATCHET FAILED — counts may only go down. Use the shared builder (SqlBuilder/SqlIdent, SqlViews, MeasureCompiler, the Analytics Kernel), or record a reasoned exemption in ${EXEMPTIONS}; remove a stale one; never raise ${BASELINE}:\n  ${bad.join('\n  ')}`);
            process.exit(1);
        }
        if (fell.length) console.log(`\nratchet ok, and counts FELL — lower the baseline (node tools/check-data-ops-duplication.mjs --baseline-write) so they cannot climb back:\n  ${fell.join('\n  ')}`);
        else console.log('\nratchet ok — counts equal the baseline');
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
