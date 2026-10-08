// Fixtures for tools/check-jar-signatures.mjs (P3h per-jar signing). Run: node --test tools/check-jar-signatures.test.mjs
// Needs the JDK's keytool/jar/jarsigner ($JAVA_HOME/bin or PATH); skipped when they are absent. Keystores are throwaway, in tmp.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { checkBundle, jdkTool } from './check-jar-signatures.mjs';

let haveJdk = true;
try { execFileSync(jdkTool('keytool'), ['-help'], { stdio: 'ignore' }); execFileSync(jdkTool('jarsigner'), ['-help'], { stdio: 'ignore' }); } catch { haveJdk = false; }
const opts = { skip: !haveJdk && 'JDK keytool/jarsigner not available' };

const PASS = randomBytes(12).toString('hex');

function keystore(dir, alias) {
    const ks = join(dir, alias + '.p12');
    execFileSync(jdkTool('keytool'), ['-genkeypair', '-alias', alias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=' + alias,
        '-storetype', 'PKCS12', '-keystore', ks, '-storepass', PASS], { stdio: 'ignore' });
    return ks;
}

function makeJar(dir, name) {
    const src = join(dir, 'src-' + name); mkdirSync(src, { recursive: true });
    writeFileSync(join(src, 'a.txt'), 'payload of ' + name + ' '.repeat(2000));
    const jar = join(dir, name);
    execFileSync(jdkTool('jar'), ['--create', '--file', jar, '-C', src, 'a.txt']);
    return jar;
}

function sign(jar, ks, alias) {
    execFileSync(jdkTool('jarsigner'), ['-keystore', ks, '-storepass:env', 'JS_TEST_PASS', jar, alias], { env: { ...process.env, JS_TEST_PASS: PASS }, stdio: 'ignore' });
}

function bundle() {
    const dir = mkdtempSync(join(tmpdir(), 'jarsig-'));
    const ks = keystore(dir, 'one');
    const jars = ['inspecto.jar', 'inspecto-util.jar', 'inspecto-ops.jar'].map((n) => makeJar(dir, n));
    makeJar(dir, 'postgresql.jar'); // third-party, vendor-owned: never checked
    for (const j of jars) sign(j, ks, 'one');
    return { dir, jars };
}

test('every first-party jar signed by one certificate passes; postgresql.jar is not checked', opts, async () => {
    const { dir } = bundle();
    try {
        const r = await checkBundle(dir);
        assert.deepEqual(r.problems, []);
        assert.equal(r.count, 3);
        assert.match(r.fingerprint, /^[0-9a-f]{64}$/);
        assert.deepEqual((await checkBundle(dir, r.fingerprint.toUpperCase())).problems, []);
        assert.match((await checkBundle(dir, 'ab'.repeat(32))).problems[0], /not the expected/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('an unsigned first-party jar fails', opts, async () => {
    const { dir } = bundle();
    try {
        makeJar(dir, 'inspecto-extra.jar');
        const r = await checkBundle(dir);
        assert.equal(r.problems.length, 1);
        assert.match(r.problems[0], /inspecto-extra\.jar: unsigned/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a tampered signed jar fails verification', opts, async () => {
    const { dir, jars } = bundle();
    try {
        // Rebuild one signed jar with a changed payload but the OLD signature files: the digest no longer matches.
        const tmp = join(dir, 'x'); mkdirSync(tmp);
        execFileSync(jdkTool('jar'), ['--extract', '--file', jars[1]], { cwd: tmp });
        writeFileSync(join(tmp, 'a.txt'), 'tampered ' + ' '.repeat(2000));
        execFileSync(jdkTool('jar'), ['--create', '--file', jars[1], '--manifest', join(tmp, 'META-INF', 'MANIFEST.MF'), '-C', tmp, 'a.txt', '-C', tmp, 'META-INF']);
        const r = await checkBundle(dir);
        assert.equal(r.problems.length, 1, r.problems.join('\n'));
        assert.match(r.problems[0], /inspecto-util\.jar: does not verify/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a jar signed by a different certificate fails', opts, async () => {
    const { dir } = bundle();
    try {
        const other = makeJar(dir, 'inspecto-other.jar');
        sign(other, keystore(dir, 'two'), 'two');
        const r = await checkBundle(dir);
        assert.equal(r.problems.length, 1);
        assert.match(r.problems[0], /DIFFERENT certificates/);
    } finally { rmSync(dir, { recursive: true, force: true }); }
});
