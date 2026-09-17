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
// HEURISTIC (deliberately simple, matches this repo's route-gating-report.mjs convention):
//   1. Scan every `*Routes.java` under `inspecto/src/main/java` for `withCapability("cap", ...)`
//      registrations — same REGISTRATION regex as route-gating-report.mjs.
//   2. Scan every `*Test.java` under `inspecto/src/test/java/com/gamma/control` for a call to
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
const BASELINE_UNCOVERED = 79;

// Usage:  node tools/check-authgate-coverage.mjs           # report + exit 1 on a ratchet regression
//         node tools/check-authgate-coverage.mjs --list    # also print every uncovered route

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const MAIN_ROOT = 'inspecto/src/main/java';
const TEST_ROOT = 'inspecto/src/test/java/com/gamma/control';

function javaFiles(root, mustInclude) {
  const out = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === 'target' || name === 'node_modules' || name === '.git') continue;
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (name.endsWith('.java') && (!mustInclude || p.includes(mustInclude))) out.push(p);
    }
  };
  walk(root);
  return out.sort();
}

// Same shape as route-gating-report.mjs's REGISTRATION regex — the capability is a literal at the call
// site, which is what makes a mechanical scan possible at all.
const REGISTRATION = /api\.(post|put|patch|delete|get)\(\s*"([^"]+)"\s*,\s*(?:ApiContext\.)?withCapability\(\s*"([^"]+)"/g;

function gatedRoutes() {
  const rows = [];
  for (const file of javaFiles(MAIN_ROOT)) {
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
  for (const file of javaFiles(TEST_ROOT)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes('Authenticators.forTest(')) continue;
    for (const m of text.matchAll(PATH_LITERAL)) literals.add(m[1]);
  }
  return literals;
}

function patternMatches(pattern, literal) {
  try {
    return new RegExp(`^${pattern}$`).test(literal);
  } catch {
    return false; // an unparseable pattern is never claimed as covered
  }
}

const routes = gatedRoutes();
const literals = [...armedPathLiterals()];
const uncovered = routes.filter((r) => !literals.some((l) => patternMatches(r.pattern, l)));

console.log(`Auth-gate coverage: ${routes.length} gated route(s), ${routes.length - uncovered.length} `
  + `covered by an armed test (Authenticators.forTest), ${uncovered.length} uncovered.`);

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
