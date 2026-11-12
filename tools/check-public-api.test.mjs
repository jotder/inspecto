// Fixture test for tools/check-public-api.mjs. Run: node --test tools/check-public-api.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { diff, readBaseline, surface, surfaceOf } from './check-public-api.mjs';

const BASE = `package x;

import com.gamma.api.PublicApi;
import java.util.Map;

/** A javadoc naming @PublicApi and a brace { must not confuse the reader. */
@PublicApi(since = "4.0.0")
public interface Hook extends AutoCloseable {
    String id();
    Result run(Map<String, Object> input, final int retries) throws Exception;
    default String label() { if (id().equals("}")) { return "{"; } return id(); }
    private void helper() {}
    String NAME = "hook";
    enum Mode { FAST, SLOW { @Override public String toString() { return "s"; } }; public void x() {} void hidden() {} }
    record Result(String status, long rows) {
        public Result { if (rows < 0) throw new IllegalArgumentException("{"); }
        public static Result ok() { return new Result("ok", 0); }
        private static final java.util.Comparator<Result> ORDER = (a, b) -> { return 0; };
    }
}
`;
const UNMARKED = `package x;
public class Internal {
    public void anything(String s) {}
    @PublicApi public<T> T glued(Class<T> type) { return null; }
    @PublicApi public Internal(int n) {}
    @PublicApi(since = "4.0.0") public static Internal of(String text) { return null; }
    private class Hidden { @PublicApi public void deep() {} }
}
`;
const lines = src => surfaceOf(src, 'X.java');

test('a marked interface records its header, members, nested types and constants - not bodies or private members', () => {
    const got = lines(BASE);
    for (const l of [
        'x.Hook | public interface Hook extends AutoCloseable',
        'x.Hook | String id()',
        'x.Hook | Result run(Map<String, Object>, int) throws Exception',
        'x.Hook | default String label()',
        'x.Hook | String NAME',
        'x.Hook.Mode | enum Mode',
        'x.Hook.Mode | constant FAST',
        'x.Hook.Mode | constant SLOW',
        'x.Hook.Mode | public void x()',
        'x.Hook.Result | record Result(String status, long rows)',
        'x.Hook.Result | public static Result ok()',
    ]) assert.ok(got.includes(l), `missing: ${l}\n${got.join('\n')}`);
    assert.ok(!got.some(l => l.includes('helper') || l.includes('hidden') || l.includes('ORDER') || l.includes('toString')), got.join('\n'));
    assert.equal(got.length, 11, got.join('\n'));
});

test('an unmarked type contributes only its @PublicApi members', () => {
    assert.deepEqual(lines(UNMARKED).sort(), [
        'x.Internal | public Internal(int)',
        'x.Internal | public static Internal of(String)',
        'x.Internal | public<T> T glued(Class<T>)',
        'x.Internal.Hidden | public void deep()',
    ]);
});

test('a source naming @PublicApi only in comments contributes nothing; a mark it cannot read fails loudly', () => {
    assert.deepEqual(lines('package x;\n// see @PublicApi\nclass A {}\n'), []);
    assert.throws(() => lines('package x;\n@PublicApi\n'), /no marked type or member was read/);
});

// The planted-change proofs: each mutation of the base source either moves the surface (RED) or does not (GREEN).
const red = (from, to) => {
    const after = BASE.replace(from, to);
    assert.notEqual(after, BASE, `fixture did not contain ${from}`);
    const d = diff(lines(BASE), lines(after));
    return d.removed.length + d.added.length > 0;
};
test('a changed return type is RED', () => assert.ok(red('String id();', 'Object id();')));
test('a changed parameter type is RED', () => assert.ok(red('final int retries', 'long retries')));
test('a removed method is RED', () => assert.ok(red('    String id();\n', '')));
test('an added method is RED (the baseline must record it)', () => assert.ok(red('String id();', 'String id();\n    int size();')));
test('a changed throws clause is RED', () => assert.ok(red(') throws Exception;', ');')));
test('a removed enum constant is RED', () => assert.ok(red('FAST, ', '')));
test('a renamed record component is RED', () => assert.ok(red('long rows)', 'long count)')));
test('a removed @PublicApi mark is RED', () => assert.ok(red('@PublicApi(since = "4.0.0")\npublic', 'public')));
test('a renamed parameter is GREEN', () => assert.ok(!red('input, final int retries', 'body, final int tries')));
test('a changed default-method body is GREEN', () => assert.ok(!red('return id(); }', 'return "x"; }')));
test('a changed private member is GREEN', () => assert.ok(!red('private void helper() {}', 'private int helper(long l) { return 1; }')));

test('scope: every tracked .java file is read, whatever module or directory holds it', () => {
    const root = mkdtempSync(join(tmpdir(), 'public-api-'));
    try {
        for (const [p, src] of [['odd/place/Hook.java', BASE], ['platform/m/src/test/java/x/Internal.java', UNMARKED]]) {
            mkdirSync(join(root, dirname(p)), { recursive: true });
            writeFileSync(join(root, p), src);
        }
        execFileSync('git', ['init', '-q'], { cwd: root });
        execFileSync('git', ['add', '.'], { cwd: root });
        const got = surface(root);
        assert.ok(got.includes('x.Hook | String id()') && got.includes('x.Internal | public Internal(int)'), got.join('\n'));
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('the committed baseline matches the repo source', () => {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..');
    const d = diff(readBaseline(root), surface(root));
    assert.deepEqual(d, { removed: [], added: [] }, 'run node tools/check-public-api.mjs --update');
});
