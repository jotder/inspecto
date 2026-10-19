#!/usr/bin/env node
// render-tests-inventory — the `tests` slot of the documentation hubs (PRODUCT-DOCS-HUB-1, docs/superpower/product-docs-hub-plan.md
// wave 4): a GENERATED list of test classes per Product Map Item, for QA/QC readers.
//
//   node tools/render-tests-inventory.mjs [--root .] [--check]      --check: exit 1 if any written file would change
//
// Writes docs/okf/product/module/<name>/tests-inventory.md for every `module.*` item whose Maven module has tests: the module
// directory is the one holding its manifest (`<dir>/src/main/resources/META-INF/inspecto/module.toon`, matched by `id`), the
// tests are git-tracked `<dir>/src/test/**/*Test.java` and `*IT.java`. UI specs are not mapped (no reliable item link).
// Also writes docs/okf/product/layer/platform/tests-tools-inventory.md listing tools/*.test.mjs. DERIVED only, never
// hand-kept (D-PDH2). A tests-inventory.md / tests-tools-inventory.md this tool would no longer write is removed.

import { existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { parseToon } from './check-offerings.mjs';

const GEN = 'tools/render-tests-inventory.mjs';

/** Java test path + source -> { cls, pkg, kind, path, doc }. */
export function javaTest(path, src) {
    const cls = path.split('/').pop().replace(/\.java$/, '');
    const pkg = /^\s*package\s+([\w.]+)\s*;/m.exec(src)?.[1] ?? '';
    const kind = /IT$/.test(cls) ? 'IT'
        : /^ControlApi/.test(cls) || (/\bHttpClient\b/.test(src) && /\bControlApi\b/.test(src)) ? 'real-HTTP control-plane test'
        : 'unit';
    return { cls, pkg, kind, path, doc: javadoc(src, cls) };
}

/** First line of the Javadoc directly above `class|record|interface <cls>`, else ''. */
export function javadoc(src, cls) {
    const at = new RegExp(`\\b(?:class|record|interface|enum)\\s+${cls}\\b`).exec(src);
    if (!at) return '';
    const before = src.slice(0, at.index), open = before.lastIndexOf('/**');
    if (open < 0) return '';
    const close = before.indexOf('*/', open);
    if (close < 0 || /[;{}]/.test(before.slice(close + 2).replace(/@\w+(\([^)]*\))?/g, ''))) return '';
    const line = before.slice(open + 3, close).split(/\r?\n/).map((l) => l.replace(/^\s*\*?\s?/, '').trim()).find((l) => l && !l.startsWith('@'));
    return line ?? '';
}

/** tools/*.test.mjs path + source -> { file, path, doc } (doc = first `//` comment line). */
export function toolTest(path, src) {
    const c = src.split(/\r?\n/).find((l) => /^\/\/\s*\S/.test(l) && !l.startsWith('#!'));
    return { file: path.split('/').pop(), path, doc: c ? c.replace(/^\/\/\s*/, '').trim() : '' };
}

/** items: { 'module/x': toon }, manifestPaths: { id: path }, testFiles: [path] -> { 'module/x': { title, id, dir, rows } }. */
export function hubs(items, manifestPaths, testFiles, readSrc) {
    const out = {};
    for (const [key, text] of Object.entries(items)) {
        const it = parseToon(text);
        if (it.kind !== 'module' || !it.module || !manifestPaths[it.module]) continue;
        const dir = manifestPaths[it.module].split('/src/main/')[0];
        const rows = testFiles.filter((f) => f.startsWith(`${dir}/src/test/`) && /(Test|IT)\.java$/.test(f))
            .map((f) => javaTest(f, readSrc(f))).sort((a, b) => a.pkg.localeCompare(b.pkg) || a.cls.localeCompare(b.cls));
        if (rows.length) out[key] = { title: it.title ?? it.id, id: it.id, dir, rows };
    }
    return out;
}

const md = (v) => String(v).replace(/\r?\n/g, ' ').replace(/(?<!\\)\|/g, '\\|');
// A summary is a VERBATIM quote of source Javadoc; a pre-rename word there is the source's to fix, so the row is exempted.
const quoted = (doc) => md(doc) + (/\b(Sources?|Flows?|Data Stores?|Issues?|Metrics?)\b/i.test(doc) ? ' <!-- vocab-allow: quoted Javadoc -->' : '');
const head = (title, id, what) => `---
type: TestsInventory
title: ${title} — tests inventory
item: ${id}
description: Generated list of the ${what} for ${id}, with how to run them.
tags: [tests, ${id.split('.')[0]}]
---
`;

export function toMarkdown({ title, id, dir, rows }) {
    const kind = id.split('.')[0], name = id.slice(kind.length + 1);
    const count = (k) => rows.filter((r) => r.kind === k).length;
    return `${head(title, id, 'test classes')}
# ${title} — tests inventory (\`${id}\`)

> **Generated** by \`${GEN}\` from the git-tracked test sources under \`${dir}/src/test/\` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [\`${id}\`](../../../../../product-map/${kind}/${name}.toon).

${rows.length} test class(es): ${count('unit')} unit, ${count('real-HTTP control-plane test')} real-HTTP control-plane, ${count('IT')} IT.
Kind is inferred: \`*IT\` → IT; a \`ControlApi*\` class, or one using both \`HttpClient\` and \`ControlApi\` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never \`+\`):

\`\`\`bash
mvn -o test -pl ${dir}
mvn -o test -pl ${dir} -Dtest=${rows.slice(0, 2).map((r) => r.cls).join(',')}
\`\`\`

| test class | package | kind | file | summary |
|---|---|---|---|---|
${rows.map((r) => `| \`${r.cls}\` | \`${r.pkg}\` | ${r.kind} | \`${r.path}\` | ${quoted(r.doc)} |`).join('\n')}
`;
}

export function toolsMarkdown(rows) {
    return `${head('Platform tooling', 'layer.platform', 'tools/*.test.mjs guard and generator tests')}
# Platform tooling — tests inventory (\`layer.platform\`)

> **Generated** by \`${GEN}\` from the git-tracked \`tools/*.test.mjs\` files — never edit this table. Hub:
> [index](index.md) · Product Map Item [\`layer.platform\`](../../../../../product-map/layer/platform.toon).

${rows.length} test file(s). Run one, or all:

\`\`\`bash
node --test tools/${rows[0]?.file ?? 'x.test.mjs'}
node --test tools/
\`\`\`

| test file | summary |
|---|---|
${rows.map((r) => `| \`${r.path}\` | ${quoted(r.doc)} |`).join('\n')}
`;
}

function main() {
    const args = process.argv.slice(2);
    const root = args.includes('--root') ? args[args.indexOf('--root') + 1] : '.';
    const check = args.includes('--check');
    const ls = (...p) => execFileSync('git', ['ls-files', ...p], { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20 }).split('\n').filter(Boolean);
    const read = (p) => readFileSync(join(root, p), 'utf8');
    const manifestPaths = {};
    for (const f of ls('*META-INF/inspecto/module.toon')) manifestPaths[parseToon(read(f)).id] = f;
    const items = {};
    for (const f of ls('product-map/module/*.toon')) items[f.replace(/^product-map\//, '').replace(/\.toon$/, '')] = read(f);
    const hs = hubs(items, manifestPaths, ls('*/src/test/*Test.java', '*/src/test/*IT.java'), read);
    const want = {};
    for (const [k, h] of Object.entries(hs)) want[join(root, 'docs/okf/product', k, 'tests-inventory.md')] = toMarkdown(h);
    const tools = ls('tools/*.test.mjs').map((f) => toolTest(f, read(f)));
    if (tools.length) want[join(root, 'docs/okf/product/layer/platform/tests-tools-inventory.md')] = toolsMarkdown(tools);
    let stale = 0;
    for (const [path, text] of Object.entries(want)) {
        if (existsSync(path) && readFileSync(path, 'utf8') === text) continue;
        stale++;
        if (check) { console.error(`  stale: ${path}`); continue; }
        writeFileSync(path, text);
    }
    const hubRoot = join(root, 'docs/okf/product');
    for (const kind of existsSync(hubRoot) ? readdirSync(hubRoot, { withFileTypes: true }).filter((d) => d.isDirectory()) : [])
        for (const n of readdirSync(join(hubRoot, kind.name), { withFileTypes: true }).filter((d) => d.isDirectory()))
            for (const name of ['tests-inventory.md', 'tests-tools-inventory.md']) {
                const path = join(hubRoot, kind.name, n.name, name);
                if (!existsSync(path) || path in want || !readFileSync(path, 'utf8').includes(GEN)) continue;
                stale++;
                if (check) console.error(`  stale (orphan): ${path}`); else rmSync(path);
            }
    const classes = Object.values(hs).reduce((a, h) => a + h.rows.length, 0);
    console.log(`render-tests-inventory: ${classes} test class(es) in ${Object.keys(hs).length} hub(s), ${tools.length} tool test(s); ${stale} file(s) ${check ? 'stale' : 'written'}`);
    if (check && stale) process.exit(1);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
