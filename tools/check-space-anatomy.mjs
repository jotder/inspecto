#!/usr/bin/env node
/**
 * Space-anatomy guard — `docs/okf/product/module/config/architecture-space-anatomy.md` must agree with the code
 * that decides what a Space folder holds.
 *
 * WHY THIS EXISTS. The page is a reference of file names, directory names and reference keys. Each of them is a
 * constant in one Java class; a page that restates constants rots the day one is added. The link and citation
 * guards prove paths, not that the page still lists every reserved file or that a key it names is still read.
 *
 * ── CHECKS (each count is printed on every run) ──────────────────────────────────────────────
 *
 *   A. COMPLETE — every name the code declares is on the page:
 *        ReservedConfigPaths.FILES / DIRS · ComponentRegistry directory map · the DuckDB files of SpaceRoot ·
 *        ImportPaths ROOT_SUFFIXES + REFUSED_SUFFIXES + `_view.toon`.
 *   B. NOT INVENTED — every name in the page's three census tables is declared by the code:
 *        "Fixed-name Space settings" (first column) ⊆ FILES · "Registry components" (second column) ⊆ the
 *        directory map · "`duckdb/`" (first column) ⊆ SpaceRoot's files.
 *   C. (retired 2026-11-13, LC-7) — the "Every link" table is generated from LinkCensus.LINKS by
 *        tools/render-space-links.mjs, whose --check runs in CI; a key there is a census key by construction.
 *   D. MEMBERS — every `Class.member` the page names in backticks: `Class.java` is tracked and contains `member`.
 *
 * ── SCOPE ────────────────────────────────────────────────────────────────────────────────────
 *
 *   - One page. Its PROSE is not judged (what a file means), only the names above.
 *   - D is evidence, not proof: a literal or a member name existing does not prove the described behaviour.
 *
 * Pure Node, no dependencies. Run by `.github/workflows/ci.yml` (guards job).
 */
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const DOC = 'docs/okf/product/module/config/architecture-space-anatomy.md';
const read = (rel) => readFileSync(join(repoRoot, rel), 'utf8');

const javaFiles = execFileSync('git', ['ls-files', '*.java'], { cwd: repoRoot, encoding: 'utf8', maxBuffer: 1 << 26 })
    .split('\n').filter(Boolean);
const mainJava = javaFiles.filter((f) => f.includes('/src/main/'));

const doc = read(DOC);
const failures = [];
const fail = (check, msg) => failures.push(`  [${check}] ${msg}`);

/** Quoted strings inside the first `<anchor> ... );` block of a source. */
function literalsAfter(src, anchor) {
    const start = src.indexOf(anchor);
    if (start < 0) throw new Error(`anchor not found: ${anchor}`);
    const end = src.indexOf(');', start);
    return [...src.slice(start, end).replace(/\/\/.*$/gm, '').matchAll(/"([^"]+)"/g)].map((m) => m[1]);
}

// ── What the code declares ────────────────────────────────────────────────────────────────────
const reserved = read('inspecto/src/main/java/com/gamma/service/ReservedConfigPaths.java');
const FILES = literalsAfter(reserved, 'FILES = Set.of(');
const DIRS = literalsAfter(reserved, 'DIRS = List.of(');
const registry = read('platform/inspecto-engine/src/main/java/com/gamma/pipeline/ComponentRegistry.java');
const REGISTRY_DIRS = [...registry.matchAll(/Map\.entry\("([a-z-]+)",\s*"[a-z-]+"\)/g)].map((m) => m[1]);
const spaceRoot = read('inspecto/src/main/java/com/gamma/service/SpaceRoot.java');
const DB_FILES = [...new Set([...spaceRoot.matchAll(/duckdb\("([^"]+)"\)/g)].map((m) => m[1]))];
const imports = read('inspecto/src/main/java/com/gamma/service/ImportPaths.java');
const SUFFIXES = [...new Set([...literalsAfter(imports, 'ROOT_SUFFIXES = List.of('),
    ...literalsAfter(imports, 'REFUSED_SUFFIXES = List.of('), '_view.toon'])];

for (const [label, set] of [['FILES', FILES], ['DIRS', DIRS], ['registry dirs', REGISTRY_DIRS],
    ['DuckDB files', DB_FILES], ['suffixes', SUFFIXES]]) {
    if (set.length === 0) fail('A', `read no ${label} from the code — the guard's own parse broke`);
}

// ── A. complete ───────────────────────────────────────────────────────────────────────────────
const inDoc = (name) => doc.includes(name);
for (const f of FILES) if (!inDoc('`' + f + '`')) fail('A', `reserved file \`${f}\` (ReservedConfigPaths.FILES) is not on the page`);
for (const d of DIRS) if (!inDoc(d)) fail('A', `reserved directory \`${d}\` (ReservedConfigPaths.DIRS) is not on the page`);
for (const d of REGISTRY_DIRS) if (!inDoc('`' + d + '`')) fail('A', `registry directory \`${d}\` (ComponentRegistry) is not on the page`);
for (const f of DB_FILES) if (!inDoc('`' + f + '`')) fail('A', `DuckDB file \`${f}\` (SpaceRoot) is not on the page`);
for (const s of SUFFIXES) if (!inDoc(s)) fail('A', `config suffix \`${s}\` (ImportPaths) is not on the page`);

// ── B. not invented ───────────────────────────────────────────────────────────────────────────
/** Table rows (cells) of the first section whose heading contains `title`. */
function tableRows(title) {
    const lines = doc.split('\n');
    const at = lines.findIndex((l) => /^#{2,4} /.test(l) && l.includes(title));
    if (at < 0) { fail('B', `the page has no section titled "${title}" — rename the guard's anchor with it`); return []; }
    const rows = [];
    for (let i = at + 1; i < lines.length && !/^#{2,4} /.test(lines[i]); i++) {
        if (lines[i].startsWith('|') && !/^\|\s*-/.test(lines[i])) rows.push(lines[i].split('|').slice(1, -1));
    }
    return rows.slice(1);   // drop the header row
}
const ticks = (cell) => [...(cell ?? '').matchAll(/`([^`]+)`/g)].map((m) => m[1]);
let censusNames = 0;
for (const row of tableRows('Fixed-name Space settings'))
    for (const t of ticks(row[0])) { censusNames++; if (!FILES.includes(t)) fail('B', `fixed-name file \`${t}\` is not in ReservedConfigPaths.FILES`); }
for (const row of tableRows('Registry components'))
    for (const t of ticks(row[1])) { censusNames++; if (!REGISTRY_DIRS.includes(t)) fail('B', `registry directory \`${t}\` is not in ComponentRegistry`); }
for (const row of tableRows('`duckdb/`'))
    for (const t of ticks(row[0])) { censusNames++; if (!DB_FILES.includes(t)) fail('B', `DuckDB file \`${t}\` is not declared by SpaceRoot`); }

// ── D. Class.member mentions ──────────────────────────────────────────────────────────────────
const byClass = new Map();
for (const f of javaFiles) {
    const c = basename(f, '.java');
    if (!byClass.has(c)) byClass.set(c, []);
    byClass.get(c).push(f);
}
const NOT_MEMBERS = new Set(['md', 'java', 'toon', 'json', 'mjs']);
const members = new Set();
for (const m of doc.matchAll(/`([A-Z][A-Za-z0-9]+)\.([A-Za-z_][A-Za-z0-9_]*)(?:\(\))?`/g))
    if (!NOT_MEMBERS.has(m[2])) members.add(m[1] + '.' + m[2]);
for (const cm of members) {
    const [cls, member] = cm.split('.');
    const files = byClass.get(cls);
    if (!files) { fail('D', `\`${cm}\`: no tracked ${cls}.java`); continue; }
    if (!files.some((f) => read(f).includes(member))) fail('D', `\`${cm}\`: ${files[0]} has no \`${member}\``);
}

// ── Verdict ───────────────────────────────────────────────────────────────────────────────────
const scope = `scope: ${DOC} against ${FILES.length} reserved file(s), ${DIRS.length} reserved dir(s), `
    + `${REGISTRY_DIRS.length} registry dir(s), ${DB_FILES.length} DuckDB file(s), ${SUFFIXES.length} suffix(es); `
    + `${censusNames} census-table name(s) checked back; `
    + `${members.size} Class.member mention(s); prose not judged`;
if (failures.length) {
    console.error(`\n✖ Space-anatomy guard: ${failures.length} mismatch(es) between the page and the code.\n`);
    console.error(failures.join('\n'));
    console.error(`\n  ${scope}\n`);
    process.exit(1);
}
console.log(`✓ Space-anatomy guard: the page lists what the code declares, and nothing else — ${scope}.`);
