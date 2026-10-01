// Fixture test for tools/java-closure.mjs — each blind spot that bit the D-1 closure script must now be SEEN.
// Run: node --test tools/java-closure.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { closure, directDeps, indexClasses, resolveSimple, strip } from './java-closure.mjs';

/** Build a throwaway repo: files = { 'mod/pkg/Name': 'java source' } under mod/src/main/java/. */
function repo(files) {
    const root = mkdtempSync(join(tmpdir(), 'java-closure-'));
    for (const [path, src] of Object.entries(files)) {
        const [mod, ...rest] = path.split('/');
        const file = join(root, mod, 'src', 'main', 'java', ...rest) + '.java';
        mkdirSync(join(file, '..'), { recursive: true });
        writeFileSync(file, src);
    }
    return root;
}
const pkg = (p, body) => `package ${p};\n${body}\n`;

test('a "/*" inside a STRING literal does not open a fake comment that hides real references', () => {
    const root = repo({
        // A LONE "/*" in one string and a LONE "*/" in a later one: a regex stripper pairs them into one fake block
        // comment and swallows the real reference between them (a "**/*.java" glob would NOT show it — "/**/" is
        // a complete empty comment on its own).
        'm1/a/A': pkg('a', `public class A {
            static final String OPEN = "pattern /* open";
            Hidden h = new Hidden();
            static final String CLOSE = "close */";
        }`),
        'm1/a/Hidden': pkg('a', 'public class Hidden {}'),
    });
    try {
        const idx = indexClasses(root);
        assert.deepEqual([...directDeps(idx, 'a.A')], ['a.Hidden']);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('a fully-qualified INLINE reference counts as an edge, with no import', () => {
    const root = repo({
        'm1/a/A': pkg('a', 'public class A { Object o = b.util.Helper.first(null); }'),
        'm2/b/util/Helper': pkg('b.util', 'public class Helper { static Object first(Object x) { return x; } }'),
    });
    try {
        const idx = indexClasses(root);
        assert.deepEqual([...directDeps(idx, 'a.A')], ['b.util.Helper']);
        assert.equal(idx.cls.get('b.util.Helper').module, 'm2');
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('a name that appears only in a comment or a string is NOT an edge', () => {
    const root = repo({
        'm1/a/A': pkg('a', `/** see {@link Ghost} */ public class A {
            // Ghost is mentioned here only
            String s = "Ghost";
        }`),
        'm1/a/Ghost': pkg('a', 'public class Ghost {}'),
    });
    try {
        assert.deepEqual([...directDeps(indexClasses(root), 'a.A')], []);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('imports (plain, static, wildcard, nested type) and same-package names are edges', () => {
    const root = repo({
        'm1/a/A': pkg('a', `import b.B;
            import static c.C.helper;
            import d.*;
            import e.Outer.Inner;
            public class A { D d; Same s; }`),
        'm1/b/B': pkg('b', 'public class B {}'),
        'm1/c/C': pkg('c', 'public class C { public static void helper() {} }'),
        'm1/d/D': pkg('d', 'public class D {}'),
        'm1/d/Unused': pkg('d', 'public class Unused {}'),
        'm1/e/Outer': pkg('e', 'public class Outer { public static class Inner {} }'),
        'm1/a/Same': pkg('a', 'public class Same {}'),
    });
    try {
        const got = [...directDeps(indexClasses(root), 'a.A')].sort();
        assert.deepEqual(got, ['a.Same', 'b.B', 'c.C', 'd.D', 'e.Outer']);
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('the stop set counts a class as reached but does not expand it', () => {
    const root = repo({
        'm1/a/A': pkg('a', 'public class A { Host h; }'),
        'm1/a/Host': pkg('a', 'public class Host { Deep d; }'),
        'm1/a/Deep': pkg('a', 'public class Deep {}'),
    });
    try {
        const idx = indexClasses(root);
        const seeds = resolveSimple(idx, ['A'], 'seeds');
        const stop = new Set(resolveSimple(idx, ['Host'], 'stop'));
        const r = closure(idx, seeds, (d) => stop.has(d));
        assert.deepEqual([...r.classes], ['a.A']);
        assert.deepEqual([...r.stopped], ['a.Host']);
        assert.equal(closure(idx, seeds).classes.size, 3, 'without the stop set the whole chain is pulled in');
    } finally { rmSync(root, { recursive: true, force: true }); }
});

test('strip() keeps code, blanks comments and literal bodies', () => {
    const s = strip('int a; /* c */ String q = "x//y"; char c = \'"\'; // tail\nint b;');
    assert.ok(s.includes('int a;') && s.includes('int b;'));
    assert.ok(!s.includes('tail') && !s.includes('x//y') && !s.includes('/* c */'));
});
