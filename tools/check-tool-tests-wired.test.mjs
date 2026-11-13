// Negative fixtures for tools/check-tool-tests-wired.mjs. Run: node --test tools/check-tool-tests-wired.test.mjs
//
// The guard exists because a test file no gate runs proves nothing. These fixtures are what stop THIS guard
// becoming that: each drives the pure `findings()` with a tree it should refuse, and asserts it says so.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findings, testFiles, workflowText } from './check-tool-tests-wired.mjs';

// A tree big enough to clear the emptiness floor, so a fixture tests the rule it means to and not the floor.
const filler = Array.from({ length: 120 }, (_, i) => `tools/filler-${i}.test.mjs`);
const fillerNamed = filler.map((f) => f.slice('tools/'.length)).join('\n');

test('the real repository passes its own guard', () => {
    assert.deepEqual(findings(testFiles(), workflowText()), []);
});

test('RED: a tool test no workflow names, and not in the baseline', () => {
    const out = findings([...filler, 'tools/brand-new.test.mjs'], fillerNamed, new Set());
    assert.equal(out.length, 1);
    assert.match(out[0], /tools\/brand-new\.test\.mjs is named by NO workflow/);
});

test('GREEN: the same file passes once a workflow names it', () => {
    assert.deepEqual(findings([...filler, 'tools/brand-new.test.mjs'], `${fillerNamed}\n  run: node --test tools/brand-new.test.mjs`, new Set()), []);
});

// Rule 3 — the point of the baseline is that it SHRINKS. Without this, wiring a test in and leaving its line
// behind is silent, and the list becomes a permanent exemption nobody revisits.
test('RED: a baseline entry that is wired now must be deleted from the baseline', () => {
    const out = findings([...filler, 'tools/paid-off.test.mjs'], `${fillerNamed}\n  run: node --test tools/paid-off.test.mjs`, new Set(['paid-off.test.mjs']));
    assert.equal(out.length, 1);
    assert.match(out[0], /wired now but is still listed in this guard's BASELINE/);
});

test('RED: a baseline entry naming a file that no longer exists', () => {
    const out = findings(filler, fillerNamed, new Set(['deleted-long-ago.test.mjs']));
    assert.equal(out.length, 1);
    assert.match(out[0], /no longer exists/);
});

test('GREEN: an unwired file that IS in the baseline is debt, not a finding', () => {
    assert.deepEqual(findings([...filler, 'tools/known-debt.test.mjs'], fillerNamed, new Set(['known-debt.test.mjs'])), []);
});

// The floor is the rule this repository keeps needing: a scan that matches nothing must fail, never pass.
// Without it, a renamed directory or a broken glob reads as "every test is wired".
test('RED: a scan that finds almost nothing fails on the emptiness floor rather than passing', () => {
    const out = findings(['tools/only-one.test.mjs'], 'only-one.test.mjs', new Set());
    assert.match(out.join('\n'), /fewer than the floor/);
});
