// Run: node --test tools/maven-repo.test.mjs
// PACKAGE-DEMOAUTH-ADVANCED-1: the local Maven repository is resolved the way Maven does, never assumed to be ~/.m2/repository.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { m2Repo, pgDriverPath } from './maven-repo.mjs';

const home = (settings) => {
    const h = mkdtempSync(join(tmpdir(), 'm2home-'));
    if (settings !== undefined) { mkdirSync(join(h, '.m2')); writeFileSync(join(h, '.m2', 'settings.xml'), settings); }
    return h;
};

test('M2_REPO wins over settings.xml', () => {
    const h = home('<settings><localRepository>C:/.m2/repository</localRepository></settings>');
    assert.equal(m2Repo({ env: { M2_REPO: 'D:/repo' }, home: h }), 'D:/repo');
});

test('<localRepository> in settings.xml moves the repository (the C:/.m2 box)', () => {
    const h = home('<settings>\n  <localRepository> C:/.m2/repository </localRepository>\n</settings>');
    assert.equal(m2Repo({ env: {}, home: h }), 'C:/.m2/repository');
});

test('an unexpanded ${...} value is skipped; no settings.xml falls back to ~/.m2/repository', () => {
    const h = home('<settings><localRepository>${env.X}/r</localRepository></settings>');
    assert.equal(m2Repo({ env: {}, home: h }), join(h, '.m2', 'repository'));
    assert.equal(m2Repo({ env: {}, home: home() }).endsWith(join('.m2', 'repository')), true);
});

test('the driver path is the standard Maven layout', () => {
    assert.equal(pgDriverPath('R', '42.7.12'), join('R', 'org', 'postgresql', 'postgresql', '42.7.12', 'postgresql-42.7.12.jar'));
});

test('package.ps1 no longer builds the driver path from %USERPROFILE%', () => {
    const ps1 = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'inspecto', 'package.ps1'), 'utf8');
    assert.doesNotMatch(ps1, /USERPROFILE\s+["']\.m2/);
    assert.match(ps1, /maven-repo\.mjs/);
});

// ── package.ps1 wiring (PACKAGE-DEMOAUTH-ADVANCED-1): each of these fails on the pre-fix script ─────────────────────────────────
const PS1 = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'inspecto', 'package.ps1'), 'utf8').replace(/\r\n/g, '\n');

test('package.ps1 throws about the PostgreSQL driver only when the bundle needs it (needsPostgresDriver), never for Basic/Standard/demo', () => {
    assert.match(PS1, /--needs-pg/);
    assert.match(PS1, /elseif \(\$pgNeeded\) \{\s*throw "[^"]*inspecto-la-store-pg/);
    // the unconditional throw of the old step 3a is gone
    assert.doesNotMatch(PS1, /if \(-not \(Test-Path \$pgJar\)\) \{\s*throw/);
});

test('package.ps1 hands --demo to the jlink module tool under -DemoAuth, so the set follows the swapped bundle', () => {
    assert.match(PS1, /\$jlinkArgs = @\([^\n]*'--staged-dir'[^\n]*\); if \(\$DemoAuth\) \{ \$jlinkArgs \+= '--demo' \}/);
    assert.match(PS1, /jlink-modules\.mjs'\) @jlinkArgs/);
});

test('package.ps1 refuses -DemoAuth -NoBuild up front when the demo-auth jar was never built', () => {
    assert.match(PS1, /if \(\$NoBuild -and -not \(Get-ChildItem[^\n]*inspecto-demo-auth\\target/);
    assert.match(PS1, /holds no inspecto-demo-auth jar\. Build it first \(mvn package -pl :inspecto-demo-auth/);
});
