#!/usr/bin/env node
/**
 * Space configuration is source-controlled, Space data is not (`SPACES-CONSOLIDATION-1` P3, D-SC1/D-SC4/D-SC7).
 *
 * Every tracked file under `spaces/` must be configuration: `space.toon`, `template.toon`, `config/**`, documentation, a
 * script. A file under a `data/` (or `audit/`, `duckdb/`, `state/`, `la-index/`, `config.secrets/`, `.inspecto/`) folder
 * is data, and a data-extension file (`.csv`, `.parquet`, `.duckdb`, …) or any file over 256 KB is too.
 *
 * ONE kind of Space data is permitted (D-SC12, 2026-11-25): pristine SAMPLES under `data/samples/` (up to 1 MiB per Space or
 * Template) and zero-row SINK SEEDS the seed gate needs (`seed.parquet`, `schema-seed.parquet`, `entries.parquet` or a dot-marker under
 * `data/<sink>/` (never in inbox, staged, samples, duckdb or audit; a marker is a `.<name>-output` file), up to 16 KiB each
 * and 256 KiB per Space or Template). That is the design `.gitignore` always had ("data/samples is the one committed exception"), now enforced.
 *
 * Everything else tracked today is a RATCHET, not an exemption: `tools/spaces-config-only.baseline.json` lists each folder
 * that still holds some (the generated Link Analysis corpora), with its file and byte count and why. The guard fails when
 *   - NEW      a tracked data file lies outside every baseline folder (data is entering the repository);
 *   - GROWN    a baseline folder holds more files or bytes than recorded;
 *   - SHRUNK   it holds fewer — lower the baseline in the same change, so the debt can only go down;
 *   - STALE    a baseline folder holds nothing tracked any more — delete its entry;
 *   - BIG      a file over 256 KB is tracked outside a baseline folder;
 *   - CAP      a Space's or Template's samples exceed 1 MiB, or a sink seed exceeds 16 KiB;
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
export const SAMPLES_CAP = 1024 * 1024;
export const SEED_CAP = 16 * 1024;
export const SEEDS_CAP = 256 * 1024;   // all the seeds of one Space or Template together
/** A zero-row snapshot, an Entity List's entries, or an engine marker (`.entity-list-output`): exact names, not any dot-file. */
const SEED_NAME = /^((seed|schema-seed|entries)\.parquet|\.[a-z]+(-[a-z]+)*-output)$/;
const NOT_A_SINK = new Set(['inbox', 'staged', 'samples', 'duckdb', 'audit']);   // folders that hold landed or runtime data, never a seed

/** The Space or Template folder a tracked path belongs to (`spaces/<id>` or `spaces/_templates/<t>`), else null. */
export function ownerFolder(path) {
  const parts = path.split('/');
  if (parts[1] === '_templates') return parts.length > 3 ? parts.slice(0, 3).join('/') : null;
  return parts.length > 2 ? parts.slice(0, 2).join('/') : null;
}

/** 'samples' for `<owner>/data/samples/**`, 'seed' for `<owner>/data/<sink>/<seed file>`, else null: the permitted data. */
export function permittedKind(path) {
  const parts = path.split('/');
  if (parts[1].startsWith('_') && parts[1] !== '_templates') return null;   // _industries holds only definitions
  const at = parts[1] === '_templates' ? 3 : 2;
  if (parts[at] !== 'data') return null;
  if (parts[at + 1] === 'samples' && parts.length > at + 2) return 'samples';
  if (parts.length >= at + 3 && !NOT_A_SINK.has(parts[at + 1]) && SEED_NAME.test(parts[parts.length - 1])) return 'seed';   // data/<sink>/[database/]<seed file>
  return null;
}

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
  const samples = new Map(); // owner folder -> bytes of permitted samples
  const seeds = new Map(); // owner folder -> bytes of permitted seeds
  const covering = (p) => Object.keys(folders).find((f) => p.startsWith(f));
  for (const { path, size } of files) {
    const kind = permittedKind(path);
    if (kind === 'samples') {
      const owner = ownerFolder(path);
      samples.set(owner, (samples.get(owner) ?? 0) + size);
      continue;
    }
    if (kind === 'seed') {
      if (size > SEED_CAP) findings.push({ kind: 'CAP', path, why: `a sink seed of ${size} bytes, over the ${SEED_CAP}-byte cap` });
      const owner = ownerFolder(path);
      seeds.set(owner, (seeds.get(owner) ?? 0) + size);
      continue;
    }
    const own = covering(path);
    if (own) {
      const cur = seen.get(own) ?? { files: 0, bytes: 0 };
      cur.files++; cur.bytes += size; seen.set(own, cur);
      continue;
    }
    const state = stateFolder(path);
    if (state) findings.push({ kind: 'NEW', path, why: `tracked data under ${state}: only data/samples/ and sink seeds may be committed` });
    else if (DATA_EXT.test(path) && !path.includes('/config/') && !path.includes('/tests/'))   // tests/ holds authored Pipeline test fixtures
      findings.push({ kind: 'NEW', path, why: 'a data file outside config/ and tests/' });
    else if (size > MAX_BYTES) findings.push({ kind: 'BIG', path, why: `${size} bytes, over the ${MAX_BYTES}-byte cap for a tracked Space file` });
  }
  for (const [owner, bytes] of samples)
    if (bytes > SAMPLES_CAP) findings.push({ kind: 'CAP', path: `${owner}/data/samples/`, why: `${bytes} bytes of samples, over the ${SAMPLES_CAP}-byte cap` });
  for (const [owner, bytes] of seeds)
    if (bytes > SEEDS_CAP) findings.push({ kind: 'CAP', path: `${owner}/data/`, why: `${bytes} bytes of sink seeds, over the ${SEEDS_CAP}-byte cap` });
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
      if (!f || permittedKind(path)) continue;
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
