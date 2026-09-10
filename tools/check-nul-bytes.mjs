#!/usr/bin/env node
// A tracked text file must contain NO NUL byte, because one NUL makes the whole file INVISIBLE to
// every recursive content search this repository depends on.
//
// WHY THIS EXISTS. Found 2026-09-10 while running scale-out spike S4: an agent reported that
// `StabilityGate.java` "silently drops out of a naive content grep across the module". Measured, it
// was worse than reported — `rg -l --glob '*.java' 'SHARED' inspecto-acquire/` listed
// CircuitBreaker, GapTracker and IntakeGovernor and NOT StabilityGate, although
// `StabilityGate.SHARED` is declared in it. ripgrep classifies a file containing a NUL byte as
// binary and skips it when recursing (it still searches it when the path is named explicitly, which
// is exactly why the hole is invisible: spot-checking one file passes). Five tracked source files
// were affected — three Java, two TypeScript — six occurrences in all, every one a separator
// character in a composite map key written as a RAW 0x00 byte instead of the escape `\0`.
//
// 🔴 THE REASON THIS IS A GUARD AND NOT A ONE-OFF FIX. This repository's entire review method is
// grep: every sweep, every census, every "is this claim still true" check. A silent false negative
// in that method is worse than a wrong answer, because nothing about the output says a file was
// skipped. `PROJECT_NOTES.md` §4 already records grep false negatives three times over. One NUL byte
// reintroduces the same failure for a whole file, and the fix is invisible in review (a raw NUL and
// the two-character escape look identical in most editors and in a diff).
//
// SCOPE, stated so it can be audited apart from the rule (this repo's own lesson — a guard's scope
// is where the silent exemptions live): every git-TRACKED file whose extension is in TEXT_EXT below.
// Untracked files are out of scope because the guard's subject is what a search over the repository
// sees. Genuinely binary tracked files (images, fonts, jars, .duckdb, .parquet, .png) are out of
// scope by extension, and the roster is printed on every run, pass or fail, so an unaudited scope
// cannot hide here.
//
// Usage:  node tools/check-nul-bytes.mjs

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

// Text extensions this repository actually tracks. A file type absent here is NOT scanned — that is
// the exemption, and it is deliberate: adding a binary type to this list would fail the guard on
// every run for a file nobody greps for source.
const TEXT_EXT = new Set([
  '.java', '.ts', '.tsx', '.js', '.mjs', '.cjs', '.html', '.css', '.scss',
  '.md', '.txt', '.xml', '.json', '.yml', '.yaml', '.toon', '.csv', '.tsv',
  '.ps1', '.sh', '.bat', '.sql', '.properties', '.gitignore', '.gitattributes',
]);

const ESCAPE = String.fromCharCode(92) + '0'; // the two-character sequence a raw NUL should be

function tracked() {
  const out = execFileSync('git', ['ls-files', '-z'], { cwd: ROOT, maxBuffer: 64 * 1024 * 1024 });
  return out.toString('utf8').split('\0').filter(Boolean);
}

const files = tracked();
const scanned = [];
const offenders = [];

for (const rel of files) {
  const ext = extname(rel).toLowerCase();
  const base = rel.split('/').pop();
  if (!TEXT_EXT.has(ext) && !TEXT_EXT.has(base)) continue;
  let buf;
  try {
    buf = readFileSync(join(ROOT, rel));
  } catch {
    continue; // deleted in the working tree; `git ls-files` lists the index
  }
  scanned.push(rel);
  const hits = [];
  for (let i = 0; i < buf.length; i++) {
    if (buf[i] === 0) {
      let line = 1;
      for (let j = 0; j < i; j++) if (buf[j] === 0x0a) line++;
      hits.push(line);
    }
  }
  if (hits.length) offenders.push({ rel, hits });
}

const extList = [...TEXT_EXT].sort().join(' ');

if (offenders.length) {
  const total = offenders.reduce((n, o) => n + o.hits.length, 0);
  console.error(`✗ NUL-byte guard: ${total} NUL byte(s) in ${offenders.length} tracked text file(s)\n`);
  for (const o of offenders) {
    console.error(`  ${o.rel}  line(s) ${o.hits.join(', ')}`);
  }
  console.error(`\n  scope: ${scanned.length} tracked text file(s) over extensions ${extList}\n`);
  console.error(`  Fix the FILE, not this guard. A NUL byte makes the whole file invisible to every`);
  console.error(`  RECURSIVE ripgrep — so it drops out of sweeps and censuses with no warning, while`);
  console.error(`  still matching when you name its path, which is what hides the hole. If the byte is`);
  console.error(`  a separator in a composite key, write the escape ${ESCAPE} instead; it compiles to the`);
  console.error(`  same character in Java, TypeScript and JavaScript.`);
  process.exit(1);
}

console.log(
  `✓ NUL-byte guard: ${scanned.length} tracked text file(s) contain no NUL byte — ` +
  `scope: extensions ${extList}; tracked non-text files are out of scope by extension, and untracked ` +
  `files are out of scope because the subject is what a search over the repository sees.`
);
