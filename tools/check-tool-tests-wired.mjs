#!/usr/bin/env node
/**
 * Tool-test wiring guard — every `tools/*.test.mjs` must be named by a GitHub workflow, or be listed here as
 * known debt. A test file no gate runs proves nothing, and nothing says so.
 *
 * WHY THIS EXISTS (`GUARD-FIXTURES-UNRUN-1`, filed 2026-11-14). `tools/check-launchers.test.mjs` — the negative
 * fixtures for the launcher guard, the file that exists precisely to prove that guard still goes red — was run by
 * NEITHER `ci.yml` NOR the pre-commit hook. On 2026-11-14 a commit changed the `serve.bat` lines one of its
 * fixtures anchored on. Its `mutate` helper replaces the first occurrence of a literal, so a missing anchor
 * mutates NOTHING: the fixture asserted that an unmutated package.ps1 goes red, which it does not, and the test
 * failed while the rule it tests was still perfectly correct. Nobody saw it for two commits, because no gate ran
 * the file. ⛔ That is the quietest failure a guard can have: the guard passes, its proof-of-redness rots, and the
 * next real regression walks through.
 *
 * The same question applies to every other tool test, and the answer was worse than expected: at the time of
 * writing 54 of 111 were named by no workflow at all (see BASELINE).
 *
 * WHAT IT CHECKS
 *   1. Every `*.test.mjs` under the scanned directories is NAMED (by basename) in some `.github/workflows/*.yml`.
 *   2. The scan finds at least MIN_TESTS files — an emptiness floor, so a glob that silently matches nothing
 *      cannot pass. (This repository's guards keep hitting that failure mode; see check-family-count.mjs.)
 *   3. The BASELINE may only SHRINK. A file in it that is NOW wired is a finding, not a pass — otherwise the
 *      list rots into a permanent exemption nobody revisits. Delete the line in the same change that wires it.
 *   4. Every BASELINE entry still exists. A stale name hides the fact that the debt moved rather than went.
 *
 * ⛔ It does NOT check that a wired test actually RUNS, or passes — only that some workflow names it. A step
 * guarded by an `if:` that is always false would still satisfy this. That is a deliberate limit: the cheap
 * structural check is what makes it runnable in the pre-commit hook on every commit.
 *
 * ⚠ WIRING ONE IN IS NOT FREE. The hook runs every `run: node …` step of ci.yml's `guards:` job, so a test added
 * THERE lands on every commit in this shared checkout. `tools/check-launchers.test.mjs` takes ~100 s (it executes
 * launchers per scenario), which is why it is wired into the `launchers-windows` job instead — CI runs it, the
 * hook does not. Prefer that for anything slow.
 *
 * Usage:  node tools/check-tool-tests-wired.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TEST_DIRS = ['tools', 'inspecto-ui/tools'];   // repo-relative, forward slashes: they are printed in the summary
const WORKFLOWS = join(ROOT, '.github', 'workflows');
/** An emptiness floor: a glob matching nothing must fail, not pass. Raise it as the tree grows; never lower it to pass. */
const MIN_TESTS = 100;

/**
 * Known debt: tool tests no workflow names. ⛔ This list may only SHRINK — see rule 3. Wiring one in means
 * deleting its line here in the SAME change. Do not add to it: a NEW test file is wired when it is written.
 *
 * 54 entries on 2026-11-14 when this guard was written; 46 after the first payment the same day, which wired
 * the guard-fixture group — the one that shares the hazard that prompted this guard, a guard whose proof of
 * redness runs nowhere. What is left, none of it approved, all of it unrun:
 *   • showcase data generators, seeds and smokes (`gen-la-*`, `seed-la-*`, `la-*-smoke`, `seed-telco-fraud-demo`)
 *     — many are slow or need a running server, so they likely belong in a nightly, not `guards:`;
 *   • UI tool lints (`inspecto-ui/tools/*`) — their GUARDS run in CI, their fixtures do not;
 *   • three odds and ends: `regroup-modules`, `release-notes`, `vuln-db-refresh`.
 * Tracked as `TOOL-TESTS-UNWIRED-BASELINE-1` in docs/BACKLOG.md.
 */
const BASELINE = new Set([
    'regroup-modules.test.mjs', 'release-notes.test.mjs', 'vuln-db-refresh.test.mjs',
    'gen-la-audit-data.test.mjs', 'gen-la-benefits-data.test.mjs', 'gen-la-card-data.test.mjs',
    'gen-la-claims-data.test.mjs', 'gen-la-crypto-data.test.mjs', 'gen-la-health-data.test.mjs',
    'gen-la-insider-data.test.mjs', 'gen-la-logistics-data.test.mjs', 'gen-la-mfs-data.test.mjs',
    'gen-la-mm-data.test.mjs', 'gen-la-ownership-data.test.mjs', 'gen-la-police-data.test.mjs',
    'gen-la-procurement-data.test.mjs', 'gen-la-telco-ra-data.test.mjs', 'gen-la-trade-data.test.mjs',
    'gen-la-utility-data.test.mjs', 'gen-la-video7-data.test.mjs',
    'la-audit-smoke.test.mjs', 'la-claims-smoke.test.mjs', 'la-mm-smoke.test.mjs',
    'seed-la-audit.test.mjs', 'seed-la-benefits.test.mjs', 'seed-la-card.test.mjs', 'seed-la-claims.test.mjs',
    'seed-la-crypto.test.mjs', 'seed-la-health.test.mjs', 'seed-la-insider.test.mjs',
    'seed-la-logistics.test.mjs', 'seed-la-mfs.test.mjs', 'seed-la-mm.test.mjs', 'seed-la-ownership.test.mjs',
    'seed-la-police.test.mjs', 'seed-la-procurement.test.mjs', 'seed-la-telco-ra.test.mjs',
    'seed-la-trade.test.mjs', 'seed-la-utility.test.mjs', 'seed-telco-fraud-demo.test.mjs',
    'check-bundle-shape.test.mjs', 'check-design-tokens.test.mjs', 'check-npm-denylist.test.mjs',
    'core-no-la-imports-lint.test.mjs', 'la-dynamic-import-lint.test.mjs', 'la-static-import-lint.test.mjs',
]);

/** Every tool test in the repo, as `<dir>/<file>` paths. Pure but for the directory reads. */
export function testFiles(root = ROOT, dirs = TEST_DIRS) {
    const out = [];
    for (const d of dirs) {
        for (const f of readdirSync(join(root, d))) if (f.endsWith('.test.mjs')) out.push(`${d.split('\\').join('/')}/${f}`);
    }
    return out.sort();
}

/** The concatenated text of every workflow file — what "a workflow names it" is decided against. */
export function workflowText(dir = WORKFLOWS) {
    return readdirSync(dir).filter((f) => /\.ya?ml$/.test(f))
        .map((f) => readFileSync(join(dir, f), 'utf8')).join('\n');
}

/** Findings over a set of test paths and the workflow text. Pure — the negative fixtures drive this directly. */
export function findings(files, workflows, baseline = BASELINE) {
    const out = [];
    if (files.length < MIN_TESTS)
        out.push(`only ${files.length} tool test(s) found, fewer than the floor of ${MIN_TESTS} — the scan matched almost nothing, which is a broken guard, not a clean tree.`);
    const names = new Set(files.map((f) => basename(f)));
    for (const f of files) {
        const name = basename(f);
        const wired = workflows.includes(name);
        if (!wired && !baseline.has(name))
            out.push(`${f} is named by NO workflow — no gate runs it, so it proves nothing. Wire it into .github/workflows/ (⚠ ci.yml's \`guards:\` job also runs on every commit via the pre-commit hook — put anything slow in another job).`);
        if (wired && baseline.has(name))
            out.push(`${f} is wired now but is still listed in this guard's BASELINE — delete that line, in the same change that wired it, or the list rots into an exemption nobody revisits.`);
    }
    for (const name of baseline) {
        if (!names.has(name))
            out.push(`the BASELINE names ${name}, which no longer exists — remove the line (and check the debt went away rather than moved).`);
    }
    return out;
}

function main() {
    const files = testFiles();
    const problems = findings(files, workflowText());
    if (problems.length) {
        console.error(`✗ Tool-test wiring guard:\n  - ${problems.join('\n  - ')}\n` +
            `  A test file no gate runs is not coverage. See GUARD-FIXTURES-UNRUN-1 in docs/BACKLOG.md.`);
        process.exit(1);
    }
    const wired = files.length - BASELINE.size;
    console.log(`✓ Tool-test wiring guard: ${wired} of ${files.length} tool test(s) are named by a workflow; ` +
        `${BASELINE.size} are KNOWN DEBT in this guard's BASELINE (unrun by any gate, not approved — the list may only shrink). ` +
        `Scope: ${TEST_DIRS.join(', ')}; a test counts as wired when any .github/workflows/*.yml names its file, ` +
        `which does NOT prove the step runs or passes.`);
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('check-tool-tests-wired.mjs')) main();
