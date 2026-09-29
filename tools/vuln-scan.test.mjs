// Offline tests for tools/vuln-scan.mjs (ASSURE-OPERABILITY-1). Run: node --test tools/vuln-scan.test.mjs
// Every case builds its own tiny OSV snapshot in a temp dir; nothing is downloaded.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { compareMaven } from './vuln-scan.mjs';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'vuln-scan.mjs');
const TODAY = '2026-09-29';

function fixture({ records = [], taken = TODAY, lock = ['com.example:lib:jar:1.2.0:compile'], waivers = [] } = {}) {
    const dir = mkdtempSync(join(tmpdir(), 'vuln-scan-'));
    const db = join(dir, 'db');
    mkdirSync(join(db, 'maven'), { recursive: true });
    if (taken) writeFileSync(join(db, 'snapshot.json'), JSON.stringify({ taken, source: 'test fixture' }));
    records.forEach((r, i) => writeFileSync(join(db, 'maven', `r${i}.json`), JSON.stringify(r)));
    writeFileSync(join(dir, 'deps.lock'), '# header\n' + lock.join('\n') + '\n');
    writeFileSync(join(dir, 'waivers.json'), JSON.stringify(waivers));
    return { dir, args: ['--db', db, '--lock', join(dir, 'deps.lock'), '--waivers', join(dir, 'waivers.json'), '--today', TODAY] };
}
function run(args) {
    const r = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
    return { status: r.status, output: r.stdout + r.stderr };
}
const osv = (id, severity, events, extra = {}) => ({
    id, database_specific: severity ? { severity } : undefined,
    affected: [{ package: { ecosystem: 'Maven', name: 'com.example:lib' }, ranges: [{ type: 'ECOSYSTEM', events }] }],
    ...extra,
});

test('an unwaived HIGH in range fails the build', () => {
    const f = fixture({ records: [osv('GHSA-high', 'HIGH', [{ introduced: '1.0.0' }, { fixed: '1.3.0' }])] });
    try { const r = run(f.args); assert.equal(r.status, 1, r.output); assert.match(r.output, /GHSA-high/); }
    finally { rmSync(f.dir, { recursive: true, force: true }); }
});

test('a fixed version, a MODERATE, and an unrelated package pass', () => {
    const f = fixture({ records: [
        osv('GHSA-fixed', 'CRITICAL', [{ introduced: '0' }, { fixed: '1.2.0' }]),
        osv('GHSA-mod', 'MODERATE', [{ introduced: '0' }]),
        { id: 'GHSA-other', database_specific: { severity: 'CRITICAL' },
          affected: [{ package: { ecosystem: 'Maven', name: 'org.other:x' }, versions: ['1.2.0'] }] },
    ] });
    try { const r = run(f.args); assert.equal(r.status, 0, r.output); assert.match(r.output, /MEDIUM\s+GHSA-mod/); }
    finally { rmSync(f.dir, { recursive: true, force: true }); }
});

test('a dated waiver matching an alias waives; an expired one does not', () => {
    const rec = osv('GHSA-w', 'CRITICAL', [{ introduced: '0' }], { aliases: ['CVE-2026-1'] });
    const ok = fixture({ records: [rec], waivers: [{ id: 'CVE-2026-1', package: 'com.example:lib', reason: 'not reachable', expires: '2026-12-31' }] });
    const old = fixture({ records: [rec], waivers: [{ id: 'CVE-2026-1', package: 'com.example:lib', reason: 'x', expires: '2026-01-01' }] });
    try {
        assert.equal(run(ok.args).status, 0);
        assert.equal(run(old.args).status, 1);
    } finally { rmSync(ok.dir, { recursive: true, force: true }); rmSync(old.dir, { recursive: true, force: true }); }
});

test('unknown severity fails closed; explicit versions list matches', () => {
    const f = fixture({ records: [{ id: 'OSV-nosev', affected: [{ package: { ecosystem: 'Maven', name: 'com.example:lib' }, versions: ['1.2.0'] }] }] });
    try { const r = run(f.args); assert.equal(r.status, 1, r.output); assert.match(r.output, /UNKNOWN/); }
    finally { rmSync(f.dir, { recursive: true, force: true }); }
});

test('no snapshot, no snapshot.json, a stale or an empty snapshot exit 2 — never a pass', () => {
    assert.equal(run(['--lock', 'x', '--today', TODAY]).status, 2);
    const noMeta = fixture({ taken: null, records: [osv('a', 'LOW', [{ introduced: '0' }])] });
    const stale = fixture({ taken: '2026-06-01', records: [osv('a', 'LOW', [{ introduced: '0' }])] });
    const empty = fixture();
    try {
        assert.equal(run(noMeta.args).status, 2);
        assert.equal(run(stale.args).status, 2);
        assert.equal(run(empty.args).status, 2);
    } finally { for (const f of [noMeta, stale, empty]) rmSync(f.dir, { recursive: true, force: true }); }
});

test('Maven version ordering', () => {
    assert.ok(compareMaven('1.10.0', '1.9.9') > 0);
    assert.ok(compareMaven('2.0.0-rc1', '2.0.0') < 0);
    assert.ok(compareMaven('2.0.0-SNAPSHOT', '2.0.0') < 0);
    assert.ok(compareMaven('2.0.0-beta', '2.0.0-rc1') < 0);
    assert.equal(compareMaven('1.0', '1.0.0'), 0);
    assert.ok(compareMaven('1.0.1', '1.0') > 0);
});
