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

test('the parse reads one link per link( line of the census', () => {
    const body = census.slice(census.indexOf('LINKS = List.of('), census.indexOf('));', census.indexOf('LINKS = List.of(')));
    const lines = body.split('\n').filter((l) => l.trim().startsWith('link('));
    const links = parseCensus(census);
    assert.equal(links.length, lines.length);
    assert.ok(links.some((l) => l.from === 'job' && l.key === 'on_pipeline' && l.type === 'ID_LIST'));
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
