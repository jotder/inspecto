#!/usr/bin/env node
// Every native library (.dll, .so, .dylib, .jnilib) the Enterprise intelligence sidecar carries must have its licence accounted for.
//
// WHY (`NATIVE-LICENCE-TEXTS-1`, 2026-10-03). The DJL tokenizers artifact embeds the MinGW-w64 GCC runtime
// (libstdc++-6.dll, libgcc_s_seh-1.dll) and winpthreads (libwinpthread-1.dll) with NO licence text. The
// GCC Runtime Library Exception lets us redistribute them, but the GPL-3.0 and exception texts (and the
// winpthreads COPYING) must accompany them. The texts live in compliance/third-party-licenses/ and
// package.ps1 copies them into the bundle's licenses/ dir.
//
// WHAT IT DOES.
//   (a) every text named in compliance/third-party-licenses/natives.json exists and matches its pinned
//       sha256 (a hand-edited or truncated licence text fails), and every native names at least one text;
//   (b) package.ps1 still copies that directory into the bundle (text check);
//   (c) with --jar <path> (a sidecar jar) or --bundle <dir> (a staged bundle): every native entry (.dll/.so/.dylib/.jnilib) in the
//       jar is classified by file name in natives.json AND its sha256 matches the per-entry pin in natives.json 'pins' (NATIVE-LICENCE-LINUX-MACOS-1), its `inJar` notice is present, and (with --bundle) every text it
//       needs is present in <bundle>/licenses/. An unclassified or unpinned native fails: a new native must be classified.
//   (d) the 'artifacts' versions in natives.json equal the versions in tools/dependencies.lock, so a bump of an
//       artifact that carries natives forces a re-pin.
//
// Usage:  node tools/check-native-licences.mjs [--jar <sidecar.jar> | --bundle <bundleDir>]

import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const licDir = join(root, 'compliance', 'third-party-licenses');
const manifest = JSON.parse(readFileSync(join(licDir, 'natives.json'), 'utf8'));
const errors = [];

for (const [file, want] of Object.entries(manifest.sha256)) {
  const p = join(licDir, file);
  if (!existsSync(p)) { errors.push(`missing licence text ${file}`); continue; }
  const got = createHash('sha256').update(readFileSync(p)).digest('hex');
  if (got !== want) errors.push(`${file}: sha256 ${got} != pinned ${want} (licence texts must be verbatim)`);
}
for (const [dll, e] of Object.entries(manifest.natives)) {
  for (const t of e.texts) if (!(t in manifest.sha256)) errors.push(`${dll}: text ${t} has no pinned sha256`);
  // NATIVE-LICENCE-SHADE-MERGE-1: every classified DLL needs a shipped licence TEXT - an SBOM entry or a
  // free-text note is not a licence text.
  // The one exception is a `textPending` naming an OPEN backlog row id, so the gap stays on the board.
  if (e.texts.length === 0) {
    const row = /^([A-Z0-9-]+-\d+):/.exec(e.textPending ?? '')?.[1];
    const board = readFileSync(join(root, 'docs', 'BACKLOG.md'), 'utf8');
    if (!row || !board.includes(`\`${row}\` —`)) errors.push(`${dll}: no licence text - every native DLL must ship one (textPending must name an open BACKLOG row)`);
    else console.warn(`check-native-licences: WARNING ${dll}: licence text pending under ${row}`);
  }
}

const pkg = readFileSync(join(root, 'inspecto', 'package.ps1'), 'utf8');
if (!/third-party-licenses/.test(pkg) || !/check-native-licences|natives\.json/.test(pkg)) {
  errors.push('inspecto/package.ps1 no longer stages compliance/third-party-licenses or checks natives.json');
}

// Minimal zip central-directory reader: entries with enough to read their bytes.
function zipEntries(path) {
  const buf = readFileSync(path);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`${path}: not a zip`);
  let n = buf.readUInt16LE(eocd + 10), off = buf.readUInt32LE(eocd + 16);
  if (n === 0xffff || off === 0xffffffff) { // zip64
    const loc = buf.readUInt32LE(eocd - 20) === 0x07064b50 ? Number(buf.readBigUInt64LE(eocd - 12)) : -1;
    n = Number(buf.readBigUInt64LE(loc + 32)); off = Number(buf.readBigUInt64LE(loc + 48));
  }
  const entries = [];
  for (let i = 0; i < n; i++) {
    const method = buf.readUInt16LE(off + 10), csize = buf.readUInt32LE(off + 20);
    const nl = buf.readUInt16LE(off + 28), xl = buf.readUInt16LE(off + 30), cl = buf.readUInt16LE(off + 32);
    const lho = buf.readUInt32LE(off + 42);
    entries.push({ name: buf.toString('utf8', off + 46, off + 46 + nl), method, csize, lho });
    off += 46 + nl + xl + cl;
  }
  const read = (e) => {
    const start = e.lho + 30 + buf.readUInt16LE(e.lho + 26) + buf.readUInt16LE(e.lho + 28);
    const raw = buf.subarray(start, start + e.csize);
    return e.method === 0 ? raw : inflateRawSync(raw);
  };
  return { entries, read };
}

// (d) the pinned artifact versions must be the ones tools/dependencies.lock resolves.
const lock = readFileSync(join(root, 'tools', 'dependencies.lock'), 'utf8');
for (const [ga, ver] of Object.entries(manifest.artifacts)) {
  const m = new RegExp('^' + ga.replace(/[.]/g, '\.') + ':[a-z]+:([^:\s]+):', 'm').exec(lock);
  if (!m) errors.push(`artifact ${ga} is not in tools/dependencies.lock - drop it from natives.json 'artifacts'`);
  else if (m[1] !== ver) errors.push(`artifact ${ga}: dependencies.lock resolves ${m[1]} but natives.json pins ${ver} - re-pin the natives`);
}

const NATIVE = /.(dll|so|dylib|jnilib)$/i;
const args = process.argv.slice(2);
const jarArg = args.indexOf('--jar') >= 0 ? args[args.indexOf('--jar') + 1] : null;
const bundleArg = args.indexOf('--bundle') >= 0 ? args[args.indexOf('--bundle') + 1] : null;
const jar = jarArg ?? (bundleArg ? join(bundleArg, 'inspecto-intelligence.jar') : null);
if (jar) {
  if (!existsSync(jar)) { console.error(`check-native-licences: ${jar} not found`); process.exit(2); }
  const { entries, read } = zipEntries(jar);
  const names = entries.map((e) => e.name);
  const libs = entries.filter((e) => NATIVE.test(e.name));
  if (libs.length === 0) { console.error(`check-native-licences: ${jar} has no native libraries - nothing to check`); process.exit(2); }
  for (const lib of libs) {
    const d = lib.name;
    const e = manifest.natives[basename(d)];
    if (!e) { errors.push(`${d}: unlisted native library - classify it in compliance/third-party-licenses/natives.json`); continue; }
    const pin = manifest.pins[d];
    if (!pin) errors.push(`${d}: native has no sha256 pin in natives.json 'pins'`);
    else {
      const got = createHash('sha256').update(read(lib)).digest('hex');
      if (got !== pin) errors.push(`${d}: sha256 ${got} != pinned ${pin} (a different build of the native - re-classify and re-pin)`);
    }
    if (e.inJar && !names.includes(e.inJar)) errors.push(`${d}: its notice ${e.inJar} is missing from the jar`);
    if (bundleArg) for (const t of e.texts) {
      if (!existsSync(join(bundleArg, 'licenses', t))) errors.push(`${d}: licence text licenses/${t} missing from the bundle`);
    }
  }
  console.log(`check-native-licences: ${libs.length} native entries in ${basename(jar)} checked`);
}

if (errors.length) {
  for (const e of errors) console.error(`check-native-licences: ${e}`);
  process.exit(1);
}
console.log('check-native-licences: OK');
