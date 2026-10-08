#!/usr/bin/env node
// MODULE-REORG-1 P3h: a per-jar signing check for a STAGED bundle (package.ps1 -SignJars runs it after signing).
//
//   node tools/check-jar-signatures.mjs <bundleDir> [--expect-fingerprint <SHA-256 hex, colons optional>]
//
// Every FIRST-PARTY jar in the bundle root (inspecto*.jar - the processor, the thin core jars, the optional modules,
// the sidecars, the demo-auth jar) must (1) verify under `jarsigner -verify` ("jar verified."), and (2) carry a
// signer certificate whose SHA-256 fingerprint is the SAME for every jar (read with `keytool -printcert -jarfile`).
// An unsigned jar, a tampered jar (digest mismatch), or a jar signed by a different certificate FAILS (exit 1).
// Third-party standalone jars (postgresql.jar) are vendor-owned and not checked. The JDK tools come from
// $JAVA_HOME/bin, else PATH. Pure check: reads the bundle, writes nothing, never touches a keystore or password.
import { readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const EXE = process.platform === 'win32' ? '.exe' : '';

export function jdkTool(name) {
    const home = process.env.JAVA_HOME;
    if (home && existsSync(join(home, 'bin', name + EXE))) return join(home, 'bin', name + EXE);
    return name;
}

function run(cmd, args) {
    return new Promise((resolve) => execFile(cmd, args, { maxBuffer: 64 * 1024 * 1024, env: process.env }, (err, stdout, stderr) =>
        resolve({ code: err ? (typeof err.code === 'number' ? err.code : 127) : 0, out: String(stdout) + String(stderr) })));
}

/** The first-party jar file names of a bundle (root only). */
export function firstPartyJars(bundleDir) {
    return readdirSync(bundleDir).filter((f) => /^inspecto.*\.jar$/.test(f)).sort();
}

/** Verifies ONE jar; resolves {jar, ok, problem?, fingerprint?}. */
export async function checkJar(bundleDir, jar) {
    const path = join(bundleDir, jar);
    const v = await run(jdkTool('jarsigner'), ['-verify', path]);
    if (/jar is unsigned/i.test(v.out)) return { jar, ok: false, problem: 'unsigned' };
    if (v.code !== 0 || !/jar verified/i.test(v.out)) return { jar, ok: false, problem: 'does not verify: ' + v.out.trim().split('\n').slice(0, 3).join(' | ') };
    const k = await run(jdkTool('keytool'), ['-printcert', '-jarfile', path]);
    const m = /Certificate fingerprints:[\s\S]*?SHA256:\s*([0-9A-Fa-f:]+)/.exec(k.out);
    if (k.code !== 0 || !m) return { jar, ok: false, problem: 'signer certificate unreadable: ' + k.out.trim().split('\n').slice(0, 2).join(' | ') };
    return { jar, ok: true, fingerprint: m[1].replace(/:/g, '').toLowerCase() };
}

/** Checks every first-party jar (4 at a time); returns {problems:[string], fingerprint, count}. */
export async function checkBundle(bundleDir, expectFingerprint = null) {
    const jars = firstPartyJars(bundleDir);
    const results = [];
    let next = 0;
    await Promise.all(Array.from({ length: 4 }, async () => {
        while (next < jars.length) { const j = jars[next++]; results.push(await checkJar(bundleDir, j)); }
    }));
    results.sort((a, b) => a.jar.localeCompare(b.jar));
    const problems = results.filter((r) => !r.ok).map((r) => `${r.jar}: ${r.problem}`);
    const prints = [...new Set(results.filter((r) => r.ok).map((r) => r.fingerprint))];
    if (jars.length === 0) problems.push('no inspecto*.jar in ' + bundleDir);
    if (prints.length > 1) problems.push('jars are signed by DIFFERENT certificates: ' + results.filter((r) => r.ok).map((r) => `${r.jar}=${r.fingerprint.slice(0, 12)}`).join(', '));
    const want = expectFingerprint ? expectFingerprint.replace(/:/g, '').toLowerCase() : null;
    if (want && prints.length === 1 && prints[0] !== want) problems.push(`signer fingerprint ${prints[0]} is not the expected ${want}`);
    return { problems, fingerprint: prints.length === 1 ? prints[0] : null, count: jars.length };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const args = process.argv.slice(2);
    const ei = args.indexOf('--expect-fingerprint');
    const bundle = args.find((a, i) => !a.startsWith('--') && (ei < 0 || i !== ei + 1));
    if (!bundle || !existsSync(bundle)) { console.error('usage: check-jar-signatures.mjs <bundleDir> [--expect-fingerprint <sha256>]'); process.exit(2); }
    const r = await checkBundle(bundle, ei >= 0 ? args[ei + 1] : null);
    if (r.problems.length) { for (const p of r.problems) console.error('check-jar-signatures FAIL: ' + p); process.exit(1); }
    console.log(`check-jar-signatures OK: ${r.count} first-party jars verify, one signer (SHA-256 ${r.fingerprint})`);
}
