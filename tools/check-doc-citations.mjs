#!/usr/bin/env node
/**
 * Citation guard — a CURRENT doc must not point at a path that is not there, or name a type this
 * repository itself declares renamed.
 *
 * WHY THIS EXISTS. `check-doc-links.mjs` polices markdown `[text](target)` links. It cannot see the other,
 * commoner way a doc points at the tree: a backticked path or class name in prose. That is not a link, so
 * nothing checked it, and it rotted — `SPEC-STALEREF-1` measured **twenty-three** stale paths, dead
 * citations and phantom rows across the doc set, the largest single family the 17-spec consolidation found.
 *
 * The headline instance is what this guard is shaped by. A release gate in a current doc rested on
 * `BatchGraphRunner` — a class that has not existed since the 2026-08-31 Consignment rename, while
 * `ConsignmentGraphRunner` has two production callers. Nine capability specs' §8 sections describe running
 * this check and, until this file, told the next shift it existed only in a session scratchpad
 * (`okf/capabilities/tooling/tooling.md` §5.2 item 1 — this area's own disease, self-inflicted).
 *
 * ── WHAT IT CHECKS ────────────────────────────────────────────────────────────────────────────────
 *
 * **A. A cited repo path resolves.** Every backticked token that looks like a repo-relative path with a
 *    source or doc extension must exist — resolved against one of BASES, or matching the tail of a
 *    tracked file. The tail rule is not a loophole: docs cite Java by its PACKAGE path
 *    (`com/gamma/acquire/SecretResolver.java`) and UI files by their app-relative one, and a citation
 *    that uniquely identifies a real file is an honest citation however much of the prefix it omits.
 *
 * **B. No current doc names a type the repo declares renamed.** The old names are PARSED from the
 *    committed codemod `tools/rename-batch-to-consignment.mjs`, never copied here: a hand-mirrored map
 *    drifts, and this repo has four recorded instances of exactly that. A line MAY name an old type when
 *    it also names the replacement on the same line — that is what recording a rename looks like, and it
 *    is why this check needs no waiver list at all.
 *
 * ── WHAT IT DELIBERATELY DOES NOT CHECK, AND WHY ──────────────────────────────────────────────────
 *
 *   - **`docs/archived-documents/**` and `docs/superpower/**` as SOURCES.** The archive is never
 *     maintained by policy (CLAUDE.md doc-lifecycle §3). `superpower/` holds IN-FLIGHT plans, whose whole
 *     job is to name things that do not exist yet — a plan citing an unbuilt class is the plan working.
 *     Citations pointing INTO either tier from a current doc ARE checked.
 *
 *   - **"every backticked CamelCase name must exist in the tree".** That was the first design, and
 *     measurement killed it: 4,654 such citations in the current tier, 56 distinct names absent from
 *     source, and the overwhelming majority were LEGITIMATE — third-party types the repo never declares
 *     (`PosixFilePermission`, `PassivePorts`, `MatChipGrid`), designs deliberately named-but-unbuilt in a
 *     board row (`DryRunProvider`, `StepTypeProvider`), and renames recorded on purpose
 *     (`GLOSSARY.md` §13's touchpoint rows; PROJECT_NOTES' "renamed from `KeycloakTokenRelay`"). Shipping
 *     it would have meant a guard that is ~45 entries of allowlist and 11 of rule. A guard whose scope is
 *     mostly exemption has been this repo's recorded failure three times, so check B narrowed to the one
 *     invariant that is OBJECTIVE: the rename map the repo itself commits.
 *
 *   - **Bare `Batch`.** The codemod maps it, but the GROUPING sense is deliberately kept —
 *     `batch_max_files`, `BatchedOperations` telemetry, JDBC `addBatch()` — and `GLOSSARY.md` records
 *     "Was `Run ⊇ Batch ⊇ File`" as history a rename would falsify. Word boundaries cannot separate the
 *     two senses for a one-word name, so it is excluded and SAID so, here and on every run.
 *
 *   - Anything inside a fenced block. A citation in a code fence is QUOTED TEXT — another document being
 *     shown — and must stay byte-exact. `check-doc-links.mjs` and `check-vocabulary.mjs` strip fences for
 *     the same reason.
 *
 * Falsified in both directions 2026-09-09: seeded a dead path and a bare old type name, saw each fail with
 * its own line number; removed them, saw green. A guard that has never been seen to fail is not a guard.
 *
 * Pure Node, no dependencies. Run by `.github/workflows/ci.yml` and `.githooks/pre-push`.
 */

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve, sep } from 'node:path';
import { execFileSync } from 'node:child_process';

/** Trees scanned. Root-level `*.md` (CLAUDE.md, README) is added separately. */
const ROOTS = ['docs', 'compliance', '.claude'];

/** Never walked: build output and the git worktrees, which hold whole second copies of the repo. */
const SKIP_DIRS = new Set(['node_modules', '.git', 'worktrees', 'dist', 'target', 'graphify-out']);

/** Exempt AS SOURCES only — see the header. Kept as prefixes so they read at the call site. */
const EXEMPT_TIERS = ['docs/archived-documents/', 'docs/superpower/'];

/**
 * Where a cited path may resolve from. `docs/` is here because the canon cites its own tree as
 * `okf/backend/…` from `docs/BACKLOG.md`; `inspecto-ui/src/app` because the UI docs and the angular-ui
 * skill cite components as `inspecto/api/foo.service.ts`, which is that app's own in-tree prefix.
 */
const BASES = ['.', 'docs', 'inspecto-ui/src/app'];

/** The codemod whose MAP is check B's source of truth. Parsed, never mirrored. */
const RENAME_CODEMOD = 'tools/rename-batch-to-consignment.mjs';

/** Excluded from the old-name set — the grouping sense survives. See the header. */
const RENAME_EXCLUDED = ['Batch'];

/** Extensions that make a slash-bearing token a path claim rather than a config key or a URL fragment. */
const PATH_EXT = /\.(java|ts|html|scss|mjs|md|toon|xml|json|yml|yaml|ps1|sh)$/;

/** A backticked span. */
const TICK = /`([^`\n]+)`/g;

/**
 * A line may cite a path that is GONE when it says so. Several docs quote a dead path deliberately —
 * `okf/capabilities/studio/studio.md` names the deleted mock's `sample-sources.ts` as the very finding it
 * is reporting, and its §8 predicted "one MISSING hit is deliberate" before this guard existed. The rule
 * is the same shape as check B's: an author who states the absence has recorded it, not rotted. What the
 * guard is for is the UNMARKED claim — a doc that still talks about a file as if it were there.
 */
const ABSENCE_MARKER = /dead path|no longer exist|does not exist|never existed|\bdeleted\b|\bretired\b/i;

/** Tokens that are patterns, placeholders or shell, not paths: `<dir>`, `a/*.md`, `$HOME/x`, `-Dfoo`. */
const NOT_A_PATH = /[<>*?…{}\s]|^-|^\$/;

/**
 * The docs' elision idiom, `inspecto-ui/.../pipelines/pipeline-editable.ts`. The prefix is decorative;
 * what is being cited is the tail, so that is what gets resolved.
 */
const ELISION = '/.../';

/**
 * Exempt as TARGETS. `spaces/**` is the data and Space-config tier: CLAUDE.md forbids committing data or
 * client files, so a doc citing an example Space config names a file that by DESIGN is not in the tree.
 * Checking it would make the guard demand a policy violation to go green.
 */
const EXEMPT_TARGETS = ['spaces/'];

/**
 * Emptiness floors. Today: 475 markdown files, ~750 path citations in the current tier. Set well below
 * that — the docs-consolidation may retire whole tiers, and a floor tuned to today would fail a
 * legitimate shrink. Lower them only alongside a real, deliberate shrink, never to green a red build.
 */
const MIN_FILES = 150;
const MIN_PATH_CITATIONS = 300;

/** Check B is worthless if the map fails to parse, so a thin map FAILS rather than passing green. */
const MIN_RENAME_ENTRIES = 20;

const slash = (p) => p.split(sep).join('/');

function fail(message) {
    console.error(`✗ Citation guard: ${message}`);
    process.exit(1);
}

/**
 * Parse the codemod's `MAP = { Old: 'New', … }`. If its shape ever changes this throws loudly, which is
 * the point: a silently-empty map would turn check B into a no-op that reports success.
 */
function loadRenames() {
    if (!existsSync(RENAME_CODEMOD)) {
        fail(
            `${RENAME_CODEMOD} is missing. Check B reads its rename map from that committed codemod ` +
                `rather than mirroring it here. If the codemod was archived, move the map into this ` +
                `guard as an explicit table — do not delete the check.`,
        );
    }
    const body = readFileSync(RENAME_CODEMOD, 'utf8').match(/const MAP = \{([\s\S]*?)\n\};/);
    if (!body) {
        fail(
            `cannot find \`const MAP = {…}\` in ${RENAME_CODEMOD}. Re-anchor this parser before trusting ` +
                `check B — a map that fails to parse would make the check silently pass everything.`,
        );
    }
    const pairs = new Map();
    const entry = new RegExp("^\\s+(\\w+):\\s*'(\\w+)'");
    for (const line of body[1].split('\n')) {
        const m = line.match(entry);
        if (m && !RENAME_EXCLUDED.includes(m[1])) pairs.set(m[1], m[2]);
    }
    if (pairs.size < MIN_RENAME_ENTRIES) {
        fail(
            `only ${pairs.size} rename(s) parsed from ${RENAME_CODEMOD}, below the floor of ` +
                `${MIN_RENAME_ENTRIES}. The map's shape changed — fix the parser, not the floor.`,
        );
    }
    return pairs;
}

/**
 * Every tracked path, for the tail rule. `git ls-files` rather than a walk: the question is what the
 * repository CONTAINS, and a walk would also see build output and this machine's untracked scratch.
 */
function trackedPaths() {
    let out;
    try {
        out = execFileSync('git', ['ls-files'], { encoding: 'utf8', maxBuffer: 1 << 28 });
    } catch (e) {
        fail(`cannot run \`git ls-files\` (${e.message}). Check A needs it for the tail rule.`);
    }
    const paths = out.split('\n').filter(Boolean).map((p) => p.trim().replace(/^"|"$/g, ''));
    if (paths.length < 1000) {
        fail(
            `\`git ls-files\` returned only ${paths.length} path(s). This repository has thousands — ` +
                `something is wrong with the checkout, and a tail rule over almost nothing would ` +
                `report every citation dead.`,
        );
    }
    return paths;
}

function collect(dir, out) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, entry.name);
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(entry.name)) continue;
            collect(p, out);
        } else if (entry.name.endsWith('.md')) {
            out.push(p);
        }
    }
}

/** Basename -> tracked paths carrying it, so the tail rule is a lookup rather than a scan of thousands. */
const byBasename = new Map();
/**
 * The repository's own top-level names. A path whose first segment is one of these is a CLAIM about this
 * tree and gets checked; one whose is not — `eoiagent-core/…` (a separate repository), or a partial like
 * `mock/handlers/ops.handler.ts` — is only ever checkable by its tail, which it has already been given.
 */
const TOP_LEVEL = new Set();
for (const p of trackedPaths()) {
    const base = p.slice(p.lastIndexOf('/') + 1);
    if (!byBasename.has(base)) byBasename.set(base, []);
    byBasename.get(base).push(p);
    TOP_LEVEL.add(p.split('/')[0]);
    // 🔴 Also the first segment RELATIVE TO EACH BASE. `docs/BACKLOG.md` cites its siblings as
    // `superpower/x.md` and `okf/backend/y.md`, which are real claims about this repo even though
    // `superpower` and `okf` are not top-level names. Without this the whole idiom was SKIPPED as
    // "some other repository" — and adding the rooted test in the guard's first version therefore made
    // eight already-dead `superpower/…` citations invisible instead of fixing them. Found 2026-09-09
    // when archiving a plan did not turn a single citation of it red.
    for (const b of BASES) {
        if (b !== '.' && p.startsWith(b + '/')) TOP_LEVEL.add(p.slice(b.length + 1).split('/')[0]);
    }
}

/** True when some tracked file IS this path or ends with it after a `/` — see the header's tail rule. */
function matchesTrackedTail(target) {
    const candidates = byBasename.get(target.slice(target.lastIndexOf('/') + 1));
    if (!candidates) return false;
    return candidates.some((p) => p === target || p.endsWith('/' + target));
}

const renames = loadRenames();
// Longest first, so `BatchEventBus` is recognised before `BatchEvent` — the codemod's own ordering rule.
const oldNames = [...renames.keys()].sort((a, b) => b.length - a.length);
const boundary = (name) => new RegExp('\\b' + name + '\\b');
const anyOldName = new RegExp('\\b(' + oldNames.join('|') + ')\\b', 'g');

const files = [];
for (const root of ROOTS) if (existsSync(root)) collect(root, files);
for (const name of readdirSync('.')) if (name.endsWith('.md')) files.push(name);

if (files.length < MIN_FILES) {
    fail(
        `only ${files.length} markdown file(s) found, below the floor of ${MIN_FILES}. Either the doc ` +
            `trees moved (fix ROOTS) or this ran from the wrong directory — a citation check over ` +
            `nothing is the bug this floor exists for.`,
    );
}

let pathCitations = 0;
let renameCitations = 0;
let recordedRenames = 0;
let recordedAbsences = 0;
const deadPaths = [];
const staleNames = [];

/** Every backticked token on one live (unfenced) line of one file. */
function scanLine(file, rel, line, lineNo) {
    let m;
    TICK.lastIndex = 0;
    while ((m = TICK.exec(line))) {
        const token = m[1].trim();
        if (!token.includes('/') || NOT_A_PATH.test(token) || /^https?:/.test(token)) continue;
        const cited = token.replace(/[.,;:)]+$/, '').replace(/^\.\//, '').replace(/\/$/, '');
        if (!PATH_EXT.test(cited)) continue;

        // An elided citation is a claim about its tail only.
        const elided = cited.includes(ELISION);
        const target = elided ? cited.slice(cited.indexOf(ELISION) + ELISION.length) : cited;
        if (EXEMPT_TARGETS.some((prefix) => target.startsWith(prefix))) continue;
        // Not a claim about this repository unless its first segment is one of our top-level names, or
        // it was elided (in which case only the tail was ever a claim) or written relative to the doc.
        // An elision keeps its prefix's claim: `eoiagent-core/.../X.java` is about ANOTHER repository and
        // is none of this guard's business, while `inspecto-ui/.../x.ts` is about ours.
        const firstSegment = cited.split('/')[0];
        const rooted = TOP_LEVEL.has(elided ? firstSegment : target.split('/')[0]);
        if (!rooted && !cited.startsWith('..')) continue;

        pathCitations++;
        const found = [dirname(file), ...BASES].some((base) => {
            try {
                return existsSync(resolve(base, decodeURIComponent(target)));
            } catch {
                return existsSync(resolve(base, target));
            }
        });
        if (found || matchesTrackedTail(target)) continue;
        if (ABSENCE_MARKER.test(line)) {
            recordedAbsences++;
            continue;
        }
        deadPaths.push({ file: rel, line: lineNo, target: cited });
    }

    // Check B works on the WHOLE line, not per-token: the replacement that licenses an old name is
    // frequently outside the backticks that carry it (`~~Batch~~` | **Consignment** in a §13 row).
    anyOldName.lastIndex = 0;
    const cited = new Set();
    let n;
    while ((n = anyOldName.exec(line))) cited.add(n[1]);
    for (const old of cited) {
        // A longer mapped name containing this one already accounts for the hit (BatchEvent in
        // BatchEventBus): only report the longest match on the line.
        if (oldNames.some((other) => other !== old && other.includes(old) && cited.has(other))) continue;
        renameCitations++;
        if (boundary(renames.get(old)).test(line)) recordedRenames++;
        else staleNames.push({ file: rel, line: lineNo, old, now: renames.get(old) });
    }
}

for (const file of files) {
    const rel = slash(file);
    if (EXEMPT_TIERS.some((prefix) => rel.startsWith(prefix))) continue;
    let inFence = false;
    readFileSync(file, 'utf8')
        .split('\n')
        .forEach((line, i) => {
            const trimmed = line.trimStart();
            if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
                inFence = !inFence;
                return;
            }
            if (!inFence) scanLine(file, rel, line, i + 1);
        });
}

if (pathCitations < MIN_PATH_CITATIONS) {
    fail(
        `only ${pathCitations} path citation(s) parsed out of ${files.length} file(s), below the floor of ` +
            `${MIN_PATH_CITATIONS}. The token pattern has almost certainly stopped matching — fix the ` +
            `parser rather than the floor.`,
    );
}

// The exemptions are stated on every run, pass or fail. An unprinted scope is an unaudited one.
const scopeNote =
    `scope: ${files.length} markdown file(s) under ${ROOTS.join(', ')} + root *.md, MINUS ` +
    `${EXEMPT_TIERS.join(' and ')} as sources (never-maintained / in-flight tiers — citations pointing ` +
    `INTO them are still checked); ${pathCitations} path citation(s), of which ${recordedAbsences} ` +
    `state the absence on the citing line and are ALLOWED as recorded history; ${renames.size} ` +
    `renamed type(s) ` +
    `read from ${RENAME_CODEMOD}, of which ${recordedRenames} citation(s) name their replacement on the ` +
    `same line and are ALLOWED as recorded history; bare \`${RENAME_EXCLUDED.join('`, `')}\` excluded ` +
    `(the grouping sense survives the rename)`;

if (deadPaths.length || staleNames.length) {
    console.error(
        `✗ Citation guard: ${deadPaths.length} dead path citation(s) and ${staleNames.length} stale ` +
            `type name(s) in current docs\n`,
    );
    for (const d of deadPaths) console.error(`  ${d.file}:${d.line}  path does not resolve:  ${d.target}`);
    if (deadPaths.length && staleNames.length) console.error('');
    for (const s of staleNames) {
        console.error(`  ${s.file}:${s.line}  renamed type:  ${s.old}  ->  ${s.now}`);
    }
    console.error(`\n  ${scopeNote}`);
    console.error(
        `\n  Fix the CITATION, not this guard. A path that moved needs its new path; one that is gone ` +
            `\n  needs the claim rewritten. A renamed type needs the current name — or, if the line is ` +
            `\n  deliberately RECORDING the rename, it needs to name the replacement too.`,
    );
    process.exit(1);
}

console.log(
    `✓ Citation guard: ${pathCitations} cited path(s) resolve and no current doc names a renamed type ` +
        `bare — ${scopeNote}.`,
);
