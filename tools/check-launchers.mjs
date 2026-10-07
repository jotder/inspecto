#!/usr/bin/env node
// The emitted launchers must hand the JVM the right flag set AND the right classpath for their edition — proved by
// RUNNING them, not by reading them.
//
// WHY THIS EXISTS (`LAUNCHER-GUARD-1`, filed 2026-09-11). `serve.bat` spent its life dropping
// `-Dauth.mode=oidc` on every Windows Standard/Enterprise bundle. The edition branch was a
// parenthesised `if exist inspecto-oidc.jar ( ... )` block, and cmd.exe expands every `%OPTS%`
// inside a parenthesised block ONCE, when the block is PARSED — so N successive
// `set "OPTS=%OPTS% ..."` statements all expand to the value OPTS held BEFORE the block and only the
// last one survives. Five of six were discarded. The service booted AUTH-FREE while printing
// `edition: Enterprise`. It was fixed on 2026-09-11 by flattening the branch to one statement per
// line; the GAP was not fixed. Nothing in this repository had ever executed an emitted launcher, so
// `security.md` §8.7's long-standing "`authMode` ↔ enforcement coupling has no test" still held, the
// only evidence was a by-hand `cmd.exe` run, and an identical regression would be just as silent —
// on the one platform CI never exercises.
//
// WHAT IT DOES. It extracts the launcher here-strings from `inspecto/package.ps1` (the single source of every
// launcher), writes each into a throwaway bundle populated with STUB jars, a `modules.list` and an
// `edition.properties` exactly as `tools/offering-classpath.mjs` emits them for one edition, puts a stub `java`
// first on PATH, runs the launcher for real, and asserts the argv the launcher actually handed that `java`.
//
// P3d STAGE 1 (2026-10-08): the classpath is no longer hand-kept in four launchers — they READ `modules.list`, and
// serve.* read the edition from `edition.properties`. So every scenario's expected `-cp` is the GENERATOR's list for
// that edition, compared IN ORDER: the launcher's output must equal `classpath(edition)`, and a launcher that
// ignores the list (or sniffs jar presence again) goes red here. Scenarios also prove: a stray jar that is not on the
// list stays OFF the classpath and cannot change the edition (the drop-in); the documented fallbacks (no list ⇒ every
// *.jar; no marker ⇒ the old heuristic); an unknown edition is refused; Preview prints Preview and is NOT Enterprise.
//
// ⚠ THE STUB IS ON PATH, NOT A TEXT SUBSTITUTION. Nothing edits the launcher: it runs verbatim,
// including its own `exec` / launch line. A guard that rewrote the launch line would stop proving
// the thing that broke.
//
// SCOPE, stated so it can be audited apart from the rules (a guard's scope is where its silent
// exemptions live):
//   • Subject: `serve.sh` and `serve.bat` EXECUTED per edition; `run.sh`/`run.bat` EXECUTED for their classpath only (runOneShot); `serve-demo.*` STATICALLY only
//     (they must read modules.list and name no jar — `staticFindings`). They have no edition branch and no auth flags.
//   • `serve.sh` is EXECUTED on every platform — bash is required, and its absence is a hard error
//     (exit 2), never a skip. A skipping check is how this repository has hidden breakage before.
//   • `serve.bat` is EXECUTED only on Windows. On every other platform it is checked STATICALLY
//     instead (the self-referential-`set`-inside-a-block rule below), so the non-Windows run is
//     never vacuous — it just proves less.
//
// The pure checks are exported (`staticFindings`, `runScenarios`); tools/check-launchers.test.mjs holds the negative
// fixtures proving each rule still goes red.
//
// Usage:  node tools/check-launchers.mjs

import { readFileSync, writeFileSync, mkdirSync, rmSync, chmodSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { classpath, renderList, renderEdition } from './offering-classpath.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGE_PS1 = join(ROOT, 'inspecto', 'package.ps1');
const IS_WINDOWS = process.platform === 'win32';

/** "Could not run" (exit 2) is distinct from a finding (exit 1): a skipping check is how breakage hides. */
class Abort extends Error {}
const abort = (msg) => { throw new Abort(msg); };

// ── extract one PowerShell single-quoted here-string by variable name ──────────────────────────
// Single-quoted (`@'` … `'@`) is literal: what is between the delimiters is byte-for-byte what
// package.ps1 writes into the bundle. A renamed variable must ABORT, not pass with nothing to check
// — that is the failure mode the sibling DuckDB-extension guard was falsified against.
export function hereString(ps1, varName) {
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

function makeBundle(dir, scenario) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(join(dir, 'bin'), { recursive: true });
  for (const jar of scenario.jars) writeFileSync(join(dir, jar), '');
  // Exactly what package.ps1 step 3a-ter has the generator write: LF-only list, one jar per line, plus the edition marker.
  if (!scenario.noList) writeFileSync(join(dir, 'modules.list'), renderList(scenario.list));
  if (!scenario.noMarker) writeFileSync(join(dir, 'edition.properties'), scenario.markerText ?? renderEdition(scenario.edition));
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

function runLauncher(kind, dir, env, allowFail) {
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
    return { status: 0, out: execFileSync(cmd, args, { cwd: dir, env: childEnv, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }) };
  } catch (e) {
    if (e.code === 'ENOENT') abort(`\`${cmd}\` is not on PATH. serve.sh must be EXECUTED, not skipped.`);
    if (allowFail && typeof e.status === 'number') return { status: e.status, out: `${e.stdout || ''}${e.stderr || ''}` };
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
  const edition = (out.match(/edition: (\w+)/) || [])[1] || '(none printed)';
  if (cpAt < 0 || cpAt === argv.length - 1) return { argv, jars: null, edition, raw: out };
  return { argv, jars: argv[cpAt + 1].replace(/^"|"$/g, '').split(/[;:]/).filter(Boolean), edition, raw: out };
}

// ── scenarios ─────────────────────────────────────────────────────────────────────────────────
// `expect` / `reject` match an argv entry exactly; a `reject` PREFIX is written with a trailing `=`
// removed so `-Dauth.mode` catches any value at all, which is the assertion Personal needs.
// The jar lists are the GENERATOR's (tools/offering-classpath.mjs), i.e. exactly what a packaged bundle's modules.list holds.
const CP = { Personal: classpath('Personal'), Professional: classpath('Professional'), Enterprise: classpath('Enterprise'), Preview: classpath('Preview') };
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
const ed = (edition, extra) => ({ edition, jars: CP[edition], list: CP[edition], jarsOnCp: CP[edition], ...extra });

export const SCENARIOS = [
  ed('Personal', {
    name: 'Personal — no OIDC jar',
    env: {},
    expect: ALWAYS,
    // The whole point of the edition seam: Personal is byte-for-byte the historic auth-free bundle.
    rejectPrefix: ['-Dauth.mode', '-Devents.backend', '-Dauth.oidc.', '-Dobjects.backend'],
  }),
  ed('Professional', {
    name: 'Professional — OIDC + secrets + geo jars, no OIDC env',
    env: {},
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet'],
    // OBJECTS-BACKEND-DEFAULT-MEMORY-1: Professional runs the engine default (`db`); only Enterprise pins one.
    rejectPrefix: ['-Dauth.oidc.', '-Dobjects.backend'],
  }),
  // 🔴 THE REGRESSION TEST. SERVEBAT-OPTS-1 lost five of these six flags and kept the last. Any
  // future rewrite that reintroduces a parse-time-expanded block fails HERE, with the flag named.
  ed('Professional', {
    name: 'Professional — OIDC + secrets + geo jars + every OIDC variable set',
    env: OIDC_ENV,
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet',
      `-Dauth.oidc.issuer=${OIDC_ENV.AUTH_OIDC_ISSUER}`,
      `-Dauth.oidc.jwksUri=${OIDC_ENV.AUTH_OIDC_JWKS_URI}`,
      `-Dauth.oidc.audience=${OIDC_ENV.AUTH_OIDC_AUDIENCE}`,
      `-Dauth.oidc.clientId=${OIDC_ENV.AUTH_OIDC_CLIENT_ID}`,
      // A REFERENCE, never the value: the backend expands ${ENV:...} at use, so the secret must not
      // reach the process command line. Asserting the literal is asserting that.
      '-Dauth.oidc.clientSecret=${ENV:AUTH_OIDC_CLIENT_SECRET}'],
    reject: [`-Dauth.oidc.clientSecret=${OIDC_ENV.AUTH_OIDC_CLIENT_SECRET}`],
  }),
  ed('Enterprise', {
    name: 'Enterprise — OIDC + secrets + geo + policy jar',
    env: OIDC_ENV,
    // OBJECTS-BACKEND-DEFAULT-MEMORY-1 (2026-09-25): PostgreSQL is MANDATORY for the operational objects.
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet', '-Dobjects.backend=postgres'],
  }),
  // Operator flags are appended LAST on purpose, so they cannot clobber the required ones.
  ed('Professional', {
    name: 'Professional — operator INSPECTO_JAVA_OPTS',
    env: { INSPECTO_JAVA_OPTS: '-Xmx4g -Dui.static.log=DEBUG' },
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Xmx4g', '-Dui.static.log=DEBUG'],
    lastAfter: { after: '-Dauth.mode=oidc', these: ['-Xmx4g', '-Dui.static.log=DEBUG'] },
  }),
  // Preview carries Enterprise's exact jars but is NOT Enterprise: it keeps the operational objects on DuckDB (operator
  // decision 2026-09-25). Before P3d the package step patched the launcher TEXT to say so; now the marker says it.
  ed('Preview', {
    name: 'Preview — Enterprise\'s jars, but edition Preview (no PostgreSQL objects backend)',
    env: OIDC_ENV,
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Devents.backend=parquet'],
    rejectPrefix: ['-Dobjects.backend'],
  }),
  // THE DROP-IN. A jar that is merely PRESENT must neither join the classpath nor change the edition: the list and the marker decide.
  ed('Personal', {
    name: 'Personal — stray inspecto-oidc.jar + inspecto-policy.jar beside it are NOT on the classpath and do not make it Enterprise',
    jars: [...CP.Personal, 'inspecto-oidc.jar', 'inspecto-policy.jar'],
    env: OIDC_ENV,
    expect: ALWAYS,
    rejectPrefix: ['-Dauth.mode', '-Devents.backend', '-Dauth.oidc.', '-Dobjects.backend'],
  }),
  // FALLBACKS (documented in package.ps1): hand-assembled directories keep booting.
  ed('Professional', {
    name: 'Professional — NO modules.list: falls back to inspecto.jar first, then every *.jar',
    noList: true, jarsOnCpSet: true,
    env: {},
    expect: [...ALWAYS, '-Dauth.mode=oidc'],
  }),
  ed('Enterprise', {
    name: 'Enterprise — NO edition.properties: falls back to the old heuristic (oidc + policy jars present)',
    noMarker: true, edition: 'Enterprise',
    env: OIDC_ENV,
    expect: [...ALWAYS, '-Dauth.mode=oidc', '-Dobjects.backend=postgres'],
  }),
  ed('Professional', {
    name: 'an UNKNOWN edition in edition.properties is refused (exit non-zero, nothing launched)',
    markerText: 'edition=Gold\n',
    env: {},
    refuses: /unknown edition/i,
  }),
];

function check(kind, scenario, got, status) {
  const where = `${kind === 'sh' ? 'serve.sh' : 'serve.bat'} · ${scenario.name}`;
  const problems = [];
  if (scenario.refuses) {
    if (status === 0) problems.push('the launcher started although edition.properties names an unknown edition');
    if (!scenario.refuses.test(got.raw)) problems.push(`the refusal did not say why (expected ${scenario.refuses}); output was: ${got.raw.trim().slice(0, 200)}`);
    if (got.argv.length && got.jars) problems.push('java was launched despite the refusal');
  } else {
    if (!got.jars) problems.push('the launcher passed no `-cp`');
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
    if (got.jars) {
      if (scenario.jarsOnCpSet) {
        // fallback: inspecto.jar first, then exactly the jars present (any order)
        if (got.jars[0] !== 'inspecto.jar') problems.push(`fallback classpath must start with inspecto.jar, got [${got.jars.join(' ')}]`);
        if ([...got.jars].sort().join(' ') !== [...scenario.jars].sort().join(' ')) problems.push(`fallback classpath is [${[...got.jars].sort().join(' ')}], expected every staged jar [${[...scenario.jars].sort().join(' ')}]`);
      } else if (got.jars.join(' ') !== scenario.jarsOnCp.join(' ')) {
        // ORDER-SENSITIVE: the launcher's classpath must BE modules.list, which is the generator's list for the edition.
        problems.push(`classpath is\n      [${got.jars.join(' ')}]\n    expected exactly the Offering's modules.list\n      [${scenario.jarsOnCp.join(' ')}]`);
      }
    }
    if (scenario.lastAfter) {
      const anchor = got.argv.indexOf(scenario.lastAfter.after);
      for (const t of scenario.lastAfter.these) {
        if (got.argv.indexOf(t) < anchor) problems.push(`${t} must be appended AFTER ${scenario.lastAfter.after}`);
      }
    }
  }
  return problems.length
    ? `${where}\n${problems.map((p) => `    ${p}`).join('\n')}\n\n  argv the launcher actually passed:\n` +
      got.argv.map((a) => `    ${a}`).join('\n') +
      `\n\n  Fix inspecto/package.ps1's launcher here-string, not this guard. A launcher that drops a\n` +
      `  flag boots a service that LOOKS like its edition and is not one (SERVEBAT-OPTS-1).`
    : null;
}

// ── static rules (run on EVERY platform; each is a list of findings, empty = green) ────────────
// A self-referential `set "VAR=%VAR% …"` inside a parenthesised block is the SERVEBAT-OPTS-1 bug
// itself, whatever the variable: cmd.exe expands the whole block once at parse time, so every such
// statement but the last is silently discarded. This is the rule the non-Windows run stands on.
// ⛔ The fix is one statement per line, NEVER `setlocal EnableDelayedExpansion` — these values carry
// operator secrets and keystore passwords, and delayed expansion eats `!` inside them.
function batBlockFindings(bat, label) {
  const out = [];
  let depth = 0;
  bat.split('\n').forEach((line, i) => {
    const code = /^\s*rem\b/i.test(line) ? '' : line.replace(/"[^"]*"/g, '""');
    const selfRef = line.match(/set\s+"(\w+)=%\1%/i);
    if (depth > 0 && selfRef) out.push(`${label} line ${i + 1}  (%${selfRef[1]}%)  re-expands a variable inside a parenthesised block — the SERVEBAT-OPTS-1 bug: ${line.trim()}`);
    for (const ch of code) { if (ch === '(') depth++; else if (ch === ')') depth = Math.max(0, depth - 1); }
  });
  if (/^\s*setlocal\s+enabledelayedexpansion/im.test(bat)) out.push(`${label} enables delayed expansion — it eats \`!\` inside operator secrets and keystore passwords. Use \`call set\` (see serve.bat).`);
  return out;
}

// The launchers are generated by the here-strings below; `serve-demo.*` are `@"` expandable here-strings (the demo variant) and are
// read by their delimiters too.
const LAUNCHERS = ['runShContent', 'runBatContent', 'serveShContent', 'serveBatContent'];

/** Static findings over a package.ps1 text: no hand-kept jar list, every launcher READS modules.list, serve.* read the edition marker. */
export function staticFindings(ps1src) {
  const ps1 = toLf(ps1src);
  const out = [];
  const texts = Object.fromEntries(LAUNCHERS.map((n) => [n, hereString(ps1, n)]));
  out.push(...batBlockFindings(texts.serveBatContent, 'serve.bat'), ...batBlockFindings(texts.runBatContent, 'run.bat'));
  for (const [name, body] of Object.entries(texts)) {
    const label = name.replace('Content', '');
    if (!body.includes('modules.list')) out.push(`${label} never reads modules.list — its classpath is hand-kept again.`);
    const handKept = [
      ...body.matchAll(/^.*\[ -f \S+\.jar \]\s*&&\s*CP="\$\{CP\}:\S+\.jar".*$/gm),
      ...body.matchAll(/^.*if exist \S+\.jar set "CP=%CP%;\S+\.jar".*$/gm),
      ...body.matchAll(/^.*CP="inspecto\.jar:inspecto-\S+\.jar".*$/gm),
    ];
    if (handKept.length) out.push(`${label} appends a NAMED jar to CP (${handKept[0][0].trim().slice(0, 90)}) — read modules.list instead.`);
  }
  for (const n of ['serveShContent', 'serveBatContent']) {
    if (!texts[n].includes('edition.properties')) out.push(`${n.replace('Content', '')} never reads edition.properties — the edition is guessed from which jars are present.`);
  }
  // the demo launchers (only emitted with -DemoAuth) are expandable here-strings
  for (const n of ['serveDemoBat', 'serveDemoSh']) {
    const open = ps1.indexOf(`$${n} = @"\n`);
    if (open < 0) { out.push(`$${n} (the demo launcher here-string) was not found in package.ps1 — renamed? fix this guard.`); continue; }
    const body = ps1.slice(open, ps1.indexOf('\n"@\n', open));
    if (!body.includes('modules.list')) out.push(`${n} never reads modules.list — the demo launcher keeps its own jar list.`);
  }
  return out;
}

/** EXECUTE serve.sh (and serve.bat on Windows) over every scenario. Returns findings; throws Abort if it cannot run. */
export function runScenarios(ps1src, scenarios = SCENARIOS) {
  const ps1 = toLf(ps1src);
  const serveSh = hereString(ps1, 'serveShContent');
  const serveBat = hereString(ps1, 'serveBatContent');
  const work = join(tmpdir(), `inspecto-launcher-guard-${process.pid}`);
  const kinds = IS_WINDOWS ? ['sh', 'bat'] : ['sh'];
  const findings = [];
  try {
    for (const kind of kinds) {
      scenarios.forEach((scenario, i) => {
        const dir = makeBundle(join(work, `${kind}-${i}`), scenario);
        installStubJava(dir);
        if (kind === 'sh') writeFileSync(join(dir, 'serve.sh'), toLf(serveSh));
        else writeFileSync(join(dir, 'serve.bat'), asBatBytes(serveBat));
        const r = runLauncher(kind, dir, scenario.env, !!scenario.refuses);
        const problem = check(kind, scenario, scenario.refuses && r.status !== 0 && !r.out.includes('ARGV') ? { argv: [], jars: null, edition: '', raw: r.out } : observe(r.out), r.status);
        if (problem) findings.push(problem);
      });
    }
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
  return { findings, kinds, ran: kinds.length * scenarios.length };
}

/**
 * EXECUTE run.sh (and run.bat on Windows): the one-shot ETL launchers have no edition branch, but they carry the same classpath
 * contract - they must put exactly modules.list on `-cp`, in order, and launch CollectorProcessor. (Before P3d run.* kept an order
 * of their own and no inspecto-policy; this is what proves they now read the list.) A throwaway Space holds a dummy pipeline file
 * so the launcher gets as far as the stub java.
 */
export function runOneShot(ps1src, editions = ['Personal', 'Professional', 'Enterprise']) {
  const ps1 = toLf(ps1src);
  const runSh = hereString(ps1, 'runShContent');
  const runBat = hereString(ps1, 'runBatContent');
  const work = join(tmpdir(), `inspecto-launcher-guard-run-${process.pid}`);
  const kinds = IS_WINDOWS ? ['sh', 'bat'] : ['sh'];
  const findings = [];
  try {
    for (const kind of kinds) {
      for (const edition of editions) {
        const sc = ed(edition, { name: `${edition} one-shot`, env: {} });
        const dir = makeBundle(join(work, `${kind}-${edition}`), sc);
        installStubJava(dir);
        mkdirSync(join(dir, 'spaces', 's', 'config', 'a'), { recursive: true });
        writeFileSync(join(dir, 'spaces', 's', 'config', 'a', 'a_pipeline.toon'), 'id: a\n');
        if (kind === 'sh') writeFileSync(join(dir, 'run.sh'), toLf(runSh));
        else writeFileSync(join(dir, 'run.bat'), asBatBytes(runBat));
        const [cmd, args] = kind === 'sh' ? ['bash', ['run.sh', 'a']] : ['cmd', ['/c', '.\\run.bat', 'a']];
        const pathSep = IS_WINDOWS ? ';' : ':';
        let out;
        try {
          out = execFileSync(cmd, args, { cwd: dir, env: { ...process.env, PATH: join(dir, 'bin') + pathSep + process.env.PATH }, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
        } catch (e) {
          if (e.code === 'ENOENT') abort(`\`${cmd}\` is not on PATH. run.sh must be EXECUTED, not skipped.`);
          abort(`run.${kind} exited ${e.status}:\n${e.stdout || ''}${e.stderr || ''}`);
        }
        const got = observe(out);
        const where = `run.${kind} · ${edition}`;
        if (!got.jars) findings.push(`${where}: the launcher passed no \`-cp\``);
        else if (got.jars.join(' ') !== CP[edition].join(' ')) findings.push(`${where}: classpath is\n      [${got.jars.join(' ')}]\n    expected exactly the Offering's modules.list\n      [${CP[edition].join(' ')}]`);
        if (!got.argv.includes('com.gamma.inspector.CollectorProcessor')) findings.push(`${where}: CollectorProcessor was not the main class`);
      }
    }
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
  return { findings, kinds, ran: kinds.length * editions.length };
}

function main() {
  const fail = (msg) => { console.error(`✗ Launcher guard: ${msg}`); process.exit(1); };
  let ps1src;
  try {
    // package.ps1 itself is CRLF in a working tree that checks out with eol=crlf; toLf() normalises before slicing.
    ps1src = readFileSync(PACKAGE_PS1, 'utf8');
    const staticProblems = staticFindings(ps1src);
    if (staticProblems.length) fail(`static launcher rules:\n  - ${staticProblems.join('\n  - ')}`);
    const { findings, kinds, ran: ranServe } = runScenarios(ps1src);
    const oneShot = runOneShot(ps1src);
    findings.push(...oneShot.findings);
    const ran = ranServe + oneShot.ran;
    if (findings.length) fail(findings[0] + (findings.length > 1 ? `\n\n  (${findings.length - 1} more scenario(s) also failed)` : ''));
    console.log(
      `✓ Launcher guard: ${ran} launcher run(s) (${oneShot.ran} of them run.sh/run.bat one-shot launches, classpath == modules.list in order) — ${SCENARIOS.length} serve scenario(s) × ` +
      `${kinds.map((k) => (k === 'sh' ? 'serve.sh' : 'serve.bat')).join(' + ')}, each EXECUTED over stub jars + the generator's modules.list/edition.properties ` +
      `with a stub java first on PATH; every classpath equalled its edition's modules.list IN ORDER and every flag set matched its edition.\n` +
      `  static rules: run.sh/run.bat/serve.*/serve-demo.* all read modules.list, none names a jar, serve.* read edition.properties, no self-referential \`set\` in a block.\n` +
      (IS_WINDOWS
        ? `  scope: serve.sh + serve.bat both executed (Windows runner).`
        : `  scope: serve.sh executed; serve.bat NOT executed on ${process.platform} — it is covered here by ` +
          `the static rules only, and is proved by execution on a Windows runner.`) +
      ` ura.sh/ura.bat (no sidecars at all) and serve-demo.* (static rules only) are not executed.`
    );
  } catch (e) {
    if (e instanceof Abort) { console.error(`✗ Launcher guard could not run: ${e.message}`); process.exit(2); }
    throw e;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
