// Negative fixtures for tools/check-openapi-summaries.mjs.
// Run: node --test tools/check-openapi-summaries.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { ALLOWED, SKELETON_SUMMARY, STOCK_DESCRIPTION, problems } from './check-openapi-summaries.mjs';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const GUARD = join(REPO, 'tools', 'check-openapi-summaries.mjs');
const real = () => JSON.parse(readFileSync(join(REPO, 'docs/api/openapi-v1.json'), 'utf8'));
const run = (root) => spawnSync(process.execPath, [GUARD], { encoding: 'utf8', env: { ...process.env, OPENAPI_SUMMARIES_ROOT: root } });

test('the real document is green', () => {
    const r = run(REPO);
    assert.equal(r.status, 0, r.stderr);
});

test('a public operation with a skeleton summary goes red (end to end)', () => {
    const doc = real();
    doc.paths['/jobs'].get.summary = `GET /jobs ${SKELETON_SUMMARY}`;
    const root = mkdtempSync(join(tmpdir(), 'openapi-summaries-'));
    mkdirSync(join(root, 'docs/api'), { recursive: true });
    writeFileSync(join(root, 'docs/api/openapi-v1.json'), JSON.stringify(doc));
    const r = run(root);
    assert.equal(r.status, 1);
    assert.match(r.stderr, /GET \/jobs has no real summary/);
});

test('a real summary over the stock description still goes red', () => {
    const doc = real();
    doc.paths['/jobs'].get.description = `${STOCK_DESCRIPTION}; request and response schemas are not yet documented.`;
    assert.match(problems(doc).out.join('\n'), /GET \/jobs has no real summary/);
});

test('an empty summary goes red', () => {
    const doc = real();
    delete doc.paths['/jobs'].get.summary;
    assert.match(problems(doc).out.join('\n'), /GET \/jobs has no real summary/);
});

test('an allow-listed operation that now has a real summary goes red, so the list only shrinks', () => {
    const doc = real();
    const [key] = ALLOWED.keys();
    const [method, path] = key.split(' ');
    doc.paths[path][method.toLowerCase()].summary = 'Ping';
    doc.paths[path][method.toLowerCase()].description = 'Answers pong.';
    assert.match(problems(doc).out.join('\n'), /is in ALLOWED but is no longer a skeleton/);
});

test('an allow-listed operation whose route is gone goes red', () => {
    const doc = real();
    const [key] = ALLOWED.keys();
    delete doc.paths[key.split(' ')[1]];
    assert.match(problems(doc).out.join('\n'), /is in ALLOWED but is no longer a skeleton \(or is gone\)/);
});
