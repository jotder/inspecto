#!/usr/bin/env node
// A bundle ships NO Spaces — only the COMMITTED Space-template gallery (spaces/_templates) — proved by
// RUNNING the staging, not by reading it.
//
// OPERATOR DECISION (2026-09-25): every edition's bundle carries spaces/_templates and nothing else under
// spaces/. Spaces are attached at deploy time (drop the folder into spaces/ or set SPACES_ROOT). The
// committed sample Spaces (default, demo, ucc) no longer ship; neither does anything untracked.
//
// WHY THIS EXISTS (`BUNDLE-UNTRACKED-SPACES-1`, found 2026-09-25). Unzipping a demo bundle built by
// `inspecto/package.ps1` in the shared sandbox checkout showed `spaces/telco-assurance` (a git-EXCLUDED
// client/RFP working set) and `spaces/cricket-analytics` (a peer session's UNTRACKED pilot) next to the
// intended samples. Step 4 enumerated `Get-ChildItem spaces/` in the WORKING TREE, so every edition's
// bundle carried whatever happened to sit in the checkout. Staging now lives in
// `inspecto/package-spaces.ps1` (`Copy-SpaceTemplates`), which reads `git ls-tree HEAD` only and keeps
// only spaces/_templates/.
//
// WHAT IT DOES. Builds a throwaway git repository holding a committed template plus every trap — a
// COMMITTED Space (it must not ship either), an untracked Space, a git-excluded Space, an untracked
// template, a staged-but-uncommitted template file, a locally modified tracked template file, runtime
// dirs and the top-level trees (uat, _shared) — dot-sources the helper under pwsh, stages into a temp
// bundle dir, and asserts the EXACT file set (templates only), the committed content of the modified
// file, and the reported Templates / NotBundled / Modified lists. It also checks package.ps1 still
// routes step 4 through the helper and carries no edition-specific Space pruning.
//
// SCOPE: the spaces/ tree of the bundle only. pwsh and git are REQUIRED — their absence is a hard
// error (exit 2), never a skip.
//
// Usage:  node tools/check-bundle-spaces.mjs

import { readFileSync, writeFileSync, mkdirSync, rmSync, mkdtempSync, readdirSync, statSync, appendFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const HELPER = join(ROOT, 'inspecto', 'package-spaces.ps1');
const PACKAGE_PS1 = join(ROOT, 'inspecto', 'package.ps1');

const fail = (msg) => { console.error(`✗ Bundle-spaces guard: ${msg}`); process.exit(1); };
const abort = (msg) => { console.error(`✗ Bundle-spaces guard could not run: ${msg}`); process.exit(2); };

// ── static: package.ps1 routes step 4 through the helper ──────────────────────────────────────
const pkg = readFileSync(PACKAGE_PS1, 'utf8');
if (!/\.\s*\(Join-Path \$adjParserDir 'package-spaces\.ps1'\)/.test(pkg))
  fail('inspecto/package.ps1 no longer dot-sources package-spaces.ps1.');
if (!/Copy-SpaceTemplates\s+-RepoRoot/.test(pkg))
  fail('inspecto/package.ps1 no longer stages spaces/ through Copy-SpaceTemplates.');
if (/Get-ChildItem\s+-Path\s+\$spacesSrc/.test(pkg))
  fail('inspecto/package.ps1 enumerates the working-tree spaces/ again — that is the leak this guard exists for.');
if (/\$staged\.Spaces/.test(pkg))
  fail('inspecto/package.ps1 prunes or re-lists staged Spaces again — the helper alone decides what ships (templates only, every edition).');

// ── fixture repository ────────────────────────────────────────────────────────────────────────
const work = mkdtempSync(join(tmpdir(), 'inspecto-bundle-spaces-'));
const repo = join(work, 'repo');
const bundle = join(work, 'bundle');
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
const put = (rel, body) => { const p = join(repo, rel); mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, body); };

try {
  mkdirSync(repo, { recursive: true });
  mkdirSync(bundle, { recursive: true });
  try { git('init', '-q'); } catch (e) { abort(`git is required: ${e.message}`); }
  git('config', 'user.email', 'guard@example.invalid');
  git('config', 'user.name', 'guard');
  git('config', 'core.autocrlf', 'false');

  const committed = {
    'spaces/README.txt': 'top-level file\n',
    'spaces/_templates/starter/template.toon': 'id: starter\n',
    'spaces/_templates/starter/config/a.toon': 'committed: true\n',
    'spaces/_templates/starter/data/samples/s.csv': 'a,b\n1,2\n',
    'spaces/alpha/space.toon': 'id: alpha\n',
    'spaces/alpha/config/a.toon': 'committed: true\n',
    'spaces/alpha/data/samples/s.csv': 'a,b\n1,2\n',
    'spaces/alpha/data/in/x.csv': 'runtime\n',
    'spaces/alpha/duckdb/keep.txt': 'runtime\n',
    'spaces/alpha/audit/a.log': 'runtime\n',
    'spaces/alpha/flows/f.toon': 'runtime\n',
    'spaces/alpha/views/v.toon': 'runtime\n',
    'spaces/uat/space.toon': 'id: uat\n',
    'spaces/_shared/ledger.txt': 'runtime\n',
  };
  for (const [rel, body] of Object.entries(committed)) put(rel, body);
  git('add', '-A');
  git('commit', '-q', '-m', 'fixture');

  // The traps — none of these may ship.
  put('spaces/_templates/starter/config/a.toon', 'committed: false  # LOCAL EDIT\n');
  put('spaces/_templates/starter/config/staged-only.toon', 'staged: true\n');
  git('add', 'spaces/_templates/starter/config/staged-only.toon');
  put('spaces/_templates/draft/template.toon', 'id: draft\n');       // untracked template
  put('spaces/probe/space.toon', 'id: probe\n');                      // untracked Space
  put('spaces/secret/space.toon', 'id: secret\n');                    // git-excluded Space
  appendFileSync(join(repo, '.git', 'info', 'exclude'), '\nspaces/secret/\n');

  // ── run the helper for real ────────────────────────────────────────────────────────────────
  const q = (s) => `'${s.replace(/'/g, "''")}'`;
  const script = `$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest; . ${q(HELPER)}; ` +
    `Copy-SpaceTemplates -RepoRoot ${q(repo)} -BundleDir ${q(bundle)} | ConvertTo-Json -Compress -Depth 3`;
  let out;
  try {
    out = execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8' });
  } catch (e) {
    if (e.code === 'ENOENT') abort('pwsh (PowerShell 7) is required and was not found on PATH.');
    fail(`Copy-SpaceTemplates threw:\n${e.stdout ?? ''}${e.stderr ?? ''}`);
  }
  const result = JSON.parse(out.trim().split(/\r?\n/).pop());

  const walk = (d) => readdirSync(d).flatMap((n) => {
    const p = join(d, n);
    return statSync(p).isDirectory() ? walk(p) : [relative(bundle, p).split(sep).join('/')];
  });
  const shipped = walk(bundle).sort();
  const expected = [
    'spaces/_templates/starter/config/a.toon',
    'spaces/_templates/starter/data/samples/s.csv',
    'spaces/_templates/starter/template.toon',
  ].sort();
  const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
  if (!same(shipped, expected))
    fail(`bundle file set is wrong.\n  expected: ${expected.join(', ')}\n  shipped:  ${shipped.join(', ')}`);

  const a = readFileSync(join(bundle, 'spaces/_templates/starter/config/a.toon'), 'utf8');
  if (a !== 'committed: true\n')
    fail(`a locally modified tracked file shipped its WORKING copy, not the committed blob: ${JSON.stringify(a)}`);

  const arr = (v) => (v == null ? [] : Array.isArray(v) ? v : [v]);
  if (!same(arr(result.Templates), ['starter']))
    fail(`reported Templates ${JSON.stringify(result.Templates)} — expected ["starter"]`);
  const notBundled = ['README.txt', '_shared', 'alpha', 'probe', 'secret', 'uat'].sort();
  if (!same(arr(result.NotBundled).slice().sort(), notBundled))   // pwsh sorts case-insensitively; compare as sets
    fail(`reported NotBundled ${JSON.stringify(result.NotBundled)} — expected ${JSON.stringify(notBundled)}`);
  if (!same(arr(result.Modified), ['spaces/_templates/starter/config/a.toon']))
    fail(`reported modified files ${JSON.stringify(result.Modified)} — expected ["spaces/_templates/starter/config/a.toon"]`);
  if (result.Files !== expected.length) fail(`reported ${result.Files} files — expected ${expected.length}`);

  console.log(`✓ Bundle-spaces guard: only spaces/_templates ships (${expected.length} committed files); committed/untracked/excluded Spaces and staged-only/untracked templates skipped; modified file shipped as committed.`);
} finally {
  rmSync(work, { recursive: true, force: true });
}
