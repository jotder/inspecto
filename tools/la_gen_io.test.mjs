// Unit test of tools/la_gen_io.py, the safe output helper of the gen-la-*-data.py generators (LA-GENERATOR-DELETE-FIRST-1).
// Run: node --test tools/la_gen_io.test.mjs. Each case drives the helper in a python child over a fresh temp folder.
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS = dirname(fileURLToPath(import.meta.url));
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

/** Runs `body` (python, may use la_gen_io, os, Path, dir) with dir = a fresh temp folder; returns its stdout lines. */
function py(body) {
  const dir = mkdtempSync(join(tmpdir(), 'la-gen-io-'));
  try {
    const code = ['import os, sys, time', 'from pathlib import Path', 'sys.path.insert(0, sys.argv[1])', 'import la_gen_io',
      'la_gen_io.PAUSE = 0.01', 'dir = Path(sys.argv[2])', body].join('\n');
    const r = spawnSync(python, ['-c', code, TOOLS, dir], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    return r.stdout.trim().split(/\r?\n/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('unchanged bytes are not rewritten: no replace, mtime kept; changed bytes are', { skip: !python }, () => {
  const out = py(`
f = dir / "a.csv"
f.write_bytes(b"h\\n1\\n")
os.utime(f, (1_000_000_000, 1_000_000_000))
calls = []
real = os.replace
os.replace = lambda a, b: (calls.append(b), real(a, b))
o = la_gen_io.Outputs()
print(o.put(f, b"h\\n1\\n"), len(calls), int(f.stat().st_mtime))
print(o.write_text(f, "h\\n2\\n"), len(calls), f.read_bytes() == b"h\\n2\\n")
print(sorted(p.name for p in dir.iterdir()))`);
  assert.deepEqual(out, ['False 0 1000000000', 'True 1 True', "['a.csv']"]);
});

test('open() writes the buffered text once, as UTF-8 with the newlines given', { skip: !python }, () => {
  const out = py(`
import csv
o = la_gen_io.Outputs()
with o.open(dir / "b.csv") as fh:
    csv.writer(fh, lineterminator="\\n").writerows([["id", "name"], ["1", "Zoë"]])
print((dir / "b.csv").read_bytes() == "id,name\\n1,Zoë\\n".encode("utf-8"))`);
  assert.deepEqual(out, ['True']);
});

test('a stale file survives every write and is removed only by prune(); written files stay', { skip: !python }, () => {
  const out = py(`
(dir / "in" / "x").mkdir(parents=True)
stale = dir / "in" / "x" / "OLD.csv"
stale.write_text("old")
o = la_gen_io.Outputs()
o.put(dir / "in" / "x" / "NEW.csv", b"new")
o.put(dir / "in" / "x" / "KEEP.csv", b"keep")
print(stale.exists())
print([p.name for p in o.prune(dir, "*/*/*.csv")])
print(sorted(p.name for p in (dir / "in" / "x").iterdir()))`);
  assert.deepEqual(out, ['True', "['OLD.csv']", "['KEEP.csv', 'NEW.csv']"]);
});

test('a PermissionError on replace is retried; one that never clears keeps the old file and leaves no temp file', { skip: !python }, () => {
  const out = py(`
f = dir / "c.csv"
f.write_bytes(b"old")
real = os.replace
left = [2]
def held(a, b):
    if left[0]:
        left[0] -= 1
        raise PermissionError(32, "The process cannot access the file because it is being used by another process")
    real(a, b)
os.replace = held
o = la_gen_io.Outputs()
print(o.put(f, b"new"), f.read_bytes(), left[0])
tries = [0]
def always(a, b):
    tries[0] += 1
    raise PermissionError(5, "Access is denied")
os.replace = always
try:
    o.put(f, b"newer")
    print("no error")
except PermissionError:
    print("raised", f.read_bytes(), "after", tries[0], "tries")
print(sorted(p.name for p in dir.iterdir()))`);
  assert.deepEqual(out, ["True b'new' 0", "raised b'new' after 10 tries", "['c.csv']"]);
});

test('a PermissionError on a stale removal is retried', { skip: !python }, () => {
  const out = py(`
(dir / "d").mkdir()
stale = dir / "d" / "OLD.csv"
stale.write_text("old")
real = Path.unlink
left = [3]
def held(self, missing_ok=False):
    if left[0]:
        left[0] -= 1
        raise PermissionError(32, "in use")
    real(self, missing_ok=missing_ok)
Path.unlink = held
print([p.name for p in la_gen_io.Outputs().prune(dir, "*/*.csv")], stale.exists(), left[0])`);
  assert.deepEqual(out, ["['OLD.csv'] False 0"]);
});
