// The spaces-config-only guard (SPACES-CONSOLIDATION-1 P3): data may not enter the repository, and what is tracked today
// can only go down. Each case plants one defect and requires the named finding. Run: node --test tools/check-spaces-config-only.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASELINE_FILE, LA_INDEX_RULE, MAX_BYTES, check, stateFolder, trackedSpaceFiles } from './check-spaces-config-only.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const IGNORE = `/spaces/*/audit/\n${LA_INDEX_RULE}\n`;
const baseline = () => ({ folders: { 'spaces/_templates/t/data/': { files: 2, bytes: 30, reason: 'test' } } });
const config = [{ path: 'spaces/demo/space.toon', size: 90 }, { path: 'spaces/_templates/t/config/a.toon', size: 400 }, { path: 'spaces/_templates/t/template.toon', size: 100 }];
const inBaseline = [{ path: 'spaces/_templates/t/data/x.csv', size: 10 }, { path: 'spaces/_templates/t/data/y.csv', size: 20 }];
const kinds = (files, b = baseline(), ignore = IGNORE) => check(files, b, ignore).map((f) => f.kind);

test('configuration and a baseline folder exactly as recorded pass', () => {
  assert.deepEqual(kinds([...config, ...inBaseline]), []);
});

test('RED: a data file in a new folder is NEW, and so is a data-extension file outside config/', () => {
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/demo/data/inbox/a.csv', size: 5 }]), ['NEW']);
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/demo/extract.parquet', size: 5 }]), ['NEW']);
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/demo/duckdb/x.db', size: 5 }]), ['NEW']);
});

test('RED: a baseline folder that grew (a file or bytes) is GROWN', () => {
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/_templates/t/data/z.csv', size: 1 }]), ['GROWN']);
  assert.deepEqual(kinds([...config, { path: 'spaces/_templates/t/data/x.csv', size: 10 }, { path: 'spaces/_templates/t/data/y.csv', size: 999 }]), ['GROWN']);
});

test('RED: a baseline folder that shrank is SHRUNK (the baseline can only go down), and an emptied one is STALE', () => {
  assert.deepEqual(kinds([...config, { path: 'spaces/_templates/t/data/x.csv', size: 10 }]), ['SHRUNK']);
  assert.deepEqual(kinds(config), ['STALE']);
});

test('RED: a large file outside a baseline folder is BIG; the same file in config/ too', () => {
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/demo/config/big.toon', size: MAX_BYTES + 1 }]), ['BIG']);
  assert.deepEqual(kinds([...config, ...inBaseline, { path: 'spaces/demo/docs/guide.md', size: MAX_BYTES + 1 }]), ['BIG']);
});

test('RED: a .gitignore without the la-index rule is IGNORE', () => {
  assert.deepEqual(kinds([...config, ...inBaseline], baseline(), '/spaces/*/audit/\n'), ['IGNORE']);
});

test('a Template that authors its own config/audit/ is not state; the Space-level audit/ is', () => {
  assert.equal(stateFolder('spaces/_templates/la-audit/config/audit/ap_invoice/p.toon'), null);
  assert.equal(stateFolder('spaces/demo/audit/2026/a.jsonl'), 'spaces/demo/audit/');
  assert.equal(stateFolder('spaces/_templates/t/data/inbox/x.csv'), 'spaces/_templates/t/data/');
  assert.equal(stateFolder('spaces/mfs-demo/config/la-index/ds/v1/data_0.parquet'), 'spaces/mfs-demo/config/la-index/');
  assert.equal(stateFolder('spaces/demo/space.toon'), null);
});

test('the repository itself passes: what is tracked under spaces/ is within the committed baseline', () => {
  const findings = check(trackedSpaceFiles(REPO), JSON.parse(readFileSync(BASELINE_FILE, 'utf8')), readFileSync(join(REPO, '.gitignore'), 'utf8'));
  assert.deepEqual(findings, []);
});
