// Fixture test for tools/render-offerings-matrix.mjs. Run: node --test tools/render-offerings-matrix.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { render, splice, BEGIN, END } from './render-offerings-matrix.mjs';
import { EDITIONS, bundleModules } from './bundle-modules.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const body = render(root);

test('the committed docs/EDITIONS.md block equals the render (the --check contract)', () => {
    const doc = readFileSync(join(root, 'docs/EDITIONS.md'), 'utf8');
    assert.equal(splice(doc, body), doc);
});
test('every optional module bundle-modules.mjs ships appears as a staged cell in its editions', () => {
    for (const e of EDITIONS) for (const m of bundleModules(e)) {
        const id = m.artifactId.replace(/^inspecto-/, '');
        if (id === 'processor') continue;
        const row = body.split('\n').find((l) => l.startsWith(`| \`${id}\` - `));
        assert.ok(row, `no row for ${id}`);
        assert.equal(row.split('|')[5 + EDITIONS.indexOf(e)].trim(), '✅', `${id} in ${e}`);
    }
});
test('a module an edition does not stage renders as absent (kafka connector is not in Personal)', () => {
    const row = body.split('\n').find((l) => l.startsWith('| `connectors-kafka` - '));
    assert.equal(row.split('|')[5].trim(), '—');
});
test('splice rewrites only between the markers, keeps CRLF, and refuses a missing or duplicated marker', () => {
    const doc = `head\r\n${BEGIN}\r\nold\r\n${END}\r\ntail\r\n`;
    assert.equal(splice(doc, 'new'), `head\r\n${BEGIN}\r\nnew\r\n${END}\r\ntail\r\n`);
    assert.throws(() => splice('head\ntail\n', 'x'), /exactly one/);
    assert.throws(() => splice(`${BEGIN}\n${END}\n${BEGIN}\n${END}\n`, 'x'), /exactly one/);
    assert.throws(() => splice(`${END}\n${BEGIN}\n`, 'x'), /exactly one/);
});
test('a stale block is detected: a hand-edited cell differs from the render', () => {
    const doc = readFileSync(join(root, 'docs/EDITIONS.md'), 'utf8');
    const edited = doc.replace('| authMode | none |', '| authMode | oidc |');
    assert.notEqual(edited, doc);
    assert.notEqual(splice(edited, body), edited);
});
