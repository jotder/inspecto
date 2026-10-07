// Negative fixtures for tools/check-launchers.mjs. Run: node --test tools/check-launchers.test.mjs
// Each test mutates the REAL inspecto/package.ps1 text one way and asserts the guard goes red and names it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SCENARIOS, runOneShot, runScenarios, staticFindings } from './check-launchers.mjs';

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
    const block = mutate('if "%EDITION%"=="Enterprise" set "OPTS=%OPTS% -Dobjects.backend=postgres"', 'if defined X (\nset "OPTS=%OPTS% -Da=b"\nset "OPTS=%OPTS% -Dc=d"\n)');
    assert.match(statics(block), /re-expands a variable inside a parenthesised block/);
    assert.match(statics(mutate('setlocal\ncd /d "%~dp0"\nif "%PORT%"=="" set "PORT=8080"\nif "%SPACES_ROOT%"=="" set "SPACES_ROOT=spaces"\nset "ANY_SPACE="', 'setlocal EnableDelayedExpansion\ncd /d "%~dp0"\nif "%PORT%"=="" set "PORT=8080"\nif "%SPACES_ROOT%"=="" set "SPACES_ROOT=spaces"\nset "ANY_SPACE="')), /enables delayed expansion/);
});

test('RED (static): the demo launchers keep a jar list of their own', () => {
    assert.match(statics(REAL.replaceAll('modules.list', 'xxx.list')), /serveDemoBat never reads modules\.list[\s\S]*serveDemoSh never reads modules\.list/);
});

test('RED (executed): a serve.sh that ignores modules.list (falls to the *.jar glob) puts the jars in the WRONG ORDER', () => {
    const t = mutate('if [ -f modules.list ]; then\n    CP="$(tr -d \'\\r\' < modules.list | tr \'\\n\' \':\')"; CP="${CP%:}"\nelse\n    echo "[serve.sh] modules.list', 'if false; then\n    CP=x\nelse\n    echo "[serve.sh] modules.list');
    assert.match(scenarios(t), /expected exactly the Offering's modules\.list/);
});

test('RED (executed): a serve.sh that sniffs jar presence instead of reading the marker lets a stray jar change the edition', () => {
    const t = mutate('if [ -f edition.properties ]; then\n    EDITION=', 'if false; then\n    EDITION=');
    assert.match(scenarios(t), /stray inspecto-oidc\.jar[\s\S]*printed `edition: Enterprise`, expected `Personal`/);
});

test('RED (executed): a serve.sh that accepts an unknown edition marker is caught', () => {
    const t = mutate('    Personal|Professional|Enterprise|Preview) ;;\n    *) echo', '    *) ;;\n    xx) echo');
    assert.match(scenarios(t), /UNKNOWN edition in edition\.properties[\s\S]*the launcher started although/);
});

test('RED (executed): Preview patched back to Enterprise\'s behaviour (postgres objects backend) is caught', () => {
    const t = mutate('[ "${EDITION}" = "Enterprise" ] && JAVA_OPTS+=', '[ "${EDITION}" != "Personal" ] && JAVA_OPTS+=');
    assert.match(scenarios(t), /Preview[\s\S]*forbidden flag {2}-Dobjects\.backend=postgres/);
});

test('the real run.sh/run.bat, EXECUTED, put exactly modules.list on -cp', () => {
    assert.deepEqual(runOneShot(REAL).findings, []);
});

test('RED (executed): a run.sh that ignores modules.list (the *.jar glob) puts the jars in the WRONG ORDER', () => {
    const t = mutate('if [ -f modules.list ]; then\n    CP="$(tr -d \'\\r\' < modules.list | tr \'\\n\' \':\')"; CP="${CP%:}"\nelse\n    echo "[run.sh] modules.list', 'if false; then\n    CP=x\nelse\n    echo "[run.sh] modules.list');
    assert.match(runOneShot(t).findings.join('\n'), /run\.sh · Professional: classpath is[\s\S]*expected exactly the Offering's modules\.list/);
});
