// Fixture test for tools/dev-doctor.mjs. Run: node --test tools/dev-doctor.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import {
    javaMajor, checkJava, checkMaven, checkNode, checkCodegraph, checkGit, checkDocker, checkDuckdbCache,
    checkUnreadable, checkLocalOnly, claudeProjectDir, runAll, exitCode,
} from './dev-doctor.mjs';

const ROOT = 'C:\\repo', HOME = 'C:\\home';
const ok = (out = '') => ({ ok: true, out }), no = { ok: false, out: '' };
/** A healthy machine; override cmds (key = `cmd args`), files, dirs, env. */
const probe = ({ cmds = {}, files = [], missing = [], dirs, env = {}, nodeVersion = 'v22.3.0' } = {}) => {
    const base = {
        'java -version': ok('openjdk version "27.0.1" 2026-10-01'),
        [`${join('C:\\jdk27', 'bin', 'java')} -version`]: ok('openjdk version "27" 2026'),
        'mvn -v': ok('Apache Maven 3.9.9\nJava version: 27.0.1, vendor: x'),
        'npm -v': ok('10.8.0\n'), 'codegraph --version': ok('1.6.0\n'),
        [`git -C ${ROOT} config --get core.hooksPath`]: ok('.githooks\n'),
        [`git -C ${ROOT} config --get user.name`]: ok('jo\n'), [`git -C ${ROOT} config --get user.email`]: ok('j@x\n'),
        'gh auth status': ok(), 'docker info --format {{.ServerVersion}}': ok('27.1\n'),
        'docker ps -a --format {{.Image}}\t{{.Names}}\t{{.State}}': ok('postgres:latest\tpg\trunning\nwso2/wso2is\twso2is\trunning\nminio/minio\tminio\trunning\n'),
        ...cmds,
    };
    return {
        root: ROOT, home: HOME, nodeVersion, env: { JAVA_HOME: 'C:\\jdk27', ...env },
        run: (c, a) => base[[c, ...a].join(' ')] ?? no,
        exists: (p) => !missing.some((m) => p.includes(m)) || files.includes(p),
        listDirs: dirs ?? ((p) => (p === ROOT ? ['a', '.git'] : p === join(ROOT, 'a') ? ['b'] : [])),
    };
};
const st = (results) => results.map((x) => x.status);

test('javaMajor parses modern, legacy and mvn output', () => {
    assert.equal(javaMajor('openjdk version "27.0.1"'), 27);
    assert.equal(javaMajor('java version "1.8.0_402"'), 8);
    assert.equal(javaMajor('Java version: 26.0.1, vendor'), 26);
    assert.equal(javaMajor('garbage'), null);
});

test('a healthy machine is all GREEN and exits 0', () => {
    const g = runAll(probe());
    assert.deepEqual(g.flatMap((x) => x.results).filter((x) => x.status !== 'ok'), []);
    assert.equal(exitCode(g), 0);
});

test('JAVA_HOME at 26 while PATH java is 27 is RED with a setx fix', () => {
    const p = probe({ env: { JAVA_HOME: 'C:\\jdk26' }, cmds: { [`${join('C:\\jdk26', 'bin', 'java')} -version`]: ok('openjdk version "26.0.1"') } });
    const jh = checkJava(p).find((x) => x.name === 'JAVA_HOME java');
    assert.equal(jh.status, 'fail'); assert.match(jh.detail, /major 26/); assert.match(jh.fix, /setx JAVA_HOME/);
    assert.equal(exitCode([{ results: checkJava(p) }]), 1);
});

test('unset JAVA_HOME and mvn on Java 26 are RED', () => {
    const p = probe({ env: { JAVA_HOME: '' }, cmds: { 'mvn -v': ok('Apache Maven 3.9.9\nJava version: 26.0.1') } });
    assert.deepEqual(st(checkJava(p)), ['ok', 'fail', 'fail']);
});

test('old Maven and missing ~/.m2 artifacts are RED with install fixes', () => {
    const p = probe({ cmds: { 'mvn -v': ok('Apache Maven 3.8.6') }, missing: ['eoiagent', 'inspector'] });
    const r = checkMaven(p);
    assert.deepEqual(st(r), ['fail', 'fail', 'fail']);
    assert.match(r[1].fix, /jotder\/inspect-agent.*mvn -DskipTests install/);
});

test('node 20, no npm, no node_modules are RED', () => {
    const r = checkNode(probe({ nodeVersion: 'v20.11.0', cmds: { 'npm -v': no }, missing: ['node_modules'] }));
    assert.deepEqual(st(r), ['fail', 'fail', 'fail']); assert.equal(r[2].fix, 'cd inspecto-ui && npm ci');
});

test('codegraph absent and no index are RED', () => {
    const r = checkCodegraph(probe({ cmds: { 'codegraph --version': no }, missing: ['.codegraph'] }));
    assert.deepEqual(st(r), ['fail', 'fail']); assert.equal(r[1].fix, 'codegraph index');
});

test('git hooksPath wrong is RED; gh unauthenticated is only a WARNING', () => {
    const r = checkGit(probe({ cmds: { [`git -C ${ROOT} config --get core.hooksPath`]: no, 'gh auth status': no } }));
    assert.deepEqual(st(r), ['fail', 'ok', 'warn']); assert.equal(r[0].fix, 'git config core.hooksPath .githooks');
});

test('docker down is a single WARNING; stopped/absent containers WARN with hints', () => {
    assert.deepEqual(st(checkDocker(probe({ cmds: { 'docker info --format {{.ServerVersion}}': no } }))), ['warn']);
    const r = checkDocker(probe({ cmds: { 'docker ps -a --format {{.Image}}\t{{.Names}}\t{{.State}}': ok('postgres:16\tpg\texited\n') } }));
    assert.deepEqual(st(r), ['ok', 'warn', 'warn', 'warn']);
    assert.equal(r[1].fix, 'docker start pg'); assert.match(r[2].fix, /wso2\/wso2is/); assert.match(r[3].fix, /9000/);
});

test('DuckDB cache: env var OR repo dir; neither is RED', () => {
    assert.equal(checkDuckdbCache(probe({ missing: ['.duckdb-extension-cache'], env: { DUCKDB_EXTENSION_CACHE: 'D:\\c' } }))[0].status, 'ok');
    const r = checkDuckdbCache(probe({ missing: ['.duckdb-extension-cache'] }));
    assert.equal(r[0].status, 'fail'); assert.match(r[0].fix, /fetch-duckdb-extensions/);
});

test('unreadable dirs at depth 1 and 2 are named as a WARNING', () => {
    const map = { [ROOT]: ['x', 'inspecto-demo'], [join(ROOT, 'x')]: null, [join(ROOT, 'inspecto-demo')]: ['runtime-natives', 'ok'],
        [join(ROOT, 'inspecto-demo', 'runtime-natives')]: null };
    const dirs = (p) => (p in map ? map[p] : []);
    const r = checkUnreadable(probe({ dirs }));
    assert.equal(r[0].status, 'warn'); assert.equal(r[0].detail, 'x, inspecto-demo/runtime-natives');
});

test('missing local-only files WARN, name the path, never fail', () => {
    const r = checkLocalOnly(probe({ missing: ['SESSION_STATUS', 'memory'] }));
    assert.deepEqual(st(r), ['ok', 'warn', 'ok', 'ok', 'ok', 'warn']);
    assert.match(r[5].fix, /C--repo/);
    assert.equal(claudeProjectDir('C:\\sandbox\\inspecto-clean'), 'C--sandbox-inspecto-clean');
});

test('a crashing check is reported RED, not thrown', () => {
    const p = probe(); p.listDirs = () => { throw new Error('boom'); };
    const g = runAll(p).find((x) => x.group === 'Checkout');
    assert.equal(g.results[0].status, 'fail'); assert.match(g.results[0].detail, /boom/);
});

test('a java launcher that crashes silently is named as a stale stub, not "not found"', async () => {
    const { checkJava } = await import('./dev-doctor.mjs');
    const probe = { env: { JAVA_HOME: 'X' }, run: (cmd) => (cmd === 'java' ? { ok: false, status: 3221226505, out: '' } : { ok: true, status: 0, out: 'version "27"\nJava version: 27' }) };
    const first = checkJava(probe)[0];
    assert.match(first.detail ?? JSON.stringify(first), /crashed .*launcher stub/);
});
