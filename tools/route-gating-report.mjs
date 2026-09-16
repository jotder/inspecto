#!/usr/bin/env node
// Generates — and, in guard mode, ENFORCES — the route-gating evidence table
// (`compliance/evidence/route-gating.md`), route-gating compliance plan step 4e.
//
// 🔴 THE POINT: the table is DERIVED, never hand-typed. Six of the seven existing evidence files are
// prose against code, which is fine for narrative; an INVENTORY typed by hand drifts the day after it is
// written — the `SPEC-COUNTS-1` class of defect this repo has recorded repeatedly. So the prose stays
// hand-authored and only the table between the markers is generated. The convention is extended, not broken.
//
// ⚠ This scan reads SOURCE, so it sees every module including the optional ones the test classpath does
// not carry. The running server's own `GET /audit/route-inventory` reports what was actually DEPLOYED.
// The two answer different questions and the evidence cites both; neither replaces the other.
//
// Usage:  node tools/route-gating-report.mjs           # rewrite the table in place
//         node tools/route-gating-report.mjs --check   # fail if the committed table differs from the code

import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const REPORT = 'compliance/evidence/route-gating.md';
const BEGIN = '<!--route-gating:begin-->';
const END = '<!--route-gating:end-->';

/** Every *Routes.java under a module's main sources. */
function routeFiles(root = '.') {
  const out = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === 'target' || name === 'node_modules' || name === '.git' || name === '.claude') continue;
      const p = join(dir, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p);
      else if (name.endsWith('.java') && p.includes(join('src', 'main', 'java'))) out.push(p);
    }
  };
  walk(root);
  return out.sort();
}

// `api.post("/x", ApiContext.withCapability("canFoo", …` — the capability is a LITERAL at the call site,
// which is exactly what CapabilityManifestTest's scan requires and why a constant there fails the build.
const REGISTRATION = /api\.(post|put|patch|delete|get)\(\s*"([^"]+)"\s*,\s*(?:ApiContext\.)?withCapability\(\s*"([^"]+)"/g;
const BARE = /api\.(post|put|patch|delete|get)\(\s*"([^"]+)"\s*,/g;

function scan() {
  const rows = new Map();                         // "METHOD pattern" -> {method, pattern, capability, file, line}
  for (const file of routeFiles()) {
    const text = readFileSync(file, 'utf8');
    const lineOf = (idx) => text.slice(0, idx).split('\n').length;
    for (const m of text.matchAll(REGISTRATION)) {
      const key = `${m[1].toUpperCase()} ${m[2]}`;
      rows.set(key, { method: m[1].toUpperCase(), pattern: m[2], capability: m[3], file, line: lineOf(m.index) });
    }
    for (const m of text.matchAll(BARE)) {
      const key = `${m[1].toUpperCase()} ${m[2]}`;
      if (rows.has(key)) continue;                // a gated registration already claimed it
      rows.set(key, { method: m[1].toUpperCase(), pattern: m[2], capability: null, file, line: lineOf(m.index) });
    }
  }
  return [...rows.values()].sort((a, b) => a.pattern.localeCompare(b.pattern) || a.method.localeCompare(b.method));
}

/** The recorded exemptions, read from the manifest itself rather than re-listed here. */
function exemptions() {
  const src = readFileSync('inspecto/src/main/java/com/gamma/control/CapabilityManifest.java', 'utf8');
  const out = new Map();
  for (const m of src.matchAll(/new Exemption\(\s*"([A-Z]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,/g))
    out.set(`${m[1]} ${m[2]}`, m[3]);
  return out;
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function table() {
  const ex = exemptions();
  const rows = scan().filter((r) => MUTATING.has(r.method));
  const lines = [
    '| Method | Route | Posture | Declared as | Registered at |',
    '|---|---|---|---|---|',
  ];
  let gated = 0, exempt = 0, undeclared = 0;
  for (const r of rows) {
    const key = `${r.method} ${r.pattern}`;
    let posture, declared;
    if (r.capability) { posture = 'gated'; declared = '`' + r.capability + '`'; gated++; }
    else if (ex.has(key)) { posture = 'exempt'; declared = ex.get(key); exempt++; }
    else { posture = '🔴 UNDECLARED'; declared = '—'; undeclared++; }
    lines.push(`| ${r.method} | \`${r.pattern}\` | ${posture} | ${declared} | \`${r.file.replace(/\\/g, '/')}:${r.line}\` |`);
  }
  return { body: lines.join('\n'), gated, exempt, undeclared, total: rows.length };
}

const t = table();
// ⛔ An undeclared mutating route must never reach the evidence file as a row that merely LOOKS alarming:
// it fails here too, so the report cannot quietly document a hole.
if (t.undeclared > 0) {
  console.error(`✗ route-gating report: ${t.undeclared} mutating route(s) declare no posture. `
    + 'They must be gated with ApiContext.withCapability or recorded as a CapabilityManifest exemption.');
  process.exit(1);
}

const doc = readFileSync(REPORT, 'utf8');
const start = doc.indexOf(BEGIN);
const stop = doc.indexOf(END);
if (start < 0 || stop < 0) {
  console.error(`✗ ${REPORT} is missing the ${BEGIN} / ${END} markers — the table has nowhere to land.`);
  process.exit(1);
}

const generated = `${BEGIN}\n\n${t.body}\n\n${END}`;
const updated = doc.slice(0, start) + generated + doc.slice(stop + END.length);
const check = process.argv.includes('--check');

if (check) {
  if (updated !== doc) {
    console.error(`✗ ${REPORT} is STALE: its table differs from the code. `
      + 'Run `node tools/route-gating-report.mjs` and commit the result. '
      + 'The evidence document may not say something the code does not.');
    process.exit(1);
  }
  console.log(`✓ Route-gating evidence: ${t.total} mutating route(s) — ${t.gated} gated, ${t.exempt} exempt, `
    + '0 undeclared; the committed table matches the code.');
} else {
  writeFileSync(REPORT, updated);
  console.log(`✓ Wrote ${REPORT}: ${t.total} mutating route(s) — ${t.gated} gated, ${t.exempt} exempt.`);
}
