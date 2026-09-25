// Fixture test for tools/check-demo-auth-isolation.mjs — each planted violation must turn the guard RED.
// Run: node --test tools/check-demo-auth-isolation.test.mjs
//
// Each case copies the real inputs (bundle-modules.mjs, package.ps1, the root pom, one shipped module's
// pom, the demo module's pom, ci.yml) into a throwaway git repo, plants ONE violation, and runs the guard
// with --root. The untouched copy must be GREEN first, or a red case would prove nothing.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const GUARD = join(repo, 'tools/check-demo-auth-isolation.mjs');
const FILES = [
    'tools/bundle-modules.mjs',
    'inspecto/package.ps1',
    'pom.xml',
    'inspecto-connectors/pom.xml',
    'inspecto-demo-auth/pom.xml',
    '.github/workflows/ci.yml',
];

/** A fixture repo, optionally mutated by `plant(root)`; returns the guard's { status, output }. */
function runGuard(plant) {
    const root = mkdtempSync(join(tmpdir(), 'demo-auth-guard-'));
    try {
        for (const f of FILES) {
            mkdirSync(dirname(join(root, f)), { recursive: true });
            cpSync(join(repo, f), join(root, f));
        }
        plant?.(root);
        execFileSync('git', ['init', '-q'], { cwd: root });
        execFileSync('git', ['-c', 'core.autocrlf=false', 'add', '-A'], { cwd: root });
        const r = spawnSync(process.execPath, [GUARD, '--root', root], { encoding: 'utf8' });
        return { status: r.status, output: r.stdout + r.stderr };
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

/** Replace `from` (must occur) with `to` in the fixture file `f`. */
const edit = (f, from, to) => (root) => {
    const p = join(root, f);
    const s = readFileSync(p, 'utf8');
    assert.ok(s.includes(from), `fixture anchor missing in ${f}: ${from}`);
    writeFileSync(p, s.replace(from, to));
};

test('the unmodified inputs are GREEN', () => {
    const r = runGuard();
    assert.equal(r.status, 0, r.output);
});

const RED = {
    'an edition module table entry (Preview takes every module)': [
        edit(
            'tools/bundle-modules.mjs',
            "    // Enterprise only.",
            "    { artifactId: 'inspecto-demo-auth', dir: 'inspecto-demo-auth', bundleFile: 'inspecto-demo-auth.jar', from: 'enterprise' },\n    // Enterprise only.",
        ),
        /bundle-modules\.mjs ships inspecto-demo-auth\.jar/,
    ],
    'the boot-smoke classpath literal': [
        edit('inspecto/package.ps1', "@('inspecto-security.jar',", "@('inspecto-security.jar','inspecto-demo-auth.jar',"),
        /outside an `if \(\$DemoAuth\)` block/,
    ],
    'an edition $modules build list': [
        edit('inspecto/package.ps1', "{ 'inspecto-security,inspecto-policy,", "{ 'inspecto-security,inspecto-demo-auth,inspecto-policy,"),
        /outside an `if \(\$DemoAuth\)` block/,
    ],
    'a staging step under a NEGATED gate': [
        edit('inspecto/package.ps1', 'Copy-Item $jarSrc "$bundleDir\\inspecto.jar"',
            'Copy-Item $jarSrc "$bundleDir\\inspecto.jar"\nif (-not $DemoAuth) { Copy-Item $x "$bundleDir\\inspecto-demo-auth.jar" }'),
        /outside an `if \(\$DemoAuth\)` block/,
    ],
    'the demo branch no longer removing inspecto-security.jar': [
        edit('inspecto/package.ps1', "Remove-Item (Join-Path $bundleDir 'inspecto-security.jar')", "Write-Host 'kept'"),
        /removes inspecto-security\.jar/,
    ],
    'inspecto-security.jar on the demo launcher classpath': [
        edit('inspecto/package.ps1', "$demoJars = @('inspecto.jar',", "$demoJars = @('inspecto.jar', 'inspecto-security.jar',"),
        /demo launcher classpath/,
    ],
    'a shipped module depending on it': [
        edit('inspecto-connectors/pom.xml', '<dependencies>',
            '<dependencies>\n<dependency><groupId>com.gamma.inspector</groupId><artifactId>inspecto-demo-auth</artifactId></dependency>'),
        /inspecto-connectors\/pom\.xml declares a dependency/,
    ],
    'an edition profile listing it as a module': [
        edit('pom.xml', '<id>edition-enterprise</id>', '<id>edition-enterprise</id>\n<modules><module>inspecto-demo-auth</module></modules>'),
        /profile 'edition-enterprise' lists inspecto-demo-auth/,
    ],
    'a workflow packaging with -DemoAuth': [
        (root) => writeFileSync(join(root, '.github/workflows/release.yml'),
            'jobs:\n  b:\n    steps:\n      - run: pwsh -File inspecto/package.ps1 -Edition Enterprise -DemoAuth\n'),
        /release\.yml:4 stages or builds the demo sign-in/,
    ],
};

for (const [name, [plant, expected]] of Object.entries(RED)) {
    test(`RED: ${name}`, () => {
        const r = runGuard(plant);
        assert.equal(r.status, 1, r.output);
        assert.match(r.output, expected);
    });
}
