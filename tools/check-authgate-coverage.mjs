#!/usr/bin/env node
// Flags a gated ControlApi route (`ApiContext.withCapability`) that no ARMED test exercises.
//
// WHY THIS EXISTS (`CONTROL-AUTHGATE-TESTCOVERAGE-1`, docs/BACKLOG.md). `ApiContext.requireCapability`
// is a no-op unless a `Subject` is attached to the exchange, and no test attaches one unless it calls
// `Authenticators.forTest(...)` to install a fake authenticator (W6: Personal edition never attaches a
// Subject at all). A route test that never arms an Authenticator runs Personal-shaped, so adding or
// removing a `withCapability` gate on it changes NO test's outcome — the gate is asserted only by the
// source reading correctly, never by an HTTP round trip. Building an armed-Authenticator test for every
// one of the ~130 route-test files is out of scope for one change; this guard instead stops the gap
// from growing: a NEW gated route with no armed test fails CI the day it is added.
//
// SCOPE (widened 2026-09-22 — see the note below; a guard's scope is where its silent exemptions live,
// so the roster is PRINTED on every run, pass or fail, the way check-nul-bytes.mjs does):
//   EVERY module's `src/main/java` and `src/test/java`, discovered by walking the repo exactly as
//   route-gating-report.mjs does. It was `inspecto/` alone until 2026-09-22.
//
// 🔴 WHY IT WAS WIDENED. Measured 2026-09-22: route-gating-report.mjs (which always scanned every module)
// reported 118 gated routes; this guard reported 95. **26 gated routes in four sibling modules — 16 in
// inspecto-ops, 6 in inspecto-exchange, 2 in inspecto-events, 2 in inspecto-geo-link — were invisible to
// it**, so none of them was ever checked for an armed test, and an armed test added in one of those
// modules could not move the verdict either way. The guard certified a subset while reading as though it
// covered everything. CapabilityManifestTest was widened to every sibling module on 2026-09-07; this was
// never widened to match.
//
// ⚠ DO NOT 'reconcile' this guard's total with route-gating-report.mjs's. They scan identically — same
// regex, same files, 121 registrations each, verified 2026-09-22 — but they REPORT different populations:
// that report is a MUTATING-route report and omits gated GETs (`/notifications/deliveries`,
// `/notifications/suppressions`, `/system/operational-db`), so it lists 118. Subtracting one from the
// other counts across two populations and yields a wrong figure; this guard's own per-module roster is
// the number to use.
//
// HEURISTIC (deliberately simple, matches this repo's route-gating-report.mjs convention):
//   1. Scan every `*Routes.java` under any module's `src/main/java` for `withCapability("cap", ...)`
//      registrations — same REGISTRATION regex as route-gating-report.mjs.
//   2. Scan every `*Test.java` under any module's `src/test/java` for a call to
//      `Authenticators.forTest(` — that marks the file as an ARMED test.
//   3. From each armed file, collect every quoted string literal that looks like a request path
//      (`"/…"`, e.g. the second argument of this repo's own `send(port, "POST", "/access/roles", ...)`
//      helpers). A gated route is COVERED if any armed file's path literal matches its registered
//      pattern (`([^/]+)` placeholders included) anchored end-to-end.
//   4. Any gated route with no covering literal in any armed file is UNCOVERED and fails the guard.
//
// This is a route-PATH match, not a method+path+capability match — the simplification the task
// deliberately calls for ("match by route-class name... not a deep semantic analysis"; path literals
// are more precise than class-name text mentions, which several existing armed tests never carry, e.g.
// ControlApiRequirementTest never writes "RequirementRoutes" anywhere in the file). It can under-flag
// a route whose path is shared with a sibling method that IS armed while this one is not — accepted,
// same tradeoff route-gating-report.mjs makes with its own simple regex scan.
//
// RATCHET, not a hard zero (`check-doc-counts.mjs`'s floor convention): grounding this row on
// 2026-09-17 found 79 of 94 gated routes uncovered — backfilling that in one change is out of scope
// (see docs/BACKLOG.md CONTROL-AUTHGATE-TESTCOVERAGE-1), so a hard "zero uncovered" gate would be red
// on day one. BASELINE is the uncovered count AS OF THAT GROUNDING. The guard fails if uncovered count
// EXCEEDS the baseline — a newly added (or re-gated) route with no armed test regresses the ratchet —
// and BASELINE must only ever move down, by hand, as routes get real armed-Authenticator coverage.
//
// ⚠ BASELINE ROSE ON 2026-09-22 BECAUSE THE SCOPE WIDENED, NOT BECAUSE COVERAGE GOT WORSE. The previous
// 79 counted only `inspecto/`; the number below counts every module. Two effects net out in it: the scope
// widening ADDS the sibling modules' always-uncovered routes, and the space-prefix matching fix REMOVES
// four that were only ever counted uncovered because the matcher could not see their armed tests. Nothing regressed to cause it — the
// routes it now admits were always uncovered, they were simply unseen. ⛔ This is the ONE circumstance in
// which the baseline may legitimately go UP, and it is recorded here so a later reader cannot mistake it
// for a ratchet being loosened.
//
// 🔴 The pre-existing regression that WAS real at the time — `PUT /settings/link-analysis`, gated with no
// armed test, which had taken the count 79 → 80 — was FIXED rather than absorbed into the new number
// (`ControlApiSettingsTest.settingsWritesRequireCanAuthorWorkbench`). Re-baselining would otherwise have
// silently forgiven it, which is how a ratchet stops meaning anything.
//
// ⬇ 79 → 71 on 2026-09-26: `ControlApiTriageGateTest` (the `canWorkIncidents` re-gate) now carries literal
// paths for ack / resolve / transition / assign / merge / split / the PATCH / Case-Rule evaluate — several of
// them were armed-tested before only through `"/objects/" + id + …` concatenations this guard cannot read.
const BASELINE_UNCOVERED = 71;

// Usage:  node tools/check-authgate-coverage.mjs           # report + exit 1 on a ratchet regression
//         node tools/check-authgate-coverage.mjs --list    # also print every uncovered route

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { sep } from 'node:path';

const MAIN_SEGMENT = join('src', 'main', 'java');
const TEST_SEGMENT = join('src', 'test', 'java');

/**
 * Every `*.java` under the given source segment, across EVERY module. `.claude` is skipped along with
 * target/node_modules/.git, exactly as route-gating-report.mjs does — agent worktrees under `.claude`
 * hold stale copies of the same sources and would double-count every route in them.
 */
function javaFiles(segment) {
  const out = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === 'target' || name === 'node_modules' || name === '.git' || name === '.claude') continue;
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (name.endsWith('.java') && p.includes(segment)) out.push(p);
    }
  };
  walk('.');
  return out.sort();
}

/** The module a scanned path belongs to — the first path segment — for the printed scope roster. */
function moduleOf(file) {
  const parts = file.split(sep).filter((x) => x && x !== '.');
  return parts.length ? parts[0] : '.';
}

// Same shape as route-gating-report.mjs's REGISTRATION regex — the capability is a literal at the call
// site, which is what makes a mechanical scan possible at all.
const REGISTRATION = /api\.(post|put|patch|delete|get)\(\s*"([^"]+)"\s*,\s*(?:ApiContext\.)?withCapability\(\s*"([^"]+)"/g;

function gatedRoutes() {
  const rows = [];
  for (const file of javaFiles(MAIN_SEGMENT)) {
    if (!file.endsWith('Routes.java')) continue;
    const text = readFileSync(file, 'utf8');
    const lineOf = (idx) => text.slice(0, idx).split('\n').length;
    for (const m of text.matchAll(REGISTRATION)) {
      rows.push({
        method: m[1].toUpperCase(),
        pattern: m[2],
        capability: m[3],
        file,
        line: lineOf(m.index),
      });
    }
  }
  return rows;
}

// Every quoted literal that looks like a request path: starts with '/', no spaces, no closing quote
// inside. Deliberately broad — false positives (a non-route literal that happens to look path-shaped)
// only widen coverage, they never hide a real gap.
const PATH_LITERAL = /"(\/[^"\s]*)"/g;

function armedPathLiterals() {
  const literals = new Set();
  for (const file of javaFiles(TEST_SEGMENT)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes('Authenticators.forTest(')) continue;
    for (const m of text.matchAll(PATH_LITERAL)) literals.add(m[1]);
  }
  return literals;
}

/**
 * Does an armed test's path literal exercise this registered pattern?
 *
 * ⚠ A space-scoped route is REGISTERED bare (`/settings/link-analysis`) but SERVED under a space prefix
 * (`/spaces/acme/settings/link-analysis`), which is the URL a real HTTP test necessarily writes. Anchoring
 * the literal end-to-end against the bare pattern therefore missed EVERY space-scoped route's coverage and
 * reported it uncovered — found 2026-09-22, when a freshly written armed test for
 * `PUT /settings/link-analysis` did not move the count at all. So the literal is tried both as-is and with
 * a leading `/spaces/<segment>` stripped.
 */
function patternMatches(pattern, literal) {
  const candidates = [literal];
  const unscoped = literal.replace(/^\/spaces\/[^/]+/, '');
  if (unscoped !== literal && unscoped.startsWith('/')) candidates.push(unscoped);
  try {
    const re = new RegExp(`^${pattern}$`);
    return candidates.some((c) => re.test(c));
  } catch {
    return false; // an unparseable pattern is never claimed as covered
  }
}

const routes = gatedRoutes();
const literals = [...armedPathLiterals()];
const uncovered = routes.filter((r) => !literals.some((l) => patternMatches(r.pattern, l)));

// The SCOPE ROSTER, printed on every run whether the guard passes or fails. A guard's scope is where its
// silent exemptions live (check-nul-bytes.mjs's header makes the same argument), and this guard spent from
// 2026-09-07 to 2026-09-22 certifying one module while reading as though it covered them all. An unaudited
// scope cannot hide here now: the modules it scanned, and the gated routes found in each, are on screen.
const byModule = new Map();
for (const r of routes) {
  const m = moduleOf(r.file);
  byModule.set(m, (byModule.get(m) ?? 0) + 1);
}
const mainModules = new Set(javaFiles(MAIN_SEGMENT).map(moduleOf));
const testModules = new Set(javaFiles(TEST_SEGMENT).map(moduleOf));
const roster = [...byModule.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  .map(([m, n]) => `${m}:${n}`).join(' · ');

console.log(`Auth-gate coverage: ${routes.length} gated route(s), ${routes.length - uncovered.length} `
  + `covered by an armed test (Authenticators.forTest), ${uncovered.length} uncovered.`);
console.log(`  scope: ${mainModules.size} module(s) with main sources, ${testModules.size} with tests `
  + `(every */src/main/java and */src/test/java; target/node_modules/.git/.claude skipped). `
  + `Gated routes by module — ${roster || 'none'}.`);

if (process.argv.includes('--list')) {
  for (const r of uncovered) {
    console.log(`  ✗ ${r.method} ${r.pattern}  [${r.capability}]  ${r.file.replace(/\\/g, '/')}:${r.line}`);
  }
}

if (uncovered.length > BASELINE_UNCOVERED) {
  console.error(`✗ Auth-gate coverage guard: ${uncovered.length} gated route(s) have no armed test`
    + ` exercising them, UP from the ${BASELINE_UNCOVERED}-route baseline. A newly gated (or re-gated)`
    + ' route needs a real-HTTP test through the armed-Authenticator idiom (see ControlApiRunRoutesTest)'
    + ' before it can ship ungated-in-test. Run with --list to see every offender.');
  process.exit(1);
}
if (uncovered.length < BASELINE_UNCOVERED) {
  console.log(`✓ Auth-gate coverage improved: ${uncovered.length} uncovered, below the `
    + `${BASELINE_UNCOVERED}-route baseline — lower BASELINE_UNCOVERED in this file to lock in the gain.`);
} else {
  console.log(`✓ Auth-gate coverage steady at the ${BASELINE_UNCOVERED}-route baseline (no regression).`);
}
