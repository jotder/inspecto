// Tests for tools/render-space-links.mjs (LC-7): the census parse reads every link line and refuses one it cannot
// read; the splice refuses a page without its markers.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { parseCensus, renderTable, splice } from './render-space-links.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const census = readFileSync(join(root, 'platform/inspecto-engine/src/main/java/com/gamma/pipeline/LinkCensus.java'), 'utf8');

test('the parse reads the census', () => {
    const links = parseCensus(census);
    assert.ok(links.length >= 40);
    assert.ok(links.some((l) => l.from === 'job' && l.key === 'on_pipeline' && l.type === 'ID_LIST'));
});

const SP = ' boolean storePath() { return fromKind.equals("z") && keyPath.equals("z"); }';
const L = (from) => `link("${from}", "k", "t", Type.ID, REFUSE, REWRITE, LOAD)`;

test('a comment holding )); or link( neither ends the list nor counts', () => {
    const src = `LINKS = List.of(\n  ${L('a')},\n  // )); and link("x", "y", "z", Type.ID, WARN, REPORT)\n  /* ${L('b')}, */\n  ${L('c')});` + SP;
    assert.deepEqual(parseCensus(src).map((l) => l.from), ['a', 'c']);
});

test('two links on one line and one link across lines are both read', () => {
    const src = `LINKS = List.of(\n  ${L('a')}, ${L('b')},\n  link("c", "k",\n       "t", Type.ID, WARN, REPORT));` + SP;
    assert.deepEqual(parseCensus(src).map((l) => l.from), ['a', 'b', 'c']);
});

test('a check the render has no label for fails loudly', () => {
    assert.throws(() => parseCensus('LINKS = List.of(\n  link("a", "k", "t", Type.ID, WARN, REPORT, AUDIT));'), /no label/);
});

test('a link line the parse cannot read fails loudly', () => {
    const broken = 'LINKS = List.of(\n            link("job", "x", Type.ID, REFUSE),\n            link("a", "b", "c", Type.ID, WARN, REPORT));';
    assert.throws(() => parseCensus(broken), /cannot read/);
});

test('a census with no links fails loudly', () => {
    assert.throws(() => parseCensus('LINKS = List.of(\n);'), /read no link/);
});

test('a link with no checks renders as none', () => {
    const t = renderTable([{ from: 'job', key: 'dir', to: 'directory', type: 'PATH', onDelete: 'WARN', onRename: 'REPORT', checks: [] }]);
    assert.match(t, /\| Job \| `dir` \| directory \| path \| warn \| report \| \*\*none\*\* \|/);
});

test('a page without the markers is refused', () => {
    assert.throws(() => splice('# page\nno markers', 'x'), /markers are missing/);
});
