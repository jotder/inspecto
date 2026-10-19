// Fixture test: the two whole-tree walkers that a local, permission-locked folder used to crash
// (tools/KnownModules.java, run by the processor build, and tools/route-gating-report.mjs's route scanner,
// shared with render-api-routes.mjs) SKIP an unreadable directory outside module sources, and still FAIL on
// one inside a src/ tree — a silently skipped source would ship a wrong manifest index or a false-green gating table.
// Run: node --test tools/unreadable-dirs.test.mjs
// ⚠ Locking a directory needs POSIX permissions and a non-root user (CI's ubuntu runner); on Windows, or as root,
// chmod cannot make a directory unreadable and the locking cases SKIP rather than pass vacuously.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { routeFiles } from './route-gating-report.mjs';

const TOOL = join(process.cwd(), 'tools', 'KnownModules.java');

function tree() {
    const root = mkdtempSync(join(tmpdir(), 'unreadable-'));
    const put = (rel, text) => { const p = join(root, rel); mkdirSync(join(p, '..'), { recursive: true }); writeFileSync(p, text); };
    put('platform/a/src/main/resources/META-INF/inspecto/module.toon', 'id: a\n');
    put('platform/a/src/main/java/com/x/ARoutes.java', 'class ARoutes {}');
    mkdirSync(join(root, 'local-bundle', 'runtime-natives'), { recursive: true });
    mkdirSync(join(root, 'platform', 'a', 'src', 'main', 'java', 'locked'), { recursive: true });
    return root;
}
/** Lock a directory; returns false where the platform cannot make it unreadable (then the case skips). */
function lock(dir) {
    chmodSync(dir, 0o000);
    try { readdirSync(dir); chmodSync(dir, 0o755); return false; } catch { return true; }
}
const javaOk = spawnSync('java', ['-version'], { encoding: 'utf8' }).status === 0;
const knownModules = (root, out) => spawnSync('java', [TOOL, root, out], { encoding: 'utf8' });

test('route scanner skips an unreadable directory outside sources and still finds the routes', (t) => {
    const root = tree(), dir = join(root, 'local-bundle', 'runtime-natives');
    try {
        if (!lock(dir)) return t.skip('this platform cannot make a directory unreadable');
        const files = routeFiles(root).map((f) => f.replace(/\\/g, '/'));
        assert.equal(files.length, 1);
        assert.match(files[0], /ARoutes\.java$/);
    } finally { chmodSync(dir, 0o755); rmSync(root, { recursive: true, force: true }); }
});

test('route scanner still throws on an unreadable directory inside a src/ tree', (t) => {
    const root = tree(), dir = join(root, 'platform', 'a', 'src', 'main', 'java', 'locked');
    try {
        if (!lock(dir)) return t.skip('this platform cannot make a directory unreadable');
        assert.throws(() => routeFiles(root), /EACCES|EPERM/);
    } finally { chmodSync(dir, 0o755); rmSync(root, { recursive: true, force: true }); }
});

test('KnownModules skips an unreadable directory outside sources and indexes the manifests', (t) => {
    if (!javaOk) return t.skip('no java on PATH');
    const root = tree(), dir = join(root, 'local-bundle', 'runtime-natives'), out = join(root, 'out');
    try {
        if (!lock(dir)) return t.skip('this platform cannot make a directory unreadable');
        const r = knownModules(root, out);
        assert.equal(r.status, 0, r.stderr);
        assert.match(r.stderr, /skipped unreadable/);
        assert.equal(readFileSync(join(out, 'index.txt'), 'utf8'), 'a\n');
    } finally { chmodSync(dir, 0o755); rmSync(root, { recursive: true, force: true }); }
});

test('KnownModules still fails on an unreadable directory inside a src/ tree', (t) => {
    if (!javaOk) return t.skip('no java on PATH');
    const root = tree(), dir = join(root, 'platform', 'a', 'src', 'main', 'java', 'locked');
    try {
        if (!lock(dir)) return t.skip('this platform cannot make a directory unreadable');
        assert.notEqual(knownModules(root, join(root, 'out')).status, 0);
    } finally { chmodSync(dir, 0o755); rmSync(root, { recursive: true, force: true }); }
});

test('KnownModules indexes a clean tree (runs everywhere java does)', (t) => {
    if (!javaOk) return t.skip('no java on PATH');
    const root = tree(), out = join(root, 'out');
    try {
        const r = knownModules(root, out);
        assert.equal(r.status, 0, r.stderr);
        assert.equal(readFileSync(join(out, 'index.txt'), 'utf8'), 'a\n');
    } finally { rmSync(root, { recursive: true, force: true }); }
});
