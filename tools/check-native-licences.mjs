#!/usr/bin/env node
// Every native DLL the Enterprise intelligence sidecar carries must have its licence accounted for.
//
// WHY (`NATIVE-LICENCE-TEXTS-1`, 2026-10-03). The DJL tokenizers artifact embeds the MinGW-w64 GCC runtime
// (libstdc++-6.dll, libgcc_s_seh-1.dll) and winpthreads (libwinpthread-1.dll) with NO licence text. The
// GCC Runtime Library Exception lets us redistribute them, but the GPL-3.0 and exception texts (and the
// winpthreads COPYING) must accompany them. The texts live in compliance/third-party-licenses/ and
// package.ps1 copies them into the bundle's licenses/ dir.
//
// WHAT IT DOES.
//   (a) every text named in compliance/third-party-licenses/natives.json exists and matches its pinned
//       sha256 (a hand-edited or truncated licence text fails), and every DLL names at least one text;
//   (b) package.ps1 still copies that directory into the bundle (text check);
//   (c) with --jar <path> (a sidecar jar) or --bundle <dir> (a staged bundle): every *.dll entry in the
//       jar is listed in natives.json, its `inJar` notice is present, and (with --bundle) every text it
//       needs is present in <bundle>/licenses/. An unlisted DLL fails: a new native must be classified.
//
// Usage:  node tools/check-native-licences.mjs [--jar <sidecar.jar> | --bundle <bundleDir>]

import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
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
for (const [dll, e] of Object.entries(manifest.dlls)) {
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

// Minimal zip central-directory reader: entry names only.
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
  const names = [];
  for (let i = 0; i < n; i++) {
    const nl = buf.readUInt16LE(off + 28), xl = buf.readUInt16LE(off + 30), cl = buf.readUInt16LE(off + 32);
    names.push(buf.toString('utf8', off + 46, off + 46 + nl));
    off += 46 + nl + xl + cl;
  }
  return names;
}

const args = process.argv.slice(2);
const jarArg = args.indexOf('--jar') >= 0 ? args[args.indexOf('--jar') + 1] : null;
const bundleArg = args.indexOf('--bundle') >= 0 ? args[args.indexOf('--bundle') + 1] : null;
const jar = jarArg ?? (bundleArg ? join(bundleArg, 'inspecto-intelligence.jar') : null);
if (jar) {
  if (!existsSync(jar)) { console.error(`check-native-licences: ${jar} not found`); process.exit(2); }
  const names = zipEntries(jar);
  const dlls = names.filter((n) => n.toLowerCase().endsWith('.dll'));
  if (dlls.length === 0) { console.error(`check-native-licences: ${jar} has no DLLs - nothing to check`); process.exit(2); }
  for (const d of dlls) {
    const e = manifest.dlls[basename(d)];
    if (!e) { errors.push(`${d}: unlisted native DLL - classify it in compliance/third-party-licenses/natives.json`); continue; }
    if (e.inJar && !names.includes(e.inJar)) errors.push(`${d}: its notice ${e.inJar} is missing from the jar`);
    if (bundleArg) for (const t of e.texts) {
      if (!existsSync(join(bundleArg, 'licenses', t))) errors.push(`${d}: licence text licenses/${t} missing from the bundle`);
    }
  }
  console.log(`check-native-licences: ${dlls.length} DLL entries in ${basename(jar)} checked`);
}

if (errors.length) {
  for (const e of errors) console.error(`check-native-licences: ${e}`);
  process.exit(1);
}
console.log('check-native-licences: OK');
