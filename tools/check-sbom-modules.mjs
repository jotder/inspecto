#!/usr/bin/env node
/**
 * SBOM module-set guard — the bill of materials must declare the jars the bundle actually ships.
 *
 * WHY THIS EXISTS. `tools/sbom.mjs` built its first-party table from four artifacts (inspecto-processor,
 * inspecto-connectors, inspecto-oidc, inspecto-policy) under a comment claiming it was "the SAME
 * table package.ps1 stages from". It was not. Since EDG-01 (2026-09-07) package.ps1 has staged eleven
 * jars for Standard and twelve for Enterprise, so every Standard bill of materials declared 4 first-party
 * jars where the bundle carried 10, and Enterprise 4 where it carried 11. javax.mail appeared in NO
 * shipped SBOM at all: it moved from inspecto-connectors to inspecto-notify-channels in EDG-01 cell 1, and
 * the generator had never heard of that module.
 *
 * package.ps1 generates these documents AFTER staging and BEFORE zipping, so they ship inside the archive
 * and are covered by its checksum and GPG signature. compliance/controls-matrix.md and
 * docs/okf/capabilities/compliance/compliance.md both rely on them. Nothing in the repo could catch the
 * gap: there was no test over the generator, and .github/workflows/release.yml merely copies its output.
 *
 * The claim "these two lists agree" is the thing that failed. This guard is that claim, made runnable.
 *
 * WHAT IT CHECKS (P3d stage 1, 2026-10-08 — package.ps1 no longer keeps three hand-written enumerations):
 *   A. the Maven module list is no longer a literal: package.ps1 must take `$modules` from
 *      `tools/offering-classpath.mjs --list-mvn`, and that generator's list must equal the generator
 *      table's edition-only modules for every edition (Professional, Enterprise, Preview);
 *   B. the `Copy-Item … "$bundleDir\*.jar"` staging steps — what lands in the bundle — must equal
 *      tools/bundle-modules.mjs's maximal (Enterprise) set + the PostgreSQL sidecar, and Personal must equal
 *      what is staged unconditionally;
 *   C. the boot smoke and every launcher must READ the bundle's `modules.list` (no jar list of their own),
 *      and the classpath the generator emits for the maximal edition must equal the staged jars — so a staged
 *      jar the list forgets (or a listed jar nothing stages) is red here, not a boot failure on a customer.
 * It also reads each module's pom to confirm the declared artifactId, because sbom.mjs keys its table by
 * artifactId and matches it against Maven's per-module banners — a wrong dir→artifactId pair silently
 * contributes ZERO components for that module, which is the same under-declaration this guard exists to stop.
 *
 * ⛔ It does NOT check that a staged jar is usable — package.ps1 already verifies SPI registrations and
 * marker classes on each staged artifact, and the boot smoke runs the assembled classpath.
 *
 * Pure Node, no dependencies, ~instant. Run by `.github/workflows/ci.yml`. The checks are `analyze()`;
 * tools/check-sbom-modules.test.mjs holds negative fixtures proving each rule still goes red.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { EDITIONS, PG_SIDECAR, bundleModules, editionOnlyModules, coreModules } from './bundle-modules.mjs';
import { classpath, mvnModules } from './offering-classpath.mjs';
import { gather } from './check-offerings.mjs';
import { verifyPerModule } from './sbom-modules.mjs';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPT = 'inspecto/package.ps1';

/**
 * Emptiness floor. package.ps1 has staged 12 jars since EDG-01. A parse that finds fewer has almost
 * certainly stopped matching the script rather than found jars deleted — the failure mode this repo has
 * hit repeatedly. ⚠ Lower this only alongside a real, deliberate shrink — never to make a red build green.
 */
const MIN_STAGED = 12;

const set = (xs) => new Set(xs);
function diff(actual, expected) {
    const missing = [...expected].filter((x) => !actual.has(x));
    const extra = [...actual].filter((x) => !expected.has(x));
    return { missing, extra, same: !missing.length && !extra.length };
}

/**
 * All findings for a package.ps1 text. `bundleModulesText` is the text of tools/bundle-modules.mjs (for the
 * stated per-edition counts); `generated` is `{ mvn: {edition: '-pl string'}, classpathMax: [jars] }` and defaults
 * to what tools/offering-classpath.mjs produces for this repo.
 */
export function analyze(text, { root = repoRoot, bundleModulesText, generated, processorPomText } = {}) {
    const problems = [];
    const bmText = bundleModulesText ?? readFileSync(join(root, 'tools/bundle-modules.mjs'), 'utf8');
    let gen = generated;
    if (!gen) {
        const g = gather(root);
        gen = { mvn: Object.fromEntries(['Professional', 'Enterprise', 'Preview'].map((e) => [e, mvnModules(e, g)])), classpathMax: classpath('Enterprise', { g }) };
    }

    // ── A. the Maven module list must come from the generator, not from a literal ───────────────────────────
    if (/\$modules\s*=\s*if\s*\(/.test(text)) {
        problems.push(`${SCRIPT} still assigns \`$modules\` from a hand-written \`if (...) { '...' } else { '...' }\` literal — it must come from \`tools/offering-classpath.mjs --list-mvn\` (the Offering), not a second list.`);
    }
    if (!/\$modules\s*=[^\n]*offering-classpath\.mjs[^\n]*--list-mvn/.test(text)) {
        problems.push(`${SCRIPT} does not take \`$modules\` from \`tools/offering-classpath.mjs ... --list-mvn\`. Either it was restructured (fix this parser) or the per-edition module list is hand-kept again.`);
    }
    for (const edition of ['Professional', 'Enterprise', 'Preview']) {
        const expected = set(editionOnlyModules(edition).map((m) => `:${m.artifactId}`));
        const got = set((gen.mvn[edition] ?? '').split(',').filter(Boolean));
        const check = diff(got, expected);
        if (!check.same) {
            problems.push(
                `${edition}: \`offering-classpath.mjs --list-mvn\` does not match the generator table's edition modules` +
                    (check.missing.length ? `\n      in tools/bundle-modules.mjs, not built by package.ps1: ${check.missing.join(', ')}` : '') +
                    (check.extra.length ? `\n      built by package.ps1, absent from tools/bundle-modules.mjs: ${check.extra.join(', ')}` : ''),
            );
        }
    }

    // ── B. the staging steps: what actually lands in the bundle ──────────────────────────────────────────────
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
        problems.push(`only ${staged.size} staged jar(s) parsed out of ${SCRIPT}, below the floor of ${MIN_STAGED}. Either the staging block changed shape (fix this parser) or jars were dropped.`);
    }
    // Enterprise is the maximal bundle, so its first-party jars plus the PostgreSQL sidecar are every jar
    // package.ps1 can stage. sbom.mjs adds PG_SIDECAR itself (third-party, test-scoped in the reactor).
    const maximal = set([...bundleModules('Enterprise').map((m) => m.bundleFile), PG_SIDECAR]);
    const maximalCheck = diff(staged, maximal);
    if (!maximalCheck.same) {
        problems.push(
            `the shipped bill of materials does not match the bundle:` +
                (maximalCheck.missing.length ? `\n      tools/bundle-modules.mjs declares, but ${SCRIPT} never stages: ${maximalCheck.missing.join(', ')}` : '') +
                (maximalCheck.extra.length ? `\n      ${SCRIPT} stages, but no SBOM would declare: ${maximalCheck.extra.join(', ')}` : ''),
        );
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

    // ── C. classpath: the generator's list covers exactly what is staged; boot smoke + launchers READ it ───
    // P3d stage 2: the core thin jars are staged by a loop over `--list-core` (rule D), so they are not Copy-Item lines; the list must name them too.
    const coreJars = coreModules().map((m) => m.bundleFile);
    const cpCheck = diff(set(gen.classpathMax), set([...staged, ...coreJars]));
    if (!cpCheck.same) {
        problems.push(
            `the classpath tools/offering-classpath.mjs emits for the maximal edition does not match the jars ${SCRIPT} stages` +
                (cpCheck.missing.length ? `\n      staged but absent from modules.list: ${cpCheck.missing.join(', ')}` : '') +
                (cpCheck.extra.length ? `\n      on modules.list but never staged: ${cpCheck.extra.join(', ')}` : ''),
        );
    }
    if (!/\$cp\s*=\s*@\(\s*Get-Content[^\n]*modules\.list/.test(text)) {
        problems.push(`${SCRIPT}'s boot-smoke \`$cp\` is not read from the bundle's modules.list (\`$cp = @(Get-Content … 'modules.list' …)\`) — a hand-kept literal would let the smoke boot a different classpath than the launchers.`);
    }
    if (!/offering-classpath\.mjs'\)\s+@classpathArgs/.test(text)) {
        problems.push(`${SCRIPT} never runs tools/offering-classpath.mjs against the bundle (\`& node …offering-classpath.mjs @classpathArgs\`), so no modules.list/edition.properties would be written.`);
    }
    // No launcher may keep a jar list of its own: any shell/batch statement that appends a NAMED inspecto jar to CP.
    const handKept = [
        ...[...text.matchAll(/^.*\[ -f \S+\.jar \]\s*&&\s*CP="\$\{CP\}:\S+\.jar".*$/gm)],
        ...[...text.matchAll(/^.*if exist \S+\.jar set "CP=%CP%;\S+\.jar".*$/gm)],
        ...[...text.matchAll(/^.*CP="inspecto\.jar:inspecto-\S+\.jar".*$/gm)],
    ].map((m) => m[0].trim());
    if (handKept.length) {
        problems.push(`${SCRIPT} still hand-keeps a classpath: ${handKept.length} launcher statement(s) append a named jar to CP (first: ${handKept[0].slice(0, 100)}) — the launchers must read modules.list.`);
    }
    const hereStrings = ['runShContent', 'runBatContent', 'serveShContent', 'serveBatContent'];
    for (const name of hereStrings) {
        const open = text.indexOf(`$${name} = @'`);
        const end = open < 0 ? -1 : text.indexOf("\n'@", open + 1);
        if (open < 0 || end < 0) { problems.push(`${SCRIPT} has no single-quoted here-string \`$${name}\` — renamed? fix this parser, do not delete the check.`); continue; }
        if (!text.slice(open, end).includes('modules.list')) problems.push(`the ${name} launcher here-string never reads modules.list.`);
    }
    const serveHere = ['serveShContent', 'serveBatContent'].map((n) => { const o = text.indexOf(`$${n} = @'`); return o < 0 ? '' : text.slice(o, text.indexOf("\n'@", o + 1)); }).join('\n');
    if (!serveHere.includes('edition.properties')) problems.push('the serve.sh/serve.bat launchers never read edition.properties — the edition would be guessed from which jars are present again.');

    // ── D. the core thin jars (P3d stage 2): staged from the generator's list, and the processor's shade excludes exactly them ─────
    // inspecto.jar used to shade every first-party library in. If the pom's artifactSet stops excluding one, that library would
    // ship twice (inside inspecto.jar AND as its thin jar, which modules.list also names) - classes duplicated, signing impossible.
    // If it excludes one the generator does not stage, the library would ship NOWHERE and the bundle would die at boot.
    if (!/offering-classpath\.mjs'\)\s+--list-core/.test(text)) {
        problems.push(`${SCRIPT} does not stage the core thin jars from \`tools/offering-classpath.mjs --list-core\` - a hand-kept list of them would drift from tools/bundle-modules.mjs CORE_MODULES and from the shade excludes.`);
    }
    let procPom = processorPomText;
    if (procPom === undefined) {
        try { procPom = readFileSync(join(root, 'inspecto/pom.xml'), 'utf8'); } catch { procPom = null; }
    }
    if (procPom === null) {
        problems.push('inspecto/pom.xml is unreadable - cannot hold its shade artifactSet against CORE_MODULES.');
    } else {
        const noComments = procPom.replace(/<!--[\s\S]*?-->/g, '');
        const excl = /<artifactSet>\s*<excludes>([\s\S]*?)<\/excludes>\s*<\/artifactSet>/.exec(noComments);
        if (!excl) {
            problems.push('inspecto/pom.xml has no shade <artifactSet><excludes> - every first-party library would be shaded into inspecto.jar again, next to its own thin jar.');
        } else {
            const excluded = set([...excl[1].matchAll(/<exclude>\s*com\.gamma\.inspector:([\w.-]+)\s*<\/exclude>/g)].map((m) => m[1]));
            const core = set(coreModules().map((m) => m.artifactId));
            const d = diff(excluded, core);
            if (!d.same) {
                problems.push(
                    `inspecto/pom.xml's shade artifactSet excludes do not equal tools/bundle-modules.mjs CORE_MODULES` +
                        (d.missing.length ? `
      core thin jar shipped but ALSO shaded into inspecto.jar (not excluded): ${d.missing.join(', ')}` : '') +
                        (d.extra.length ? `
      excluded from the shade but not a core thin jar (it would ship nowhere): ${d.extra.join(', ')}` : ''),
                );
            }
        }
        if (/<Class-Path>|<mainClass>/.test(noComments)) {
            problems.push('inspecto/pom.xml gives inspecto.jar a Main-Class / Class-Path - it is the product jar beside thin libraries now; launchers use `-cp` from modules.list, a manifest entry would hide a missing list.');
        }
    }

    // ── E. per-module SBOMs (P3f): generated by sbom.mjs with the combined one, then VERIFIED against the staged bundle ─────────────────
    // Every jar on modules.list has `sbom/<jar>.sbom.cdx.json`; verification (tools/sbom-modules.mjs --verify, also runnable here as
    // `--bundle <dir>`) is a packaging gate, so a per-jar bill of materials that disagrees with its jar never reaches the zip.
    if (!/sbom\.mjs'\)[^\n]*--build-id/.test(text)) {
        problems.push(`${SCRIPT} runs tools/sbom.mjs without \`--build-id\` - the per-module SBOMs would not record which build each jar came from.`);
    }
    const verifyAt = text.search(/sbom-modules\.mjs'\)\s+--verify\s+\$bundleDir/);
    if (verifyAt < 0) {
        problems.push(`${SCRIPT} never runs \`tools/sbom-modules.mjs --verify $bundleDir\` - per-module SBOMs could be missing or stale for a staged jar and the bundle would still ship.`);
    } else {
        if (!/sbom-modules\.mjs'\)\s+--verify\s+\$bundleDir\s*\r?\n\s*if \(\$LASTEXITCODE -ne 0\) \{ throw/.test(text)) {
            problems.push(`${SCRIPT} runs per-module SBOM verification but does not throw on a non-zero exit - a failed check would not stop packaging.`);
        }
        const listAt = text.search(/offering-classpath\.mjs'\)\s+@classpathArgs/);
        if (listAt >= 0 && verifyAt < listAt) {
            problems.push(`${SCRIPT} verifies per-module SBOMs BEFORE modules.list is written - the verification reads modules.list and would check nothing.`);
        }
    }

    // ── each module's declared artifactId ────────────────────────────────────────────────────────────────────
    // sbom.mjs keys SHIPPED by artifactId and matches it against Maven's per-module banners. A wrong
    // dir→artifactId pair contributes ZERO components for that module and says nothing while doing it.
    for (const m of [...bundleModules('Enterprise'), ...coreModules()]) {
        let pom;
        try {
            pom = readFileSync(join(root, m.dir, 'pom.xml'), 'utf8');
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

    // ── the per-edition counts bundle-modules.mjs states in prose ───────────────────────────────────────────
    // That file's `bundleModules` doc comment names the counts a reader will trust without running
    // anything. It drifted the moment inspecto-agent arrived (PKG-5) and said 2/10/11 against a real
    // 2/11/12 for five days. Prose beside a list is not checked by the list, so check it here.
    const COUNTS = /Personal (\d+), Professional (\d+), Enterprise (\d+), Preview (\d+)/.exec(bmText);
    if (!COUNTS) {
        problems.push(
            `tools/bundle-modules.mjs no longer states its per-edition counts as \`Personal N, Professional N, Enterprise N\`. ` +
                `Either the comment was reworded (fix this parser) or it was dropped — a count nobody asserts is how that comment went stale in the first place.`,
        );
    } else {
        const stated = { Personal: +COUNTS[1], Professional: +COUNTS[2], Enterprise: +COUNTS[3], Preview: +COUNTS[4] };
        for (const edition of EDITIONS) {
            const actual = bundleModules(edition).length;
            if (stated[edition] !== actual) {
                problems.push(`tools/bundle-modules.mjs says ${edition} ships ${stated[edition]} first-party module(s), but its own MODULES table yields ${actual}. Correct the comment.`);
            }
        }
    }
    return { problems, staged };
}

function main() {
    const bi = process.argv.indexOf('--bundle');
    if (bi >= 0) {
        // A STAGED bundle (or an unzipped release): run the per-module checks over its real files.
        const dir = process.argv[bi + 1];
        const bp = dir ? verifyPerModule(dir) : ['--bundle needs a directory'];
        if (bp.length) { console.error(`✗ SBOM module-set guard (bundle ${dir}):\n  - ${bp.join('\n  - ')}`); process.exit(1); }
        console.log(`✓ SBOM module-set guard (bundle ${dir}): per-module SBOMs present and consistent with modules.list and the combined SBOM.`);
        return;
    }
    const text = readFileSync(join(repoRoot, SCRIPT), 'utf8');
    const { problems, staged } = analyze(text);
    if (problems.length) {
        console.error(
            `✗ SBOM module-set guard: the shipped bill of materials and the bundle disagree:\n  - ${problems.join('\n  - ')}\n` +
                `  ${SCRIPT} is the authority on what ships. Fix tools/bundle-modules.mjs to match it — and if a\n` +
                `  module was genuinely added or removed, change BOTH, never just the one that is red.`,
        );
        process.exit(1);
    }
    const counts = EDITIONS.map((e) => `${e} ${bundleModules(e).length}`).join(', ');
    console.log(
        `✓ SBOM module-set guard: ${staged.size} staged jar(s) in ${SCRIPT} match the generator's table, ` +
            `the Offering classpath (modules.list) and the launchers that read it; first-party per edition — ${counts}.`,
    );
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
