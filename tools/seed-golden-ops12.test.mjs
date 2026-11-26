// Tests for tools/seed-golden-ops12.mjs — the files phase and the backlog reading; no server.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { FIXTURE, JOB, REFERENCE, STREAM, WAITING_DAY, seedFiles, waitingDayOf } from './seed-golden-ops12.mjs';

test('the Job is a by-day Job over the stream, waiting on a daily Reference the fixture never delivers', () => {
    assert.equal(JOB['schedule.mode'], 'by_day');
    assert.equal(STREAM, 'parcel_scans');
    assert.equal(REFERENCE, 'carrier_rates');
    const ref = readFileSync(join(FIXTURE, 'config/ops12/carrier_rates_pipeline.toon'), 'utf8');
    assert.match(ref, /^produces: reference$/m);
    assert.match(ref, /^ {2}versions: daily$/m);
    assert.match(readFileSync(join(FIXTURE, 'config/ops12/parcel_scans_schema.toon'), 'utf8'), /^partitionKey: SCAN_DATE$/m);
    // never a file for the Reference: no inbox folder for it in the fixture
    assert.equal(existsSync(join(FIXTURE, 'data/inbox/carrier_rates')), false);
    // the stream's one file is for the waiting day
    const csv = readFileSync(join(FIXTURE, 'data/inbox/parcel_scans/PARCEL_SCANS_20261001.csv'), 'utf8').trim().split('\n');
    assert.ok(csv.slice(1).every((l) => l.endsWith(`,${WAITING_DAY}`)));
});

test('the files phase copies the config and the inbox file into a Space folder', () => {
    const space = mkdtempSync(join(tmpdir(), 'ops12-space-'));
    mkdirSync(join(space, 'config'));
    const written = seedFiles(space);
    for (const f of ['config/ops12/parcel_scans_pipeline.toon', 'config/ops12/carrier_rates_pipeline.toon',
        'data/inbox/parcel_scans/PARCEL_SCANS_20261001.csv'])
        assert.ok(written.includes(f) && existsSync(join(space, f)), f);
    assert.throws(() => seedFiles(mkdtempSync(join(tmpdir(), 'not-a-space-'))), /not a Space folder/);
});

test('the waiting day is read from the backlog only when it waits for the Reference', () => {
    const backlog = (status, reason) => ({ data: [{ jobName: 'parcel_rates_job', days: [{ day: WAITING_DAY, status, waitingReason: reason }] }] });
    assert.equal(waitingDayOf(backlog('WAITING', 'waiting for reference carrier_rates (2026-10-01); settling')), WAITING_DAY);
    assert.equal(waitingDayOf(backlog('READY', null)), null);
    assert.equal(waitingDayOf(backlog('WAITING', 'settling (quiet 34s remaining)')), null, 'settling is not the Reference');
    assert.equal(waitingDayOf({ data: [] }), null);
});
