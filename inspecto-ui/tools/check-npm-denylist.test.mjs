import { test } from 'node:test';
import assert from 'node:assert/strict';
import { violations } from './check-npm-denylist.mjs';

const lock = (pkgs) => ({ packages: { '': {}, ...Object.fromEntries(pkgs.map(([p, v]) => [p, { version: v }])) } });

test('a denied version is reported, including a nested copy', () => {
    const bad = violations(lock([['node_modules/x/node_modules/@antv/g6-plugins', '1.2.9']]));
    assert.deepEqual(bad.map((b) => `${b.name}@${b.version}`), ['@antv/g6-plugins@1.2.9']);
});

test('a safe version of a denied package, and an unrelated package, pass', () => {
    assert.equal(violations(lock([['node_modules/@antv/g6-plugins', '1.3.0'], ['node_modules/@antv/g6', '5.1.1']])).length, 0);
});
