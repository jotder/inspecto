#!/usr/bin/env node
/**
 * SBOM module-set guard — the bill of materials must declare the jars the bundle actually ships.
 *
 * WHY THIS EXISTS. `tools/sbom.mjs` built its first-party table from four artifacts (inspecto-processor,
 * inspecto-connectors, inspecto-security, inspecto-policy) under a comment claiming it was "the SAME
 * table package.ps1 stages from". It was not. Since EDG-01 (2026-09-07) package.ps1 has staged eleven
 * jars for Standard and twelve for Enterprise, so every Standard bill of materials declared 4 first-party
 * jars where the bundle carried 10, and Enterprise 4 where it carried 11. javax.mail appeared in NO
 * shipped SBOM at all: it moved from inspecto-connectors to inspecto-notify-channels in EDG-01 cell 1,
 * and the generator had never heard of that module.
 *
 * package.ps1 generates these documents AFTER staging and BEFORE zipping, so they ship inside the archive
 * and are covered by its checksum and GPG signature. docs/compliance/controls-matrix.md and
 * docs/okf/capabilities/compliance/compliance.md both rely on them. Nothing in the repo could catch the
 * gap: there was no test over the generator, and .github/workflows/release.yml merely copies its output.
 *
 * The claim "these two lists agree" is the thing that failed. This guard is that claim, made runnable.
 *
 * WHAT IT CHECKS. package.ps1 enumerates the staged jars THREE times, independently:
 *   A. the `$modules` assignment — the Maven module list built per edition;
 *   B. the `Copy-Item … "$bundleDir\*.jar"` staging steps — what lands in the bundle;
 *   C. the boot-smoke classpath — the same list the generated launchers build.
 * It parses all three, requires them to agree with each other, and then requires them to agree with
 * tools/bundle-modules.mjs, which is what sbom.mjs actually renders. It also reads each module's pom to
 * confirm the declared artifactId, because sbom.mjs keys its table by artifactId and matches it against
 * Maven's per-module banners — a wrong dir→artifactId pair silently contributes ZERO components for that
 * module, which is the same under-declaration this guard exists to stop.
 *
 * ⛔ It does NOT check that a staged jar is usable — package.ps1 already verifies SPI registrations and
 * marker classes on each staged artifact, and the boot smoke runs the assembled classpath.
 *
 * Pure Node, no dependencies, ~instant. Run by `.github/workflows/ci.yml`.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { EDITIONS, PG_SIDECAR, bundleModules, editionOnlyModules } from './bundle-modules.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = 'inspecto/package.ps1';

/**
 * Emptiness floors. package.ps1 has staged 12 jars since EDG-01. A parse that finds fewer has almost
 * certainly stopped matching the script rather than found jars deleted — the failure mode this repo has
 * hit repeatedly. ⚠ Lower these only alongside a real, deliberate shrink — never to make a red build green.
 */
const MIN_STAGED = 12;
const MIN_EDITION_MODULES = 8;

const problems = [];
function fail(message) {
    console.error(`✗ SBOM module-set guard: ${message}`);
    process.exit(1);
}
const set = (xs) => new Set(xs);
function diff(actual, expected) {
    const missing = [...expected].filter((x) => !actual.has(x));
    const extra = [...actual].filter((x) => !expected.has(x));
    return { missing, extra, same: !missing.length && !extra.length };
}

const text = readFileSync(join(repoRoot, SCRIPT), 'utf8');

// ── A. the `$modules` assignment: the Maven modules built per edition ────────────────────────────
const modulesAssign =
    /\$modules\s*=\s*if\s*\(\s*\$Edition\s+-eq\s+'Enterprise'\s*\)\s*\{\s*'([^']+)'\s*\}\s*else\s*\{\s*'([^']+)'\s*\}/.exec(
        text,
    );
if (!modulesAssign) {
    fail(
        `${SCRIPT} has no parseable \`$modules = if ($Edition -eq 'Enterprise') { … } else { … }\` ` +
            `assignment. Either it was restructured (fix this parser) or the per-edition module list is ` +
            `gone. A guard that silently matches nothing is worse than no guard.`,
    );
}
const psModules = {
    Enterprise: modulesAssign[1].split(',').map((s) => s.trim()).filter(Boolean),
    Standard: modulesAssign[2].split(',').map((s) => s.trim()).filter(Boolean),
};
for (const [edition, list] of Object.entries(psModules)) {
    if (list.length < MIN_EDITION_MODULES) {
        fail(
            `${SCRIPT}'s $modules names only ${list.length} module(s) for ${edition}, below the floor of ` +
                `${MIN_EDITION_MODULES}. Either the list really shrank (lower the floor deliberately) or the parse broke.`,
        );
    }
}

// ── B. the staging steps: what actually lands in the bundle ──────────────────────────────────────
// A Copy-Item at column 0 is unconditional (every edition); an indented one sits inside an edition `if`.
// This reads the script's INDENTATION on purpose: if package.ps1 is ever reformatted, the Personal
// assertion below fails loudly rather than quietly passing over a set nobody matched.
// `[ \t]*`, not `\s*`: `\s` matches newlines, so a greedy run would start at an earlier line and
// capture the newline as part of the "indentation", making every match look indented.
const STAGE = /^([ \t]*)Copy-Item[ \t]+\$\w+[ \t]+"\$bundleDir\\([\w.-]+\.jar)"/gm;
const staged = new Set();
const stagedAlways = new Set();
for (const m of text.matchAll(STAGE)) {
    staged.add(m[2]);
    if (m[1] === '') stagedAlways.add(m[2]);
}
if (staged.size < MIN_STAGED) {
    fail(
        `only ${staged.size} staged jar(s) parsed out of ${SCRIPT}, below the floor of ${MIN_STAGED}. ` +
            `Either the staging block changed shape (fix this parser) or jars were dropped.`,
    );
}

// ── C. the boot-smoke classpath: the same list the generated launchers build ─────────────────────
const cpStart = text.indexOf('$cp = @(');
if (cpStart < 0) fail(`${SCRIPT} has no \`$cp = @(\` boot-smoke classpath to cross-check the staging steps against.`);
const cpEnd = text.indexOf('Where-Object', cpStart);
const cpJars = set(
    [...text.slice(cpStart, cpEnd < 0 ? cpStart + 2000 : cpEnd).matchAll(/'([\w.-]+\.jar)'/g)].map((m) => m[1]),
);
if (cpJars.size < MIN_STAGED) {
    fail(
        `only ${cpJars.size} jar(s) parsed out of ${SCRIPT}'s boot-smoke classpath, below the floor of ` +
            `${MIN_STAGED}. The classpath literal changed shape — fix this parser.`,
    );
}

// ── package.ps1 must agree with ITSELF ───────────────────────────────────────────────────────────
const selfCheck = diff(cpJars, staged);
if (!selfCheck.same) {
    problems.push(
        `${SCRIPT} contradicts itself: the boot-smoke classpath and the staging steps name different jars` +
            (selfCheck.missing.length ? `\n      staged but never on the boot classpath: ${selfCheck.missing.join(', ')}` : '') +
            (selfCheck.extra.length ? `\n      on the boot classpath but never staged: ${selfCheck.extra.join(', ')}` : ''),
    );
}

// ── the generator must agree with package.ps1 ────────────────────────────────────────────────────
// Enterprise is the maximal bundle, so its first-party jars plus the PostgreSQL sidecar are every jar
// package.ps1 can stage. sbom.mjs adds PG_SIDECAR itself (third-party, test-scoped in the reactor).
const maximal = set([...bundleModules('Enterprise').map((m) => m.bundleFile), PG_SIDECAR]);
const maximalCheck = diff(staged, maximal);
if (!maximalCheck.same) {
    problems.push(
        `the shipped bill of materials does not match the bundle:` +
            (maximalCheck.missing.length
                ? `\n      tools/bundle-modules.mjs declares, but ${SCRIPT} never stages: ${maximalCheck.missing.join(', ')}`
                : '') +
            (maximalCheck.extra.length
                ? `\n      ${SCRIPT} stages, but no SBOM would declare: ${maximalCheck.extra.join(', ')}`
                : ''),
    );
}

// Per-edition membership comes from $modules, which names reactor DIRECTORIES.
for (const edition of ['Standard', 'Enterprise']) {
    const expected = set(editionOnlyModules(edition).map((m) => m.dir));
    const check = diff(set(psModules[edition]), expected);
    if (!check.same) {
        problems.push(
            `${edition}: the generator's edition modules do not match ${SCRIPT}'s $modules` +
                (check.missing.length ? `\n      declared by the generator, not built by package.ps1: ${check.missing.join(', ')}` : '') +
                (check.extra.length ? `\n      built by package.ps1, absent from the generator: ${check.extra.join(', ')}` : ''),
        );
    }
}

// Personal ships exactly what package.ps1 stages unconditionally.
const personalCheck = diff(stagedAlways, set(bundleModules('Personal').map((m) => m.bundleFile)));
if (!personalCheck.same) {
    problems.push(
        `Personal: the generator's set does not match the jars ${SCRIPT} stages unconditionally` +
            (personalCheck.missing.length ? `\n      declared for Personal, staged only conditionally: ${personalCheck.missing.join(', ')}` : '') +
            (personalCheck.extra.length ? `\n      staged in every edition, absent from Personal's set: ${personalCheck.extra.join(', ')}` : ''),
    );
}

// ── each module's declared artifactId ────────────────────────────────────────────────────────────
// sbom.mjs keys SHIPPED by artifactId and matches it against Maven's per-module banners. A wrong
// dir→artifactId pair contributes ZERO components for that module and says nothing while doing it.
for (const m of bundleModules('Enterprise')) {
    let pom;
    try {
        pom = readFileSync(join(repoRoot, m.dir, 'pom.xml'), 'utf8');
    } catch {
        problems.push(`${m.dir}/pom.xml does not exist, but tools/bundle-modules.mjs stages ${m.bundleFile} from it`);
        continue;
    }
    const declared = /<artifactId>([^<]+)<\/artifactId>/.exec(pom.replace(/<parent>[\s\S]*?<\/parent>/, ''));
    if (!declared) {
        problems.push(`${m.dir}/pom.xml declares no artifactId outside its <parent> block`);
    } else if (declared[1] !== m.artifactId) {
        problems.push(
            `${m.dir}/pom.xml declares artifactId '${declared[1]}', but tools/bundle-modules.mjs says ` +
                `'${m.artifactId}'. sbom.mjs matches Maven's module banners by artifactId, so this module ` +
                `would contribute NO components to the bill of materials — silently.`,
        );
    }
}

if (problems.length) {
    fail(
        `the shipped bill of materials and the bundle disagree:\n  - ${problems.join('\n  - ')}\n` +
            `  ${SCRIPT} is the authority on what ships. Fix tools/bundle-modules.mjs to match it — and if a\n` +
            `  module was genuinely added or removed, change BOTH, never just the one that is red.`,
    );
}

const counts = EDITIONS.map((e) => `${e} ${bundleModules(e).length}`).join(', ');
console.log(
    `✓ SBOM module-set guard: ${staged.size} staged jar(s) in ${SCRIPT} match the generator's table ` +
        `across all 3 enumerations; first-party per edition — ${counts}.`,
);
