// Tests for tools/telecom-usage.mjs - PRODUCT-ASSEMBLY-1 PA-5c (D-PA17). Run: node --test tools/telecom-usage.test.mjs
// The end-to-end proof (every App's controls over the composed Space) is TelcoComposedSpaceGoldenTest (Java).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BRIDGE_PLAN, COLUMNS, READERS, applyUsageFeed, convert, parseCsv, pipelineToon, rateCents, tariffRate, usageUnits } from './telecom-usage.mjs';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
const committed = join(repo, 'spaces', '_templates');
const parts = Object.fromEntries(READERS.map((r) => [r.template, join(committed, r.template)]));
const read = (t, rel) => parseCsv(readFileSync(join(committed, t, ...rel.split('/')), 'utf8'), rel);

test('the composed corpus is the union of the parts\' usage samples, deterministic', () => {
    const a = convert(parts);
    assert.deepEqual(convert(parts), a, 'the same samples give the same files');
    const usage = parseCsv(a.files['data/samples/telecom_usage/TELECOM_USAGE_20260703.csv'], 'usage');
    const ra = read('telco-ra', 'data/samples/switch_xdr/SWITCH_XDR_20260703.csv');
    const fm = read('telco-fraud', 'data/samples/cdr/CDR_20260701.csv');
    assert.equal(usage.length, ra.length + fm.length);
    assert.deepEqual(Object.keys(usage[0]), COLUMNS.map(([n]) => n));
    assert.equal(new Set(usage.map((r) => r.record_id)).size, usage.length, 'one record per id');
    const byId = new Map(usage.map((r) => [r.record_id, r]));
    for (const s of ra) {
        const u = byId.get(s.XDR_ID);
        assert.equal(u.a_number, s.SUBSCRIBER_ID);
        assert.equal(u.usage_units, s.USAGE_UNITS);
        assert.equal(u.direction, 'MO', 'RA\'s switch records are originated usage');
        assert.equal(u.partner, s.PARTNER);
    }
    for (const c of fm) {
        const u = byId.get(c.record_id);
        for (const k of Object.keys(c)) assert.equal(u[k], c[k], `${c.record_id}.${k} is FM's own value`);
        assert.equal(u.partner, '', 'FM\'s records are on-net');
    }
    const sorted = [...usage].sort((x, y) => (x.start_ts < y.start_ts ? -1 : x.start_ts > y.start_ts ? 1 : x.record_id < y.record_id ? -1 : 1));
    assert.deepEqual(usage, sorted, 'one feed in time order');
    assert.equal(a.counts.bridged, fm.filter((c) => c.direction === 'MO').length);
});

test('every originated record of FM gets a mediated, rated and billed row RA\'s controls agree with', () => {
    const { files } = convert(parts);
    const fmMo = read('telco-fraud', 'data/samples/cdr/CDR_20260701.csv').filter((c) => c.direction === 'MO');
    const tariff = read('telco-ra', 'data/samples/tariff/TARIFF_20260701.csv');
    const mediated = parseCsv(files['data/samples/mediated_xdr/MEDIATED_XDR_TELECOM_20260703.csv'], 'm');
    const rated = parseCsv(files['data/samples/rated_usage/RATED_USAGE_TELECOM_20260703.csv'], 'r');
    const billed = parseCsv(files['data/samples/billed_invoice/BILLED_INVOICE_TELECOM_202607.csv'], 'b');
    assert.deepEqual(new Set(mediated.map((m) => m.XDR_ID)), new Set(fmMo.map((c) => c.record_id)), 'lost at mediation: none');
    assert.deepEqual(new Set(rated.map((m) => m.XDR_ID)), new Set(fmMo.map((c) => c.record_id)), 'lost at rating: none');
    const sums = new Map();
    for (const r of rated) {
        assert.equal(r.PLAN, BRIDGE_PLAN);
        const m = mediated.find((x) => x.XDR_ID === r.XDR_ID);
        assert.equal(m.USAGE_UNITS, r.USAGE_UNITS, 'switch, mediated and rated units agree');
        // RA's re-rating: ABS(ROUND(units x rate, 2) - CHARGE) within the tolerance, from the ONE tariff row in force.
        const cents = rateCents(Number(r.USAGE_UNITS), tariffRate(tariff, r.PLAN, r.SERVICE, r.EVENT_TS));
        assert.equal(Math.round(Number(r.CHARGE) * 100), cents);
        sums.set(r.SUBSCRIBER_ID, (sums.get(r.SUBSCRIBER_ID) ?? 0) + cents);
    }
    assert.equal(billed.length, sums.size, 'one invoice line per subscriber and month');
    for (const b of billed) {
        assert.equal(b.BILL_PERIOD, '2026-07');
        assert.equal(Math.round(Number(b.CHARGE) * 100), sums.get(b.SUBSCRIBER_ID), `${b.SUBSCRIBER_ID}: billed = rated`);
    }
    const raBilled = new Set(read('telco-ra', 'data/samples/billed_invoice/BILLED_INVOICE_202607.csv').map((b) => b.SUBSCRIBER_ID));
    assert.ok(billed.every((b) => !raBilled.has(b.SUBSCRIBER_ID)), 'FM\'s subscribers are not RA\'s');
});

test('rating rounds half away from zero, units follow the service, a tariff must be unambiguous', () => {
    assert.equal(rateCents(37, '0.0015'), 6);        // 0.0555 -> 0.06
    assert.equal(rateCents(10, '0.0005'), 1);        // 0.005 -> 0.01
    assert.equal(rateCents(9, '0.0005'), 0);         // 0.0045 -> 0.00
    assert.equal(usageUnits('VOICE', '125'), 125);
    assert.equal(usageUnits('SMS', '0'), 1);
    assert.equal(usageUnits('DATA', '3600'), 60);
    assert.equal(usageUnits('DATA', '61'), 2);
    assert.throws(() => usageUnits('FAX', '1'), /not VOICE, SMS or DATA/);
    assert.throws(() => usageUnits('VOICE', '-1'), /whole number/);
    const tariff = read('telco-ra', 'data/samples/tariff/TARIFF_20260701.csv');
    assert.equal(tariffRate(tariff, 'PLAN_B', 'VOICE', '2026-07-01 10:00:00'), '0.0015');
    assert.throws(() => tariffRate(tariff, 'PLAN_B', 'DATA', '2026-07-03 10:00:00'), /2 tariff rows/, 'RA\'s planted duplicate tariff row');
    assert.throws(() => parseCsv('a,b\n"x",y\n', 't'), /quoted/);
    assert.throws(() => parseCsv('a,b\nx\n', 't'), /1 fields/);
});

test('the Pipeline clones every record to each App store present, projected onto its names', () => {
    const toon = pipelineToon(READERS);
    assert.match(toon, /^  mode: clone$/m);
    assert.match(toon, /^sinks\[3\]\{database,format\}:\n  data\/switch_xdr\/database,PARQUET\n  data\/cdr\/database,PARQUET\n  data\/voice_cdr\/database,PARQUET$/m);
    assert.match(toon, /where: "direction = 'MO'"/);
    assert.ok(!toon.split('\n').some((l) => /^\s*#/.test(l)), 'no # line (it would truncate the file)');
    const fmOnly = pipelineToon(READERS.filter((r) => r.template !== 'telco-ra'));
    assert.match(fmOnly, /^sinks\[2\]/m);
    assert.match(fmOnly, /^  default: cdr$/m);
});

test('applyUsageFeed swaps the feeds, refuses a clash and leaves a lone reader alone', () => {
    const dir = mkdtempSync(join(tmpdir(), 'telecom-usage-'));
    try {
        const out = join(dir, 'out');
        for (const t of Object.keys(parts)) cpSync(parts[t], out, { recursive: true, force: false, errorOnExist: false });
        assert.equal(applyUsageFeed(out, { 'telco-ra': parts['telco-ra'] }), null, 'one reader keeps its own feed');
        const r = applyUsageFeed(out, parts);
        assert.deepEqual(r.readers, ['switch_xdr', 'cdr', 'voice_cdr']);
        assert.ok(r.files.includes('config/telecom_usage/telecom_usage_pipeline.toon'));
        assert.throws(() => applyUsageFeed(out, parts), /already ships config\/telecom_usage/);
        const rule = readFileSync(join(out, 'config/registry/decision-rules/tf_long_roaming_call_escalate.toon'), 'utf8');
        const before = readFileSync(join(parts['telco-fraud'], 'config/registry/decision-rules/tf_long_roaming_call_escalate.toon'), 'utf8');
        assert.equal(rule, before.replace(/^target: cdr$/m, 'target: telecom_usage'), 'only the target changes; every line end is kept');
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});

test('a retarget keeps a CRLF line end and a target on the last line', () => {
    const dir = mkdtempSync(join(tmpdir(), 'telecom-usage-'));
    try {
        const out = join(dir, 'out');
        for (const t of Object.keys(parts)) cpSync(parts[t], out, { recursive: true });
        const f = join(out, 'config/registry/decision-rules/tf_long_roaming_call_escalate.toon');
        writeFileSync(f, 'name: x\r\ntargetType: pipeline\r\n\r\ntarget: cdr');
        applyUsageFeed(out, parts);
        assert.equal(readFileSync(f, 'utf8'), 'name: x\r\ntargetType: pipeline\r\n\r\ntarget: telecom_usage');
    } finally {
        rmSync(dir, { recursive: true, force: true });
    }
});
