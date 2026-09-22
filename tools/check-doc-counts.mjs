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
// Exemptions: NONE, by construction — a marker either resolves and matches, or the guard fails. The ONE
// thing not scanned is a fenced block, and that is not an exemption but a SCOPE call: text in a fence is a
// marker being SHOWN, not one being asserted. See `DOC-COUNTS-FENCED-MARKER-1` at the scan loop below.
//
// Usage:  node tools/check-doc-counts.mjs
// Marker: <!--count:ID--> placed directly after the number, e.g.  **119**<!--count:processors-->

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { bundleModules, editionOnlyModules } from './bundle-modules.mjs';
import { join, relative, sep, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { trackedPaths } from './tracked-paths.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CONTRACTS = 'inspecto-ui/src/app/inspecto/contracts';
const BACKLOG = 'docs/BACKLOG.md';

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
    // ── SPEC-COUNTS-1's last hand-typed count, closed 2026-09-15: `tools/bundle-modules.mjs` is the one
    // module list sbom.mjs and check-sbom-modules.mjs both read (the dependency count was already derived —
    // `locked-dependencies` below). ⚠ Named for the SET: "staged jars" was stated eight ways because it
    // silently meant different sets (first-party only vs + the PG sidecar; Professional vs Enterprise).
    'optional-modules': {
        floor: 2,
        what: 'first-party modules an edition can add beyond Personal (Professional + Enterprise floors)',
        derive: () => editionOnlyModules('Enterprise').length,
        source: 'tools/bundle-modules.mjs',
    },
    'enterprise-first-party-jars': {
        floor: 1,
        what: 'first-party jars the Enterprise bundle stages (NOT counting the postgresql sidecar)',
        derive: () => bundleModules('Enterprise').length,
        source: 'tools/bundle-modules.mjs',
    },
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
    'spi-extension-points': {
        floor: 1,
        what: 'distinct SPI interfaces a plugin jar can implement — every class the core discovers through '
            + 'ServiceLoader.load(X.class), OptionalSpi.all(X.class) or a SpiSlot(X.class) in inspecto*/src/main',
        derive: () => {
            const re = /(?:ServiceLoader\s*\.\s*load|OptionalSpi\s*\.\s*all|SpiSlot\s*(?:\.\s*\w+)?\s*(?:<[^>]*>)?)\s*\(\s*([A-Za-z_][\w.]*)\s*\.class/g;
            const names = new Set();
            for (const f of javaMainFiles()) {
                const src = read(f);
                for (const m of src.matchAll(re)) {
                    const n = m[1].split('.').pop();
                    if (n.length > 1) names.add(n);          // `X.class` in a generic helper is not a seam
                }
            }
            if (names.size === 0) throw new Error('spi-extension-points: no loader call sites found — re-anchor this parse');
            return names.size;
        },
        source: 'inspecto*/src/main (ServiceLoader / OptionalSpi / SpiSlot call sites)',
    },
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
    // SPEC-COUNTS-1's last unguarded count, and it had drifted TWO ways at once (2026-09-15): four prose
    // sites said 94 and two said 95 while the lock held 96. A generated artifact already existed, so per
    // that row's own rule this is a marker, not six hand-edits — the hand-edits are what produced two
    // wrong numbers from one right one.
    // ⚠ Counts the LOCK, not a `mvn dependency:list` run: the lock is the committed baseline a reviewer
    // diffs, it is what `check-dependencies.mjs` enforces, and it needs no network or build to read.
    // -- The BACKLOG rank census -- "N rows: A x P1 / B x P2 / C x P3", stated twice on one page and
    // drifted THREE times (2026-09-14, 2026-09-15, corrected 2026-09-16 after standing wrong by 18 P2
    // rows for two days). The two statements had also drifted INDEPENDENTLY of each other, which is the
    // same class this guard exists for: the rows themselves are the contract, so derive from them.
    // /!\ SELF-REFERENCE HAZARD, and it is the failure shape this file's header records (a guard that
    // counted its own documentation). The file being counted also CARRIES the markers, and section 0's
    // "Rules of use" quotes these very grep patterns literally. Two things keep the derive off its own
    // documentation:
    //   * the slice -- only lines strictly between the `## 3.` and `## 6.` headings are counted, and
    //     section 0 (which quotes the patterns) and the census block (which carries the markers) both
    //     live ABOVE `## 3.`;
    //   * the anchors -- a quoted pattern sits inside a `> ` blockquote and backticks, so it cannot
    //     match a `^- **P...` row anchor even if the slice ever moved.
    // THE P3 PATTERN IS DELIBERATELY LOOSER than the other two: one row spells its rank
    // `- **P3 . RELEASE-GATED (next MAJOR), not demand-gated**`, and a strict `^- \*\*P3\*\*`
    // silently undercounts by one. Do not "tidy" it.
    // `zeroOk`: a RANK bucket may legitimately be empty — the board reached ZERO P1s on 2026-09-17 and this guard
    // failed in the direction of FAILING ("derived a non-count: 0") on the best state it can report. The emptiness
    // floor still holds where it belongs: `backlog-rows` (a scan matching nothing) keeps the `n <= 0` rule.
    'backlog-p1': { floor: 2, zeroOk: true, what: 'P1 rows on the board (sections 3-5)', derive: () => backlogRanks().p1, source: BACKLOG },
    'backlog-p2': { floor: 2, zeroOk: true, what: 'P2 rows on the board (sections 3-5)', derive: () => backlogRanks().p2, source: BACKLOG },
    'backlog-p3': { floor: 2, zeroOk: true, what: 'P3 rows on the board (sections 3-5)', derive: () => backlogRanks().p3, source: BACKLOG },
    // Derived, never summed by hand -- and cross-checked inside backlogRanks() against a rank-agnostic
    // count, so a row spelled with an unrecognised rank FAILS rather than silently vanishing.
    'backlog-rows': { floor: 2, what: 'P-ranked rows on the board (sections 3-5)', derive: () => backlogRanks().total, source: BACKLOG },
    'locked-dependencies': {
        floor: 4,
        what: 'third-party artifacts in the committed dependency lock',
        derive: () => read('tools/dependencies.lock')
            .split('\n').filter(l => l.trim() && !l.trim().startsWith('#')).length,
        source: 'tools/dependencies.lock',
    },
};


/**
 * Count the board's rows by rank, using section 0's OWN authoritative patterns, over the slice between
 * the `## 3.` and `## 6.` headings. See the manifest comment for why the slice -- rather than a
 * whole-file grep -- is what keeps this guard from counting its own documentation.
 */
function backlogRanks() {
    const lines = read(BACKLOG).split('\n');
    const from = lines.findIndex(l => /^## 3\./.test(l));
    const to = lines.findIndex(l => /^## 6\./.test(l));
    if (from < 0 || to < 0 || to <= from) {
        throw new Error('BACKLOG.md: the `## 3.` / `## 6.` headings did not bound a slice -- re-anchor this parse');
    }
    const rows = lines.slice(from + 1, to);
    const n = re => rows.filter(l => re.test(l)).length;
    const p1 = n(/^- \*\*P1\*\*/), p2 = n(/^- \*\*P2\*\*/), p3 = n(/^- \*\*P3( |\*)/);
    const total = n(/^- \*\*P/);
    if (p1 + p2 + p3 !== total) {
        throw new Error(`BACKLOG.md: ${total} P-ranked rows but P1+P2+P3 = ${p1 + p2 + p3} -- a row `
            + "spells its rank in a way section 0's patterns do not match; fix the ROW, not this parse");
    }
    return { p1, p2, p3, total };
}

/** Every .java under an inspecto* module's src/main, repo-relative. */
function javaMainFiles(out = []) {
    for (const mod of readdirSync(ROOT)) {
        if (!mod.startsWith('inspecto')) continue;
        const main = join(ROOT, mod, 'src', 'main');
        (function rec(d) {
            let es; try { es = readdirSync(d); } catch { return; }
            for (const e of es) {
                const p = join(d, e);
                let st; try { st = statSync(p); } catch { continue; }
                if (st.isDirectory()) rec(p);
                else if (e.endsWith('.java')) out.push(relative(ROOT, p).split(sep).join('/'));
            }
        })(main);
    }
    return out;
}

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

// ── scope: the WHOLE repository, as a DENY-list (`SKIP_DIRS`), not an allow-list of trees. ───────
//
// ⛔ Until 2026-09-17 this read `['docs', 'compliance', '.claude']` + a root `*.md` pass — the SAME
// allow-list whose twin in `check-doc-links.mjs` was the bug behind `README-LINKS-BROKEN-IN-REPO-1`
// (`inspecto/README.md`, the customer's first page in the bundle, carried 29 dead links while the
// guard stayed green because `inspecto/` was in none of its three roots). That guard was fixed at
// the SHAPE on 2026-09-16; this one kept the pre-fix scope for a day. `DOC-COUNTS-GUARD-SCOPE-1`.
//
// An allow-list answers "did we remember to add this tree?"; a deny-list answers "is there a reason
// to skip this tree?" — and only the second fails loudly when someone adds a fourth doc-bearing
// directory. Widening cost NO findings and no noise: it took the walk from 493 markdown files to
// 529, and the 36 it gained (`asn-parser/docs/` ×10, `inspecto-agent/docs/` ×14, `inspecto/README.md`,
// `inspecto-ui/README.md`, `tools/templates/*/README.md`, …) carry ZERO `<!--count:*-->` markers
// today — measured, not assumed. The point is that a marker placed in one of them is now POLICED
// instead of silently unguarded.
const ROOTS = ['.'];

/**
 * Never walked: build output and the git worktrees, which contain whole second copies of the repo.
 * Copied from `check-doc-links.mjs`, and every entry was re-checked against THIS guard rather than
 * assumed to transfer — two of them carry real markers:
 *   - `graphify-out` holds dated GRAPH_REPORT snapshots with **8** `<!--count:parser-node-types-->`
 *     markers frozen at whatever the contract derived on the day each snapshot was taken;
 *   - `inspecto-deploy` is the packaged BUNDLE — gitignored build output carrying a stale COPY of the
 *     whole docs tree: 485 markdown files and **59** markers in this checkout.
 * Both are generated artifacts nobody edits, so policing them would fail the build over a stale copy
 * and teach the next shift to ignore this guard. `dist`/`target` hold no markdown at all today and are
 * kept for parity with the sibling.
 * ⛔ Neither is present in a FRESH CLONE (or a fresh worktree), so a widening verified only there looks
 * green and goes red the moment it meets a checkout that has ever built a bundle or run `graphify`.
 * That is exactly how the sibling's own widening was nearly shipped blind — verify against a built tree.
 */
const SKIP_DIRS = new Set(['node_modules', '.git', 'worktrees', 'dist', 'target', 'graphify-out',
                           'inspecto-deploy']);

// The archive is exempt ENTIRELY here, unlike in `check-doc-links.mjs` where it is exempt only as a
// link SOURCE: that tier is never maintained (CLAUDE.md doc-lifecycle §3), so a marker inside it would
// be a standing failure by policy rather than a drift anyone is expected to fix.
const EXEMPT = ['docs/archived-documents'];


/*
 * ⛔ TRACKED markdown only. A document this checkout does not track is not a current doc, and it must
 * not be able to refuse an unrelated lane's push in a shared sandbox.
 * `DOC-GUARDS-SCAN-IGNORED-SOURCES-1`: three guards collected their SUBJECTS with a filesystem walk,
 * so another session's gitignored `*.local.md` — rewritten by a stop hook, and unfixable by its own
 * rules because the correction does not survive the hook — made the gate red for one shift and green
 * for another on the same commit, which is the property a gate exists to deny.
 * The target side already moved to `git ls-files` (`tracked-paths.mjs`, LINKGUARD-CASE-1); this is the
 * same rule applied to the subject side, as guard-coverage.md §"A fourth shape" already prescribes.
 * ⚠ What this gives up, stated plainly: a NEW doc goes unchecked until it is `git add`ed.
 */
function walk(dir, out = []) {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return out; }
    for (const e of entries) {
        if (e.isDirectory() && SKIP_DIRS.has(e.name)) continue;
        const p = join(dir, e.name);
        let st; try { st = statSync(p); } catch { continue; }
        if (st.isDirectory()) walk(p, out);
        else if (e.name.endsWith('.md')) out.push(p);
    }
    return out;
}

const rel = f => relative(ROOT, f).split(sep).join('/');
// The `.` walk subsumes what used to be a separate root-level `*.md` pass.
const all = [];
for (const t of ROOTS) all.push(...walk(join(ROOT, t)));
const trackedMd = new Set(trackedPaths());
const untrackedSkipped = all.map(rel).filter(r => !trackedMd.has(r));
const tracked = all.filter(f => trackedMd.has(rel(f)));
const files = tracked.filter(f => !EXEMPT.some(x => rel(f).startsWith(x + '/')));

// ── derive first, so a broken parse fails loudly rather than comparing against NaN ──────────────
const derived = {};
const failures = [];
for (const [id, spec] of Object.entries(MANIFEST)) {
    try {
        const n = spec.derive();
        if (!Number.isInteger(n) || n < 0 || (n === 0 && !spec.zeroOk)) throw new Error(`derived a non-count: ${n}`);
        derived[id] = n;
    } catch (e) {
        failures.push(`  cannot derive '${id}' from ${spec.source}\n      ${e.message}`);
    }
}

// ── scan markers ────────────────────────────────────────────────────────────────────────────────
// ⛔ Anything inside a fenced block (``` or ~~~) is QUOTED TEXT, not an assertion — `DOC-COUNTS-FENCED-MARKER-1`.
// Found by HITTING it: the section recording `DOC-COUNTS-GUARD-SCOPE-1` could not show a marker by example, so
// it had to write the id as `*` to dodge the scan. Widening the scope to the whole repo widened that trap to
// every markdown file in it. `check-doc-links.mjs`, `check-doc-citations.mjs` and `check-bundle-doc-links.mjs`
// all strip fences with exactly this idiom, for exactly this reason; this guard was the odd one out.
// ⚠ INLINE `` `code` `` IS DELIBERATELY NOT STRIPPED, and that is a separate decision from the fence — they
// merely arrived in one sentence of the row. `check-vocabulary.mjs` strips inline spans; `check-doc-citations.mjs`
// requires them. Here the asymmetry is the FLOOR: floors now sit below the marked counts (68 markers, floors
// summing to 55), so a live marker hidden by one stray backtick would NOT trip the ratchet — it would just stop
// being policed, silently, which is the failure shape this file's own header records. Measured: ZERO of the 68
// markers sit inside inline backticks today, so stripping them would buy nothing and risk exactly that. A doc
// showing a marker by example puts it in a FENCE.
const MARKER = /<!--\s*count:([a-z0-9-]+)\s*-->/g;
const seen = Object.fromEntries(Object.keys(MANIFEST).map(k => [k, 0]));
let markers = 0;
let quoted = 0;   // markers skipped inside a fence — printed, because a silent exemption is not an exemption

for (const f of files) {
    const lines = readFileSync(f, 'utf8').split('\n');
    let inFence = false;
    lines.forEach((line, i) => {
        let m;
        const trimmed = line.trimStart();
        if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
            inFence = !inFence;
            return;
        }
        if (inFence) {
            MARKER.lastIndex = 0;
            while (MARKER.exec(line)) quoted++;
            return;
        }
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
    console.error(`  scope: ${files.length} current-tier markdown file(s) (${all.length} total — the `
        + `WHOLE repo minus ${[...SKIP_DIRS].join('/')}; ${EXEMPT.join(', ')} exempt as `
        + `never-maintained), ${markers} live marker(s) + ${quoted} quoted inside a fenced block (not `
        + `asserted); inline \`code\` IS still scanned\n`);
    console.error('  Fix the DOC, or the contract — not this guard. A count in a doc must equal what');
    console.error('  its owning contract derives. If a line is deliberately recording an OLD figure,');
    console.error('  do not mark it: markers assert the current value.\n');
    process.exit(1);
}

console.log(`✓ Doc-count guard: ${markers} marked count statement(s) all match what their owning `
    + `contract derives — ${tally}; scope: ${files.length} current-tier TRACKED markdown file(s)`
    + (untrackedSkipped.length ? ` (+${untrackedSkipped.length} untracked/ignored NOT read)` : '') + ` `
    + `(${all.length} total — the WHOLE repo minus ${[...SKIP_DIRS].join('/')}; `
    + `${EXEMPT.join(', ')} exempt as never-maintained); ${quoted} further marker(s) quoted inside a `
    + `fenced block and NOT asserted; inline \`code\` IS still scanned.`);
