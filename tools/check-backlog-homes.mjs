#!/usr/bin/env node
/**
 * Backlog owning-doc guard — a `→` pointer is a CLAIM, not a fact.
 *
 * WHY THIS EXISTS. Rows in `docs/BACKLOG.md` end with `→ okf/…/some-concept.md`, asserting that the
 * named document owns that row's detail or its as-built. Nothing checked the assertion. Two pointers
 * were caught by hand pointing at documents that never mention their subject at all
 * (`MEASURE-SHORTHAND-ONE-HOME-1` → `catalog-vs-executors.md`, `DUCKLAKE-GRAPH-LANE-1` →
 * `db-layer.md`), and a row that claims a home it does not have is worse than a row with no home:
 * the next shift reads the doc, finds nothing, and re-derives from scratch — or worse, believes the
 * doc is the as-built and writes against it.
 *
 * ⚠ `tools/check-doc-citations.mjs` already resolves that the PATH exists, and this guard does not
 * duplicate that half — it inherits it. The gap is the second half: the target resolves and says
 * nothing about the subject. (It still reports an unresolvable pointer, because a pointer that does
 * not resolve cannot be checked for mention and silently skipping it would be a hole; in practice
 * the citation guard fails first on the same line.)
 *
 * ── WHAT "MENTIONS THE SUBJECT" MEANS, EXACTLY ────────────────────────────────────────────────────
 *
 * The subject is the row's IDENTIFIER — `LIKE-THIS-1`, the stable token the board itself coins. The
 * target must contain that identifier as a whole token (not as a prefix of `LIKE-THIS-11`). That is
 * a string comparison against an exact, repo-coined symbol. It is not prose scanning, not a keyword
 * heuristic, not a similarity score. There is nothing to tune and nothing to exempt: the id is
 * either in the file or it is not.
 *
 * 🔴 THE DESIGN THIS DELIBERATELY IS NOT — and the reason is written down one directory over. The
 * obvious guard asks "does the target discuss this row's TOPIC", by matching the row's title words or
 * its nouns against the doc's prose. `tools/check-doc-counts.mjs`'s header records what happened the
 * one time a prose scanner was built here: over 238 docs it matched 14 lines, the "failures" were a
 * line reference, a sentence about one node type and correction notes naming an old figure on
 * purpose — while MISSING the real phrasings. A guard that is mostly exemption is worse than no
 * guard. Topic-matching would be that guard again: a row titled "Pipeline graph" would match every
 * doc in the tree, and a row whose doc uses the canonical synonym would fail for being correct.
 * An identifier has neither problem, because it is not language.
 *
 * Exemptions: NONE. There is no allow list, no waiver comment, no per-row opt-out.
 *
 * ── SCOPE LIMIT, STATED ON EVERY RUN, PASS OR FAIL ────────────────────────────────────────────────
 *
 * A row with no identifier has no exact subject, so its pointers CANNOT be checked this way. Those
 * rows are not silently skipped — silence is an exemption by another name. The guard counts them,
 * counts how many owning-doc pointers they carry, and prints the line number of every one, so the
 * unverifiable claims are visible in the same output as the verified ones. Requiring an id on every
 * row would be the better invariant, but it is an editorial change to a file three concurrent
 * sessions are writing; it is filed as the next step, not smuggled in by a guard.
 *
 * ── WHAT IS AND IS NOT A POINTER ──────────────────────────────────────────────────────────────────
 *
 * `→` is also ordinary prose on this board ("`number` → `string`", "P2→P3", a mapping between two
 * Java symbols). The parser therefore reads a POINTER LIST grammar — ``→ `target`[ §sec]( · `target`
 * [ §sec])*`` — and then keeps only targets ending in `.md`. Measured on the current board: 78
 * targets parse, of which 24 are not repo docs (Java symbols, a JVM property, a `package.ps1` path,
 * a section token). Those are not reported; they are counted and printed. A `git tag`, an external
 * URL and a bare `§1` are likewise not `.md` and fall out by the same rule.
 *
 * Targets are resolved relative to `docs/`, which is how the board writes them.
 *
 * ⚠ The archived tier is CHECKED, not exempted. `docs/archived-documents/**` is never maintained, so
 * it is exempt as a SOURCE in the other guards — but a pointer INTO it is a claim made by a CURRENT
 * doc, and the other guards check those too. If an archived plan does not name the row, the row's
 * home is wrong wherever the plan lives.
 *
 * Falsified in three directions before wiring (see the shift report): the clean corpus's real state
 * reported; a legitimately homed row repointed at a doc that does not mention it, seen RED with its
 * line number; that same row restored, seen green.
 *
 * Pure Node, no dependencies. Run by `.github/workflows/ci.yml` and `.githooks/pre-push`.
 *
 * Usage: node tools/check-backlog-homes.mjs
 */

import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BACKLOG = 'docs/BACKLOG.md';
const DOCS = 'docs';

// A row is a top-level board bullet that opens with its rank. Everything up to the next bullet,
// heading or block quote belongs to it — rows wrap over several lines.
const ROW_START = /^- \*\*P[123]\*\*/;
const BLOCK_END = /^(?:#{1,6} |[-*] |> )/;

// The id shape the board coins: SCREAMING-KEBAB ending in a number. Anchored, so it matches a whole
// token and nothing else.
const ID_SHAPE = /^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+-\d+$/;

// The pointer-list grammar. Only what follows `→` in this exact shape is a pointer.
const POINTER_LIST =
    /→\s*((?:`[^`\n]+`(?:\s+§[^`\n·]*)?)(?:\s*·\s*`[^`\n]+`(?:\s+§[^`\n·]*)?)*)/g;

const backlogPath = join(ROOT, BACKLOG);
if (!existsSync(backlogPath)) {
    console.error(`✗ Backlog owning-doc guard: ${BACKLOG} not found — run from the repository root.`);
    process.exit(1);
}

// ── parse the board into rows ────────────────────────────────────────────────────────────────────
const lines = readFileSync(backlogPath, 'utf8').split('\n');
const rows = [];
let current = null;
let inFence = false;
lines.forEach((line, i) => {
    if (line.trimStart().startsWith('```')) inFence = !inFence;
    if (!inFence && ROW_START.test(line)) {
        current = { start: i + 1, head: line, body: [line], lineNos: [i + 1] };
        rows.push(current);
        return;
    }
    if (!current) return;
    if (!inFence && BLOCK_END.test(line)) {
        current = null;
        return;
    }
    current.body.push(line);
    current.lineNos.push(i + 1);
});

// The row's own identifier: the first id-shaped token, backticked or bolded, in the row's TITLE —
// the part of the first line before its first em dash. Restricting to the title is what keeps this
// exact: ids named mid-prose belong to OTHER rows this one cross-references, and taking one of those
// as the subject is how a checker invents a claim nobody made.
function rowId(head) {
    const title = head.split('—')[0];
    for (const m of title.matchAll(/`([^`]+)`|\*\*([^*]+)\*\*/g)) {
        const token = (m[1] || m[2]).trim();
        if (ID_SHAPE.test(token)) return token;
    }
    return null;
}

// Whole-token containment: `FOO-1` must not be satisfied by `FOO-11` or `PRE-FOO-1`.
function mentions(text, id) {
    const esc = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(`(?<![A-Z0-9-])${esc}(?![A-Z0-9-])`).test(text);
}

const targetCache = new Map();
function targetText(rel) {
    if (!targetCache.has(rel)) {
        const p = join(ROOT, DOCS, rel);
        targetCache.set(rel, existsSync(p) ? readFileSync(p, 'utf8') : null);
    }
    return targetCache.get(rel);
}

// ── walk ─────────────────────────────────────────────────────────────────────────────────────────
const failures = [];
const unverifiable = []; // { line, target } on rows that carry no identifier
let docPointers = 0;
let nonDocTargets = 0;
let identified = 0;

for (const row of rows) {
    const id = rowId(row.head);
    if (id) identified++;
    row.body.forEach((line, bi) => {
        const lineNo = row.lineNos[bi];
        for (const list of line.matchAll(POINTER_LIST)) {
            for (const t of list[1].matchAll(/`([^`\n]+)`/g)) {
                const target = t[1];
                if (!target.endsWith('.md')) {
                    nonDocTargets++;
                    continue;
                }
                docPointers++;
                if (!id) {
                    unverifiable.push({ line: lineNo, target });
                    continue;
                }
                const text = targetText(target);
                if (text === null) {
                    failures.push({ line: lineNo, target, id, why: 'the target does not resolve under docs/' });
                    continue;
                }
                if (!mentions(text, id)) {
                    failures.push({ line: lineNo, target, id, why: `the target never mentions \`${id}\`` });
                }
            }
        }
    });
}

// A floor, not a nicety. If the pointer grammar stops matching — the board switches separator, a row
// format changes — this guard would pass over nothing and report success. That is the failure shape
// this repo has shipped twice: a guard that cannot fire. Set EQUAL to the number parsed today.
const MIN_DOC_POINTERS = 52;

const scope =
    `scope: ${rows.length} board row(s), ${identified} with an identifier and ` +
    `${rows.length - identified} without; ${docPointers + nonDocTargets} \`→\` target(s) parsed, of ` +
    `which ${nonDocTargets} are not repo docs (Java symbols, properties, section tokens, tags, URLs) ` +
    `and are not pointers; ${docPointers} owning-doc pointer(s) found, of which ` +
    `${docPointers - unverifiable.length} were CHECKED against their row's identifier and ` +
    `${unverifiable.length} could not be (see the scope limit below), resolved under ${DOCS}/. ` +
    `docs/archived-documents/** is CHECKED as a target, not exempt — a pointer into the archive is ` +
    `still a claim made by a current doc. Exemptions: NONE`;

if (docPointers < MIN_DOC_POINTERS) {
    console.error(
        `✗ Backlog owning-doc guard: only ${docPointers} owning-doc pointer(s) parsed, below the floor ` +
            `of ${MIN_DOC_POINTERS}. The pointer grammar has almost certainly stopped matching — fix the ` +
            `parser rather than the floor.\n\n  ${scope}`,
    );
    process.exit(1);
}

// The scope limit is printed on every run, pass or fail. An unstated scope is an unaudited one, and
// a row with no id is a pointer nobody is checking.
function printScopeLimit(out) {
    if (!unverifiable.length) return;
    out(
        `\n  ⚠ SCOPE LIMIT — ${unverifiable.length} owning-doc pointer(s) on rows with NO identifier ` +
            `cannot be\n    checked: there is no exact subject to look for. These claims are UNVERIFIED, ` +
            `not passing:`,
    );
    for (const u of unverifiable) out(`      ${BACKLOG}:${u.line}  →  ${u.target}`);
    out(`    Give those rows an id and they become checkable. Do not read their absence here as a pass.`);
}

if (failures.length) {
    console.error(
        `✗ Backlog owning-doc guard: ${failures.length} row(s) claim an owning doc that does not name ` +
            `them\n`,
    );
    for (const f of failures) {
        console.error(`  ${BACKLOG}:${f.line}  →  ${f.target}\n      ${f.why}`);
    }
    console.error(`\n  ${scope}`);
    printScopeLimit(console.error);
    console.error(
        `\n  Fix the POINTER or the DOC, never this guard. Either the row points at the wrong document ` +
            `\n  — repoint it at the one that actually owns the detail — or the owning doc was never ` +
            `\n  written up, in which case write the concept and name the row in it. A pointer is a ` +
            `\n  claim about where the knowledge lives; if nobody can follow it, it was never true.`,
    );
    process.exit(1);
}

console.log(
    `✓ Backlog owning-doc guard: all ${docPointers - unverifiable.length} checkable owning-doc ` +
        `pointer(s) land on a document that names ` +
        `their row — ${scope}.`,
);
printScopeLimit(console.log);
