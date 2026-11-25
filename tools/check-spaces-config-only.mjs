#!/usr/bin/env node
/**
 * Space configuration is source-controlled, Space data is not (`SPACES-CONSOLIDATION-1` P3, D-SC1/D-SC4/D-SC7).
 *
 * Every tracked file under `spaces/` must be configuration: `space.toon`, `template.toon`, `config/**`, documentation, a
 * script. A file under a `data/` (or `audit/`, `duckdb/`, `state/`, `la-index/`, `config.secrets/`, `.inspecto/`) folder
 * is data, and a data-extension file (`.csv`, `.parquet`, `.duckdb`, …) or any file over 256 KB is too.
 *
 * The data tracked today is a RATCHET, not an exemption: `tools/spaces-config-only.baseline.json` lists each folder that
 * still holds some, with its file and byte count and why. The guard fails when
 *   - NEW      a tracked data file lies outside every baseline folder (data is entering the repository);
 *   - GROWN    a baseline folder holds more files or bytes than recorded;
 *   - SHRUNK   it holds fewer — lower the baseline in the same change, so the debt can only go down;
 *   - STALE    a baseline folder holds nothing tracked any more — delete its entry;
 *   - BIG      a file over 256 KB is tracked outside a baseline folder;
 *   - IGNORE   `.gitignore` lacks the rule for the Link Analysis index (`/spaces/<id>/config/la-index/`, D-SC9).
 *
 *   node tools/check-spaces-config-only.mjs                  the guard
 *   node tools/check-spaces-config-only.mjs --write-baseline  print the baseline for what is tracked now (a starting point)
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
export const BASELINE_FILE = join(REPO, 'tools', 'spaces-config-only.baseline.json');
export const MAX_BYTES = 256 * 1024;
/** Folders that hold a Space's state when they sit directly in the Space (or Template) folder. A Template may author its
 *  own `config/audit/` (la-audit's audit-domain feeds), so `audit` is state only at that level. */
const STATE_AT_SPACE_ROOT = new Set(['data', 'audit', 'duckdb', 'state', 'config.secrets', '.inspecto']);
const DATA_EXT = /\.(csv|tsv|psv|parquet|duckdb|db|sqlite|dat)$/i;
export const LA_INDEX_RULE = '/spaces/*/config/la-index/';

/** The state folder a tracked path lies in (`spaces/<x>/data/` or `spaces/_templates/<t>/data/`), else null. */
export function stateFolder(path) {
  const parts = path.split('/');
  const at = parts[1] === '_templates' ? 3 : 2;   // spaces/_templates/<t>/<seg> or spaces/<id>/<seg>
  if (at < parts.length - 1 && STATE_AT_SPACE_ROOT.has(parts[at])) return parts.slice(0, at + 1).join('/') + '/';
  const idx = parts.indexOf('la-index');           // the Link Analysis index lives in config/, wherever
  if (idx > 0 && idx < parts.length - 1) return parts.slice(0, idx + 1).join('/') + '/';
  return null;
}

/** files: [{path, size}] tracked under spaces/; baseline: {folders: {<prefix>: {files, bytes, reason}}}; ignoreText: .gitignore. */
export function check(files, baseline, ignoreText) {
  const findings = [];
  const folders = baseline.folders ?? {};
  const seen = new Map(); // baseline folder -> {files, bytes}
  const covering = (p) => Object.keys(folders).find((f) => p.startsWith(f));
  for (const { path, size } of files) {
    const own = covering(path);
    if (own) {
      const cur = seen.get(own) ?? { files: 0, bytes: 0 };
      cur.files++; cur.bytes += size; seen.set(own, cur);
      continue;
    }
    const state = stateFolder(path);
    if (state) findings.push({ kind: 'NEW', path, why: `tracked data under ${state}: Space data is generated or supplied, never committed` });
    else if (DATA_EXT.test(path) && !path.includes('/config/') && !path.includes('/tests/'))   // tests/ holds authored Pipeline test fixtures
      findings.push({ kind: 'NEW', path, why: 'a data file outside config/ and tests/' });
    else if (size > MAX_BYTES) findings.push({ kind: 'BIG', path, why: `${size} bytes, over the ${MAX_BYTES}-byte cap for a tracked Space file` });
  }
  for (const [folder, want] of Object.entries(folders)) {
    const cur = seen.get(folder);
    if (!cur) { findings.push({ kind: 'STALE', path: folder, why: 'nothing is tracked there any more: delete its baseline entry' }); continue; }
    if (cur.files > want.files || cur.bytes > want.bytes)
      findings.push({ kind: 'GROWN', path: folder, why: `${cur.files} files / ${cur.bytes} bytes now, baseline ${want.files} / ${want.bytes}` });
    else if (cur.files < want.files || cur.bytes < want.bytes)
      findings.push({ kind: 'SHRUNK', path: folder, why: `${cur.files} files / ${cur.bytes} bytes now, baseline ${want.files} / ${want.bytes}: lower the baseline` });
  }
  if (!ignoreText.split(/\r?\n/).some((l) => l.trim() === LA_INDEX_RULE))
    findings.push({ kind: 'IGNORE', path: '.gitignore', why: `lacks the rule ${LA_INDEX_RULE} (the Link Analysis index is data written into a Space's config folder)` });
  return findings;
}

export function trackedSpaceFiles(root = REPO) {
  const out = execFileSync('git', ['ls-files', '-z', '--', 'spaces'], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  return out.split('\0').filter(Boolean).map((path) => ({ path, size: statSync(join(root, path)).size }));
}

function run() {
  const files = trackedSpaceFiles();
  if (process.argv.includes('--write-baseline')) {
    const folders = {};
    for (const { path, size } of files) {
      const f = stateFolder(path);
      if (!f) continue;
      folders[f] ??= { files: 0, bytes: 0, reason: 'TODO' };
      folders[f].files++; folders[f].bytes += size;
    }
    console.log(JSON.stringify({ folders: Object.fromEntries(Object.entries(folders).sort()) }, null, 2));
    return 0;
  }
  const baseline = JSON.parse(readFileSync(BASELINE_FILE, 'utf8'));
  const findings = check(files, baseline, readFileSync(join(REPO, '.gitignore'), 'utf8'));
  if (!findings.length) {
    const n = Object.keys(baseline.folders).length;
    console.log(`✓ Spaces-config-only guard: ${files.length} tracked file(s) under spaces/; ${n} data folder(s) still tracked, all within their baseline`);
    return 0;
  }
  console.error(`✗ Spaces-config-only guard: ${findings.length} finding(s)`);
  for (const f of findings.slice(0, 40)) console.error(`  ${f.kind.padEnd(6)} ${f.path}  — ${f.why}`);
  if (findings.length > 40) console.error(`  … ${findings.length - 40} more`);
  console.error('  Space configuration is tracked, Space data is not (docs/superpower/2026-11-25-spaces-consolidation-plan.md).');
  return 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exit(run());
