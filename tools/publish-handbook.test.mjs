import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import os from 'node:os';
import { mkdirSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { LOCAL, plan } from './publish-handbook.mjs';
import { loadManifest } from './render-handbook.mjs';

const fs = (entries) => ({ exists: (p) => p in entries, list: (p) => entries[p] || [] });
const MIN = 'handbook:\n  title: T\nchapters:\n  start:\n    title: S\n    group: G\n';

test('no places set: both local renders only', () => {
    assert.deepEqual(plan({ external: null, internal: null }, fs({})), [
        { out: LOCAL.external, internal: false }, { out: LOCAL.internal, internal: true }]);
});

test('a set place is rendered too, with its own audience', () => {
    const runs = plan({ external: 'web', internal: 'team' }, fs({}));
    assert.deepEqual(runs.slice(2), [{ out: 'web', internal: false }, { out: 'team', internal: true }]);
});

test('the internal render never goes to the external place', () => {
    assert.throws(() => plan({ external: 'same', internal: 'same' }, fs({})), /must not be or sit inside/);
    assert.throws(() => plan({ external: 'web/', internal: './a/../web' }, fs({})), /must not be or sit inside/);
});

test('nor anywhere inside the external place', () => {
    assert.throws(() => plan({ external: 'web', internal: path.join('web', 'team') }, fs({})), /sit inside/);
    assert.throws(() => plan({ external: null, internal: path.join(LOCAL.external, 'x') }, fs({})), /sit inside/);
    const runs = plan({ external: path.join('team', 'public'), internal: 'team' }, fs({}));
    assert.equal(runs.length, 4);
});

test('a child folder whose name starts with .. is still inside', () => {
    assert.throws(() => plan({ external: 'web', internal: path.join('web', '..team') }, fs({})), /sit inside/);
});

test('a link to the external place in the internal path is followed, even when the leaf does not exist yet', () => {
    const tmp = mkdtempSync(path.join(os.tmpdir(), 'hb-'));
    try {
        mkdirSync(path.join(tmp, 'www'));
        symlinkSync(path.join(tmp, 'www'), path.join(tmp, 'alias'), 'junction');
        assert.throws(() => plan({ external: path.join(tmp, 'www'), internal: path.join(tmp, 'alias', 'team') }), /sit inside/);
    } finally { rmSync(tmp, { recursive: true, force: true }); }
});

test('on Windows a case-only difference is the same place', () => {
    assert.throws(() => plan({ external: 'C:/x/Site', internal: 'c:/x/site' }, { ...fs({}), platform: 'win32' }), /sit inside/);
});

test('a non-empty folder this tool did not write is refused', () => {
    assert.throws(() => plan({ external: 'web', internal: null }, fs({ web: ['index.php'] })), /refused/);
    const ok = plan({ external: 'web', internal: null }, fs({ web: ['index.html', '.handbook'], [path.join('web', '.handbook')]: [] }));
    assert.equal(ok.length, 3);
});

test('the manifest reads `none` as not decided', () => {
    const book = loadManifest(MIN.replace('  title: T\n', '  title: T\n  publish:\n    external: none\n    internal: /srv/team\n'));
    assert.deepEqual(book.publish, { external: null, internal: '/srv/team' });
    assert.deepEqual(loadManifest(MIN).publish, { external: null, internal: null });
});
