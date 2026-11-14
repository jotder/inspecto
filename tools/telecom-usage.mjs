// telecom-usage - the `telecom` Industry's ONE usage feed in a composed Space (PRODUCT-ASSEMBLY-1 PA-5c, decision D-PA17;
// plan docs/superpower/2026-11-10-product-assembly-plan.md §11).
//
// Three Telco Apps each ingest the network usage record under their own name: Revenue Assurance as `switch_xdr`
// (telco-ra), Fraud Management as `cdr` (telco-fraud) and SIM-box bypass as `voice_cdr` (telco-bypass). Each template
// keeps its own feed, so it still installs alone. When tools/compose-space.mjs composes a telecom Space from two or more
// of them, `applyUsageFeed` swaps those feeds for ONE canonical Pipeline, `telecom_usage`, whose `route: mode: clone`
// writes every record into each App's store (`data/<store>/database`, the path every Job, Reconciliation, Expectation
// and Dataset already reads), each branch projecting the canonical columns onto that App's names.
//
// Why a fan-out and not views: a `sql.template` Job registers its `sources` as views over the store folder
// (`<dataDir>/<store>/**/*.parquet`, SqlTemplateJob), never over a Dataset or a `config/views` view, so FM's and
// bypass's Jobs can only read a real store.
//
// The composed corpus (`convert`): the union of the parts' usage samples in the canonical shape, plus the rows RA's
// controls need for the other Apps' traffic. RA reads only originated (MO) records; each such record of another App gets
// a mediated row (the same units), a rated row (rated at the tariff of BRIDGE_PLAN, in force at its start time) and,
// per subscriber and month, a billed invoice line equal to the rated sum. So RA's completeness, lost-xDR, re-rating and
// rated-vs-billed controls find nothing new, and settlement sees no partner (the records are on-net). Deterministic:
// the same samples give byte-identical files.

import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const fail = (msg) => { throw new Error(msg); };

export const PIPELINE = 'telecom_usage';
/** The plan every other App's subscriber is rated on in RA's chain: one tariff row per service at any time in July 2026. */
export const BRIDGE_PLAN = 'PLAN_B';

/** The canonical usage record: FM's and bypass's names (two of the three readers), plus RA's usage units and partner. */
export const COLUMNS = [
    ['record_id', 'VARCHAR', 'Usage record identifier, stable across switch, mediation and rating', 'INTERNAL'],
    ['start_ts', 'TIMESTAMP', 'Call, SMS or session start time (one agreed time zone for the whole feed)', 'INTERNAL'],
    ['direction', 'VARCHAR', 'MO (originated by a_number) or MT (terminated on b_number)', 'INTERNAL'],
    ['service', 'VARCHAR', 'VOICE, SMS or DATA (a data session)', 'INTERNAL'],
    ['a_number', 'VARCHAR', 'Calling number; the subscriber who is charged for an MO record', 'MSISDN'],
    ['b_number', 'VARCHAR', 'Called number as dialled (DATA: the APN)', 'MSISDN'],
    ['duration_s', 'INTEGER', 'Answered duration, 0 when unanswered (DATA: session seconds)', 'INTERNAL'],
    ['usage_units', 'BIGINT', 'Charged units: seconds (VOICE), events (SMS) or MB (DATA)', 'INTERNAL'],
    ['charge', 'DOUBLE', 'Charge carried on the record, when the network rated it', 'INTERNAL'],
    ['roaming', 'VARCHAR', 'Y when the a_number was roaming', 'INTERNAL'],
    ['cell_id', 'VARCHAR', 'Serving cell', 'INTERNAL'],
    ['partner', 'VARCHAR', 'Terminating interconnect partner (blank = on-net)', 'INTERNAL'],
    ['answered', 'VARCHAR', 'OPTIONAL. Y or N: whether the attempt was answered', 'INTERNAL'],
    ['release_cause', 'VARCHAR', 'OPTIONAL. Release cause (ITU Q.850 code, 16 = normal clearing)', 'INTERNAL'],
    ['imei', 'VARCHAR', 'OPTIONAL. Device identity of the calling or called SIM', 'PII'],
    ['imsi', 'VARCHAR', 'OPTIONAL. SIM identity of the calling or called SIM', 'IMSI'],
];
const NAMES = COLUMNS.map(([n]) => n);

/**
 * A branch's projection. `* EXCLUDE (<every canonical column>)` keeps what the ingest lane adds to each row (the lineage
 * tag `__src_id` and any other system column the writer reads), then the App's own columns follow.
 */
const project = (cols) => `SELECT * EXCLUDE (${NAMES.join(', ')}), ${cols} FROM input`;

/**
 * The Apps' usage feeds, in branch order: the part template, its Pipeline and store, and the clone branch that writes the
 * store (`where` picks the records, `select` names the columns as the App's own schema does).
 */
export const READERS = [
    {
        template: 'telco-ra', pipeline: 'switch_xdr', where: "direction = 'MO'",
        select: project('record_id AS XDR_ID, start_ts AS EVENT_TS, CAST(start_ts AS DATE) AS EVENT_DATE, a_number AS SUBSCRIBER_ID, '
            + 'b_number AS B_NUMBER, service AS SERVICE, partner AS PARTNER, CAST(usage_units AS BIGINT) AS USAGE_UNITS'),
    },
    {
        template: 'telco-fraud', pipeline: 'cdr', where: 'TRUE',
        select: project('record_id, start_ts, direction, service, a_number, b_number, CAST(duration_s AS INTEGER) AS duration_s, '
            + 'charge, roaming, cell_id'),
    },
    {
        template: 'telco-bypass', pipeline: 'voice_cdr', where: 'TRUE',
        select: project('record_id, start_ts, direction, service, a_number, b_number, CAST(duration_s AS INTEGER) AS duration_s, '
            + 'roaming, cell_id, answered, release_cause, imei, imsi'),
    },
];

// ── CSV (the samples carry no quotes; a quoted field is refused rather than mis-split) ──

export function parseCsv(text, what) {
    const lines = text.replace(/\r\n/g, '\n').split('\n').filter((l) => l !== '');
    if (lines.some((l) => l.includes('"'))) fail(`${what}: a quoted CSV field is not supported here`);
    const header = lines[0].split(',');
    return lines.slice(1).map((l, i) => {
        const v = l.split(',');
        if (v.length !== header.length) fail(`${what} line ${i + 2}: ${v.length} fields, the header has ${header.length}`);
        return Object.fromEntries(header.map((h, j) => [h, v[j]]));
    });
}

const csv = (header, rows) => [header.join(','), ...rows.map((r) => header.map((h) => r[h] ?? '').join(','))].join('\n') + '\n';

/** The rows of every CSV under `<dir>/data/samples/<feed>/`, files in name order; [] when the part ships none. */
function samples(dir, feed) {
    const d = join(dir, 'data', 'samples', feed);
    if (!existsSync(d)) return [];
    return readdirSync(d).filter((f) => f.toLowerCase().endsWith('.csv')).sort()
        .flatMap((f) => parseCsv(readFileSync(join(d, f), 'utf8'), `${feed}/${f}`));
}

// ── rating, in integer arithmetic (DuckDB's ROUND(units * rate, 2) on DECIMALs: half away from zero) ──

/** A decimal string as an integer count of 1e-4 (`"0.0015"` -> 15). */
function tenThousandths(s) {
    const m = /^(\d+)(?:\.(\d{1,4}))?$/.exec(s);
    if (!m) fail(`rate '${s}' is not a decimal with at most four places`);
    return Number(m[1]) * 10000 + Number((m[2] ?? '').padEnd(4, '0'));
}

/** Cents as a `d.dd` string. */
const money = (cents) => `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;

/** Round(units * rate, 2) in cents. */
export function rateCents(units, rate) {
    const v = units * tenThousandths(rate);   // units x 1e-4
    return Math.floor((v + 50) / 100);
}

/** The one tariff row of `plan`/`service` in force at `ts` (EFFECTIVE_FROM <= ts < EFFECTIVE_TO, as RA's re-rating reads it). */
export function tariffRate(tariff, plan, service, ts) {
    const hit = tariff.filter((t) => t.PLAN === plan && t.SERVICE === service && t.EFFECTIVE_FROM <= ts && (t.EFFECTIVE_TO === '' || ts < t.EFFECTIVE_TO));
    if (hit.length !== 1) fail(`${hit.length} tariff rows for ${plan}/${service} at ${ts}; the bridge needs exactly one`);
    return hit[0].RATE_PER_UNIT;
}

/** Units RA charges for a record of another App: seconds, one event, or MB (one a session minute, rounded up). */
export function usageUnits(service, durationS) {
    const d = Number(durationS);
    if (!Number.isInteger(d) || d < 0) fail(`duration_s '${durationS}' is not a whole number of seconds`);
    if (service === 'VOICE') return d;
    if (service === 'SMS') return 1;
    if (service === 'DATA') return Math.ceil(d / 60);
    return fail(`service '${service}' is not VOICE, SMS or DATA`);
}

/**
 * The composed corpus from the part templates' folders (`dirs`: template id -> folder). Returns { files, counts }:
 * `files` maps `data/samples/<feed>/<FILE>.csv` to its text.
 */
export function convert(dirs) {
    const has = (t) => dirs[t] !== undefined;
    const rows = [];

    // RA's switch records: the subscriber originates them; the charge is the one RA's rating gave the record.
    let raRated = [];
    let tariff = [];
    if (has('telco-ra')) {
        raRated = samples(dirs['telco-ra'], 'rated_usage');
        tariff = samples(dirs['telco-ra'], 'tariff');
        const charge = new Map(raRated.map((r) => [r.XDR_ID, r.CHARGE]));
        for (const s of samples(dirs['telco-ra'], 'switch_xdr')) {
            if (s.EVENT_TS.slice(0, 10) !== s.EVENT_DATE) fail(`switch_xdr ${s.XDR_ID}: EVENT_DATE ${s.EVENT_DATE} is not the date of EVENT_TS ${s.EVENT_TS}`);
            rows.push({
                record_id: s.XDR_ID, start_ts: s.EVENT_TS, direction: 'MO', service: s.SERVICE, a_number: s.SUBSCRIBER_ID,
                b_number: s.B_NUMBER, duration_s: s.SERVICE === 'VOICE' ? s.USAGE_UNITS : '0', usage_units: s.USAGE_UNITS,
                charge: charge.get(s.XDR_ID) ?? '', roaming: 'N', partner: s.PARTNER, _app: 'telco-ra',
            });
        }
    }
    // FM's and bypass's call records, as they are, with the units RA would charge.
    for (const [t, feed] of [['telco-fraud', 'cdr'], ['telco-bypass', 'voice_cdr']]) {
        if (!has(t)) continue;
        for (const r of samples(dirs[t], feed)) rows.push({ ...r, usage_units: String(usageUnits(r.service, r.duration_s)), partner: '', _app: t });
    }
    const ids = new Set();
    for (const r of rows) {
        if (ids.has(r.record_id)) fail(`record_id ${r.record_id} is in two parts' samples`);
        ids.add(r.record_id);
    }
    rows.sort((a, b) => (a.start_ts < b.start_ts ? -1 : a.start_ts > b.start_ts ? 1 : a.record_id < b.record_id ? -1 : a.record_id > b.record_id ? 1 : 0));

    const files = {};
    const days = [...new Set(rows.map((r) => r.start_ts.slice(0, 10)))];
    const stamp = (days.at(-1) ?? '').replace(/-/g, '');
    files[`data/samples/${PIPELINE}/TELECOM_USAGE_${stamp}.csv`] = csv(NAMES, rows);

    // RA's chain for every other App's originated record.
    const bridged = has('telco-ra') ? rows.filter((r) => r._app !== 'telco-ra' && r.direction === 'MO') : [];
    if (bridged.length) {
        const mediated = [];
        const rated = [];
        const billed = new Map();   // SUBSCRIBER_ID|BILL_PERIOD -> cents
        for (const r of bridged) {
            const cents = rateCents(Number(r.usage_units), tariffRate(tariff, BRIDGE_PLAN, r.service, r.start_ts));
            const day = r.start_ts.slice(0, 10);
            mediated.push({ XDR_ID: r.record_id, EVENT_DATE: day, SUBSCRIBER_ID: r.a_number, SERVICE: r.service, USAGE_UNITS: r.usage_units });
            rated.push({ XDR_ID: r.record_id, EVENT_TS: r.start_ts, EVENT_DATE: day, SUBSCRIBER_ID: r.a_number, PLAN: BRIDGE_PLAN, SERVICE: r.service, USAGE_UNITS: r.usage_units, CHARGE: money(cents) });
            const k = `${r.a_number}|${day.slice(0, 7)}`;
            billed.set(k, (billed.get(k) ?? 0) + cents);
        }
        const raSubscribers = new Set(raRated.map((r) => r.SUBSCRIBER_ID));
        const invoices = [...billed.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([k, cents], i) => {
            const [sub, period] = k.split('|');
            if (raSubscribers.has(sub)) fail(`subscriber ${sub} is billed by RA's own samples too; one invoice line per subscriber and month`);
            return { INVOICE_ID: `INVTU${String(i + 1).padStart(5, '0')}`, BILL_PERIOD: period, SUBSCRIBER_ID: sub, CHARGE: money(cents) };
        });
        const periods = [...new Set(invoices.map((i) => i.BILL_PERIOD))];
        files[`data/samples/mediated_xdr/MEDIATED_XDR_TELECOM_${stamp}.csv`] = csv(['XDR_ID', 'EVENT_DATE', 'SUBSCRIBER_ID', 'SERVICE', 'USAGE_UNITS'], mediated);
        files[`data/samples/rated_usage/RATED_USAGE_TELECOM_${stamp}.csv`] = csv(['XDR_ID', 'EVENT_TS', 'EVENT_DATE', 'SUBSCRIBER_ID', 'PLAN', 'SERVICE', 'USAGE_UNITS', 'CHARGE'], rated);
        for (const p of periods)
            files[`data/samples/billed_invoice/BILLED_INVOICE_TELECOM_${p.replace('-', '')}.csv`] = csv(['INVOICE_ID', 'BILL_PERIOD', 'SUBSCRIBER_ID', 'CHARGE'], invoices.filter((i) => i.BILL_PERIOD === p));
    }
    const count = (t) => rows.filter((r) => r._app === t).length;
    return { files, counts: { records: rows.length, ...Object.fromEntries(Object.keys(dirs).map((t) => [t, count(t)])), bridged: bridged.length } };
}

// ── the canonical Pipeline ──

const q = (s) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/** The `telecom_usage` Pipeline: one inbox, one clone branch (and sink) per App store present. */
export function pipelineToon(readers) {
    const dirs = [['backup', 'backup'], ['temp', 'temp'], ['errors', 'errors'], ['quarantine', 'quarantine'], ['markers', 'markers'], ['status_dir', 'status'], ['log_dir', 'logs']];
    return [
        `name: ${PIPELINE}`,
        'active: true',
        `description: ${q(`The telecom Industry's one usage feed (PRODUCT-ASSEMBLY-1 PA-5c): every network usage record lands once and is written into each App's own store - ${readers.map((r) => `${r.pipeline} (${r.template})`).join(', ')}.`)}`,
        '',
        'dirs:',
        `  poll:       data/inbox/${PIPELINE}`,
        `  database:   data/${PIPELINE}/database`,
        ...dirs.map(([key, d]) => `  ${`${key}:`.padEnd(12)}data/${PIPELINE}/${d}`),
        '',
        'output:',
        '  format: PARQUET',
        '  compression: snappy',
        '',
        `sinks[${readers.length}]{database,format}:`,
        ...readers.map((r) => `  data/${r.pipeline}/database,PARQUET`),
        '',
        'route:',
        '  mode: clone',
        `  default: ${readers[0].pipeline}`,
        `  branches[${readers.length}]:`,
        ...readers.flatMap((r) => [
            `    - key: ${r.pipeline}`,
            `      where: ${q(r.where)}`,
            `      database: data/${r.pipeline}/database`,
            '      steps[1]:',
            '        - sql:',
            `            sql: ${q(r.select)}`,
        ]),
        '',
        'processing:',
        '  threads: 1',
        '  file_pattern: "glob:**/*.csv"',
        '  duplicate_check:',
        '    enabled: true',
        '    marker_extension: .processed',
        '    retention_days: 30',
        `  schema_file: ${PIPELINE}_schema.toon`,
        '  csv_settings:',
        '    delimiter: ","',
        '    null_padding: true',
        '    date_formats[1]: "%Y-%m-%d"',
        '    timestamp_formats[1]: "%Y-%m-%d %H:%M:%S"',
        '',
    ].join('\n');
}

export function schemaToon() {
    return [
        'raw:',
        '  name: TELECOM_USAGE',
        '  format: CSV',
        `  fields[${COLUMNS.length}]{name,selector,type,description,unit,classification}:`,
        ...COLUMNS.map(([n, t, d, c], i) => `    ${n},"${i}",${t},${q(d)},"${n === 'duration_s' ? 's' : n === 'usage_units' ? 'count' : n === 'charge' ? 'USD' : ''}","${c}"`),
        'mapping:',
        `  canonicalName: ${PIPELINE}`,
        '  rawName: TELECOM_USAGE',
        `  fields[${COLUMNS.length}]:`,
        ...COLUMNS.flatMap(([n]) => [`    - name: ${n}`, `      from: ${n}`, '      fn: keep']),
        '',
    ].join('\n');
}

/**
 * Swap the usage feeds of the parts in `out` (a composed Space folder) for the one canonical feed. `parts` maps each part
 * template id to its staged folder (the samples are read from there). Does nothing when fewer than two parts read usage.
 * Returns null or { readers, files, counts }.
 */
export function applyUsageFeed(out, parts) {
    const readers = READERS.filter((r) => parts[r.template] !== undefined);
    if (readers.length < 2) return null;
    if (existsSync(join(out, 'config', PIPELINE))) fail(`a part already ships config/${PIPELINE}`);
    const dirs = Object.fromEntries(readers.map((r) => [r.template, parts[r.template]]));
    const { files, counts } = convert(dirs);
    // The Apps' own usage Pipelines and samples leave; their stores, seeds and every reader stay.
    for (const r of readers) {
        rmSync(join(out, 'config', r.pipeline), { recursive: true, force: true });
        rmSync(join(out, 'data', 'samples', r.pipeline), { recursive: true, force: true });
    }
    files[`config/${PIPELINE}/${PIPELINE}_pipeline.toon`] = pipelineToon(readers);
    files[`config/${PIPELINE}/${PIPELINE}_schema.toon`] = schemaToon();
    for (const [rel, text] of Object.entries(files)) {
        const to = join(out, ...rel.split('/'));
        if (existsSync(to)) fail(`${rel} is shipped by a part; the usage feed would overwrite it`);
        mkdirSync(dirname(to), { recursive: true });
        writeFileSync(to, text);
    }
    // What names a removed Pipeline follows the canonical one: a Decision Rule on a usage Pipeline's ingest, and a Job
    // reading a usage Pipeline's file arrivals (RA's switch KPI completeness).
    const gone = new Set(readers.map((r) => r.pipeline));
    const retargeted = [];
    const retarget = (rel, re) => {
        const f = join(out, ...rel.split('/'));
        const text = readFileSync(f, 'utf8');
        const next = text.replace(re, (m, key, name) => (gone.has(name) ? `${key}${PIPELINE}` : m));
        if (next !== text) { writeFileSync(f, next); retargeted.push(rel); }
    };
    for (const [folder, re, onlyIf] of [
        // Within one line only ([ \t], never \s): the line end, a following blank line and a CR are kept.
        ['config/registry/decision-rules', /^(target:[ \t]*)([^\s]+)(?=[ \t]*\r?$)/m, /^targetType:[ \t]*pipeline[ \t]*\r?$/m],
        ['config/jobs', /^([ \t]+pipeline:[ \t]*)([^\s]+)(?=[ \t]*\r?$)/m, null],
    ]) {
        const d = join(out, ...folder.split('/'));
        if (!existsSync(d)) continue;
        for (const f of readdirSync(d).filter((x) => x.endsWith('.toon')).sort()) {
            if (onlyIf && !onlyIf.test(readFileSync(join(d, f), 'utf8'))) continue;
            retarget(`${folder}/${f}`, re);
        }
    }
    return { readers: readers.map((r) => r.pipeline), files: Object.keys(files).sort(), retargeted: retargeted.sort(), counts };
}
