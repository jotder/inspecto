#!/usr/bin/env node
// Repo-wide code-coverage floors (GUARD-SWEEP-1g, operator decision 2026-09-08).
//
// WHY A SCRIPT AND NOT `jacoco:check`. jacoco's check goal is bound per module, and there is no
// report-aggregate module here, so binding it would enforce a floor on EVERY module independently —
// the opposite of the repo-wide floor that was chosen. inspecto-util (49.6%) and asn-golden (4.4%)
// would fail on their own numbers while the codebase as a whole sits comfortably above the floor.
// Summing the per-module CSVs is the only way to enforce one number, and it matches the guard idiom
// the rest of tools/ already uses.
//
// ⚠ REPORTS MUST EXIST. Absent reports FAIL rather than pass — a guard that silently no-ops when its
// input is missing is the first shape guard-coverage.md warns about, and this one would hit it every
// time someone forgot `-Pcoverage`.
//
// ⚠ FLOORS ARE DELIBERATELY BELOW THE MEASURED BASELINE, not equal to it. Coverage moves a little with
// any change; a floor set at the current number fires on noise and teaches people to ignore it.
// Baseline measured 2026-09-08 — backend instr 81.01% / branch 67.19%, UI stmt 73.82% / branch 70.04%.
//
// ⛔ These numbers are a POLICY choice, not a measurement. Raise them deliberately when coverage
// improves; do not lower one to make a red build green.

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, sep } from 'node:path';

const FLOORS = {
    backend: { instruction: 78.0, branch: 64.0 },
    ui: { statements: 70.0, branches: 66.0 },
};

const repoRoot = process.cwd();

// Which halves to enforce. ⚠ Explicit scope matters: ci.yml builds Java and never the UI, ui.yml the
// reverse, so a single "everything must be present" rule would fail whichever job legitimately lacks the
// other half. Each named scope REQUIRES its own source to exist — so `--backend` in a job that lost
// `-Pcoverage` still fails loudly, which is the property worth keeping. No flag = check what is present,
// requiring at least one.
const args = process.argv.slice(2);
const wantBackend = args.includes('--backend') || args.length === 0;
const wantUi = args.includes('--ui') || args.length === 0;
const explicit = args.length > 0;

/** Quote-aware CSV row split. ⚠ jacoco's GROUP column contains literal commas — e.g.
 *  "Inspecto — Operational objects (Standard edition, optional)" — so a naive split corrupts EVERY
 *  row of every module whose name has a parenthetical. Cost an hour the first time it was parsed. */
function splitCsvLine(line) {
    const out = [];
    let cur = '';
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '"') {
            if (quoted && line[i + 1] === '"') {
                cur += '"';
                i++;
            } else quoted = !quoted;
        } else if (c === ',' && !quoted) {
            out.push(cur);
            cur = '';
        } else cur += c;
    }
    out.push(cur);
    return out;
}

/** Every module's `target/site/jacoco/jacoco.csv` under the repo, at any module depth.
 *  ⚠ Do NOT write the glob with a leading star-slash in this comment — that sequence CLOSES the
 *  comment block and the rest of the file then parses as code (it did, with a baffling
 *  "Unexpected identifier 'mvn'" forty lines later). */
function findJacocoCsvs(dir, found = [], depth = 0) {
    if (depth > 4) return found;
    for (const name of readdirSync(dir)) {
        if (name === 'node_modules' || name === '.git' || name === '.claude') continue;
        const p = join(dir, name);
        let st;
        try {
            st = statSync(p);
        } catch {
            continue;
        }
        if (!st.isDirectory()) continue;
        const csv = join(p, 'target', 'site', 'jacoco', 'jacoco.csv');
        if (existsSync(csv)) found.push(csv);
        findJacocoCsvs(p, found, depth + 1);
    }
    return found;
}

function pct(covered, missed) {
    const total = covered + missed;
    return total === 0 ? null : (100 * covered) / total;
}

// ── backend ────────────────────────────────────────────────────────────────────────────────────────
// ⚠ Gated on wantBackend, exactly as the UI probe below is gated on wantUi: findJacocoCsvs walks
// the whole repo, and a `--ui` run in ui.yml has no Java build to find.
const csvs = wantBackend ? findJacocoCsvs(repoRoot) : [];
// ⚠ Only an EXPLICIT `--backend` requires its own input to exist — the same rule `--ui` follows
// below. A no-flag run "checks what is present, requiring at least one" (the args comment above) and
// leans on the both-missing check further down. ci.yml passes `--backend`, so a Java build that lost
// `-Pcoverage` STILL fails loudly there, which is the property worth keeping.
//
// 🔴 This was a bare `csvs.length === 0`, which made `check-coverage.mjs --ui` impossible to pass: it
// died demanding jacoco.csv before reaching the UI block, in the one job that by design never builds
// Java. ui.yml's "Coverage floors (UI)" step was red from b4bdc6d6 until this fix.
if (wantBackend && csvs.length === 0 && explicit) {
    console.error('\n✖ Coverage guard: CANNOT RUN — --backend was requested but no jacoco.csv exists.');
    console.error('  Run `mvn -o clean test -Pcoverage -Pedition-enterprise` first.');
    console.error('  ⚠ Failing rather than passing on purpose: a guard that no-ops when its input is');
    console.error('    missing would pass on every build that forgot the profile.');
    process.exit(1);
}

const perModule = [];
const totals = { im: 0, ic: 0, bm: 0, bc: 0, lm: 0, lc: 0 };
for (const csv of csvs) {
    const mod = csv.replace(repoRoot + sep, '').split(sep + 'target')[0];
    const lines = readFileSync(csv, 'utf8').trim().split(/\r?\n/);
    const head = splitCsvLine(lines[0]);
    const idx = (n) => head.indexOf(n);
    const m = { im: 0, ic: 0, bm: 0, bc: 0, lm: 0, lc: 0 };
    for (const line of lines.slice(1)) {
        const f = splitCsvLine(line);
        m.im += Number(f[idx('INSTRUCTION_MISSED')] || 0);
        m.ic += Number(f[idx('INSTRUCTION_COVERED')] || 0);
        m.bm += Number(f[idx('BRANCH_MISSED')] || 0);
        m.bc += Number(f[idx('BRANCH_COVERED')] || 0);
        m.lm += Number(f[idx('LINE_MISSED')] || 0);
        m.lc += Number(f[idx('LINE_COVERED')] || 0);
    }
    for (const k of Object.keys(totals)) totals[k] += m[k];
    perModule.push({ mod, instr: pct(m.ic, m.im), branch: pct(m.bc, m.bm), size: m.ic + m.im });
}

// null when there is no backend data at all — the shape `ui` already uses, so the report can skip a
// half it does not have instead of formatting nulls.
const backend = csvs.length
    ? { instruction: pct(totals.ic, totals.im), branch: pct(totals.bc, totals.bm), line: pct(totals.lc, totals.lm) }
    : null;

// ── UI (optional: present only after `npm run test:coverage`) ──────────────────────────────────────
let ui = null;
for (const p of wantUi ? ['inspecto-ui/coverage/gamma/coverage-summary.json', 'inspecto-ui/coverage/coverage-summary.json'] : []) {
    if (existsSync(join(repoRoot, p))) {
        const t = JSON.parse(readFileSync(join(repoRoot, p), 'utf8')).total;
        ui = { statements: t.statements.pct, branches: t.branches.pct, lines: t.lines.pct, path: p };
        break;
    }
}

if (wantUi && ui === null && explicit) {
    console.error('\n✖ Coverage guard: CANNOT RUN — --ui was requested but no coverage-summary.json exists.');
    console.error('  Run `npm run test:coverage` in inspecto-ui/ first.');
    process.exit(1);
}
if (csvs.length === 0 && ui === null) {
    console.error('\n✖ Coverage guard: CANNOT RUN — no coverage data of either kind was found.');
    process.exit(1);
}

// ── report ─────────────────────────────────────────────────────────────────────────────────────────
const failures = [];
const check = (label, actual, floor) => {
    const ok = actual >= floor;
    if (!ok) failures.push(`${label} ${actual.toFixed(2)}% is below the ${floor.toFixed(1)}% floor`);
    return `${ok ? '✓' : '✖'} ${label.padEnd(22)} ${actual.toFixed(2).padStart(6)}%  (floor ${floor.toFixed(1)}%)`;
};

if (backend) {
    console.log(`\nBackend — ${csvs.length} module report(s), ${totals.ic + totals.im} instructions`);
    console.log('  ' + check('instructions', backend.instruction, FLOORS.backend.instruction));
    console.log('  ' + check('branches', backend.branch, FLOORS.backend.branch));
    console.log(`  · lines ${backend.line.toFixed(2)}% (reported, no floor)`);

    perModule.sort((a, b) => a.instr - b.instr);
    console.log('\n  lowest-covered modules (context for a drop, not individually gated):');
    for (const m of perModule.slice(0, 5)) {
        console.log(`    ${m.instr.toFixed(1).padStart(5)}%  ${String(m.size).padStart(7)} instrs  ${m.mod}`);
    }
} else {
    // ⚠ Not a failure: ui.yml runs `--ui` in a job that never builds Java, and ci.yml runs its own
    // `--backend`. Saying so beats silently reporting half the picture as if it were all of it — the
    // same reasoning as the UI arm below.
    console.log('\nBackend — no jacoco.csv (run `mvn -o clean test -Pcoverage -Pedition-enterprise`). Skipped.');
}

if (ui) {
    console.log(`\nUI — ${ui.path}`);
    console.log('  ' + check('statements', ui.statements, FLOORS.ui.statements));
    console.log('  ' + check('branches', ui.branches, FLOORS.ui.branches));
    console.log(`  · lines ${ui.lines.toFixed(2)}% (reported, no floor)`);
} else {
    // ⚠ Not a failure: the backend guard runs in ci.yml where the UI is never built, and ui.yml runs
    // its own. Saying so beats silently reporting half the picture as if it were all of it.
    console.log('\nUI — no coverage-summary.json (run `npm run test:coverage` in inspecto-ui/). Skipped.');
}

if (failures.length) {
    console.error('\n✖ Coverage guard: ' + failures.length + ' floor(s) breached');
    for (const f of failures) console.error('  · ' + f);
    console.error('\n⛔ Do not lower a floor to make this green. Either restore the coverage, or raise the');
    console.error('   floor deliberately as a recorded policy change with the operator.');
    process.exit(1);
}
console.log('\n✓ Coverage guard: every floor met.');
