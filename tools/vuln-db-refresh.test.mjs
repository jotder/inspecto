// Offline tests for tools/vuln-db-refresh.mjs. Run: node --test tools/vuln-db-refresh.test.mjs
// No network: fixtures are tiny zips built here. Mutations (each should turn a named test red) are listed at the bottom.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32, deflateRawSync } from 'node:zlib';
import { check, parseArgs, readZip, refresh, verify } from './vuln-db-refresh.mjs';

const TOOL = join(dirname(fileURLToPath(import.meta.url)), 'vuln-db-refresh.mjs');
const quiet = () => {};

function zip(entries, deflate = false) {      // entries: {name: string}
    const locals = [], central = []; let off = 0;
    for (const [name, text] of Object.entries(entries)) {
        const raw = Buffer.from(text), data = deflate ? deflateRawSync(raw) : raw, nm = Buffer.from(name);
        const l = Buffer.alloc(30); l.writeUInt32LE(0x04034b50, 0); l.writeUInt16LE(deflate ? 8 : 0, 8);
        l.writeUInt32LE(crc32(raw), 14); l.writeUInt32LE(data.length, 18); l.writeUInt32LE(raw.length, 22); l.writeUInt16LE(nm.length, 26);
        const c = Buffer.alloc(46); c.writeUInt32LE(0x02014b50, 0); c.writeUInt16LE(deflate ? 8 : 0, 10);
        c.writeUInt32LE(crc32(raw), 16); c.writeUInt32LE(data.length, 20); c.writeUInt32LE(raw.length, 24); c.writeUInt16LE(nm.length, 28); c.writeUInt32LE(off, 42);
        locals.push(l, nm, data); central.push(c, nm); off += 30 + nm.length + data.length;
    }
    const cd = Buffer.concat(central), e = Buffer.alloc(22);
    e.writeUInt32LE(0x06054b50, 0); e.writeUInt16LE(central.length / 2, 8); e.writeUInt16LE(central.length / 2, 10);
    e.writeUInt32LE(cd.length, 12); e.writeUInt32LE(off, 16);
    return Buffer.concat([...locals, cd, e]);
}
const rec = id => JSON.stringify({ id, affected: [{ package: { ecosystem: 'Maven', name: 'g:a' }, versions: ['1.0'] }] });
function fixture(entries = { 'GHSA-1.json': rec('GHSA-1'), 'GHSA-2.json': rec('GHSA-2') }, deflate = true) {
    const root = mkdtempSync(join(tmpdir(), 'vdr-'));
    mkdirSync(join(root, 'src', 'Maven'), { recursive: true });
    writeFileSync(join(root, 'src', 'Maven', 'all.zip'), zip(entries, deflate));
    return { root, src: join(root, 'src'), db: join(root, 'db') };
}
const opts = (f, extra = {}) => ({ ...parseArgs(['--db', f.db, '--source', f.src, '--today', '2026-10-10'], {}), ...extra });

test('zip reader handles stored and deflated entries', () => {
    for (const d of [false, true]) assert.equal(readZip(zip({ 'a.json': '{"id":"x"}' }, d))[0].data.toString(), '{"id":"x"}');
});

test('refresh from a local source writes the layout vuln-scan reads, with a manifest', async () => {
    const f = fixture(); await refresh(opts(f), quiet);
    const m = JSON.parse(readFileSync(join(f.db, 'snapshot.json'), 'utf8'));
    assert.equal(m.taken, '2026-10-10'); assert.equal(m.files[0].records, 2); assert.match(m.files[0].sha256, /^[0-9a-f]{64}$/);
    assert.ok(existsSync(join(f.db, 'Maven', 'GHSA-1.json'))); assert.ok(existsSync(join(f.db, '_archives', 'Maven.zip')));
    assert.equal(verify(f.db).ok, true);
    // the real scanner accepts it: a matching UNKNOWN-severity record exits 1 (it RAN and matched; 2 would mean could-not-run)
    const lock = join(f.root, 'lock'); writeFileSync(lock, 'g:a:jar:1.0:compile\n');
    const r = spawnSync('node', [join(dirname(TOOL), 'vuln-scan.mjs'), '--db', f.db, '--lock', lock, '--today', '2026-10-10'], { encoding: 'utf8' });
    assert.equal(r.status, 1, r.stderr + r.stdout);
});

test('default run (no --confirm, no --source) is a dry run: prints URL + target, writes nothing, never fetches', async () => {
    const f = fixture(); const out = [];
    const orig = globalThis.fetch; globalThis.fetch = () => { throw new Error('NETWORK TOUCHED'); };
    try { await refresh({ ...parseArgs(['--db', f.db], {}), source: null }, l => out.push(l)); } finally { globalThis.fetch = orig; }
    assert.match(out.join('\n'), /DRY RUN[\s\S]*Maven\/all\.zip[\s\S]*would write/);
    assert.equal(existsSync(f.db), false);
});

test('CLI default is a dry run that exits 0 without --confirm', () => {
    const f = fixture(); const r = spawnSync('node', [TOOL, '--db', f.db], { encoding: 'utf8' });
    assert.equal(r.status, 0); assert.match(r.stdout, /DRY RUN/); assert.equal(existsSync(f.db), false);
});

test('a corrupt archive fails atomically: the previous snapshot is untouched and no temp dir remains', async () => {
    const f = fixture(); await refresh(opts(f), quiet);
    const before = readFileSync(join(f.db, 'snapshot.json'), 'utf8');
    writeFileSync(join(f.src, 'Maven', 'all.zip'), zip({ 'bad.json': '{not json' }));
    await assert.rejects(refresh(opts(f, { today: '2026-10-11' }), quiet));
    assert.equal(readFileSync(join(f.db, 'snapshot.json'), 'utf8'), before);
    assert.deepEqual(readdirSync(f.root).filter(n => n.startsWith('db.')), []);
    writeFileSync(join(f.src, 'Maven', 'all.zip'), Buffer.from('not a zip at all, really not a zip'));
    await assert.rejects(refresh(opts(f), quiet)); assert.equal(verify(f.db).ok, true);
});

test('an archive with zero records is refused', async () => {
    const f = fixture({ 'README.txt': 'x' }); await assert.rejects(refresh(opts(f), quiet), /zero/);
    assert.equal(existsSync(f.db), false);
});

test('verify detects a tampered record, a tampered archive, and a missing manifest', async () => {
    const f = fixture(); await refresh(opts(f), quiet);
    writeFileSync(join(f.db, 'Maven', 'GHSA-1.json'), rec('GHSA-EVIL'));
    assert.match(verify(f.db).problems.join(), /extracted records/);
    await refresh(opts(f), quiet); writeFileSync(join(f.db, '_archives', 'Maven.zip'), 'x');
    assert.match(verify(f.db).problems.join(), /archive sha256/);
    assert.equal(verify(join(f.root, 'nowhere')).ok, false);
});

test('--check: fresh passes; too old, future-dated, and tampered fail', async () => {
    const f = fixture(); await refresh(opts(f), quiet);
    const c = (today, days = 30) => check({ db: f.db, today, maxAgeDays: days }, quiet);
    assert.equal(c('2026-10-10'), 0); assert.equal(c('2026-11-09'), 0);
    assert.equal(c('2026-11-10'), 1); assert.equal(c('2026-10-09'), 1); assert.equal(c('2026-10-20', 5), 1);
    writeFileSync(join(f.db, 'Maven', 'GHSA-2.json'), rec('GHSA-X')); assert.equal(c('2026-10-10'), 1);
    assert.equal(check({ db: undefined, today: '2026-10-10', maxAgeDays: 30 }, quiet), 1);
});

test('--check CLI exit codes 0 / 1 and --source file:// is accepted', () => {
    const f = fixture(); const url = 'file:///' + f.src.replace(/\\/g, '/');
    execFileSync('node', [TOOL, '--db', f.db, '--source', url, '--today', '2026-10-10']);
    assert.equal(spawnSync('node', [TOOL, '--check', '--db', f.db, '--today', '2026-10-12', '--max-age-days', '3']).status, 0);
    assert.equal(spawnSync('node', [TOOL, '--check', '--db', f.db, '--today', '2026-12-12', '--max-age-days', '3']).status, 1);
});

// One-line mutations the operator can apply by hand (each must turn the named test red):
//  1. in refresh(): change `!o.source && !o.confirm` to `false`            -> 'default run ... is a dry run' (the in-process test stubs fetch)
//     ⚠ the CLI dry-run test would then perform a REAL download - run only the first test, never the whole file, with this mutation
//  2. in refresh(): build into `db` instead of `tmp` (and drop the catch cleanup) -> 'corrupt archive fails atomically'
//  3. in verify(): drop the `treeHash(d).sha !== f.treeSha256` clause      -> 'verify detects a tampered record'
//  4. in check(): change `age > o.maxAgeDays` to `age > o.maxAgeDays + 100` -> '--check: ... too old'
//  5. in refresh(): remove the `entries.length === 0` throw                 -> 'zero records is refused'
