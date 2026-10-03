// Fixture tests for check-native-licences.mjs (NATIVE-LICENCE-LINUX-MACOS-1): plants each violation in a
// throwaway copy of the repo's inputs and requires the guard to go red; the real tree must stay green.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, cpSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash, } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const sha = (b) => createHash('sha256').update(b).digest('hex');

// Minimal STORED zip writer (CRC is never checked by the guard's reader).
function zip(files) {
  const parts = [], central = [];
  let off = 0;
  for (const [name, data] of Object.entries(files)) {
    const nb = Buffer.from(name), lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(nb.length, 26);
    lh.writeUInt32LE(data.length, 18); lh.writeUInt32LE(data.length, 22);
    parts.push(lh, nb, data);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt32LE(data.length, 20); ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(nb.length, 28); ch.writeUInt32LE(off, 42);
    central.push(ch, nb);
    off += 30 + nb.length + data.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(central.length / 2, 8); end.writeUInt16LE(central.length / 2, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...parts, cd, end]);
}

function fixture(entries, mutate = () => {}) {
  const dir = mkdtempSync(join(tmpdir(), 'native-lic-'));
  for (const d of ['tools', 'inspecto', 'docs']) mkdirSync(join(dir, d), { recursive: true });
  cpSync(join(repo, 'tools', 'check-native-licences.mjs'), join(dir, 'tools', 'check-native-licences.mjs'));
  cpSync(join(repo, 'tools', 'dependencies.lock'), join(dir, 'tools', 'dependencies.lock'));
  cpSync(join(repo, 'inspecto', 'package.ps1'), join(dir, 'inspecto', 'package.ps1'));
  cpSync(join(repo, 'docs', 'BACKLOG.md'), join(dir, 'docs', 'BACKLOG.md'));
  cpSync(join(repo, 'compliance'), join(dir, 'compliance'), { recursive: true });
  const mp = join(dir, 'compliance', 'third-party-licenses', 'natives.json');
  const m = JSON.parse(readFileSync(mp, 'utf8'));
  m.pins = Object.fromEntries(Object.entries(entries).map(([n, d]) => [n, sha(d)]));
  mutate(m);
  writeFileSync(mp, JSON.stringify(m));
  const jar = join(dir, 'sidecar.jar');
  writeFileSync(jar, zip(entries));
  return spawnSync('node', [join(dir, 'tools', 'check-native-licences.mjs'), '--jar', jar], { encoding: 'utf8' });
}

const so = 'ai/onnxruntime/native/linux-x64/libonnxruntime.so';
const bytes = Buffer.from('fake-native');

test('the real manifest is green', () => {
  const r = spawnSync('node', [join(repo, 'tools', 'check-native-licences.mjs')], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
});

test('a classified, pinned .so and .dylib pass', () => {
  const r = fixture({ [so]: bytes, 'ai/onnxruntime/native/osx-x64/libonnxruntime.dylib': Buffer.from('x'), 'ThirdPartyNotices.txt': Buffer.from('n') },
    (m) => { m.pins = {}; });
  assert.equal(r.status, 1); // pins were cleared -> must fail (negative probe for the next test)
  assert.match(r.stderr, /no sha256 pin/);
  const ok = fixture({ [so]: bytes, 'ai/onnxruntime/native/osx-x64/libonnxruntime.dylib': Buffer.from('x'), 'ThirdPartyNotices.txt': Buffer.from('n') });
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /2 native entries/);
});

test('an unclassified .so fails', () => {
  const r = fixture({ 'native/linux/libmystery.so': bytes });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /unlisted native library/);
});

test('an unclassified .dylib fails', () => {
  const r = fixture({ 'native/osx/libmystery.dylib': bytes });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /unlisted native library/);
});

test('an unclassified .dll still fails', () => {
  const r = fixture({ 'native/win/mystery.dll': bytes });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /unlisted native library/);
});

test('a classified .so whose bytes differ from the pin fails', () => {
  const r = fixture({ [so]: bytes }, (m) => { m.pins[so] = sha(Buffer.from('other build')); });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /!= pinned/);
});

test('a version bump in dependencies.lock without a re-pin fails', () => {
  const r = fixture({ [so]: bytes }, (m) => { m.artifacts['com.microsoft.onnxruntime:onnxruntime'] = '1.19.0'; });
  assert.equal(r.status, 1);
  assert.match(r.stderr, /re-pin the natives/);
});
