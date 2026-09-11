#!/usr/bin/env node
/**
 * Family-count guard — every stated count of `OperationalDb.Family` must equal the enum itself.
 *
 * WHY THIS EXISTS. The roster's size is written down in NINE places outside the enum: two test tripwires
 * and seven sentences across `db-layer.md`, `data-plane.md` and the scale-out plan. Adding a family
 * therefore means a nine-file sweep, and it was missed BY HAND TWICE IN TWO SHIFTS — on 2026-09-12
 * `EVENTS` (D6) took the roster 12 → 13 and `RUN_LEASE` (phase B1) took it 13 → 14, and both times the
 * two test tripwires were what caught it, after a full ~14-minute reactor run.
 *
 * The tripwires work, but they are an expensive way to learn and they say nothing about the prose. This
 * guard makes the whole set structural and instant: the enum is the single source of truth, and every
 * number-word or digit asserting a family count must agree with it.
 *
 * WHAT IT CHECKS
 *   1. `OperationalDb.Family` parses to at least MIN_FAMILIES entries (an emptiness floor, so a parse
 *      that silently matches nothing cannot pass — the failure mode this repo keeps hitting).
 *   2. Every `<number-word> famil…` / `<number-word>-family` phrase in the scanned docs names that count.
 *   3. Both test tripwires (`Family.values().length` and the `/system/db` roster size) assert it.
 *
 * ⛔ It does NOT check that a new family is CORRECT — that it honours the shared URL, has a SpaceRoot
 * accessor, or is reported by `/system/db`. `OperationalDbTest`'s loop and `ControlApiSystemRoutesTest`
 * own those, and they must stay. This guard only refuses a tree whose stated counts contradict the enum.
 *
 * ⚠ `inspecto-deploy/` is deliberately NOT scanned: it is gitignored build output carrying stale copies
 * of these same docs, and failing on a generated artifact would teach the next shift to ignore the guard.
 *
 * Pure Node, no dependencies, ~instant.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ENUM_FILE = 'inspecto/src/main/java/com/gamma/service/OperationalDb.java';

/** Docs whose prose states the count. Add a file here when it starts quoting the roster's size. */
const DOC_FILES = [
    'docs/okf/backend/engine/db-layer.md',
    'docs/okf/capabilities/data-plane/data-plane.md',
    'docs/superpower/enterprise-scale-out-plan.md',
];

/** The two test tripwires, and the pattern that carries the number in each. */
const TEST_ASSERTIONS = [
    {
        file: 'inspecto/src/test/java/com/gamma/service/OperationalDbTest.java',
        pattern: /assertEquals\((\d+),\s*OperationalDb\.Family\.values\(\)\.length/,
        what: 'the roster size',
    },
    {
        file: 'inspecto/src/test/java/com/gamma/control/ControlApiSystemRoutesTest.java',
        pattern: /assertEquals\((\d+),\s*families\.size\(\)/,
        what: "/system/db's reported family count",
    },
];

/**
 * The emptiness floor. The roster has had at least a dozen entries since it was consolidated; a parse
 * finding fewer has almost certainly stopped matching the enum rather than found families deleted.
 * ⚠ Lower this only alongside a real, deliberate shrink — never to make a red build green.
 */
const MIN_FAMILIES = 12;

const WORDS = {
    10: 'ten', 11: 'eleven', 12: 'twelve', 13: 'thirteen', 14: 'fourteen', 15: 'fifteen',
    16: 'sixteen', 17: 'seventeen', 18: 'eighteen', 19: 'nineteen', 20: 'twenty',
};
const WORD_TO_NUMBER = Object.fromEntries(Object.entries(WORDS).map(([n, w]) => [w, Number(n)]));

function fail(message) {
    console.error(`✗ Family-count guard: ${message}`);
    process.exit(1);
}

// ── 1. the source of truth ──────────────────────────────────────────────────────────
const enumSource = readFileSync(ENUM_FILE, 'utf8');
const enumStart = enumSource.indexOf('enum Family');
if (enumStart < 0) fail(`could not find 'enum Family' in ${ENUM_FILE} — the guard's parse is broken.`);
// Entries look like `NAME("label", "prop", …)` at the start of a line, up to the enum's terminating `;`.
const enumBody = enumSource.slice(enumStart, enumSource.indexOf('\n        ;', enumStart) + 1 || undefined);
const families = [...enumBody.matchAll(/^\s{8}([A-Z][A-Z0-9_]*)\s*\(/gm)].map((m) => m[1]);
const unique = [...new Set(families)];

if (unique.length < MIN_FAMILIES) {
    fail(
        `parsed only ${unique.length} families from ${ENUM_FILE} (floor is ${MIN_FAMILIES}). ` +
            `The enum almost certainly changed shape and this guard stopped matching it — ` +
            `fix the PARSE, do not lower the floor.`,
    );
}

const expected = unique.length;
const expectedWord = WORDS[expected];
if (!expectedWord) {
    fail(`the roster has ${expected} families and this guard has no word for that — extend WORDS.`);
}

// ── 2. the prose ────────────────────────────────────────────────────────────────────
const problems = [];
const PROSE = /\b([a-z]+)(?:\s+|-)famil(?:y|ies)\b/gi;

for (const file of DOC_FILES) {
    const text = readFileSync(file, 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, i) => {
        for (const m of line.matchAll(PROSE)) {
            const word = m[1].toLowerCase();
            const stated = WORD_TO_NUMBER[word];
            if (stated === undefined) continue; // not a number word ("store families", "the families")
            if (stated !== expected) {
                problems.push(
                    `${file}:${i + 1} says "${m[0].trim()}" but the enum has ${expected}`,
                );
            }
        }
    });
}

// ── 3. the tripwires ────────────────────────────────────────────────────────────────
for (const { file, pattern, what } of TEST_ASSERTIONS) {
    const text = readFileSync(file, 'utf8');
    const m = text.match(pattern);
    if (!m) {
        problems.push(
            `${file}: could not find the assertion for ${what} — it was renamed or removed. ` +
                `⛔ That tripwire is load-bearing; restore it rather than deleting this check.`,
        );
        continue;
    }
    if (Number(m[1]) !== expected) {
        problems.push(`${file} asserts ${m[1]} for ${what}, but the enum has ${expected}`);
    }
}

if (problems.length) {
    fail(
        `OperationalDb.Family has ${expected} entries (${expectedWord}), but:\n  - ` +
            problems.join('\n  - ') +
            `\n  The ENUM is the source of truth. Update the statements to match it, never the reverse.`,
    );
}

console.log(
    `✓ Family-count guard: OperationalDb.Family has ${expected} entries; ` +
        `${DOC_FILES.length} doc file(s) and ${TEST_ASSERTIONS.length} test tripwire(s) agree.`,
);
