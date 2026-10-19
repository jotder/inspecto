// Fixture test for tools/render-tests-inventory.mjs. Run: node --test tools/render-tests-inventory.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hubs, javaTest, javadoc, toMarkdown, toolTest, toolsMarkdown } from './render-tests-inventory.mjs';

const SRC = {
    'features/ops/src/test/java/a/OpsTest.java': 'package a.b;\nimport x;\n/**\n * Ops store round-trips.\n * More.\n */\n@Tag("x")\nclass OpsTest {}\n',
    'features/ops/src/test/java/a/ControlApiOpsTest.java': 'package a.b;\nfinal class ControlApiOpsTest {}\n',
    'features/ops/src/test/java/a/RouteTest.java': 'package a.c;\nimport java.net.http.HttpClient;\nclass RouteTest { ControlApi api; HttpClient c; }\n',
    'features/ops/src/test/java/a/StoreIT.java': 'package a.b;\n/** Real DB. */\nclass StoreIT {}\n',
    'features/ops/src/test/java/a/Helper.java': 'package a.b;\nclass Helper {}\n',
    'features/other/src/test/java/a/OtherTest.java': 'package o;\nclass OtherTest {}\n',
};
const items = {
    'module/ops': 'id: module.ops\nkind: module\ntitle: Ops\nmodule: ops\n',
    'module/nomanifest': 'id: module.nomanifest\nkind: module\ntitle: N\nmodule: nomanifest\n',
    'module/other': 'id: module.other\nkind: module\ntitle: O\nmodule: notests\n',
};
const manifests = { ops: 'features/ops/src/main/resources/META-INF/inspecto/module.toon', notests: 'features/empty/src/main/resources/META-INF/inspecto/module.toon' };
const build = () => hubs(items, manifests, Object.keys(SRC), (p) => SRC[p]);

test('a module item gets the tests under its manifest\'s module dir only', () => {
    const h = build();
    assert.deepEqual(Object.keys(h), ['module/ops']);
    assert.equal(h['module/ops'].dir, 'features/ops');
    assert.deepEqual(h['module/ops'].rows.map((r) => r.cls), ['ControlApiOpsTest', 'OpsTest', 'StoreIT', 'RouteTest']);
});
test('kind is inferred from name or ControlApi+HttpClient use', () => {
    const kinds = Object.fromEntries(build()['module/ops'].rows.map((r) => [r.cls, r.kind]));
    assert.deepEqual(kinds, { ControlApiOpsTest: 'real-HTTP control-plane test', OpsTest: 'unit', StoreIT: 'IT', RouteTest: 'real-HTTP control-plane test' });
});
test('javadoc: first line of the block directly above the class, annotations allowed', () => {
    assert.equal(javaTest('x/OpsTest.java', SRC['features/ops/src/test/java/a/OpsTest.java']).doc, 'Ops store round-trips.');
    assert.equal(javadoc('/** Field doc. */\nint x;\nclass A {}', 'A'), '');
    assert.equal(javadoc('class A {}', 'A'), '');
});
test('markdown: banner, comma-joined run command, escaped table', () => {
    const t = toMarkdown(build()['module/ops']);
    assert.match(t, /Generated\*\* by `tools\/render-tests-inventory.mjs`/);
    assert.match(t, /mvn -o test -pl features\/ops -Dtest=ControlApiOpsTest,OpsTest/);
    assert.doesNotMatch(t, /-Dtest=\S*\+/);
    assert.match(t, /\| `StoreIT` \| `a.b` \| IT \| `features\/ops\/src\/test\/java\/a\/StoreIT.java` \| Real DB. \|/);
});
test('tool tests: first comment line is the summary', () => {
    const r = toolTest('tools/a.test.mjs', '#!/usr/bin/env node\n// Tests a | b.\nimport x;\n');
    assert.deepEqual(r, { file: 'a.test.mjs', path: 'tools/a.test.mjs', doc: 'Tests a | b.' });
    assert.match(toolsMarkdown([r]), /\| `tools\/a.test.mjs` \| Tests a \\\| b. \|/);
});
