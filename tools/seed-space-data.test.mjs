// SPACES-CONSOLIDATION-1 P2: the generators write into any folder they are given, and the registry in
// seed-space-data.mjs cannot drift from them. Run: node --test tools/seed-space-data.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { GENERATORS, seed } from './seed-space-data.mjs';

const TOOLS = dirname(fileURLToPath(import.meta.url));
const REPO = join(TOOLS, '..');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

/** Generators under tools/ that write a Template's data folder (gen-*-data.*), minus the ones that make something else. */
const NOT_TEMPLATE_DATA = new Set(['gen-la-video7-data.mjs']);
const generatorFiles = readdirSync(TOOLS).filter((f) => /^gen-.*-data\.(py|mjs)$/.test(f) && !/\.test\./.test(f) && !NOT_TEMPLATE_DATA.has(f));

function filesUnder(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) filesUnder(p, out); else if (!e.name.startsWith('.')) out.push(p);
  }
  return out;
}
const sha = (p) => createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');

test('the registry names every data generator, and each generator writes the Template it is registered for', () => {
  const registered = Object.values(GENERATORS).flat().sort();
  assert.deepEqual(registered, [...generatorFiles].sort(), 'every tools/gen-*-data.* is registered exactly once');
  for (const [template, files] of Object.entries(GENERATORS)) {
    for (const f of files) {
      assert.ok(existsSync(join(TOOLS, f)), `${f} exists`);
      const src = readFileSync(join(TOOLS, f), 'utf8');
      assert.ok(/"_templates"\s*\/\s*"([^"]+)"|'_templates',\s*'([^']+)'/.test(src), `${f} names its Template folder`);
      const m = src.match(/"_templates"\s*\/\s*"([^"]+)"/) || src.match(/'_templates',\s*'([^']+)'/);
      assert.equal(m[1] ?? m[2], template, `${f} writes spaces/_templates/${template}`);
      assert.ok(/--out/.test(src) || /data_dir\(/.test(src), `${f} honours --out`);
    }
  }
});

test('an unregistered Template is a named failure, not a silent no-op', () => {
  assert.throws(() => seed('aml', join(tmpdir(), 'never')), /no generator/);
});

test('every generator, run with --out, reproduces what the Template commits today (so untracking it loses nothing)', { skip: !python, timeout: 600000 }, () => {
  const bad = [];
  for (const template of Object.keys(GENERATORS)) {
    const out = mkdtempSync(join(tmpdir(), `seed-${template}-`));
    try {
      const r = seed(template, join(out, 'data'));
      assert.ok(r.files > 0, `${template} wrote nothing`);
      const committed = join(REPO, 'spaces', '_templates', template, 'data');
      for (const p of filesUnder(join(out, 'data'))) {
        const rel = relative(join(out, 'data'), p);
        const c = join(committed, rel);
        if (!existsSync(c)) bad.push(`${template}: ${rel} is not committed`);
        else if (sha(p) !== sha(c)) bad.push(`${template}: ${rel} differs from the committed file`);
      }
    } finally {
      rmSync(out, { recursive: true, force: true });
    }
  }
  assert.deepEqual(bad, []);
});

test('--out writes only into the folder it is given (the Template folder is untouched)', { skip: !python, timeout: 120000 }, () => {
  const before = filesUnder(join(REPO, 'spaces', '_templates', 'la-claims', 'data')).map((p) => `${p}:${statSync(p).mtimeMs}`);
  const out = mkdtempSync(join(tmpdir(), 'seed-claims-'));
  try {
    seed('la-claims', join(out, 'data'));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
  const after = filesUnder(join(REPO, 'spaces', '_templates', 'la-claims', 'data')).map((p) => `${p}:${statSync(p).mtimeMs}`);
  assert.deepEqual(after, before);
});
