// Fixture-based tests for tools/regroup-modules.mjs: a tiny synthetic git repo in a temp dir, never the real tree.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import {
    run, buildMoves, makeCtx, mapPath, unmapPath, relinkTarget, rewriteMavenProjectLists, rewriteGitignore,
    rewriteChildPomRelativePath, rewriteModulePaths, rewriteJavaRelativePaths, PHASE_A, PHASE_B,
} from './regroup-modules.mjs';

const TABLE = { spi: [], platform: ['inspecto-api'], features: ['inspecto-ops'], providers: ['asn-parser'] };

const FILES = {
    'pom.xml': '<project><modules>\n<module>providers/asn-parser/asn-decoders</module>\n<module>inspecto-api</module>\n<module>inspecto</module>\n</modules>\n'
        + '<profiles><profile><id>p</id><modules><!-- the ops add-on -->\n<module>inspecto-ops</module>\n</modules></profile></profiles></project>\n',
    'inspecto/pom.xml': '<project><parent><artifactId>root</artifactId><relativePath>../pom.xml</relativePath></parent><artifactId>inspecto-processor</artifactId></project>\n',
    'platform/inspecto-api/pom.xml': '<project><parent><artifactId>root</artifactId><relativePath>../pom.xml</relativePath></parent><artifactId>inspecto-api</artifactId></project>\n',
    'features/inspecto-ops/pom.xml': '<project><parent><artifactId>root</artifactId><relativePath>../pom.xml</relativePath></parent><artifactId>inspecto-ops</artifactId></project>\n',
    'providers/asn-parser/asn-decoders/pom.xml': '<project><artifactId>asn-decoders</artifactId><modules><module>asn-core</module></modules></project>\n',
    'providers/asn-parser/asn-decoders/asn-core/pom.xml': '<project><artifactId>asn-core</artifactId><sourceDirectory>../../src/main/java</sourceDirectory></project>\n',
    'providers/asn-parser/src/main/java/A.java': 'class A {}\n',
    'platform/inspecto-api/src/main/resources/META-INF/inspecto/module.toon': 'id: api\n',
    'features/inspecto-ops/src/main/resources/META-INF/inspecto/module.toon': 'id: ops\n',
    'features/inspecto-ops/src/main/java/Ops.java': 'class Ops {}\n',
    'features/inspecto-ops/src/test/java/OpsTest.java':
        'class OpsTest { Path a = Path.of("..", "spaces", "demo"); Path b = Path.of("../platform/inspecto-api/pom.xml"); String esc = "../escape"; Path r = Path.of("..").toAbsolutePath(); }\n',
    'features/inspecto-ops/README.md': 'See [guide](../docs/guide.md) and [api](../platform/inspecto-api/pom.xml).\n',
    'spaces/x.toon': 'a: 1\n',
    'docs/guide.md': 'Read [ops](../features/inspecto-ops/src/main/java/Ops.java) or [gone](../features/inspecto-ops/nope.md).\n\n'
        + 'Cited as `features/inspecto-ops/src/main/java/Ops.java`; build with `mvn -pl :inspecto-api,:inspecto-ops -am`; artifact `inspecto-ops` stays; jar inspecto-ops.jar stays.\n'
        + '```\nmvn -pl :inspecto-ops test\n```\n',
    'docs/archived-documents/old.md': 'History: `features/inspecto-ops/src/old.java` and [x](../../features/inspecto-ops/y.md).\n',
    'inspecto/package.ps1': "$d = Join-Path $sandboxRoot 'features\inspecto-ops\\target'\n$m = ':inspecto-api,:inspecto-ops'\n& mvn -pl inspecto,:inspecto-ops -am\nCopy-Item inspecto-ops.jar x\n",
    '.github/workflows/ci.yml': 'steps:\n  - run: mvn -pl :inspecto-api -am test\n  - with:\n      path: features/inspecto-ops/target/surefire-reports/*.xml\n',
    '.gitignore': '/target/\n/inspecto-ops/target/\n!/inspecto/examples/**\n',
};

function git(root, ...a) { return execFileSync('git', a, { cwd: root, encoding: 'utf8' }); }

function makeRepo(branch = 'master') {
    const root = mkdtempSync(join(tmpdir(), 'regroup-test-'));
    for (const [p, t] of Object.entries(FILES)) { mkdirSync(dirname(join(root, p)), { recursive: true }); writeFileSync(join(root, p), t); }
    git(root, 'init', '-q', '-b', branch);
    git(root, 'config', 'core.autocrlf', 'false'); git(root, 'config', 'user.email', 't@example.invalid'); git(root, 'config', 'user.name', 'T'); git(root, 'config', 'commit.gpgsign', 'false');
    git(root, 'add', '-A'); git(root, 'commit', '-q', '-m', 'base');
    return root;
}
const snapshot = (root) => Object.fromEntries(git(root, 'ls-files').split('\n').filter(Boolean).map((p) => [p, readFileSync(join(root, p), 'utf8')]));
const read = (root, p) => readFileSync(join(root, p), 'utf8');
const quiet = { quiet: true, table: TABLE };

test('dry-run writes nothing (tree and index untouched) but reports what it would do', () => {
    const root = makeRepo();
    try {
        const before = snapshot(root);
        const r = run({ root, ...quiet });
        assert.equal(git(root, 'status', '--porcelain'), '');
        assert.deepEqual(snapshot(root), before);
        assert.equal(r.a.stats.rewriteRootPomModules['root-pom'].occ, 3);
        assert.ok(r.b.stats.rewriteChildPomRelativePath['child-pom'].files === 2);   // inspecto keeps ../pom.xml, the asn aggregator has none
        assert.ok(r.b.changes.size > 5);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('--apply makes two commits: pure moves + root pom first, rewrites second', () => {
    const root = makeRepo();
    try {
        const r = run({ root, apply: true, ...quiet });
        assert.ok(r.commit1 && r.commit2);
        assert.ok(existsSync(join(root, 'features/inspecto-ops/pom.xml')) && existsSync(join(root, 'platform/inspecto-api/pom.xml')));
        assert.ok(existsSync(join(root, 'providers/asn-parser/asn-decoders/asn-core/pom.xml')) && existsSync(join(root, 'inspecto/pom.xml')));
        assert.ok(!existsSync(join(root, 'features', 'inspecto-ops')));
        // commit 1: only renames (R100) and the root pom
        const c1 = git(root, 'show', '-M', '--name-status', '--format=', 'HEAD~1').trim().split('\n');
        assert.ok(c1.every((l) => l.startsWith('R100') || l === 'M\tpom.xml'), c1.join('\n'));
        // root pom
        const pom = read(root, 'pom.xml');
        for (const m of ['providers/asn-parser/asn-decoders', 'platform/inspecto-api', 'features/inspecto-ops']) assert.ok(pom.includes(`<module>${m}</module>`), m);
        assert.ok(pom.includes('<module>inspecto</module>'));
        // child poms: depth + 1, nested asn paths untouched (they move as a unit)
        assert.match(read(root, 'features/inspecto-ops/pom.xml'), /<relativePath>\.\.\/\.\.\/pom\.xml</);
        assert.match(read(root, 'inspecto/pom.xml'), /<relativePath>\.\.\/pom\.xml</);
        assert.match(read(root, 'providers/asn-parser/asn-decoders/asn-core/pom.xml'), /\.\.\/\.\.\/src\/main\/java/);
        // docs: a link recomputed, a dead link left, citations + -pl rewritten, artifact tokens and jar names kept, archive untouched
        const doc = read(root, 'docs/guide.md');
        assert.ok(doc.includes('](../features/inspecto-ops/src/main/java/Ops.java)'));
        assert.ok(doc.includes('](../features/inspecto-ops/nope.md)') || doc.includes('](../features/inspecto-ops/nope.md)'));
        assert.ok(doc.includes('`features/inspecto-ops/src/main/java/Ops.java`'));
        assert.ok(doc.includes('-pl :inspecto-api,:inspecto-ops -am') && doc.includes('mvn -pl :inspecto-ops test'));
        assert.ok(doc.includes('artifact `inspecto-ops` stays') && doc.includes('jar inspecto-ops.jar stays'));
        assert.equal(read(root, 'docs/archived-documents/old.md'), FILES['docs/archived-documents/old.md']);
        // a moved module's README: its own links recomputed from the NEW location
        assert.equal(read(root, 'features/inspecto-ops/README.md'), 'See [guide](../../docs/guide.md) and [api](../../platform/inspecto-api/pom.xml).\n');
        // package.ps1: backslash paths, module lists, -pl; the jar file name is not a path
        const ps1 = read(root, 'inspecto/package.ps1');
        assert.ok(ps1.includes("'features\\inspecto-ops\\target'"), ps1);
        assert.ok(ps1.includes("$m = ':inspecto-api,:inspecto-ops'") && ps1.includes('-pl inspecto,:inspecto-ops -am') && ps1.includes('Copy-Item inspecto-ops.jar'));
        // workflow + .gitignore
        const ci = read(root, '.github/workflows/ci.yml');
        assert.ok(ci.includes('-pl :inspecto-api -am') && ci.includes('path: features/inspecto-ops/target/surefire-reports'));
        assert.equal(read(root, '.gitignore'), '/target/\n/features/inspecto-ops/target/\n!/inspecto/examples/**\n');
        // Java: one more `..` in a MOVED module's cwd-relative repo paths, sibling-module paths gain the group, jail-escape strings untouched
        const j = read(root, 'features/inspecto-ops/src/test/java/OpsTest.java');
        assert.ok(j.includes('Path.of("..", "..", "spaces", "demo")'));
        assert.ok(j.includes('Path.of("../../platform/inspecto-api/pom.xml")'));
        assert.ok(j.includes('"../escape"') && j.includes('Path.of("..", "..").toAbsolutePath()'));
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('a second --apply is a no-op, and the dry-run of a regrouped tree finds nothing to change (idempotent)', () => {
    const root = makeRepo();
    try {
        run({ root, apply: true, ...quiet });
        const head = git(root, 'rev-parse', 'HEAD');
        const r = run({ root, apply: true, ...quiet });
        assert.equal(r.commit1, false);
        assert.equal(r.commit2, false);
        assert.equal(git(root, 'rev-parse', 'HEAD'), head);
        const d = run({ root, ...quiet });
        assert.equal(d.a.changes.size + d.b.changes.size, 0);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('--apply refuses a dirty tree and a branch other than master', () => {
    const dirty = makeRepo();
    try {
        writeFileSync(join(dirty, 'stray.txt'), 'x');
        assert.throws(() => run({ root: dirty, apply: true, ...quiet }), /not empty/);
        assert.ok(existsSync(join(dirty, 'features', 'inspecto-ops')));
    } finally { rmSync(dirty, { recursive: true, force: true }); }
    const dev = makeRepo('develop');
    try {
        assert.throws(() => run({ root: dev, apply: true, ...quiet }), /not master/);
        assert.ok(existsSync(join(dev, 'features', 'inspecto-ops')));
    } finally { rmSync(dev, { recursive: true, force: true }); }
});

test('a module the table does not cover is refused, by name', () => {
    const root = makeRepo();
    try {
        assert.throws(() => run({ root, quiet: true, table: { ...TABLE, features: [] } }), /not covered by the table: inspecto-ops/);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

// ── the rewrite functions on their own ──
const moves = buildMoves(TABLE);
const ctx = makeCtx({ moves, newPaths: ['pom.xml', 'spaces/x.toon', 'docs/guide.md', 'features/inspecto-ops/pom.xml', 'platform/inspecto-api/pom.xml'], artifactIds: new Map([['inspecto-api', 'inspecto-api'], ['inspecto-ops', 'inspecto-ops']]) });
const file = (np, cls) => ({ np, op: unmapPath(np, moves), cls });
const twice = (fn, text, f) => { const once = fn(text, f, ctx).text; assert.equal(fn(once, f, ctx).text, once, `${fn.name} is not idempotent`); return once; };

test('mapPath / unmapPath are inverse and leave unmoved paths alone', () => {
    assert.equal(mapPath('features/inspecto-ops/src/A.java', moves), 'features/inspecto-ops/src/A.java');
    assert.equal(unmapPath('features/inspecto-ops/src/A.java', moves), 'features/inspecto-ops/src/A.java');
    assert.equal(mapPath('inspecto/pom.xml', moves), 'inspecto/pom.xml');
    assert.equal(unmapPath('features/other/x', moves), 'features/other/x');
});

test('rewriteMavenProjectLists: bare module tokens become :artifactId, `inspecto` and non-modules stay, idempotent', () => {
    const f = file('docs/x.md', 'docs');
    assert.equal(twice(rewriteMavenProjectLists, 'mvn -pl inspecto,:inspecto-ops,inspecto-ui -am', f), 'mvn -pl inspecto,:inspecto-ops,inspecto-ui -am');
    assert.equal(twice(rewriteMavenProjectLists, 'mvn -pl !:inspecto-api test', f), 'mvn -pl !:inspecto-api test');
    assert.equal(twice(rewriteMavenProjectLists, '$modules = \':inspecto-api,:inspecto-ops\'', file('inspecto/package.ps1', 'scripts')), "$modules = ':inspecto-api,:inspecto-ops'");
});

test('rewriteModulePaths: path positions only; never an already-prefixed path, a URL, a Maven repo path, or a plain artifact name', () => {
    const f = file('docs/x.md', 'docs');
    const t = 'a features/inspecto-ops/src b `features\inspecto-ops\\target` c features/inspecto-ops/src d https://h/inspecto-ops/x e com/gamma/inspector/inspecto-ops/1.0/x f inspecto-ops.jar g ./platform/inspecto-api/pom.xml h $ROOT/platform/inspecto-api/x';
    assert.equal(twice(rewriteModulePaths, t, f),
        'a features/inspecto-ops/src b `features\\inspecto-ops\\target` c features/inspecto-ops/src d https://h/inspecto-ops/x e com/gamma/inspector/inspecto-ops/1.0/x f inspecto-ops.jar g ./platform/inspecto-api/pom.xml h $ROOT/platform/inspecto-api/x');
    // a JS regex literal ending in a slash is not a path
    assert.equal(rewriteModulePaths('assert.match(s, /reaches inspecto-ops/);', file('tools/x.test.mjs', 'tools'), ctx).text, 'assert.match(s, /reaches inspecto-ops/);');
});

test('relinkTarget: a link already valid is left alone; a moved target and a moved file are recomputed', () => {
    const c = makeCtx({ moves, newPaths: ['docs/a.md', 'docs/b.md', 'features/inspecto-ops/README.md', 'features/inspecto-ops/src/A.java', 'platform/inspecto-api/pom.xml'] });
    assert.equal(relinkTarget('b.md', file('docs/a.md', 'docs'), c), null);
    assert.equal(relinkTarget('../features/inspecto-ops/src/A.java#L3', file('docs/a.md', 'docs'), c), '../features/inspecto-ops/src/A.java#L3');
    assert.equal(relinkTarget('../docs/b.md', file('features/inspecto-ops/README.md', 'docs'), c), '../../docs/b.md');
    assert.equal(relinkTarget('https://x.y/z', file('docs/a.md', 'docs'), c), null);
    assert.equal(relinkTarget('../missing.md', file('docs/a.md', 'docs'), c), undefined);
});

test('rewriteGitignore / rewriteChildPomRelativePath / rewriteJavaRelativePaths are idempotent and scoped', () => {
    assert.equal(twice(rewriteGitignore, '/inspecto-ops/target/\n!/inspecto-api/x\n/inspecto/target/\n', file('.gitignore', 'gitignore')), '/features/inspecto-ops/target/\n!/platform/inspecto-api/x\n/inspecto/target/\n');
    assert.equal(twice(rewriteChildPomRelativePath, '<relativePath>../pom.xml</relativePath>', file('features/inspecto-ops/pom.xml', 'child-pom')), '<relativePath>../../pom.xml</relativePath>');
    const j = twice(rewriteJavaRelativePaths, 'Path.of("..", "spaces"); "../spaces/x"; "../x"; Path.of("../platform/inspecto-api/pom.xml")', file('features/inspecto-ops/src/test/java/T.java', 'java'));
    assert.equal(j, 'Path.of("..", "..", "spaces"); "../../spaces/x"; "../x"; Path.of("../../platform/inspecto-api/pom.xml")');
    // an unmoved module (inspecto/) keeps its depth, but a sibling MODULE path still gains its group
    assert.equal(twice(rewriteJavaRelativePaths, 'Path.of("..", "spaces"); "../features/inspecto-ops/x"', file('inspecto/src/test/java/T.java', 'java')), 'Path.of("..", "spaces"); "../features/inspecto-ops/x"');
});

test('every Phase B rewrite is a named function (the dry-run table is keyed by name)', () => {
    for (const fn of [...PHASE_A, ...PHASE_B]) assert.ok(fn.name && fn.name !== 'anonymous', 'unnamed rewrite');
});
