#!/usr/bin/env node
// Every launcher that starts the engine WITHOUT space discovery must declare `-Dassist.safety.roots`.
//
// WHY THIS EXISTS (`SAFETY-ROOTS-UNDECLARED-1`, filed 2026-09-16). `SafetyPolicy.defaultPolicy()` has
// no working-directory fallback, on purpose: with `-Dassist.safety.roots` unset and no space hosted,
// the allowed-roots list is EMPTY and `PathJail.requireUnderAny` throws `no allowed roots configured`
// for every jailed value. That is the correct fail-closed posture — an unconfigured deployment must
// not quietly grant the server's working directory — but it means the flag is load-bearing rather
// than optional on exactly the surfaces that host no space:
//
//   • the one-shot ETL CLI (`run.sh` / `run.bat`) — `CollectorProcessor` runs no discovery at all;
//   • single-tenant serve (`serve-example.sh` / `.ps1`) — `SpaceManager.single()` wraps one already-
//     built service and, unlike `SpaceManager.discover()`, never calls `SpaceBootstrap.load`, so it
//     pushes NOTHING into `DiscoveredRoots`. The comment in both serve-example scripts says exactly
//     this; nothing enforced it.
//
// The bundle's `serve.sh` / `serve.bat` are deliberately NOT subjects: they pass `-Dspaces.root=spaces`
// and go through `SpaceManager.discover()`, which registers each space base with `DiscoveredRoots`
// before loading its configs. Their flag set is proved by EXECUTION in `tools/check-launchers.mjs`,
// whose scope note excludes the one-shot launchers this guard covers. The two guards partition the
// launcher surface between them; neither is a superset of the other.
//
// ⚠ WHY THIS IS NOT A SUBSTRING CHECK. All six subjects also NAME `-Dassist.safety.roots` in their
// own explanatory comments — several times each. A guard that grepped the raw text would stay green
// after the real declaration was deleted, reporting a hit it could not have missed. Comments are
// stripped first (`#` for sh/ps1, `rem` for bat), and the assertion is made against code lines only.
// A probe that cannot return a negative is not a probe.
//
// SCOPE, stated so it can be audited apart from the rules:
//   • Subjects: the four `inspecto/examples/{run,serve}-example.{sh,ps1}` runners, plus the
//     `$runShContent` / `$runBatContent` here-strings in `inspecto/package.ps1` (the single source of
//     the bundle's one-shot launchers). A missing subject ABORTS (exit 2) — a renamed file must not
//     make this guard vacuously pass, which is how a check becomes a false zero.
//   • It asserts the flag is DECLARED with a non-empty value. It does NOT assert the value is the
//     right directory: that is a property of the running deployment, not of the script text.
//
// Usage:  node tools/check-safety-roots-declared.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const FLAG = '-Dassist.safety.roots';

const fail = (msg) => { console.error(`✗ Safety-roots guard: ${msg}`); process.exit(1); };
const abort = (msg) => { console.error(`✗ Safety-roots guard could not run: ${msg}`); process.exit(2); };

const read = (rel) => {
  try {
    return readFileSync(join(ROOT, rel), 'utf8');
  } catch {
    abort(`\`${rel}\` is missing. It was renamed or moved — point this guard at the new path, do NOT\n` +
      `  delete the check. A subject that cannot be read is an unproved launcher, not a passing one.`);
  }
};

// Extract one PowerShell single-quoted here-string (`@'` … `'@`) by variable name — literal, so what
// lies between the delimiters is byte-for-byte what package.ps1 writes into the bundle.
function hereString(ps1, varName) {
  const open = ps1.indexOf(`$${varName} = @'\n`);
  if (open < 0) abort(`\`$${varName}\` is not a single-quoted here-string in inspecto/package.ps1.\n` +
    `  It was renamed, re-quoted, or moved. Point this guard at the new name — do NOT delete the check.`);
  const bodyStart = open + `$${varName} = @'\n`.length;
  const end = ps1.indexOf(`\n'@\n`, bodyStart);
  if (end < 0) abort(`\`$${varName}\` has no closing \`'@\` line in inspecto/package.ps1.`);
  return ps1.slice(bodyStart, end + 1);
}

// Drop whole-line comments. `#` covers sh and PowerShell; `rem` covers cmd. Only leading-position
// comments are stripped — a trailing `# …` on a real command line is left alone, so a declaration can
// never be hidden from this guard by appending a comment to it.
const codeLines = (text) => text
  .split(/\r?\n/)
  .filter((l) => !/^\s*(#|rem\b|::)/i.test(l));

// `=` followed by at least one character that is not a quote, whitespace or line end: an empty or
// quoted-empty value declares the flag while leaving the root list just as empty as omitting it.
const DECLARES = new RegExp(`${FLAG.replace(/[.\-]/g, '\\$&')}=[^"'\\s]`);

const ps1 = readFileSync(join(ROOT, 'inspecto', 'package.ps1'), 'utf8').replace(/\r\n/g, '\n');

const SUBJECTS = [
  { name: 'inspecto/examples/run-example.sh', text: read('inspecto/examples/run-example.sh') },
  { name: 'inspecto/examples/run-example.ps1', text: read('inspecto/examples/run-example.ps1') },
  { name: 'inspecto/examples/serve-example.sh', text: read('inspecto/examples/serve-example.sh') },
  { name: 'inspecto/examples/serve-example.ps1', text: read('inspecto/examples/serve-example.ps1') },
  { name: 'inspecto/package.ps1 · $runShContent (bundle run.sh)', text: hereString(ps1, 'runShContent') },
  { name: 'inspecto/package.ps1 · $runBatContent (bundle run.bat)', text: hereString(ps1, 'runBatContent') },
];

const problems = [];
let mentionsInComments = 0;
for (const s of SUBJECTS) {
  const all = s.text.split(/\r?\n/);
  const code = codeLines(s.text);
  mentionsInComments += all.filter((l) => l.includes(FLAG)).length - code.filter((l) => l.includes(FLAG)).length;
  if (!code.some((l) => DECLARES.test(l))) {
    const commented = all.some((l) => l.includes(FLAG));
    problems.push(`${s.name}\n      declares no ${FLAG}=<value> on any code line` +
      (commented ? `\n      (it is NAMED in a comment there — a comment configures nothing)` : ''));
  }
}

if (problems.length) {
  fail(`a launcher that hosts no space declares no path-jail root:\n` +
    problems.map((p) => `    ${p}`).join('\n\n') +
    `\n\n  These surfaces run no space discovery, so ${FLAG} is their ONLY source of allowed roots.\n` +
    `  Without it \`SafetyPolicy.defaultPolicy()\` returns an EMPTY list and every jailed value dies\n` +
    `  with "no allowed roots configured" — the fail-closed posture working as designed, against a\n` +
    `  deployment that simply forgot to declare its root. ⛔ The fix is to declare the root in the\n` +
    `  launcher, NEVER to give SafetyPolicy a working-directory fallback: that fallback existed until\n` +
    `  2026-08-14 and silently granted the server's working directory to every containment check.`);
}

console.log(
  `✓ Safety-roots guard: ${SUBJECTS.length}/${SUBJECTS.length} space-less launchers declare ${FLAG}=<value> ` +
  `on a code line — ${SUBJECTS.map((s) => s.name.replace(/^inspecto\/(examples\/)?/, '')).join(', ')}.\n` +
  `  ${mentionsInComments} further mention(s) sit in comments and were excluded: this guard asserts against ` +
  `code lines only, so deleting a declaration cannot be masked by the comment that explains it.\n` +
  `  scope: the one-shot + single-tenant launchers. The bundle's serve.sh/serve.bat are covered by ` +
  `tools/check-launchers.mjs instead — they pass -Dspaces.root and register roots via DiscoveredRoots.`
);
