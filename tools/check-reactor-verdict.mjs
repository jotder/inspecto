#!/usr/bin/env node
// A Maven reactor log is a PASS verdict only if it PROVES every module ran. This guard refuses to
// call anything else a pass — because a halted reactor is the quietest false green this repo has.
//
// WHY THIS EXISTS. Filed as BACKLOG row `REACTOR-HALT-IS-A-SILENT-PASS-1` (2026-09-16) after a red
// in an upstream module nearly landed a false verdict TWICE in one day; one agent came within a
// sentence of reporting a pass on tests that never executed. Reproduced deliberately on 2026-09-16
// with the real toolchain (Maven 3.9.16 / surefire 3.2.5) on a two-module reactor:
//
//   1. `mvn -o -q clean test` on a GREEN tree writes a log of ZERO BYTES and exits 0. So an empty
//      log is indistinguishable from a build that did nothing — this repo has already recorded a
//      no-op build reporting exit 0 with an empty log.
//   2. `-q` SUPPRESSES the "Reactor Summary" entirely. That summary is the only place Maven states
//      which modules were SKIPPED. Under `-q` there is no record that a module was skipped at all.
//   3. 🔴 THE LOAD-BEARING ONE. `mvn clean` only cleans modules the reactor actually REACHES. When
//      an upstream module fails, every downstream module keeps `target/surefire-reports/*.txt` from
//      the PREVIOUS run — green reports, correct-looking counts, no marker of any kind. Measured:
//      with an upstream COMPILE error, the `build-verify` skill's own authoritative recipe ("don't
//      parse the log at all — SUM THE SUREFIRE REPORTS") reported `failures=0 errors=0` for a tree
//      on which the downstream module ran not one test. The recipe that exists to defeat log-parsing
//      traps is itself the delivery mechanism for this one.
//
// ⇒ Neither the exit code, nor the log text, nor the surefire reports can be trusted ALONE. This
// guard cross-checks all three, and treats a SKIPPED module as a NON-VERDICT, never as a pass.
//
// SCOPE, stated so it can be audited apart from the rule (this repo's own lesson — a guard's scope
// is where the silent exemptions live): the guard judges ONE Maven log file that the caller names,
// plus the surefire reports under --root. It does NOT run Maven, and it cannot tell you that the
// log belongs to the tree you are looking at — pass a log you just produced. Modules that are not
// in the reactor at all (the edition-gated ones, absent without `-Pedition-*`) are invisible here
// by construction; that hole is the SKILL's subject, not this guard's, and is documented there.
//
// Usage:
//   mvn -o clean test -Pedition-enterprise -B > build.log 2>&1   # -B, NOT -q (see check 1)
//   node tools/check-reactor-verdict.mjs build.log [--root .] [--expect-modules N]
//
// Exit 0 = VERIFIED PASS.  Exit 1 = NON-VERDICT or real failure.  Exit 2 = could not run.
//
// ⛔ THIS GUARD DOES NOT BELONG IN `.githooks/pre-push`, deliberately. Every other `tools/check-*.mjs`
// is a pure repo-STATE check: no arguments, ~1s, answerable from the tree alone. This one judges a
// BUILD, and at push time there is no build log to judge — producing one would mean running a 20+
// minute reactor on every push. `.githooks/pre-push`'s own header records why that is fatal: a hook
// that fails or stalls on some machines gets `core.hooksPath` unset entirely, after which NO layer
// runs locally. This is a VERIFY-TIME tool. It belongs where a verdict is claimed — the `build-verify`
// skill's verdict step and the `verify-runner` agent, both of which now call it.
// ⇒ WIRED INTO `ci.yml` instead (REACTOR-VERDICT-CI-1, operator 2026-09-25), where a full reactor already
// runs: the `test` job tees its `-B` reactor run to `$RUNNER_TEMP/reactor.log` under pipefail and gates on
// this guard in an `if: always()` step.

import { readFileSync, statSync, existsSync } from 'node:fs';
import { globSync } from 'node:fs';
import { join, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');

const args = process.argv.slice(2);
const logPath = args.find((a) => !a.startsWith('--'));
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const ROOT = flag('--root') ?? REPO;
const EXPECT = flag('--expect-modules') ? Number(flag('--expect-modules')) : undefined;

if (!logPath) {
  console.error('✗ reactor-verdict guard: no log file named.\n');
  console.error('  Usage: node tools/check-reactor-verdict.mjs <build.log> [--root .] [--expect-modules N]');
  console.error('  Produce the log with `-B`, never `-q`: `-q` suppresses the Reactor Summary, which is');
  console.error('  the ONLY place Maven records that a module was SKIPPED.');
  process.exit(2);
}
if (!existsSync(logPath)) {
  console.error(`✗ reactor-verdict guard: log not found: ${logPath}`);
  process.exit(2);
}

const log = readFileSync(logPath, 'utf8');
const problems = [];
const notes = [];

// ── check 1: the log must not be empty ─────────────────────────────────────────
// A zero-byte log is the signature of both `-q` on a green build AND of a build that never ran.
// Those two are indistinguishable, so neither may be called a pass.
if (log.trim().length === 0) {
  console.error('✗ reactor-verdict guard: NON-VERDICT — the log is EMPTY.');
  console.error('  `mvn -q` writes nothing at all on success, and so does a build that never started.');
  console.error('  Those are indistinguishable. Re-run with `-B` instead of `-q` and judge THAT log.');
  process.exit(1);
}

// ── check 2: the Reactor Summary must be present ───────────────────────────────
// This is the `-q` trap. Without the summary there is no record of which modules were skipped, so
// no amount of "no failures" in the rest of the log can add up to a verdict.
const summaryStart = log.indexOf('Reactor Summary');
if (summaryStart < 0) {
  console.error('✗ reactor-verdict guard: NON-VERDICT — no "Reactor Summary" in this log.');
  console.error('  `-q` suppresses it. The summary is the ONLY place Maven states which modules were');
  console.error('  SKIPPED, so without it a halted reactor and a full green run read identically.');
  console.error('  Re-run with `-B` (or plain, without `-q`) and judge that log.');
  process.exit(1);
}

// ── check 3: every module in the summary must be SUCCESS ───────────────────────
// A SKIPPED module is UNVERIFIED, not passing. `-fae` still skips modules that depend on a failed
// one, so this check matters just as much under --fail-at-end.
//
// ⚠ THE DOT LEADER IS OPTIONAL. Maven pads a summary row to a fixed width with '.' — but when the
// module's "<name> <version>" already fills that width it emits ONE OR ZERO dots and just spaces:
//     [INFO] inspecto-engine 4.0.0-SNAPSHOT ..... SUCCESS [01:54 min]
//     [INFO] Inspecto util (DuckDB access + CSV/file/tar helpers) 4.0.0-SNAPSHOT SUCCESS [ 15.157 s]
// The first version of this guard required `\.{3,}` and therefore parsed 12 of this repo's 32
// modules while reporting them all SUCCESS — a guard that could not fire on 20 modules, found only
// by running it against the real reactor. Hence the build-order cross-check below: it fails the
// guard on its OWN parse rather than under-reporting quietly.
const rows = [];
for (const line of log.slice(summaryStart).split(/\r?\n/)) {
  const m = line.match(
    /^\[(?:INFO|WARNING|ERROR)\]\s+(\S.*?)[\s.]+(SUCCESS|FAILURE|SKIPPED)(?:\s+\[[^\]]*\])?\s*$/,
  );
  if (m && m[1] !== 'BUILD') rows.push({ name: m[1].trim(), status: m[2] });
  if (rows.length && /^\[[A-Z]+\] -{20,}/.test(line)) break;
}
if (rows.length === 0) {
  console.error('✗ reactor-verdict guard: NON-VERDICT — a "Reactor Summary" header with no parsable');
  console.error('  module rows. Do not guess: re-run the build and judge a complete log.');
  process.exit(1);
}

// ── check 3b: the guard must account for EVERY module Maven planned to build ────
// Self-check against Maven's own "Reactor Build Order", which lists every module with a packaging
// suffix. If the two disagree, this guard's own parsing is under-reporting and NOTHING it says can
// be trusted — so it fails rather than hand back a confident partial answer.
const orderStart = log.indexOf('Reactor Build Order');
if (orderStart >= 0 && orderStart < summaryStart) {
  let planned = 0;
  for (const line of log.slice(orderStart, summaryStart).split(/\r?\n/)) {
    if (/^\[INFO\]\s+\S.*\s\[(?:pom|jar|war|ear|maven-plugin|bundle)\]\s*$/.test(line)) planned++;
  }
  if (planned > 0 && planned !== rows.length) {
    console.error('✗ reactor-verdict guard: NON-VERDICT — THIS GUARD FAILED TO PARSE THE SUMMARY.');
    console.error(`  Maven's Reactor Build Order lists ${planned} module(s); this guard parsed only`);
    console.error(`  ${rows.length} summary row(s). A partial parse silently under-reports SKIPPED`);
    console.error('  modules, so no verdict may be drawn from it. Fix the row regex in this file.');
    process.exit(1);
  }
}
const skipped = rows.filter((r) => r.status === 'SKIPPED');
const failed = rows.filter((r) => r.status === 'FAILURE');
if (failed.length) problems.push(`${failed.length} module(s) FAILED: ${failed.map((r) => r.name).join(', ')}`);
if (skipped.length) {
  problems.push(
    `${skipped.length} module(s) SKIPPED — UNVERIFIED, not passing: ${skipped.map((r) => r.name).join(', ')}`,
  );
}

// ── check 4: BUILD SUCCESS must be stated ──────────────────────────────────────
if (!/^\[INFO\] BUILD SUCCESS/m.test(log)) {
  problems.push('the log does not state BUILD SUCCESS');
}

// ── check 5: no surefire report may PREDATE this build ─────────────────────────
// The load-bearing check. `mvn clean` only cleans modules the reactor reaches, so a downstream
// module keeps its previous run's green reports when an upstream halts. Summing them reports a
// pass for tests that never ran. The build window is computed from Maven's own two footer lines,
// so it needs nothing from the caller and cannot drift from the log it is judging.
const finishedAt = log.match(/^\[INFO\] Finished at:\s*(\S+)/m)?.[1];
const totalTime = log.match(/^\[INFO\] Total time:\s*(.+?)\s*$/m)?.[1];
const parseSeconds = (t) => {
  if (!t) return undefined;
  let m = t.match(/^(\d+):(\d+)\s*(?:h|min)$/); // 01:23 min  /  01:23 h
  if (m) return t.endsWith('h') ? (+m[1] * 60 + +m[2]) * 60 : +m[1] * 60 + +m[2];
  m = t.match(/^([\d.]+)\s*s$/);
  if (m) return +m[1];
  return undefined;
};
const secs = parseSeconds(totalTime);
let staleness = 'not checked';
const stale = [];
let fresh = 0;
const perModule = new Map();

if (!finishedAt || secs === undefined) {
  // Not fatal on its own, but it must never pass SILENTLY as "checked".
  problems.push(
    `cannot compute the build window (Finished at=${finishedAt ?? 'absent'}, Total time=${totalTime ?? 'absent'})` +
      ' — so STALE surefire reports could not be ruled out. Re-run with `-B`.',
  );
} else {
  // 2s slack: Maven's footer is whole-second precision and the clock may round against us.
  const startMs = Date.parse(finishedAt) - secs * 1000 - 2000;
  // `inspecto-deploy` is a staged BUNDLE, not a reactor module — its copies would double-count.
  // `node_modules` / `.git` are excluded for walk cost; neither holds a Maven module.
  const SKIP_DIR = new Set(['inspecto-deploy', 'node_modules', '.git']);
  const reports = globSync('**/target/surefire-reports/*.txt', { cwd: ROOT })
    .filter((p) => !p.split(/[\\/]/).some((seg) => SKIP_DIR.has(seg)));
  for (const rel of reports) {
    const abs = join(ROOT, rel);
    const mod = rel.split(/[\\/]/).slice(0, -3).join('/') || '.';
    if (statSync(abs).mtimeMs < startMs) {
      stale.push(rel);
      continue;
    }
    fresh++;
    const m = readFileSync(abs, 'utf8').match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/);
    if (!m) continue;
    const cur = perModule.get(mod) ?? [0, 0, 0, 0];
    for (let i = 0; i < 4; i++) cur[i] += Number(m[i + 1]);
    perModule.set(mod, cur);
  }
  staleness = `${fresh} fresh, ${stale.length} stale of ${reports.length}`;
  if (stale.length) {
    const mods = [...new Set(stale.map((p) => p.split(/[\\/]/).slice(0, -3).join('/')))];
    problems.push(
      `${stale.length} surefire report(s) in ${mods.length} module(s) PREDATE this build — they are ` +
        `leftovers from an earlier run and summing them reports a pass for tests that never ran: ${mods.join(', ')}`,
    );
  }
}

// ── check 6: optional expected module count ────────────────────────────────────
if (EXPECT !== undefined && rows.length !== EXPECT) {
  problems.push(`reactor had ${rows.length} module(s), expected ${EXPECT} — a shrunken reactor is not a pass`);
} else if (EXPECT === undefined) {
  notes.push('no --expect-modules given: a reactor that silently LOST a module (a missing -Pedition-* profile) is out of this run\'s scope');
}

// ── report ─────────────────────────────────────────────────────────────────────
const totals = [...perModule.values()].reduce(
  (a, v) => [a[0] + v[0], a[1] + v[1], a[2] + v[2], a[3] + v[3]],
  [0, 0, 0, 0],
);

if (problems.length) {
  console.error(`✗ reactor-verdict guard: NON-VERDICT — this log is not a pass (${problems.length} finding(s))\n`);
  for (const p of problems) console.error(`  • ${p}`);
  console.error(`\n  reactor rows: ${rows.length}  (SUCCESS ${rows.length - skipped.length - failed.length},` +
    ` FAILURE ${failed.length}, SKIPPED ${skipped.length})`);
  console.error(`  surefire reports under ${ROOT}: ${staleness}`);
  console.error('\n  ⛔ A SKIPPED module is UNVERIFIED, never passing, and a surefire report older than');
  console.error('     the build is a leftover, never evidence. Fix the upstream red and re-run the WHOLE');
  console.error('     reactor before reporting any verdict. Do not sum reports across two runs.');
  process.exit(1);
}

console.log(
  `✓ reactor-verdict guard: PASS — all ${rows.length} reactor module(s) SUCCESS, BUILD SUCCESS stated, ` +
    `and all ${fresh} surefire report(s) were written by THIS build (${staleness}).`,
);
for (const mod of [...perModule.keys()].sort()) console.log(`    ${mod.padEnd(34)} ${perModule.get(mod).join('/')}`);
console.log(
  `  TEST-BEARING MODULES=${perModule.size} TOTAL=${totals[0]} failures=${totals[1]} errors=${totals[2]} skipped=${totals[3]}`,
);
for (const n of notes) console.log(`  note: ${n}`);
