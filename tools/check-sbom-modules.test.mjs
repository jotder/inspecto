// Negative fixtures for tools/check-sbom-modules.mjs. Run: node --test tools/check-sbom-modules.test.mjs
// Each test mutates the REAL inspecto/package.ps1 text one way and asserts the guard names it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { analyze } from './check-sbom-modules.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const REAL = readFileSync(join(ROOT, 'inspecto', 'package.ps1'), 'utf8');
const problems = (text, opts) => analyze(text, opts).problems.join('\n');
const mutate = (from, to) => { assert.ok(REAL.includes(from), `fixture anchor missing from package.ps1: ${from.slice(0, 60)}`); return REAL.replace(from, to); };

test('the real package.ps1 is GREEN', () => assert.equal(problems(REAL), ''));

test('RED: a hand-written $modules literal is back', () => {
    const t = mutate('    $modules = ((& node', "    $modules = if ($Edition -eq 'Enterprise') { ':a' } else { ':b' }\n    $unused = ((& node");
    assert.match(problems(t), /still assigns `\$modules` from a hand-written/);
});

test('RED: $modules no longer comes from offering-classpath --list-mvn', () => {
    const t = mutate('--list-mvn) | Out-String', '--other) | Out-String');
    assert.match(problems(t), /does not take `\$modules` from/);
});

test('RED: a staged jar disappears from the staging steps (bundle-modules still declares it)', () => {
    const t = mutate('Copy-Item $backupJarSrc "$bundleDir\\inspecto-backup.jar"', 'Write-Host skipped');
    assert.match(problems(t), /tools\/bundle-modules\.mjs declares, but .* never stages: inspecto-backup\.jar/);
});

test('RED: package.ps1 stages a jar nothing declares', () => {
    const t = mutate('Copy-Item $backupJarSrc "$bundleDir\\inspecto-backup.jar"', 'Copy-Item $backupJarSrc "$bundleDir\\inspecto-backup.jar"\n    Copy-Item $backupJarSrc "$bundleDir\\inspecto-rogue.jar"');
    assert.match(problems(t), /stages, but no SBOM would declare: inspecto-rogue\.jar/);
});

test('RED: the generated classpath misses a staged jar / lists one nothing stages', () => {
    // a generated list that forgot backup (and everything else) and invented a ghost
    const bad = { mvn: {}, classpathMax: ['inspecto.jar', 'inspecto-ghost.jar'] };
    const p = problems(REAL, { generated: bad });
    assert.match(p, /staged but absent from modules\.list: .*inspecto-backup\.jar/);
    assert.match(p, /on modules\.list but never staged: inspecto-ghost\.jar/);
});

test('RED: the boot smoke goes back to a hand-kept $cp literal', () => {
    const t = mutate("$cp = @(Get-Content (Join-Path $bundleDir 'modules.list')", "$cp = @('inspecto.jar', 'inspecto-oidc.jar') + @(Get-Content (Join-Path $bundleDir 'modules.list')");
    assert.match(problems(t), /boot-smoke `\$cp` is not read from the bundle's modules\.list/);
});

test('RED: a launcher re-grows a hand-kept jar list (sh and bat shapes)', () => {
    assert.match(problems(mutate('JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"\n# Extra', 'JAVA="java"; [ -x "runtime/bin/java" ] && JAVA="runtime/bin/java"\n[ -f inspecto-foo.jar ] && CP="${CP}:inspecto-foo.jar"\n# Extra')), /still hand-keeps a classpath/);
    assert.match(problems(mutate('set "CP=%CP:~1%"\nrem ASSURE', 'set "CP=%CP:~1%"\nif exist inspecto-foo.jar set "CP=%CP%;inspecto-foo.jar"\nrem ASSURE')), /still hand-keeps a classpath/);
    assert.match(problems(mutate('if [ -f edition.properties ]; then', 'if [ -f inspecto-oidc.jar ]; then CP="inspecto.jar:inspecto-oidc.jar"; fi\nif [ -f edition.properties ]; then')), /still hand-keeps a classpath/);
});

test('RED: a launcher stops reading modules.list, and serve.* stops reading edition.properties', () => {
    assert.match(problems(REAL.replaceAll('modules.list', 'xxx.list')), /never reads modules\.list/);
    assert.match(problems(REAL.replaceAll('edition.properties', 'xxx.properties')), /never read edition\.properties/);
});

test('RED: the generator is never run against the bundle', () => {
    const t = mutate("& node (Join-Path $sandboxRoot 'tools\\offering-classpath.mjs') @classpathArgs", 'Write-Host nothing');
    assert.match(problems(t), /never runs tools\/offering-classpath\.mjs against the bundle/);
});

test('RED: bundle-modules.mjs prose counts drift from its table', () => {
    const bm = readFileSync(join(ROOT, 'tools', 'bundle-modules.mjs'), 'utf8').replace(/Personal 2, Professional 21/, 'Personal 2, Professional 99');
    assert.match(problems(REAL, { bundleModulesText: bm }), /says Professional ships 99 first-party module/);
});

test('RED: the --list-mvn output disagrees with the generator table', () => {
    const gen = { mvn: { Professional: ':inspecto-oidc', Enterprise: '', Preview: '' }, classpathMax: [] };
    assert.match(problems(REAL, { generated: gen }), /Professional: `offering-classpath\.mjs --list-mvn` does not match/);
});
