#!/usr/bin/env node
// SPEC-COUNTS-1 — "a count is stated N ways". Kills the CLASS, not the instances.
//
// The instances were already repaired by the 17-area consolidation (node types 30, processors 119,
// transform functions 23, step types 16). Nothing held them there: each of those numbers is restated
// by hand in two to eight current documents, and every restatement is a fresh chance to drift. This
// guard makes each statement DERIVED — the committed contract that owns a number is the only source,
// parsed at run time, never mirrored here.
//
// 🔴 THE DESIGN THIS DELIBERATELY IS NOT. The obvious guard scans prose for numbers near a noun. I
// built that as a census first and it is unshippable, which is worth recording so nobody rebuilds it:
// over 238 current docs it matched 14 lines, and the "failures" were a line reference
// (`Roles.java:121-131`, `mapping-editor.dialog.ts:30,121-128`), a sentence about one specific node
// type ("One node type is executable and unreachable"), and correction notes that name the old figure
// on purpose. It also MISSED real phrasings ("the 119-entry catalog", "119 entries, 8 families").
// A number in prose is indistinguishable from a line number, a version or a date fragment, so that
// guard is mostly exemption — the shape this repo has recorded as a failure three times over. The
// invariant the repository states about ITSELF is different and exact: a contract owns the number.
//
// Exemptions: NONE, by construction. A marker either resolves and matches, or the guard fails.
//
// Usage:  node tools/check-doc-counts.mjs
// Marker: <!--count:ID--> placed directly after the number, e.g.  **119**<!--count:processors-->

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, sep, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CONTRACTS = 'inspecto-ui/src/app/inspecto/contracts';

// ── the manifest: id -> how to DERIVE it from the repo. Never a literal. ─────────────────────────
// `floor` is the number of marked statements that must exist, and it is set EQUAL to the number
// currently marked — deliberately, not lazily. It is a ratchet, not a nicety: adding a marked
// statement is free, removing one must be a decision someone writes down here.
// 🔴 I first set these floors BELOW the marked counts and the falsification caught it: deleting a
// marker left 2 of 3 and the guard went green. Slack in a floor is exactly where a marker
// disappears unnoticed — the "when a guard stops reporting something, prove it was FIXED and not
// merely EXCLUDED" lesson, which this guard nearly reproduced.
const MANIFEST = {
    'processors': {
        floor: 8,
        what: 'Step Processor catalog entries',
        derive: () => json(`${CONTRACTS}/processor-catalog.contract.json`).processors.length,
        source: `${CONTRACTS}/processor-catalog.contract.json`,
    },
    // The status SPLIT is a count too — "34 delivered / 18 partial" went stale in three docs the day one
    // processor flipped, and the total-only marker above could not see it. `planned` is the remainder.
    'processors-delivered': {
        floor: 3,
        what: 'Step Processors with status delivered',
        derive: () => json(`${CONTRACTS}/processor-catalog.contract.json`).processors
            .filter(p => p.status === 'delivered').length,
        source: `${CONTRACTS}/processor-catalog.contract.json`,
    },
    'processors-partial': {
        floor: 3,
        what: 'Step Processors with status partial',
        derive: () => json(`${CONTRACTS}/processor-catalog.contract.json`).processors
            .filter(p => p.status === 'partial').length,
        source: `${CONTRACTS}/processor-catalog.contract.json`,
    },
    'processor-families': {
        floor: 1,
        what: 'Step Processor families',
        derive: () => json(`${CONTRACTS}/processor-catalog.contract.json`).families.length,
        source: `${CONTRACTS}/processor-catalog.contract.json`,
    },
    // ⚠ Named for the SET, not the noun. "transform functions" denotes TWO unrelated registries:
    // this 23-entry ETL/mapping catalog, and the 30 legacy ASN vendor functions registered through
    // `TransformFunctionProvider` (`LegacyVendorFunctions`, which reaches no bundle). They are not
    // reconcilable into one number, and a marker called `transform-functions` would re-create exactly
    // the ambiguity this guard exists to end. Ambiguity in the NOUN is the root cause of the class:
    // "node types" meant three different sets before anyone noticed the counts disagreed.
    'sql-mapping-functions': {
        floor: 2,
        what: 'SQL functions the mapping grid offers (NOT the 30 ASN vendor functions)',
        derive: () => json(`${CONTRACTS}/sql-functions.contract.json`).length,
        source: `${CONTRACTS}/sql-functions.contract.json`,
    },
    'step-types': {
        floor: 5,
        what: 'Recipe step-type entries (over 9 verbs)',
        derive: () => json(`${CONTRACTS}/step-types.contract.json`).length,
        source: `${CONTRACTS}/step-types.contract.json`,
    },
    'node-types': {
        floor: 3,
        what: 'built-in node types',
        // No contract owns the roster — `node-attributes.contract.json` holds only the types that
        // HAVE attribute specs (11 of them), which is the very ambiguity that made "node types" a
        // five-way count. The enum is the roster, so parse the enum.
        derive: () => {
            const src = read('inspecto-engine/src/main/java/com/gamma/pipeline/BuiltinNodeType.java');
            const body = src.split('implements PipelineNodeType {')[1];
            if (!body) throw new Error('BuiltinNodeType: enum body not found — re-anchor this parse');
            const m = body.match(/^ {4}[A-Z][A-Z0-9_]*\("[^"]+"/gm) || [];
            return m.length;
        },
        source: 'inspecto-engine/src/main/java/com/gamma/pipeline/BuiltinNodeType.java',
    },
    'node-types-with-attributes': {
        floor: 1,
        what: 'node types carrying an attribute spec (NOT the roster — see node-types)',
        derive: () => Object.keys(json(`${CONTRACTS}/node-attributes.contract.json`))
            .filter(k => !k.startsWith('_')).length,
        source: `${CONTRACTS}/node-attributes.contract.json`,
    },
    // ── "parser frontends" — the loudest instance of the class: stated as 3 / 5 / 6 / 7 / 9 / 10.
    // ⚠ Not one wrong number: FOUR sets sharing one noun. Each derivable set gets its own id; the one
    // that is a prose taxonomy (the three byte→row MECHANISMS: DuckDB-native read, read_text + SQL,
    // the StreamingFileIngester plugin) has no owner in code and is deliberately not here.
    // "Eight formats" (= the ten tokens minus two aliases) is not derivable either: the aliases live in
    // two `equals` calls, not a structure, so a guard would have to mirror them — write "ten tokens"
    // with the marker and let the prose bound "eight" to it.
    'parsing-frontend-tokens': {
        floor: 5,
        what: 'parsing.frontend tokens accepted at config load (aliases counted: fixed_width, excel)',
        derive: () => javaSetOf('inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java',
            'FRONTENDS'),
        source: 'inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java',
    },
    'builtin-parsers': {
        floor: 3,
        what: 'DuckDB-native built-in parsers served by GET /parsers (NOT the plugin ids, NOT the tokens)',
        derive: () => javaSetOf('inspecto-engine/src/main/java/com/gamma/parse/BuiltinParsers.java', 'IDS'),
        source: 'inspecto-engine/src/main/java/com/gamma/parse/BuiltinParsers.java',
    },
    'parser-node-types': {
        floor: 4,
        what: 'parser.* node types in the step catalog (bare `parser` excluded)',
        derive: () => json(`${CONTRACTS}/step-types.contract.json`)
            .filter(e => String(e.type).startsWith('parser.')).length,
        source: `${CONTRACTS}/step-types.contract.json`,
    },
    // A FIFTH set, found while placing the markers above: the frontends the UI ships its OWN schema-form
    // specs for (plugin parsers render the served `grammarSchema` instead). The union type is the owner;
    // its neighbouring comment and `duckdb.md` both said "four" — stale since `xlsx` gained an arm.
    'ui-specced-frontends': {
        floor: 1,
        what: 'built-in frontends the UI carries its own schema-form specs for (the ParsingFrontend union)',
        derive: () => {
            const src = read('inspecto-ui/src/app/inspecto/grammar/parsing-attributes.ts');
            const m = src.match(/export type ParsingFrontend = ([^;]+);/);
            if (!m) throw new Error('ParsingFrontend: union type not found — re-anchor this parse');
            return (m[1].match(/'[a-z_]+'/g) || []).length;
        },
        source: 'inspecto-ui/src/app/inspecto/grammar/parsing-attributes.ts',
    },
};

/** Count the string literals in a Java `Set.of("a", "b", …)` field. Re-anchor here if the field moves. */
function javaSetOf(rel, field) {
    const src = read(rel);
    const m = src.match(new RegExp(`\\b${field}\\s*=\\s*Set\\.of\\(([^)]*)\\)`));
    if (!m) throw new Error(`${field}: Set.of(...) literal not found — re-anchor this parse`);
    return (m[1].match(/"[^"]+"/g) || []).length;
}

// ── DELIBERATELY NOT IN THE MANIFEST, and this is a measurement result rather than an omission ──
// `job types` (12) and `maintenance tasks` (24) are two of the six counts SPEC-COUNTS-1 named, and
// both are left out ON PURPOSE:
//
//   * Neither has a committed contract. Both are assembled from two sources — a built-in list plus
//     whatever `ServiceLoader` finds — so a script would have to enumerate every
//     `META-INF/services/…` file across modules and read each provider's returned Set.
//   * ⛔ More decisively, both totals are EDITION-DEPENDENT: job types are 10 on Personal and 12 once
//     `inspecto-ops` is bundled; maintenance tasks are 20 built-in ids (across 19 switch arms) plus 4
//     contributed by `inspecto-backup`/`inspecto-ops`. "The count" does not exist until you fix which
//     modules are on the classpath, so a guard asserting ONE number would be asserting a falsehood —
//     and it would do so in the name of ending wrong counts.
//   * ⚠ `JobType` (the enum) has 4 constants and is `@Deprecated`. Deriving from it would reproduce
//     one of the wrong numbers the docs already state.
//
// A doc that states either number must therefore say WHICH shape it means. That is a writing rule,
// not something a guard can settle — a guard measures; it must not decide.

function read(rel) {
    const p = join(ROOT, rel);
    if (!existsSync(p)) throw new Error(`derive source is missing: ${rel}`);
    return readFileSync(p, 'utf8');
}
function json(rel) { return JSON.parse(read(rel)); }

// ── scope: the current tier. `docs/archived-documents/` is never maintained, so a marker there
// would be a standing failure by policy; everything else that is maintained is in scope. ─────────
const TREES = ['docs', 'compliance', '.claude'];
const EXEMPT = ['docs/archived-documents'];

function walk(dir, out = []) {
    let entries;
    try { entries = readdirSync(dir); } catch { return out; }
    for (const e of entries) {
        if (e === 'node_modules' || e === '.git' || e === 'worktrees') continue;
        const p = join(dir, e);
        let st; try { st = statSync(p); } catch { continue; }
        if (st.isDirectory()) walk(p, out);
        else if (e.endsWith('.md')) out.push(p);
    }
    return out;
}

const rel = f => relative(ROOT, f).split(sep).join('/');
const all = [];
for (const t of TREES) all.push(...walk(join(ROOT, t)));
for (const e of readdirSync(ROOT)) if (e.endsWith('.md')) all.push(join(ROOT, e));
const files = all.filter(f => !EXEMPT.some(x => rel(f).startsWith(x + '/')));

// ── derive first, so a broken parse fails loudly rather than comparing against NaN ──────────────
const derived = {};
const failures = [];
for (const [id, spec] of Object.entries(MANIFEST)) {
    try {
        const n = spec.derive();
        if (!Number.isInteger(n) || n <= 0) throw new Error(`derived a non-count: ${n}`);
        derived[id] = n;
    } catch (e) {
        failures.push(`  cannot derive '${id}' from ${spec.source}\n      ${e.message}`);
    }
}

// ── scan markers ────────────────────────────────────────────────────────────────────────────────
const MARKER = /<!--\s*count:([a-z0-9-]+)\s*-->/g;
const seen = Object.fromEntries(Object.keys(MANIFEST).map(k => [k, 0]));
let markers = 0;

for (const f of files) {
    const lines = readFileSync(f, 'utf8').split('\n');
    lines.forEach((line, i) => {
        let m;
        MARKER.lastIndex = 0;
        while ((m = MARKER.exec(line))) {
            markers++;
            const id = m[1];
            const at = `${rel(f)}:${i + 1}`;
            if (!(id in MANIFEST)) {
                failures.push(`  ${at}  unknown count id '${id}' — add it to the manifest or fix the marker`);
                continue;
            }
            seen[id]++;
            // the number is the last one before the marker on this line
            const before = line.slice(0, m.index);
            const nums = before.match(/\d+/g);
            if (!nums) {
                failures.push(`  ${at}  marker 'count:${id}' has no number before it on the line`);
                continue;
            }
            const stated = +nums[nums.length - 1];
            if (derived[id] !== undefined && stated !== derived[id]) {
                failures.push(`  ${at}  states ${stated}, but ${MANIFEST[id].source} derives `
                    + `${derived[id]} (${MANIFEST[id].what})`);
            }
        }
    });
}

for (const [id, spec] of Object.entries(MANIFEST)) {
    if (seen[id] < spec.floor) {
        failures.push(`  scope floor: 'count:${id}' is marked ${seen[id]}x, floor is ${spec.floor} — `
            + `a deleted marker must FAIL, not silently narrow this guard`);
    }
}

// ── report. The figures print on every run, pass or fail: a guard that reports nothing when green
// tells you nothing about what it declined to look at. ──────────────────────────────────────────
const tally = Object.entries(MANIFEST)
    .map(([id, s]) => `${id}=${derived[id] ?? '??'} (${seen[id]} marked, floor ${s.floor})`)
    .join(' · ');

if (failures.length) {
    console.error(`✗ Doc-count guard: ${failures.length} problem(s)\n`);
    console.error(failures.join('\n'));
    console.error(`\n  derived: ${tally}`);
    console.error(`  scope: ${files.length} current-tier markdown file(s) (${all.length} total; `
        + `${EXEMPT.join(', ')} exempt as never-maintained), ${markers} marker(s), NO exemptions by design\n`);
    console.error('  Fix the DOC, or the contract — not this guard. A count in a doc must equal what');
    console.error('  its owning contract derives. If a line is deliberately recording an OLD figure,');
    console.error('  do not mark it: markers assert the current value.\n');
    process.exit(1);
}

console.log(`✓ Doc-count guard: ${markers} marked count statement(s) all match what their owning `
    + `contract derives — ${tally}; scope: ${files.length} current-tier markdown file(s) `
    + `(${all.length} total; ${EXEMPT.join(', ')} exempt as never-maintained); NO exemptions by design.`);
