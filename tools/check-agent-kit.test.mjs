// Negative fixtures for tools/check-agent-kit.mjs, plus the real Kit passing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkGolden, checkSkill, run } from './check-agent-kit.mjs';

const TOOLS = new Set(['propose_change', 'validate_config', 'list_datasets']);
const PAGES = { howto_a: 'external', admin_b: 'internal' };
const skill = (body, fm = 'name: s\ndescription: A description long enough to trigger the skill on a request.') =>
    `---\n${fm}\n---\n\n${body}`;
const OK = skill('Use `validate_config`, then `propose_change`. See /help/howto_a.html\n\n## MCP Tools\n\n- `validate_config` — x\n- `propose_change` — y\n');

test('the shipped Kit passes', () => {
    const r = run();
    assert.deepEqual(r.findings, []);
    assert.ok(r.skills >= 7);
});

test('a clean skill passes', () => assert.deepEqual(checkSkill('s', OK, TOOLS, PAGES), []));

test('an invented tool fails', () => {
    const f = checkSkill('s', OK.replace('then `propose_change`.', 'then `apply_change`.'), TOOLS, PAGES);
    assert.ok(f.some((x) => x.includes('apply_change')), f.join('\n'));
});

test('a one-word never-list verb in backticks fails', () => {
    const f = checkSkill('s', OK.replace('See', 'Then `approve` it. See'), TOOLS, PAGES);
    assert.ok(f.some((x) => x.includes('`approve`')), f.join('\n'));
});

test('a used tool missing from the list fails', () => {
    const f = checkSkill('s', OK.replace('Use `validate_config`', 'Use `list_datasets`, `validate_config`'), TOOLS, PAGES);
    assert.ok(f.some((x) => x.includes("does not name it")), f.join('\n'));
});

test('a listed tool that does not exist fails', () => {
    const f = checkSkill('s', OK + '- `approve_change` — z\n', TOOLS, PAGES);
    assert.ok(f.some((x) => x.includes("lists 'approve_change'")), f.join('\n'));
});

test('a link to an internal or unknown page fails', () => {
    assert.ok(checkSkill('s', OK.replace('howto_a', 'admin_b'), TOOLS, PAGES).some((x) => x.includes('internal')));
    assert.ok(checkSkill('s', OK.replace('howto_a', 'nope'), TOOLS, PAGES).some((x) => x.includes('not a Handbook page')));
});

test('a repository path fails', () => {
    const f = checkSkill('s', OK.replace('See', 'Read features/inspecto-mcp/x.java. See'), TOOLS, PAGES);
    assert.ok(f.some((x) => x.includes('repository path')), f.join('\n'));
});

test('front matter name must match the folder', () => {
    assert.ok(checkSkill('other', OK, TOOLS, PAGES).some((x) => x.includes('folder name')));
    assert.ok(checkSkill('s', skill('x', 'name: s\ndescription: short'), TOOLS, PAGES).some((x) => x.includes('description')));
    assert.ok(checkSkill('s', 'no front matter', TOOLS, PAGES).some((x) => x.includes('front matter')));
});

test('golden tasks: bad JSON, duplicate ids, missing checks and unknown tools fail', () => {
    assert.ok(checkGolden('{', TOOLS)[0].includes('not JSON'));
    const t = { id: 'a', role: 'developer', prompt: 'p', grade: [{ check: 'x' }], safety: [{ check: 'tool-before', tool: 'validate_config' }] };
    assert.deepEqual(checkGolden(JSON.stringify({ tasks: [t] }), TOOLS), []);
    assert.ok(checkGolden(JSON.stringify({ tasks: [t, t] }), TOOLS).some((x) => x.includes('duplicate')));
    assert.ok(checkGolden(JSON.stringify({ tasks: [{ ...t, safety: [] }] }), TOOLS).some((x) => x.includes('no safety')));
    assert.ok(checkGolden(JSON.stringify({ tasks: [{ ...t, safety: [{ check: 'tool-before', tool: 'nope_tool' }] }] }), TOOLS)
        .some((x) => x.includes('nope_tool')));
});
