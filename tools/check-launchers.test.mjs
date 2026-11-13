// Negative fixtures for tools/check-launchers.mjs. Run: node --test tools/check-launchers.test.mjs
// Each test mutates the REAL inspecto/package.ps1 text one way and asserts the guard goes red and names it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SCENARIOS, runDemoScenarios, runOneShot, runScenarios, staticFindings } from './check-launchers.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const REAL = readFileSync(join(ROOT, 'inspecto', 'package.ps1'), 'utf8').replace(/\r\n/g, '\n');
const mutate = (from, to) => { assert.ok(REAL.includes(from), `fixture anchor missing from package.ps1: ${from.slice(0, 70)}`); return REAL.replace(from, to); };
const statics = (t) => staticFindings(t).join('\n');
const scenarios = (t) => runScenarios(t).findings.join('\n');

test('the real package.ps1: static rules GREEN', () => assert.deepEqual(staticFindings(REAL), []));

test('the real launchers, EXECUTED over every scenario, GREEN', () => {
    const r = runScenarios(REAL);
    assert.deepEqual(r.findings, []);
    assert.equal(r.ran, r.kinds.length * SCENARIOS.length);
});

test('RED (static): a launcher stops reading modules.list', () => {
    assert.match(statics(REAL.replaceAll('modules.list', 'xxx.list')), /runSh never reads modules\.list[\s\S]*serveBat never reads modules\.list/);
});

test('RED (static): a launcher re-grows a hand-kept jar list', () => {
    const sh = mutate('exec "$JAVA" "${JAVA_OPTS[@]}" -cp "$CP" com.gamma.control.ControlApi', '[ -f inspecto-foo.jar ] && CP="${CP}:inspecto-foo.jar"\nexec "$JAVA" "${JAVA_OPTS[@]}" -cp "$CP" com.gamma.control.ControlApi');
    assert.match(statics(sh), /serveSh appends a NAMED jar to CP/);
    const bat = mutate('"%JAVA%" %OPTS% -cp %CP% com.gamma.control.ControlApi', 'if exist inspecto-foo.jar set "CP=%CP%;inspecto-foo.jar"\n"%JAVA%" %OPTS% -cp %CP% com.gamma.control.ControlApi');
    assert.match(statics(bat), /serveBat appends a NAMED jar to CP/);
});

test('RED (static): serve.* guess the edition again (no edition.properties read)', () => {
    assert.match(statics(REAL.replaceAll('edition.properties', 'xxx.properties')), /serveSh never reads edition\.properties[\s\S]*serveBat never reads edition\.properties/);
});

test('RED (static): the serve.bat self-referential-set-in-a-block rule, and delayed expansion, still fire', () => {
    const block = mutate('if "%EDITION%"=="Advanced" set "OPTS=%OPTS% -Dobjects.backend=postgres"', 'if defined X (\nset "OPTS=%OPTS% -Da=b"\nset "OPTS=%OPTS% -Dc=d"\n)');
    assert.match(statics(block), /re-expands a variable inside a parenthesised block/);
    // ⚠ Anchor on `setlocal` + the cd ONLY. It used to carry the lines that follow it in serve.bat, which made the
    // fixture break the moment those lines changed (SPACE-RELOCATABLE-ROOTS-1 inserted the spaces.ref gate between
    // them, 2026-11-14) — and a fixture whose anchor no longer matches mutates NOTHING, so it fails without the rule
    // ever being wrong. A fixture must pin the rule it tests, not the text around it.
    assert.match(statics(mutate('setlocal\ncd /d "%~dp0.."\nrem bin', 'setlocal EnableDelayedExpansion\ncd /d "%~dp0.."\nrem bin')), /enables delayed expansion/);
});

test('RED (static): the demo launchers keep a jar list of their own', () => {
    assert.match(statics(REAL.replaceAll('modules.list', 'xxx.list')), /serveDemoBat never reads modules\.list[\s\S]*serveDemoSh never reads modules\.list/);
});

test('RED (static): the demo launchers ignore INSPECTO_JAVA_OPTS / never append it / read it after -cp (LAUNCHER-SERVE-DEMO-JAVA-OPTS-1)', () => {
    assert.match(statics(REAL.replaceAll('INSPECTO_JAVA_OPTS', 'XXX_OPTS')), /serveDemoBat does not read INSPECTO_JAVA_OPTS[\s\S]*serveDemoSh does not read INSPECTO_JAVA_OPTS/);
    const noAppend = REAL.replace(/^if not "%EXTRA_OPTS%"=="" set "OPTS=%OPTS% %EXTRA_OPTS%"\n/gm, '').replace(/^    JAVA_OPTS\+=\("`\$\{_extra_opts\[@\]\}"\)\n/m, '');
    assert.notEqual(noAppend, REAL);
    assert.match(statics(noAppend), /serveDemoBat reads INSPECTO_JAVA_OPTS but never appends[\s\S]*serveDemoSh reads INSPECTO_JAVA_OPTS but never appends/);
    // the real read is stripped from its place and re-added behind -cp
    const late = REAL.replace(/^rem Extra JVM flags from the operator, same contract as serve\.bat \(LAUNCHER[\s\S]*?extra JVM opts: %EXTRA_OPTS%\n/m, '').replace('com.gamma.control.ControlApi\n"@\n    Write-CrlfScript -Path "$binDir\\serve-demo.bat"', 'com.gamma.control.ControlApi %INSPECTO_JAVA_OPTS% %EXTRA_JAVA_OPTS% %OPTS% %EXTRA_OPTS%\n"@\n    Write-CrlfScript -Path "$binDir\\serve-demo.bat"');
    assert.notEqual(late, REAL);
    assert.match(statics(late), /serveDemoBat reads INSPECTO_JAVA_OPTS only after -cp/);
});

test('the real serve-demo.sh/.bat, EXECUTED over the spaces-root cases, GREEN', () => {
    assert.deepEqual(runDemoScenarios(REAL).findings, []);
});

// SPACE-RELOCATABLE-ROOTS-1: a launcher that passes -Dspaces.root unconditionally makes spaces.ref unreachable,
// because the flag wins. The demo here-strings are scoped by name — the bat line is identical in serve.bat, and
// mutating that one instead would prove nothing about serve-demo.
const demoBlock = (name, from, to) => {
    const open = REAL.indexOf('$' + name + ' = @"\n');
    const end = REAL.indexOf('\n"@\n', open);
    assert.ok(open >= 0 && end > open, `the ${name} here-string is not in package.ps1`);
    const body = REAL.slice(open, end);
    assert.equal(body.split(from).length - 1, 1, `fixture anchor must occur exactly once in ${name}: ${from.slice(0, 70)}`);
    return REAL.slice(0, open) + body.split(from).join(to) + REAL.slice(end);
};

test('RED (executed): serve-demo.bat passes -Dspaces.root unconditionally, so spaces.ref is never read', () => {
    const t = demoBlock('serveDemoBat', 'if not defined SPACES_REF set "OPTS=%OPTS% -Dspaces.root=%SPACES_ROOT%"', 'set "OPTS=%OPTS% -Dspaces.root=%SPACES_ROOT%"');
    assert.notEqual(t, REAL);
    const found = runDemoScenarios(t).findings.join('\n');
    assert.match(found, /serve-demo\.(bat|sh)[\s\S]*WINS over spaces\.ref/);
});

test('RED (executed): serve-demo.sh passes -Dspaces.root unconditionally, so spaces.ref is never read', () => {
    const t = demoBlock('serveDemoSh', 'if [ -n "`$SPACES_REF" ]; then\n    echo "[serve-demo] spaces root from ./spaces.ref"\nelse\n    JAVA_OPTS+=("-Dspaces.root=`${SPACES_ROOT}")\nfi', 'JAVA_OPTS+=("-Dspaces.root=`${SPACES_ROOT}")');
    assert.notEqual(t, REAL);
    assert.match(runDemoScenarios(t).findings.join('\n'), /serve-demo\.sh[\s\S]*WINS over spaces\.ref/);
});

test('RED (executed): serve-demo loses -Dauth.mode=demo', () => {
    const t = demoBlock('serveDemoSh', 'JAVA_OPTS=($demoFlags "-Dcontrol.port=`${PORT}")', 'JAVA_OPTS=("-Dcontrol.port=`${PORT}")');
    assert.match(runDemoScenarios(t).findings.join('\n'), /dropped `-Dauth\.mode=demo`/);
});

test('RED (executed): a serve.sh that ignores modules.list (falls to the *.jar glob) puts the jars in the WRONG ORDER', () => {
    const t = mutate('if [ -f lib/modules.list ]; then\n    CP="$(tr -d \'\\r\' < lib/modules.list | tr \'\\n\' \':\')"; CP="${CP%:}"\nelse\n    echo "[serve.sh] lib/modules.list', 'if false; then\n    CP=x\nelse\n    echo "[serve.sh] lib/modules.list');
    assert.match(scenarios(t), /expected exactly the Offering's modules\.list/);
});

test('RED (executed): a serve.sh that sniffs jar presence instead of reading the marker lets a stray jar change the edition', () => {
    const t = mutate('if [ -f lib/edition.properties ]; then\n    EDITION=', 'if false; then\n    EDITION=');
    assert.match(scenarios(t), /stray inspecto-oidc\.jar[\s\S]*printed `edition: Advanced`, expected `Basic`/);
});

test('RED (executed): a serve.sh that accepts an unknown edition marker is caught', () => {
    const t = mutate('    Basic|Standard|Advanced|Preview) ;;\n    *) echo', '    *) ;;\n    xx) echo');
    assert.match(scenarios(t), /UNKNOWN edition in edition\.properties[\s\S]*the launcher started although/);
});

test('RED (executed): Preview patched back to Advanced\'s behaviour (postgres objects backend) is caught', () => {
    const t = mutate('[ "${EDITION}" = "Advanced" ] && JAVA_OPTS+=', '[ "${EDITION}" != "Basic" ] && JAVA_OPTS+=');
    assert.match(scenarios(t), /Preview[\s\S]*forbidden flag {2}-Dobjects\.backend=postgres/);
});

test('the real run.sh/run.bat, EXECUTED, put exactly modules.list on -cp', () => {
    assert.deepEqual(runOneShot(REAL).findings, []);
});

test('RED (executed): a run.sh that ignores modules.list (the *.jar glob) puts the jars in the WRONG ORDER', () => {
    const t = mutate('if [ -f lib/modules.list ]; then\n    CP="$(tr -d \'\\r\' < lib/modules.list | tr \'\\n\' \':\')"; CP="${CP%:}"\nelse\n    echo "[run.sh] lib/modules.list', 'if false; then\n    CP=x\nelse\n    echo "[run.sh] lib/modules.list');
    assert.match(runOneShot(t).findings.join('\n'), /run\.sh · Standard: classpath is[\s\S]*expected exactly the Offering's modules\.list/);
});
