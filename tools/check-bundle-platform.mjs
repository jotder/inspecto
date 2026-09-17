#!/usr/bin/env node
// The platform label on a deployment zip must come from the runtime the zip embeds — never a literal.
//
// WHY THIS EXISTS (`RELEASE-BUNDLE-PLATFORM-MISMATCH-1`, 2026-09-17). release.yml runs package.ps1 on
// ubuntu-latest, where jlink links a LINUX image into bundle/runtime/. The one zip a tag published was
// then produced by `Compress-BundleForPlatform -Platform 'windows_amd64'` — a hard-coded label that
// stripped the linux_amd64 DuckDB extensions and kept the windows_amd64 ones, so the published bundle
// paired a Linux JVM with extensions it could not load. Nothing could see it: the boot smoke runs
// against the staging dir, which holds BOTH platforms' extensions, before the zip step strips one.
//
// WHAT IT DOES. Pure text checks over inspecto/package.ps1 (no PowerShell needed, so it runs on any
// CI runner):
//   (a) no call to Compress-BundleForPlatform passes a LITERAL -Platform (quoted string);
//   (b) every -Platform argument is a variable, and that variable is assigned from Get-RuntimePlatform
//       somewhere in the script (the ONLY permitted fallback is the documented -NoRuntime host-OS branch,
//       which the guard recognises by its exact shape so a second, unannounced fallback is refused);
//   (c) Get-RuntimePlatform exists and reads the image (references bin/java.exe and the ELF check),
//       so renaming or hollowing it out cannot pass with a same-named stub.
// A renamed function or a script without any Compress-BundleForPlatform call ABORTS (exit 2) — a
// guard with nothing to check must not pass.
//
// Falsified both ways on 2026-09-17 before being trusted: `-Platform 'windows_amd64'` at the compress
// call → exit 1 (a); `$hostPlatform = 'linux_amd64'` in place of the Get-RuntimePlatform read → exit 1 (b).
//
// Usage:  node tools/check-bundle-platform.mjs

import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGE_PS1 = join(ROOT, 'inspecto', 'package.ps1');

const fail = (msg) => { console.error(`✗ Bundle-platform guard: ${msg}`); process.exit(1); };
const abort = (msg) => { console.error(`✗ Bundle-platform guard could not run: ${msg}`); process.exit(2); };

const ps1 = readFileSync(PACKAGE_PS1, 'utf8').replace(/\r\n/g, '\n');
// Strip full-line comments so a commented-out old call or a prose mention does not count either way.
const code = ps1.split('\n').filter((l) => !/^\s*#/.test(l)).join('\n');

// (c) the derivation function must exist and must read the image.
const fnStart = code.indexOf('function Get-RuntimePlatform');
if (fnStart < 0) abort('`function Get-RuntimePlatform` is not defined in inspecto/package.ps1 — renamed? Point this guard at the new name; do NOT delete the check.');
const fnBody = code.slice(fnStart, code.indexOf('\n}\n', fnStart));
for (const must of ["'java.exe'", '0x7F', "'windows_amd64'", "'linux_amd64'"]) {
  if (!fnBody.includes(must)) fail(`Get-RuntimePlatform no longer contains ${must} — it must read the platform off the jlink image (bin/java.exe → windows_amd64, ELF bin/java → linux_amd64), not guess it.`);
}

// (a)+(b) every compress call.
const calls = [...code.matchAll(/^\s*Compress-BundleForPlatform\s+(.*)$/gm)].map((m) => m[1]);
if (calls.length === 0) abort('no `Compress-BundleForPlatform` call found in inspecto/package.ps1 — the zip step was renamed or removed.');

const assignedFromRuntime = new Set(
  [...code.matchAll(/^\s*\$(\w+)\s*=\s*Get-RuntimePlatform\b/gm)].map((m) => m[1]));
// The single sanctioned fallback: under -NoRuntime there is no image to read, so the HOST OS labels the
// zip. Recognised by exact shape; any other literal assignment to a platform variable is a defect.
const noRuntimeFallback = /^\s*\$(\w+)\s*=\s*if\s*\(\$IsWindows\s+-or\s+\$env:OS\s+-eq\s+'Windows_NT'\)\s*\{\s*'windows_amd64'\s*\}\s*else\s*\{\s*'linux_amd64'\s*\}/gm;
const fallbackVars = new Set([...code.matchAll(noRuntimeFallback)].map((m) => m[1]));

for (const args of calls) {
  const m = /-Platform\s+(\S+)/.exec(args);
  if (!m) fail(`Compress-BundleForPlatform call without -Platform: \`${args.trim()}\``);
  const arg = m[1];
  if (/^['"]/.test(arg)) fail(`hard-coded platform at the compress call: \`-Platform ${arg}\`. The label must be read off the embedded runtime (Get-RuntimePlatform) — a literal here is exactly how a Linux JVM shipped with windows_amd64 extensions.`);
  if (!/^\$\w+$/.test(arg)) fail(`-Platform must be a plain variable, got \`${arg}\` in \`${args.trim()}\``);
  const v = arg.slice(1);
  if (!assignedFromRuntime.has(v)) fail(`\`$${v}\` is passed as -Platform but is never assigned from Get-RuntimePlatform. Derive it from the image; do not label the zip by host OS or by assumption.`);
  // Any OTHER literal assignment to the variable (outside the recognised -NoRuntime fallback) is a second
  // source of truth and therefore a defect.
  const literalAssigns = [...code.matchAll(new RegExp(`^\\s*\\$${v}\\s*=\\s*(['"][^'"]*['"]|if\\b[^\\n]*)$`, 'gm'))];
  for (const la of literalAssigns) {
    if (la[1].startsWith('if') && fallbackVars.has(v)) continue;
    fail(`\`$${v}\` is also assigned a literal/host-derived value: \`${la[0].trim()}\`. Only Get-RuntimePlatform (and the documented -NoRuntime host-OS fallback) may set it.`);
  }
}

console.log(`✓ Bundle-platform guard: ${calls.length} Compress-BundleForPlatform call(s), every -Platform derived from Get-RuntimePlatform [${[...assignedFromRuntime].map((v) => '$' + v).join(', ')}]`);
