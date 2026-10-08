#!/usr/bin/env node
/**
 * Family-count guard — every stated count of the operational-store roster must equal the roster itself.
 *
 * THE ROSTER IS OPEN (MODULE-REORG-P1-FAMILY, 2026-10-08). The roster is the CORE families (the
 * `OperationalDb.Family` enum, parsed from source) PLUS the families optional modules contribute, which each
 * module declares in its `module.toon` as `provides.storeFamilies` (parsed from every manifest in the tree;
 * a per-module test pins manifest == provider). Prose states the whole roster (core + contributed = what an
 * Enterprise classpath lists); the processor's own test tripwires state the CORE count (that classpath has no
 * module); the ops module's tripwires state the contributed count and the whole. Same discipline as before — a
 * new family still needs a conscious doc/test update — only the source of truth became two parses.
 *
 * WHY THIS EXISTS. The roster's size is written down in NINE places outside the enum: two test tripwires
 * and seven sentences across `db-layer.md`, `data-plane.md` and the scale-out plan. Adding a family
 * therefore means a nine-file sweep, and it was missed BY HAND TWICE IN TWO SHIFTS — on 2026-09-12
 * `EVENTS` (D6) took the roster 12 → 13 and `RUN_LEASE` (phase B1) took it 13 → 14, and both times the
 * two test tripwires were what caught it, after a full ~14-minute reactor run.
 *
 * The tripwires work, but they are an expensive way to learn and they say nothing about the prose. This
 * guard makes the whole set structural and instant: the enum + the manifests are the source of truth, and
 * every number-word or digit asserting a family count must agree with them.
 *
 * WHAT IT CHECKS
 *   1. `OperationalDb.Family` parses to at least MIN_FAMILIES entries (an emptiness floor, so a parse
 *      that silently matches nothing cannot pass — the failure mode this repo keeps hitting).
 *   2. Every `storeFamilies[N]: A,B` manifest line lists N names, none duplicating the roster.
 *   3. Every `<number-word> famil…` / `<number-word>-family` phrase in the scanned docs names the TOTAL count.
 *   4. The test tripwires assert the count they own (core / contributed / total).
 *
 * ⛔ It does NOT check that a new family is CORRECT — that it honours the shared URL, has a SpaceRoot
 * accessor, or is reported by `/system/operational-db`. `OperationalDbTest`'s loop,
 * `ControlApiSystemRoutesTest` and the ops module's `StoreFamilyParityTest` own those, and they must stay.
 * This guard only refuses a tree whose stated counts contradict the roster.
 *
 * ⚠ `inspecto-deploy/` is deliberately NOT scanned: it is gitignored build output carrying stale copies
 * of these same docs, and failing on a generated artifact would teach the next shift to ignore the guard.
 *
 * `FAMILY_COUNT_ROOT` re-roots every path (the guard's own test runs it against a fixture tree).
 *
 * Pure Node, no dependencies, ~instant.
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.env.FAMILY_COUNT_ROOT ?? '.';
const at = (p) => join(ROOT, p);

const ENUM_FILE = 'inspecto/src/main/java/com/gamma/service/OperationalDb.java';

/** Docs whose prose states the count. Add a file here when it starts quoting the roster's size. */
const DOC_FILES = [
    'docs/okf/backend/engine/db-layer.md',
    'docs/okf/capabilities/data-plane/data-plane.md',
    'docs/superpower/enterprise-scale-out-plan.md',
];

/**
 * The test tripwires, and the pattern that carries the number in each. `of` says which count the assertion
 * must state: `core` (the processor's own classpath, no modules), `contributed` (the ops module's own four) or
 * `total` (core + contributed, an Enterprise classpath).
 */
const TEST_ASSERTIONS = [
    {
        file: 'inspecto/src/test/java/com/gamma/service/OperationalDbTest.java',
        pattern: /assertEquals\((\d+),\s*OperationalDb\.Family\.values\(\)\.length/,
        what: 'the core roster size',
        of: 'core',
    },
    {
        file: 'inspecto/src/test/java/com/gamma/control/ControlApiSystemRoutesTest.java',
        pattern: /assertEquals\((\d+),\s*families\.size\(\)/,
        what: "/system/operational-db's reported family count on the processor's own classpath",
        of: 'core',
    },
    {
        file: 'features/inspecto-ops/src/test/java/com/gamma/service/ObjectFamiliesOperationalDbTest.java',
        pattern: /assertEquals\((\d+),\s*m\.provides\(\)\.storeFamilies\(\)\.size\(\)/,
        what: "the ops module's contributed family count",
        of: 'contributed',
    },
    {
        file: 'features/inspecto-ops/src/test/java/com/gamma/service/ObjectFamiliesOperationalDbTest.java',
        pattern: /assertEquals\((\d+),\s*OperationalDb\.all\(\)\.size\(\)/,
        what: 'the whole roster on an Enterprise classpath',
        of: 'total',
    },
];

/** Module groups scanned for a `module.toon`. */
const MANIFEST_DIRS = ['spi', 'platform', 'features', 'la', 'providers', 'inspecto'];

function findManifests() {
    const found = [];
    for (const top of MANIFEST_DIRS) {
        const dir = at(top);
        if (!existsSync(dir)) continue;
        for (const mod of readdirSync(dir)) {
            const f = join(dir, mod, 'src/main/resources/META-INF/inspecto/module.toon');
            if (existsSync(f)) found.push(f);
        }
        const direct = join(dir, 'src/main/resources/META-INF/inspecto/module.toon');
        if (existsSync(direct)) found.push(direct);
    }
    return found;
}

/**
 * The emptiness floor for the CORE enum. The core roster has had at least a dozen entries since it was
 * consolidated; a parse finding fewer has almost certainly stopped matching the enum rather than found
 * families deleted. (12 after the four Operational Object families moved to inspecto-ops, 2026-10-08.)
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

// ── 1. the source of truth: the core enum + the contributed manifests ───────────────
const enumSource = readFileSync(at(ENUM_FILE), 'utf8');
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

const core = unique.length;

// The contributed families: `storeFamilies[N]: A,B,C` under `provides:` in each module.toon.
const contributed = [];
const manifestProblems = [];
for (const file of findManifests()) {
    const m = readFileSync(file, 'utf8').match(/^\s*storeFamilies\[(\d+)\]:\s*(.*)$/m);
    if (!m) continue;
    const names = m[2].split(',').map((n) => n.trim()).filter(Boolean);
    if (names.length !== Number(m[1])) {
        manifestProblems.push(`${file}: storeFamilies[${m[1]}] declares ${m[1]} but lists ${names.length}`);
    }
    for (const n of names) {
        if (unique.includes(n) || contributed.includes(n)) {
            manifestProblems.push(`${file}: store family '${n}' duplicates a name already on the roster`);
        }
        contributed.push(n);
    }
}

const expected = core + contributed.length;
const COUNTS = { core, contributed: contributed.length, total: expected };
const expectedWord = WORDS[expected];
if (!expectedWord) {
    fail(`the roster has ${expected} families and this guard has no word for that — extend WORDS.`);
}

// ── 2. the prose ────────────────────────────────────────────────────────────────────
const problems = [...manifestProblems];
// ⚠ The `\**` are load-bearing, not decoration: until 2026-09-16 this pattern required the number word to
// touch "famil…" directly, so a BOLDED count — `the **fourteen** families` — matched nothing and the guard
// went GREEN on it. That is exactly how `db-layer.md` came to say fourteen on one line and fifteen on
// another, in the same file, with this guard passing. ⛔ A guard that skips the emphasised statements
// skips the ones an author was most deliberate about.
const PROSE = /\b([a-z]+)\**(?:\s+|-)\**famil(?:y|ies)\b/gi;

for (const file of DOC_FILES) {
    const text = readFileSync(at(file), 'utf8');
    const lines = text.split('\n');
    lines.forEach((line, i) => {
        for (const m of line.matchAll(PROSE)) {
            const word = m[1].toLowerCase();
            const stated = WORD_TO_NUMBER[word];
            if (stated === undefined) continue; // not a number word ("store families", "the families")
            if (stated !== expected) {
                problems.push(`${file}:${i + 1} says "${m[0].trim()}" but the roster has ${expected}`);
            }
        }
    });
}

// ── 3. the tripwires ────────────────────────────────────────────────────────────────
for (const { file, pattern, what, of } of TEST_ASSERTIONS) {
    const text = readFileSync(at(file), 'utf8');
    const m = text.match(pattern);
    if (!m) {
        problems.push(
            `${file}: could not find the assertion for ${what} — it was renamed or removed. ` +
                `⛔ That tripwire is load-bearing; restore it rather than deleting this check.`,
        );
        continue;
    }
    if (Number(m[1]) !== COUNTS[of]) {
        problems.push(`${file} asserts ${m[1]} for ${what}, but the ${of} count is ${COUNTS[of]}`);
    }
}

if (problems.length) {
    fail(
        `the operational-store roster has ${expected} entries (${expectedWord}: ${core} core + ` +
            `${contributed.length} contributed), but:\n  - ` +
            problems.join('\n  - ') +
            `\n  The ENUM (core) and the module manifests (contributed) are the source of truth. ` +
            `Update the statements to match them, never the reverse.`,
    );
}

console.log(
    `✓ Family-count guard: the roster has ${expected} entries (${core} core + ${contributed.length} contributed: ` +
        `${contributed.join(', ') || 'none'}); ` +
        `${DOC_FILES.length} doc file(s) and ${TEST_ASSERTIONS.length} test tripwire(s) agree.`,
);
