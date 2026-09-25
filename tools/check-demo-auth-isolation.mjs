#!/usr/bin/env node
/**
 * Demo-auth isolation guard — the Demo User sign-in module must never reach a real (non-demo) bundle.
 *
 * WHY THIS EXISTS. `inspecto-demo-auth` (DEMO-AUTH-1) registers a permit-by-picker Authenticator: anyone
 * who can reach the sign-in page picks a Demo User and is signed in with that user's roles, no password.
 * It is for offline internal demos only. What keeps it out of a customer bundle was, until this guard,
 * convention alone: `inspecto/package.ps1` stages the jar only under `-DemoAuth`, and the jar sits outside
 * the three enumerations `tools/check-sbom-modules.mjs` parses. Nothing failed if either stopped being true.
 * The demo-auth plan promised a guard for exactly this and it was never built.
 *
 * WHAT IT CHECKS (each rule names the hole it closes):
 *   1. `tools/bundle-modules.mjs` — no edition's module set (Preview included: it takes EVERY module
 *      in MODULES unconditionally) contains inspecto-demo-auth. That table feeds sbom.mjs and the
 *      SBOM guard, so an entry there is a module declared as shipped.
 *   2. `inspecto/package.ps1` — every non-comment line that names inspecto-demo-auth lies inside an
 *      `if ($DemoAuth …) { … }` block. That one rule covers the `$modules` build list, the staging
 *      steps, the boot-smoke `$cp` literal and the serve.sh / serve.bat here-strings, all of which sit
 *      outside those blocks. It also requires the demo branch to REMOVE inspecto-security.jar and the
 *      demo launcher's `$demoJars` to omit it, so a demo bundle never carries two Authenticators.
 *   3. Every tracked pom.xml — none but the module's own declares a dependency on inspecto-demo-auth,
 *      and no edition profile lists it as a module. A dependency would ride into a shaded shipped jar
 *      (inspecto.jar is a fat jar) without any staging line mentioning it.
 *   4. Every other tracked launcher / script / workflow (*.sh *.bat *.cmd *.ps1 Dockerfile* *.yml
 *      *.yaml) — none names inspecto-demo-auth or passes -DemoAuth on a non-comment line. A release
 *      workflow calling `package.ps1 -DemoAuth` would publish a demo build as the release.
 *
 * The runtime backstop is in the core: the Authenticator slot refuses to boot when more than one
 * provider is registered (SpiSlot, fail-closed posture), so a hand-assembled classpath with both
 * inspecto-security.jar and inspecto-demo-auth.jar fails loudly instead of taking whichever came first.
 *
 * Usage: node tools/check-demo-auth-isolation.mjs [--root <dir>]   (--root exists for the fixture test,
 * tools/check-demo-auth-isolation.test.mjs; the default is this repo). Pure Node + git ls-files.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

const DEMO = 'inspecto-demo-auth';
const SCRIPT = 'inspecto/package.ps1';

const rootArg = process.argv.indexOf('--root');
const root = rootArg > 0 ? resolve(process.argv[rootArg + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..');

const problems = [];
const fail = (msg) => {
    console.error(`✗ demo-auth isolation guard: ${msg}`);
    process.exit(1);
};
const isComment = (line) => /^\s*(#|rem\b|::|<!--)/i.test(line);
const lsFiles = (...patterns) =>
    execFileSync('git', ['ls-files', '-z', '--', ...patterns], { cwd: root, encoding: 'utf8' })
        .split('\0')
        .filter(Boolean);

// ── 1. the edition module table ──────────────────────────────────────────────────────────────────
const { EDITIONS, bundleModules } = await import(pathToFileURL(join(root, 'tools/bundle-modules.mjs')).href);
for (const edition of EDITIONS) {
    const hit = bundleModules(edition).find((m) => [m.artifactId, m.dir, m.bundleFile].some((v) => v.includes(DEMO)));
    if (hit) problems.push(`tools/bundle-modules.mjs ships ${hit.bundleFile} in the ${edition} edition`);
}

// ── 2. package.ps1: every reference is gated by $DemoAuth ────────────────────────────────────────
const ps = readFileSync(join(root, SCRIPT), 'utf8');
/** [start, end) offsets of every `if (<cond naming $DemoAuth>) { … }` statement, found by paren/brace matching. */
function demoBlocks(text) {
    const blocks = [];
    for (const m of text.matchAll(/\bif\s*\(/g)) {
        let i = m.index + m[0].length;
        let depth = 1;
        const condStart = i;
        while (i < text.length && depth) depth += text[i] === '(' ? 1 : text[i] === ')' ? -1 : 0, i++;
        // Gated means the condition LEADS with $DemoAuth and cannot be true without it: `if (-not $DemoAuth)`
        // or `if ($x -or $DemoAuth)` is the opposite of a gate.
        const cond = text.slice(condStart, i - 1);
        if (!/^\s*\$DemoAuth\b/.test(cond) || /-or\b/i.test(cond)) continue;
        while (/\s/.test(text[i])) i++;
        if (text[i] !== '{') continue;
        const start = m.index; // the condition counts as gated too (`if ($DemoAuth -and (Test-Path …demo jar…))`)
        depth = 0;
        do depth += text[i] === '{' ? 1 : text[i] === '}' ? -1 : 0, i++;
        while (i < text.length && depth);
        if (depth) fail(`${SCRIPT}: an \`if ($DemoAuth)\` block never closes — the brace matcher lost its place. Fix this parser.`);
        blocks.push([start, i]);
    }
    return blocks;
}
const blocks = demoBlocks(ps);
if (blocks.length < 3) {
    fail(
        `${SCRIPT}: found ${blocks.length} \`if ($DemoAuth)\` block(s), expected at least 3 (bundle dir, staging swap, ` +
            `launchers). Either -DemoAuth was restructured (fix this parser) or removed (retire this guard deliberately).`,
    );
}
const inBlock = (offset) => blocks.some(([s, e]) => offset >= s && offset < e);
let offset = 0;
let gatedRefs = 0;
for (const [n, line] of ps.split('\n').entries()) {
    if (!isComment(line)) {
        for (let k = line.indexOf(DEMO); k >= 0; k = line.indexOf(DEMO, k + 1)) {
            if (inBlock(offset + k)) gatedRefs++;
            else problems.push(`${SCRIPT}:${n + 1} names ${DEMO} outside an \`if ($DemoAuth)\` block: ${line.trim()}`);
        }
    }
    offset += line.length + 1;
}
if (!gatedRefs) fail(`${SCRIPT}: no gated ${DEMO} reference found at all — the -DemoAuth branch changed shape. Fix this parser.`);
const gated = blocks.map(([s, e]) => ps.slice(s, e)).join('\n');
if (!/Remove-Item[^\n]*'inspecto-security\.jar'/.test(gated)) {
    problems.push(`${SCRIPT}: no \`if ($DemoAuth)\` block removes inspecto-security.jar — a demo bundle would carry two Authenticators`);
}
const demoJars = /\$demoJars\s*=\s*@\(([\s\S]*?)\)/.exec(ps);
if (!demoJars) fail(`${SCRIPT}: no \`$demoJars = @(…)\` demo launcher classpath found. Fix this parser.`);
if (demoJars[1].includes('inspecto-security.jar')) {
    problems.push(`${SCRIPT}: the demo launcher classpath ($demoJars) names inspecto-security.jar next to ${DEMO}.jar`);
}

// ── 3. poms: nothing depends on the module, no edition profile lists it ─────────────────────────
const poms = lsFiles('pom.xml', '*/pom.xml');
if (!poms.includes('pom.xml')) fail(`git ls-files under ${root} lists no root pom.xml — wrong --root?`);
for (const pom of poms) {
    if (pom === `${DEMO}/pom.xml`) continue;
    const xml = readFileSync(join(root, pom), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    for (const dep of xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
        if (new RegExp(`<artifactId>\\s*${DEMO}\\s*</artifactId>`).test(dep[1])) {
            problems.push(`${pom} declares a dependency on ${DEMO} — it would ride into a shipped jar`);
        }
    }
    for (const profile of xml.matchAll(/<profile>([\s\S]*?)<\/profile>/g)) {
        if (new RegExp(`<module>\\s*${DEMO}\\s*</module>`).test(profile[1])) {
            const id = /<id>([^<]+)<\/id>/.exec(profile[1])?.[1] ?? '?';
            problems.push(`${pom} profile '${id}' lists ${DEMO} as a module — no edition profile may carry it`);
        }
    }
}

// ── 4. every other launcher / script / workflow ─────────────────────────────────────────────────
for (const file of lsFiles('*.sh', '*.bat', '*.cmd', '*.ps1', '*Dockerfile*', '*.yml', '*.yaml')) {
    if (file === SCRIPT) continue;
    for (const [n, line] of readFileSync(join(root, file), 'utf8').split('\n').entries()) {
        if (!isComment(line) && (line.includes(DEMO) || /-DemoAuth\b/.test(line))) {
            problems.push(`${file}:${n + 1} stages or builds the demo sign-in outside package.ps1 -DemoAuth: ${line.trim()}`);
        }
    }
}

if (problems.length) {
    fail(
        `${DEMO} can reach a non-demo bundle:\n  - ${problems.join('\n  - ')}\n` +
            `  The Demo User sign-in has NO real authentication. Only \`package.ps1 -DemoAuth\` may stage it,\n` +
            `  and only in place of inspecto-security.jar (docs/okf/backend/editions/local-testing-without-iam.md).`,
    );
}
console.log(
    `✓ demo-auth isolation guard: ${DEMO} is in no edition's module set, ${gatedRefs} package.ps1 reference(s) all ` +
        `gated by -DemoAuth across ${blocks.length} block(s), ${poms.length} pom(s) and every other launcher clean.`,
);
