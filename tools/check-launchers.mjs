#!/usr/bin/env node
// The emitted launchers must hand the JVM the right flag set for their edition — proved by RUNNING
// them, not by reading them.
//
// WHY THIS EXISTS (`LAUNCHER-GUARD-1`, filed 2026-09-11). `serve.bat` spent its life dropping
// `-Dauth.mode=oidc` on every Windows Standard/Enterprise bundle. The edition branch was a
// parenthesised `if exist inspecto-security.jar ( ... )` block, and cmd.exe expands every `%OPTS%`
// inside a parenthesised block ONCE, when the block is PARSED — so N successive
// `set "OPTS=%OPTS% ..."` statements all expand to the value OPTS held BEFORE the block and only the
// last one survives. Five of six were discarded. The service booted AUTH-FREE while printing
// `edition: Enterprise`. It was fixed on 2026-09-11 by flattening the branch to one statement per
// line; the GAP was not fixed. Nothing in this repository has ever executed an emitted launcher, so
// `security.md` §8.7's long-standing "`authMode` ↔ enforcement coupling has no test" still held, the
// only evidence was a by-hand `cmd.exe` run, and an identical regression would be just as silent —
// on the one platform CI never exercises.
//
// WHAT IT DOES. It extracts the `$serveShContent` / `$serveBatContent` here-strings from
// `inspecto/package.ps1` (the single source of both launchers), writes each into a throwaway bundle
// populated with STUB jars for one edition, puts a stub `java` first on PATH, runs the launcher for
// real, and asserts the argv the launcher actually handed that `java`.
//
// ⚠ THE STUB IS ON PATH, NOT A TEXT SUBSTITUTION. Nothing edits the launcher: it runs verbatim,
// including its own `exec` / launch line. A guard that rewrote the launch line would stop proving
// the thing that broke.
//
// SCOPE, stated so it can be audited apart from the rules (a guard's scope is where its silent
// exemptions live):
//   • Subject: `serve.sh` and `serve.bat` ONLY. `run.sh`/`run.bat`/`ura.sh`/`ura.bat` are one-shot
//     ETL launchers with no edition branch and no auth flags; they are out of scope and untested here.
//   • `serve.sh` is EXECUTED on every platform — bash is required, and its absence is a hard error
//     (exit 2), never a skip. A skipping check is how this repository has hidden breakage before.
//   • `serve.bat` is EXECUTED only on Windows. On every other platform it is checked STATICALLY
//     instead (the self-referential-`set`-inside-a-block rule below), so the non-Windows run is
//     never vacuous — it just proves less.
//
// Usage:  node tools/check-launchers.mjs

import { readFileSync, writeFileSync, mkdirSync, rmSync, chmodSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGE_PS1 = join(ROOT, 'inspecto', 'package.ps1');
const IS_WINDOWS = process.platform === 'win32';

const fail = (msg) => { console.error(`✗ Launcher guard: ${msg}`); process.exit(1); };
const abort = (msg) => { console.error(`✗ Launcher guard could not run: ${msg}`); process.exit(2); };

// ── extract one PowerShell single-quoted here-string by variable name ──────────────────────────
// Single-quoted (`@'` … `'@`) is literal: what is between the delimiters is byte-for-byte what
// package.ps1 writes into the bundle. A renamed variable must ABORT, not pass with nothing to check
// — that is the failure mode the sibling DuckDB-extension guard was falsified against.
function hereString(ps1, varName) {
  const open = ps1.indexOf(`$${varName} = @'\n`);
  if (open < 0) abort(`\`$${varName}\` is not a single-quoted here-string in inspecto/package.ps1.\n` +
    `  It was renamed, re-quoted, or moved. Point this guard at the new name — do NOT delete the check.`);
  const bodyStart = open + `$${varName} = @'\n`.length;
  const end = ps1.indexOf(`\n'@\n`, bodyStart);
  if (end < 0) abort(`\`$${varName}\` has no closing \`'@\` line in inspecto/package.ps1.`);
  return ps1.slice(bodyStart, end + 1);
}

// ── bundle fixtures ───────────────────────────────────────────────────────────────────────────
// package.ps1 writes serve.sh with LF and serve.bat with CRLF + ASCII (Write-LfScript /
// Write-CrlfScript). Reproduced exactly, non-ASCII included: the `rem` comments carry ⛔/🔴, which
// .NET's ASCII encoder turns into `?`, and cmd.exe therefore never sees them either.
const toLf = (s) => s.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
const asBatBytes = (s) => Buffer.from(
  toLf(s).replace(/\n/g, '\r\n').replace(/[^\x00-\x7F]/g, '?'), 'latin1');

function makeBundle(dir, jars) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(join(dir, 'bin'), { recursive: true });
  for (const jar of jars) writeFileSync(join(dir, jar), '');
  return dir;
}

// The stub `java`. serve.sh builds a real argv ARRAY, so its stub prints one entry per line.
// serve.bat cannot: cmd.exe builds ONE command line and the JVM's own tokenizer splits it on
// whitespace, so the stub echoes that line verbatim and the guard splits it the same way the JVM
// would. ⛔ Not `for %%A in (%*)` — cmd's `for` also splits on `=`, which would turn
// `-Dcontrol.port=8080` into two "arguments" that no JVM ever sees.
function installStubJava(dir) {
  if (IS_WINDOWS) {
    // cmd.exe resolves a bare `java` per PATH directory across PATHEXT, so `java.bat` in a directory
    // that holds no java.exe wins.
    writeFileSync(join(dir, 'bin', 'java.bat'), '@echo off\r\necho ARGVLINE:%*\r\n', 'latin1');
  }
  const sh = '#!/usr/bin/env bash\nfor a in "$@"; do printf "ARGV:%s\\n" "$a"; done\n';
  writeFileSync(join(dir, 'bin', 'java'), sh);
  chmodSync(join(dir, 'bin', 'java'), 0o755);
}

function runLauncher(kind, dir, env) {
  const pathSep = IS_WINDOWS ? ';' : ':';
  const childEnv = { ...process.env, ...env, PATH: join(dir, 'bin') + pathSep + process.env.PATH };
  // Never inherit the operator's own OIDC/JVM settings into a scenario that must NOT see them.
  for (const k of ['AUTH_OIDC_ISSUER', 'AUTH_OIDC_JWKS_URI', 'AUTH_OIDC_AUDIENCE', 'AUTH_OIDC_CLIENT_ID',
    'AUTH_OIDC_CLIENT_SECRET', 'INSPECTO_JAVA_OPTS', 'EXTRA_JAVA_OPTS', 'INSPECTO_DB_URL', 'PORT',
    'SPACES_ROOT', 'CORS_ORIGIN', 'HTTPS_KEYSTORE', 'HTTPS_KEYSTORE_PASSWORD']) {
    if (!(k in env)) delete childEnv[k];
  }
  // `.\serve.bat`, not `serve.bat`: a machine with NoDefaultCurrentDirectoryInExePath set does not
  // search the working directory, and the failure reads as "not recognized as an internal command".
  const [cmd, args] = kind === 'sh' ? ['bash', ['serve.sh']] : ['cmd', ['/c', '.\\serve.bat']];
  try {
    return execFileSync(cmd, args, { cwd: dir, env: childEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    if (e.code === 'ENOENT') abort(`\`${cmd}\` is not on PATH. serve.sh must be EXECUTED, not skipped.`);
    abort(`${kind === 'sh' ? 'serve.sh' : 'serve.bat'} exited ${e.status}:\n${e.stdout || ''}${e.stderr || ''}`);
  }
}

// argv, plus the `-cp` value split into jar names and the edition the launcher printed.
function observe(out) {
  const lines = out.split(/\r?\n/);
  const argv = lines.some((l) => l.startsWith('ARGVLINE:'))
    ? lines.find((l) => l.startsWith('ARGVLINE:')).slice(9).trim().split(/\s+/).filter(Boolean)
    : lines.filter((l) => l.startsWith('ARGV:')).map((l) => l.slice(5));
  if (!argv.length) abort(`the launcher produced no ARGV lines — the stub java was not reached:\n${out}`);
  const cpAt = argv.indexOf('-cp');
  if (cpAt < 0 || cpAt === argv.length - 1) fail(`the launcher passed no \`-cp\`:\n  ${argv.join('\n  ')}`);
  const edition = (out.match(/edition: (\w+)/) || [])[1] || '(none printed)';
  return { argv, jars: argv[cpAt + 1].split(/[;:]/).filter(Boolean), edition, raw: out };
}

// ── scenarios ─────────────────────────────────────────────────────────────────────────────────
// `expect` / `reject` match an argv entry exactly; a `reject` PREFIX is written with a trailing `=`
// removed so `-Dauth.mode` catches any value at all, which is the assertion Personal needs.
const PERSONAL = ['inspecto.jar'];
const PROFESSIONAL = [...PERSONAL, 'inspecto-security.jar'];
const ENTERPRISE = [...PROFESSIONAL, 'inspecto-policy.jar'];
const OIDC_ENV = {
  AUTH_OIDC_ISSUER: 'https://idp.example/realms/x',
  AUTH_OIDC_JWKS_URI: 'https://idp.example/realms/x/jwks',
  AUTH_OIDC_AUDIENCE: 'inspecto',
  AUTH_OIDC_CLIENT_ID: 'inspecto-ui',
  // ⚠ Not a credential, and spelled so `check-secrets.mjs` can tell: it recognises `example` as a
  // placeholder. The launcher must never put this value on the command line at all — the `reject`
  // below asserts exactly that — so a distinctive string is what makes the assertion meaningful.
  AUTH_OIDC_CLIENT_SECRET: 'example-value-that-must-never-be-passed',
};
const ALWAYS = ['--enable-native-access=ALL-UNNAMED', '-Dcontrol.port=8080', '-Dspaces.root=spaces'];

const SCENARIOS = [
  {
    name: 'Personal — no security jar',
    jars: PERSONAL, env: {}, edition: 'Personal',
    expect: ALWAYS,
    // The whole point of the edition seam: Personal is byte-for-byte the historic auth-free bundle.
    rejectPrefix: ['-Dauth.mode', '-Devents.backend', '-Dauth.oidc.'],
    jarsOnCp: ['inspecto.jar'],
  },
  {
    name: 'Professional — security jar, no OIDC env',
    jars: PROFESSIONAL, env: {}, edition: 'Professional',
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet'],
    rejectPrefix: ['-Dauth.oidc.'],
    jarsOnCp: ['inspecto.jar', 'inspecto-security.jar'],
  },
  {
    // 🔴 THE REGRESSION TEST. SERVEBAT-OPTS-1 lost five of these six flags and kept the last. Any
    // future rewrite that reintroduces a parse-time-expanded block fails HERE, with the flag named.
    name: 'Professional — security jar + every OIDC variable set',
    jars: PROFESSIONAL, env: OIDC_ENV, edition: 'Professional',
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet',
      `-Dauth.oidc.issuer=${OIDC_ENV.AUTH_OIDC_ISSUER}`,
      `-Dauth.oidc.jwksUri=${OIDC_ENV.AUTH_OIDC_JWKS_URI}`,
      `-Dauth.oidc.audience=${OIDC_ENV.AUTH_OIDC_AUDIENCE}`,
      `-Dauth.oidc.clientId=${OIDC_ENV.AUTH_OIDC_CLIENT_ID}`,
      // A REFERENCE, never the value: the backend expands ${ENV:...} at use, so the secret must not
      // reach the process command line. Asserting the literal is asserting that.
      '-Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}'],
    reject: [`-Dauth.oidc.clientSecret=${OIDC_ENV.AUTH_OIDC_CLIENT_SECRET}`],
    jarsOnCp: ['inspecto.jar', 'inspecto-security.jar'],
  },
  {
    name: 'Enterprise — security + policy jar',
    jars: ENTERPRISE, env: OIDC_ENV, edition: 'Enterprise',
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet'],
    jarsOnCp: ['inspecto.jar', 'inspecto-security.jar', 'inspecto-policy.jar'],
  },
  {
    // Operator flags are appended LAST on purpose, so they cannot clobber the required ones.
    name: 'Professional — operator INSPECTO_JAVA_OPTS',
    jars: PROFESSIONAL, env: { INSPECTO_JAVA_OPTS: '-Xmx4g -Dui.static.log=DEBUG' }, edition: 'Professional',
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Xmx4g', '-Dui.static.log=DEBUG'],
    lastAfter: { after: '-Dauth.mode=oidc', these: ['-Xmx4g', '-Dui.static.log=DEBUG'] },
    jarsOnCp: ['inspecto.jar', 'inspecto-security.jar'],
  },
];

function check(kind, scenario, got) {
  const where = `${kind === 'sh' ? 'serve.sh' : 'serve.bat'} · ${scenario.name}`;
  const problems = [];
  for (const want of scenario.expect || []) {
    if (!got.argv.includes(want)) problems.push(`missing flag  ${want}`);
  }
  for (const bad of scenario.reject || []) {
    if (got.argv.includes(bad)) problems.push(`forbidden flag  ${bad}`);
  }
  for (const prefix of scenario.rejectPrefix || []) {
    const hit = got.argv.find((a) => a.startsWith(prefix));
    if (hit) problems.push(`forbidden flag  ${hit}  (nothing may start with ${prefix} here)`);
  }
  if (got.edition !== scenario.edition) {
    problems.push(`printed \`edition: ${got.edition}\`, expected \`${scenario.edition}\``);
  }
  const cp = [...got.jars].sort().join(' ');
  const wantCp = [...scenario.jarsOnCp].sort().join(' ');
  if (cp !== wantCp) problems.push(`classpath is [${cp}], expected [${wantCp}]`);
  if (scenario.lastAfter) {
    const anchor = got.argv.indexOf(scenario.lastAfter.after);
    for (const t of scenario.lastAfter.these) {
      if (got.argv.indexOf(t) < anchor) problems.push(`${t} must be appended AFTER ${scenario.lastAfter.after}`);
    }
  }
  if (problems.length) {
    fail(`${where}\n${problems.map((p) => `    ${p}`).join('\n')}\n\n  argv the launcher actually passed:\n` +
      got.argv.map((a) => `    ${a}`).join('\n') +
      `\n\n  Fix inspecto/package.ps1's launcher here-string, not this guard. A launcher that drops a\n` +
      `  flag boots a service that LOOKS like its edition and is not one (SERVEBAT-OPTS-1).`);
  }
}

// ── the static serve.bat rule (runs on EVERY platform) ────────────────────────────────────────
// A self-referential `set "VAR=%VAR% …"` inside a parenthesised block is the SERVEBAT-OPTS-1 bug
// itself, whatever the variable: cmd.exe expands the whole block once at parse time, so every such
// statement but the last is silently discarded. This is the rule the non-Windows run stands on.
// ⛔ The fix is one statement per line, NEVER `setlocal EnableDelayedExpansion` — these values carry
// operator secrets and keystore passwords, and delayed expansion eats `!` inside them.
function staticBatRule(bat) {
  const offenders = [];
  let depth = 0;
  bat.split('\n').forEach((line, i) => {
    const code = /^\s*rem\b/i.test(line) ? '' : line.replace(/"[^"]*"/g, '""');
    const selfRef = line.match(/set\s+"(\w+)=%\1%/i);
    if (depth > 0 && selfRef) offenders.push({ n: i + 1, v: selfRef[1], line: line.trim() });
    for (const ch of code) { if (ch === '(') depth++; else if (ch === ')') depth = Math.max(0, depth - 1); }
  });
  if (offenders.length) {
    fail(`serve.bat re-expands a variable inside a parenthesised block — the SERVEBAT-OPTS-1 bug:\n` +
      offenders.map((o) => `    line ${o.n}  (%${o.v}%)  ${o.line}`).join('\n') +
      `\n\n  cmd.exe expands a parenthesised block ONCE at PARSE time, so all but the last of these\n` +
      `  statements is discarded and the flags they add vanish silently. Write one \`if … set …\` per\n` +
      `  line. ⛔ Do NOT reach for \`setlocal EnableDelayedExpansion\`: these values carry operator\n` +
      `  secrets and keystore passwords, and delayed expansion eats \`!\` inside them.`);
  }
  return bat.split('\n').filter((l) => /set\s+"(\w+)=%\1%/i.test(l)).length;
}

// ── run ───────────────────────────────────────────────────────────────────────────────────────
// package.ps1 itself is CRLF in the working tree; normalise before slicing so the here-string
// delimiters match on either checkout style.
const ps1 = toLf(readFileSync(PACKAGE_PS1, 'utf8'));
const serveSh = hereString(ps1, 'serveShContent');
const serveBat = hereString(ps1, 'serveBatContent');

const selfRefLines = staticBatRule(serveBat);

const work = join(tmpdir(), `inspecto-launcher-guard-${process.pid}`);
const kinds = IS_WINDOWS ? ['sh', 'bat'] : ['sh'];
try {
  for (const kind of kinds) {
    for (const scenario of SCENARIOS) {
      const dir = makeBundle(join(work, `${kind}-${SCENARIOS.indexOf(scenario)}`), scenario.jars);
      installStubJava(dir);
      if (kind === 'sh') writeFileSync(join(dir, 'serve.sh'), toLf(serveSh));
      else writeFileSync(join(dir, 'serve.bat'), asBatBytes(serveBat));
      check(kind, scenario, observe(runLauncher(kind, dir, scenario.env)));
    }
  }
} finally {
  rmSync(work, { recursive: true, force: true });
}

const ran = kinds.length * SCENARIOS.length;
console.log(
  `✓ Launcher guard: ${ran} launcher run(s) — ${SCENARIOS.length} edition scenario(s) × ` +
  `${kinds.map((k) => (k === 'sh' ? 'serve.sh' : 'serve.bat')).join(' + ')}, each EXECUTED over stub jars ` +
  `with a stub java first on PATH; every emitted flag set matched its edition.\n` +
  `  serve.bat static rule: ${selfRefLines} self-referential \`set\` statement(s), all outside any ` +
  `parenthesised block (the SERVEBAT-OPTS-1 shape).\n` +
  (IS_WINDOWS
    ? `  scope: serve.sh + serve.bat both executed (Windows runner).`
    : `  scope: serve.sh executed; serve.bat NOT executed on ${process.platform} — it is covered here by ` +
      `the static rule only, and is proved by execution on a Windows runner.`) +
  ` run.sh/run.bat/ura.sh/ura.bat are out of scope: no edition branch, no auth flags.`
);
