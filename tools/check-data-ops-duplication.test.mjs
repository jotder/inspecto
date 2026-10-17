// Fixture tests for tools/check-data-ops-duplication.mjs. Run: node --test tools/check-data-ops-duplication.test.mjs
//
// Every category is proven to FIRE on a tiny fake Java file, every suppression (shared home, comment, exemption) is proven
// to need its exact condition, and the CLI ratchet is driven end to end in a throwaway git repo: it exits 1 when a count
// rises above the baseline or an exemption goes stale, and 0 (asking for a lower baseline) when a count falls.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { counts, lex, methods, ratchet, scan, scanFile } from './check-data-ops-duplication.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const GUARD = join(HERE, 'check-data-ops-duplication.mjs');
const J = (m, name) => `${m}/src/main/java/com/x/${name}.java`;
const first = (s) => s.split('/')[0];
const run = (src, exempt = []) => scan(Object.keys(src), (f) => src[f], first, exempt);

test('lex blanks comments but keeps string literals and line structure', () => {
    const { code, strings } = lex('// read_csv(\n/* q() */ String s = "read_parquet(\'x\')"; char c = \'"\';');
    assert.ok(!code.includes('read_csv'));
    assert.equal(code.split('\n').length, 2);
    assert.deepEqual(strings.map((s) => s.value), ["read_parquet('x')"]);
});

test('methods finds declarations, not control blocks, lambdas or anonymous classes', () => {
    const { code } = lex(`class A {
        @Override public static String q(String ident) throws Exception { if (x) { r(() -> { }); } return ""; }
        Runnable r = new Runnable() { public void run() { for (;;) {} } };
        record R(int a) { }
    }`);
    assert.deepEqual(methods(code).map((m) => m.name), ['q', 'run']);
});

test('a: a private SQL quoting helper is found; delegation is noted; a non-String method is not', () => {
    const f = scanFile(J('m', 'A'), `class A {
        private static String q(String i) { return "\\"" + i.replace("\\"", "\\"\\"") + "\\""; }
        static String lit(String s) { return SqlIdent.sqlStr(s); }
        static int quote(String s) { return 0; }
    }`);
    assert.deepEqual(f.map((x) => [x.category, x.member, x.line, x.note]), [['a', 'q', 2, 'reimplements'], ['a', 'lit', 3, 'delegates to the shared builder']]);
});

test('a: the shared builders are the home, not findings', () => {
    assert.deepEqual(scanFile('platform/inspecto-util/src/main/java/com/gamma/util/SqlIdent.java', 'class S { public static String q(String s) { return s; } }'), []);
});

test('b: a hand-built read_* in a string literal is found with its enclosing method; javadoc mentions and SqlViews are not', () => {
    const body = `class B {
        /** uses read_parquet('x') */
        void load() { st.execute("SELECT * FROM read_parquet(" + p + ")"); Runnable r = () -> run("read_csv_auto('a')"); }
    }`;
    assert.deepEqual(scanFile(J('m', 'B'), body).map((x) => [x.category, x.member, x.line, x.note]),
        [['b', 'load', 3, 'read_parquet('], ['b', 'load', 3, 'read_csv_auto(']]);
    assert.deepEqual(scanFile('platform/inspecto-sql/src/main/java/com/gamma/sql/SqlViews.java', body), []);
});

test('c: statistics, similarity and union-find are found; an ordinary find() and the graph library are not', () => {
    const body = `class C {
        static double median(List<Double> v) { return 0; }
        public static double scaledMad(Collection<Double> v, double m) { return 0; }
        static double jaroWinkler(String a, String b) { return 0; }
        private static String find(Map<String, String> parent, String k) { return k; }
        public Optional<X> find(String id) { return null; }
    }`;
    assert.deepEqual(scanFile(J('m', 'C'), body).map((x) => [x.member, x.note]),
        [['median', ''], ['scaledMad', ''], ['jaroWinkler', ''], ['find', 'union-find']]);
    assert.deepEqual(scanFile('la/inspecto-la-graph/src/main/java/com/x/G.java', body), []);
    const withUnion = scanFile(J('m', 'U'), 'class U { int find(int x) { return x; } void union(int a, int b) { } }');
    assert.deepEqual(withUnion.map((x) => x.member), ['find']);
});

test('d: a JVM switch over countDistinct and avg is found; MeasureCompiler is the home', () => {
    const body = 'class D { double compute(String agg) { switch (agg) { case "countDistinct" -> { return 1; } case "avg" -> { return 2; } } return 0; } }';
    assert.deepEqual(scanFile(J('m', 'D'), body).map((x) => [x.category, x.member]), [['d', 'compute']]);
    assert.deepEqual(scanFile(J('m', 'D2'), 'class D2 { Map m = Map.of("countDistinct", "avg"); }'), []);
    assert.deepEqual(scanFile('platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java', body), []);
});

test('an exemption suppresses only its exact category + file + member, carries its reason, and goes stale when unmatched', () => {
    const src = { [J('m', 'H')]: 'class H { static String esc(Object o) { return ""; } static String q(String s) { return s; } }' };
    const ex = [{ category: 'a', file: J('m', 'H'), member: 'esc', reason: 'HTML' }, { category: 'a', file: J('m', 'Gone'), member: 'q', reason: 'x' }];
    const r = run(src, ex);
    assert.deepEqual(r.findings.map((f) => f.member), ['q']);
    assert.deepEqual(r.exempted.map((f) => [f.member, f.reason]), [['esc', 'HTML']]);
    assert.deepEqual(r.stale.map((e) => e.file), [J('m', 'Gone')]);
    assert.equal(run(src, []).findings.length, 2, 'without the exemption both count');
});

test('every recorded exemption (tools/data-ops-duplication.exemptions.json) names a category, a file, a member and a reason', () => {
    const exempt = JSON.parse(readFileSync(join(HERE, 'data-ops-duplication.exemptions.json'), 'utf8'));
    assert.ok(exempt.length > 0);
    for (const e of exempt) assert.ok(/^[abcd]$/.test(e.category) && e.file.endsWith('.java') && e.member && e.reason.length > 10, JSON.stringify(e));
});

test('ratchet fails on an increase (incl. a new module), passes on a decrease', () => {
    const base = { a: { m: 2 }, b: {}, c: {}, d: {} };
    assert.deepEqual(ratchet({ a: { m: 3 }, b: {}, c: {}, d: {} }, base).rose, ['a/m: 3 > baseline 2']);
    assert.deepEqual(ratchet({ a: { m: 2 }, b: { n: 1 }, c: {}, d: {} }, base).rose, ['b/n: 1 > baseline 0']);
    const down = ratchet({ a: { m: 1 }, b: {}, c: {}, d: {} }, base);
    assert.deepEqual([down.rose, down.fell], [[], ['a/m: 1 < baseline 2']]);
    assert.deepEqual(counts([{ category: 'c', module: 'z' }, { category: 'c', module: 'z' }]), { a: {}, b: {}, c: { z: 2 }, d: {} });
});

test('CLI: report-only exits 0; --ratchet fails on a new copy and passes (asking to lower) when one is removed', () => {
    const root = mkdtempSync(join(tmpdir(), 'dodup-'));
    try {
        const put = (rel, text) => { mkdirSync(dirname(join(root, rel)), { recursive: true }); writeFileSync(join(root, rel), text); };
        const git = (...a) => execFileSync('git', a, { cwd: root, encoding: 'utf8' });
        const cli = (...a) => spawnSync(process.execPath, [GUARD, '--root', root, ...a], { encoding: 'utf8' });
        git('init', '-q');
        put(J('mod', 'A'), 'class A { static String lit(String s) { return s; } static String q(String s) { return s; } }');
        git('add', '-A');
        assert.equal(cli('--baseline-write').status, 0);
        assert.equal(cli('--ratchet').status, 0);

        put(J('mod', 'B'), 'class B { void x() { run("SELECT * FROM read_csv(\'f\')"); } }');
        git('add', '-A');
        const report = cli();
        assert.equal(report.status, 0, 'report mode never fails');
        assert.match(report.stdout, /scope: 2 tracked main Java files/);
        const up = cli('--ratchet');
        assert.equal(up.status, 1);
        assert.match(up.stderr, /RATCHET FAILED[\s\S]*b\/mod: 1 > baseline 0/);

        put(J('mod', 'B'), 'class B { }');
        put(J('mod', 'A'), 'class A { static String q(String s) { return s; } }');
        const down = cli('--ratchet');
        assert.equal(down.status, 0);
        assert.match(down.stdout, /counts FELL — lower the baseline[\s\S]*a\/mod: 1 < baseline 2/);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});
