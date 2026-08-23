import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { draftReferenceRows, draftStreamRows } from './onboarding.handler';
import {
    NOTIFICATION_CHANNELS_COLL,
    NOTIFICATION_DELIVERIES_COLL,
    NOTIFICATION_RULES_COLL,
    NOTIFICATIONS_COLL,
    type ChannelDelivery,
    type NotificationChannel,
} from '../notify';
import type { NotificationRule } from '../../api/notifications.service';

/**
 * The demo catch-all mock domain — the port of the old `demo-mock` interceptor: every endpoint not
 * owned by a feature-specific handler (health, status, report, metrics, collectors, pipeline runtime
 * views, notifications, catalog, diagnoses, config specs) so the full UI works offline for
 * stakeholder demos. Notifications now live in the {@link MockStore} (read / read-all / delete
 * genuinely round-trip and survive a reload); the read-only reporting surfaces stay canned,
 * regenerated fresh each app boot. Registered FIRST, matching the old interceptor-chain position.
 */

// The collection constant moved to ../notify (shared with the fan-out helper); re-export for the seeds.
export { NOTIFICATIONS_COLL };

const NOTIFICATION_PREFS_COLL = 'notification-pref';

interface Notification {
    id: string;
    ts: number;
    timestamp: string;
    category: string;
    sourceType: string;
    sourceId: string;
    title: string;
    body: string;
    state: 'UNREAD' | 'READ';
    readAt: number | null;
}

const NOW = Date.now();
export const PIPELINES = ['cdr_ingest', 'subscriber_load', 'voucher_etl', 'billing_daily', 'fraud_events'];

// ── health / status / report ────────────────────────────────────────────────

const READY = { status: 'READY', pipelines: PIPELINES.length };

const PIPELINE_STATUSES = PIPELINES.map((name, i) => ({
    pipeline: name,
    paused: i === 3,
    committedBatches: 120 + i * 37,
    quarantineFiles: i % 3 === 0 ? 2 : 0,
    lastBatchId: `batch-${1000 + i}`,
    lastBatchStatus: i === 4 ? 'FAILED' : 'SUCCESS',
    lastBatchTime: new Date(NOW - i * 3_600_000).toISOString(),
}));

const STATUS_REPORT = {
    generatedAt: new Date(NOW).toISOString(),
    pipelineCount: PIPELINES.length,
    pausedCount: 1,
    totalCommittedBatches: PIPELINE_STATUSES.reduce((s, p) => s + p.committedBatches, 0),
    totalQuarantineFiles: PIPELINE_STATUSES.reduce((s, p) => s + p.quarantineFiles, 0),
    pipelines: PIPELINE_STATUSES,
};

const SERVICE_REPORT = {
    generatedAt: new Date(NOW).toISOString(),
    totalBatches: 847,
    success: 831,
    failed: 16,
    errorRate: 0.019,
    totalOutputRows: 2_847_000,
    p50DurationMs: 1200,
    p95DurationMs: 4800,
    p99DurationMs: 9500,
    windowFrom: new Date(NOW - 7 * 86_400_000).toISOString(),
    windowTo: new Date(NOW).toISOString(),
    pipelines: PIPELINES.map((name, i) => ({
        pipeline: name,
        totalBatches: 150 + i * 20,
        success: 145 + i * 19,
        failed: 5 + (i % 3),
        errorRate: 0.01 + i * 0.005,
        totalOutputRows: 500_000 + i * 100_000,
        p50DurationMs: 800 + i * 200,
        p95DurationMs: 3000 + i * 500,
        p99DurationMs: 7000 + i * 800,
        windowFrom: new Date(NOW - 7 * 86_400_000).toISOString(),
        windowTo: new Date(NOW).toISOString(),
    })),
};

const METRICS_TEXT = `# HELP inspecto_batches_total Total batches processed
# TYPE inspecto_batches_total counter
inspecto_batches_total{pipeline="cdr_ingest"} 257
inspecto_batches_total{pipeline="subscriber_load"} 190
inspecto_batches_total{pipeline="voucher_etl"} 148
inspecto_batches_total{pipeline="billing_daily"} 132
inspecto_batches_total{pipeline="fraud_events"} 120
# HELP inspecto_errors_total Total errors
# TYPE inspecto_errors_total counter
inspecto_errors_total{pipeline="cdr_ingest"} 3
inspecto_errors_total{pipeline="fraud_events"} 8
`;

// ── acquisition metrics ─────────────────────────────────────────────────────

const ACQ_METRICS: Record<string, unknown> = {
    inspecto_files_discovered_total: {
        type: 'counter',
        help: 'Files discovered',
        series: [
            { labels: 'connector="sftp"', value: 1247 },
            { labels: 'connector="s3"', value: 834 },
            { labels: 'connector="local"', value: 423 },
        ],
    },
    inspecto_files_downloaded_total: {
        type: 'counter',
        help: 'Files downloaded',
        series: [
            { labels: 'connector="sftp"', value: 1230 },
            { labels: 'connector="s3"', value: 830 },
            { labels: 'connector="local"', value: 423 },
        ],
    },
    inspecto_downloads_failed_total: {
        type: 'counter',
        help: 'Downloads failed',
        series: [
            { labels: 'connector="sftp"', value: 17 },
            { labels: 'connector="s3"', value: 4 },
        ],
    },
    inspecto_watermark_skipped_total: {
        type: 'counter',
        help: 'Watermark skipped',
        series: [{ labels: 'connector="sftp"', value: 52 }],
    },
    inspecto_bytes_transferred_total: {
        type: 'counter',
        help: 'Bytes transferred',
        series: [
            { labels: 'connector="sftp"', value: 4_812_000_000 },
            { labels: 'connector="s3"', value: 2_100_000_000 },
            { labels: 'connector="local"', value: 890_000_000 },
        ],
    },
    inspecto_active_connections: {
        type: 'gauge',
        help: 'Active connections',
        series: [
            { labels: 'connector="sftp"', value: 3 },
            { labels: 'connector="s3"', value: 2 },
        ],
    },
};

// ── collectors ──────────────────────────────────────────────────────────────

const COLLECTORS = [
    {
        pipeline: 'cdr_ingest',
        id: 'sftp_cdr',
        connector: 'sftp',
        connection: 'prod-sftp',
        includes: ['*.csv'],
        excludes: [],
        recursiveDepth: 1,
        duplicateMode: 'checksum',
        duplicateOnChange: 'reprocess',
        guarantee: 'at-least-once',
        incrementalWatermark: 'last_modified',
        fetchParallel: 4,
        fetchRateLimit: 0,
        postAction: 'archive',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'subscriber_load',
        id: 's3_subscribers',
        connector: 's3',
        connection: 'aws-prod',
        includes: ['subscribers/*.parquet'],
        excludes: [],
        recursiveDepth: 2,
        duplicateMode: 'name',
        duplicateOnChange: 'skip',
        guarantee: 'exactly-once',
        incrementalWatermark: 'last_modified',
        fetchParallel: 8,
        fetchRateLimit: 0,
        postAction: 'none',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'voucher_etl',
        id: 'local_vouchers',
        connector: 'local',
        connection: null,
        includes: ['vouchers/**/*.json'],
        excludes: ['*.tmp'],
        recursiveDepth: 3,
        duplicateMode: 'checksum',
        duplicateOnChange: 'reprocess',
        guarantee: 'at-least-once',
        incrementalWatermark: null,
        fetchParallel: 1,
        fetchRateLimit: 0,
        postAction: 'delete',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'billing_daily',
        id: 'db_billing',
        connector: 'jdbc',
        connection: 'billing-db',
        includes: ['billing_events'],
        excludes: [],
        recursiveDepth: 0,
        duplicateMode: 'watermark',
        duplicateOnChange: 'skip',
        guarantee: 'exactly-once',
        incrementalWatermark: 'event_id',
        fetchParallel: 1,
        fetchRateLimit: 100,
        postAction: 'none',
        dbWatermarkCurrent: '2026-06-28',
    },
    {
        pipeline: 'fraud_events',
        id: 'kafka_fraud',
        connector: 'kafka',
        connection: 'kafka-cluster',
        includes: ['fraud.*'],
        excludes: [],
        recursiveDepth: 0,
        duplicateMode: 'offset',
        duplicateOnChange: 'skip',
        guarantee: 'exactly-once',
        incrementalWatermark: 'offset',
        fetchParallel: 3,
        fetchRateLimit: 0,
        postAction: 'commit',
        dbWatermarkCurrent: 'offset:12847',
    },
    // Case-study pack CS1–CS5 (docs/superpower/pipeline-case-studies.md) — one data source per pipeline.
    {
        pipeline: 'mediation_backbone',
        id: 'sftp_cdr_asn1',
        connector: 'sftp',
        connection: 'cdr_sftp_prod',
        includes: ['**/*.asn1'],
        excludes: ['*.tmp'],
        recursiveDepth: 3,
        duplicateMode: 'checksum',
        duplicateOnChange: 'reprocess',
        guarantee: 'at-least-once',
        incrementalWatermark: 'last_modified',
        fetchParallel: 6,
        fetchRateLimit: 0,
        postAction: 'archive',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'fraud_velocity_stream',
        id: 'kafka_sim_swaps',
        connector: 'kafka',
        connection: 'kafka-cluster',
        includes: ['sim.swaps'],
        excludes: [],
        recursiveDepth: 0,
        duplicateMode: 'offset',
        duplicateOnChange: 'skip',
        guarantee: 'exactly-once',
        incrementalWatermark: 'offset',
        fetchParallel: 2,
        fetchRateLimit: 0,
        postAction: 'commit',
        dbWatermarkCurrent: 'offset:99120',
    },
    {
        pipeline: 'audit_recon_feeds',
        id: 's3_switch_dumps',
        connector: 's3',
        connection: 's3_archive',
        includes: ['switch/**/*.asn1'],
        excludes: [],
        recursiveDepth: 4,
        duplicateMode: 'name',
        duplicateOnChange: 'skip',
        guarantee: 'exactly-once',
        incrementalWatermark: 'last_modified',
        fetchParallel: 8,
        fetchRateLimit: 0,
        postAction: 'none',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'format_gauntlet',
        id: 'local_dropzone',
        connector: 'local',
        connection: null,
        includes: ['drops/**/*'],
        excludes: ['*.partial'],
        recursiveDepth: 5,
        duplicateMode: 'checksum',
        duplicateOnChange: 'reprocess',
        guarantee: 'at-least-once',
        incrementalWatermark: null,
        fetchParallel: 1,
        fetchRateLimit: 0,
        postAction: 'delete',
        dbWatermarkCurrent: null,
    },
    {
        pipeline: 'deadletter_torture',
        id: 'ftp_legacy_gold',
        connector: 'ftp',
        connection: 'legacy_ftp_down',
        includes: ['*.csv'],
        excludes: [],
        recursiveDepth: 1,
        duplicateMode: 'name',
        duplicateOnChange: 'skip',
        guarantee: 'at-least-once',
        incrementalWatermark: 'last_modified',
        fetchParallel: 1,
        fetchRateLimit: 50,
        postAction: 'archive',
        dbWatermarkCurrent: null,
    },
];

// ── pipelines list + detail ─────────────────────────────────────────────────

const PIPELINE_VIEWS = PIPELINES.map((name, i) => ({
    name,
    configPath: `pipelines/${name}.toon`,
    paused: i === 3,
    committedBatches: 120 + i * 37,
}));

/**
 * One pipeline's batch ledger rows exactly as BatchAuditWriter writes them (header order
 * consignment_id…cast_failures; mock-never-more-lenient — `input_files`/`input_rows`/`output_rows`/
 * `rejected_files`/`committed_at` were mock inventions, and `COMMITTED` was never an engine batch
 * status: IngestOutcome is SUCCESS | EMPTY | FAILED). Newest-first — also the alert-evaluation
 * metric source (ops.handler mirrors AlertService's column reads over these rows).
 */
export function batches(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 25 }, (_, i) => {
        const failed = i % 8 === 0;
        const durationMs = 800 + ((i * 137) % 6000);
        const end = NOW - i * 3_600_000;
        return {
            consignment_id: `${pipeline}-b${1000 + i}`,
            pipeline,
            schema_name: pipeline,
            output_table: failed ? '' : pipeline,
            start_time: ledgerTime(end - durationMs),
            end_time: ledgerTime(end),
            status: failed ? 'FAILED' : 'SUCCESS',
            member_count: String(3 + (i % 5)),
            rejected_count: String(failed ? 1 : 0),
            total_input_rows: String(1200 + ((i * 311) % 8000)),
            total_output_rows: String(failed ? 0 : 1100 + ((i * 293) % 7500)),
            output_file_count: String(failed ? 0 : 1 + (i % 3)),
            total_output_bytes: String(failed ? 0 : 250_000 + ((i * 7919) % 400_000)),
            duration_ms: String(durationMs),
            error: failed ? 'COPY failed: could not cast column 3 to BIGINT' : '',
            // -1 = "not measured" is written as a BLANK cell (never 0) — mirror both forms
            cast_failures: i % 5 === 0 ? '' : String(failed ? 2 : 0),
        };
    });
}

/** "yyyy-MM-dd HH:mm:ss" — the status ledger's timestamp format (DuckDbUtil.DT_FMT), NOT ISO. */
function ledgerTime(ms: number): string {
    return new Date(ms).toISOString().slice(0, 19).replace('T', ' ');
}

/**
 * The per-file `_status_` ledger rows exactly as BatchAuditWriter writes them (header order
 * start_time…consignment_id; mock-never-more-lenient — `file_name`/`rows`/`received_at` were mock
 * inventions that hid the real spellings until 2026-08-13). `status` is SUCCESS or one of the three
 * QUARANTINED_* literals MemberAudit produces — there is no per-file FAILED. `error` is empty on
 * success; per-file output_sizes_bytes is a run of zeros in the real writer too.
 */
function files(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 20 }, (_, i) => {
        const start = NOW - i * 1_800_000;
        const quarantined = i % 12 === 0;
        return {
            start_time: ledgerTime(start),
            end_time: ledgerTime(start + 40_000 + ((i * 173) % 9000)),
            filename: `${pipeline}_${20260601 + i}.csv`,
            status: quarantined ? 'QUARANTINED_MISMATCH' : 'SUCCESS',
            parsed_rows: String(quarantined ? 0 : 400 + ((i * 71) % 3000)),
            // The real status ledger carries error_rows per file; every 4th file rejected some rows, so
            // the "View the rejected rows" affordance (which keys off this count) is reachable offline.
            error_rows: String(i % 4 === 0 ? 3 + (i % 7) : 0),
            output_paths: quarantined ? '' : `out/${pipeline}/day=${20260601 + i}/part-0.parquet`,
            output_sizes_bytes: quarantined ? '' : '0',
            duration_ms: String(40_000 + ((i * 173) % 9000)),
            error: quarantined ? 'column count mismatch: expected 12, found 14' : '',
            consignment_id: `${pipeline}-b${1000 + (i % 10)}`,
            // The unpack stage stamps the archive/compressed original a member came out of; blank
            // for a file that arrived as itself, which is most of them.
            origin: i % 5 === 0 ? `${pipeline}_${20260601 + i}.tar.gz` : '',
        };
    });
}

/**
 * `GET /status/problem-files` — the cross-pipeline, file-grain rollup. DERIVED from `files()` above
 * rather than invented, so the mock cannot disagree with the per-pipeline ledger the same offline
 * session shows in Run Detail (mock-never-more-lenient): FULL = a non-SUCCESS status, PARTIAL =
 * SUCCESS with `error_rows > 0`, clean files omitted, newest first, `total` the TRUE count with the
 * summary counts computed PRE-limit exactly as the route does.
 */
function problemFiles(url: string): unknown {
    const q = new URLSearchParams(url.includes('?') ? url.slice(url.indexOf('?') + 1) : '');
    const limit = Math.min(Number(q.get('limit') ?? 1000) || 1000, 5000);
    const since = q.get('since') ?? '';

    const rows: Record<string, unknown>[] = [];
    let fullCount = 0;
    let partialCount = 0;
    const withProblems = new Set<string>();
    for (const pipeline of PIPELINES) {
        for (const f of files(pipeline)) {
            const errorRows = Number(f['error_rows'] ?? '0');
            const full = f['status'] !== 'SUCCESS';
            if (!full && errorRows <= 0) continue;
            if (since && f['end_time'] && f['end_time'] < since) continue;
            if (full) fullCount++;
            else partialCount++;
            withProblems.add(pipeline);
            rows.push({
                pipeline,
                filename: f['filename'],
                verdict: full ? 'FULL' : 'PARTIAL',
                status: f['status'],
                parsedRows: Number(f['parsed_rows'] ?? '-1'),
                errorRows,
                error: f['error'] ?? '',
                consignmentId: f['consignment_id'] ?? '',
                time: f['end_time'] ?? '',
                origin: f['origin'] ?? '',
            });
        }
    }
    rows.sort((a, b) => String(b['time']).localeCompare(String(a['time'])));
    const total = rows.length;
    return {
        rows: rows.slice(0, limit),
        total,
        truncated: total > limit,
        fullCount,
        partialCount,
        warningCount: 0,
        pipelinesWithProblems: withProblems.size,
    };
}

function lineage(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 15 }, (_, i) => ({
        consignment_id: `${pipeline}-b${1000 + i}`,
        source_file: `${pipeline}_${20260601 + i}.csv`,
        output_table: `${pipeline}_output`,
        output_partition: `day=${20260601 + i}`,
        rows_in: String(1200 + ((i * 311) % 5000)),
        rows_out: String(1100 + ((i * 293) % 4500)),
    }));
}

/**
 * Rejected rows for one input file — the real `_errors.csv` columns verbatim
 * (`line_number,column,reason,raw_line`, written by `DuckDbCsvIngester.writeRejects`). Only files
 * whose name marks them bad have detail, so the 404 path stays reachable offline.
 */
function rejectedRows(file: string): Record<string, string>[] {
    if (!/bad|reject|error/i.test(file)) return [];
    const reasons = ['CAST', 'TOO MANY COLUMNS', 'MISSING COLUMNS', 'UNQUOTED VALUE'];
    return Array.from({ length: 6 }, (_, i) => ({
        line_number: String(7 + i * 13),
        column: i % 3 === 0 ? 'AMT' : i % 3 === 1 ? 'EVENT_DATE' : '',
        reason: reasons[i % reasons.length],
        raw_line: `SUB${1000 + i},notanumber,2026-06-0${(i % 9) + 1},extra`,
    }));
}

/**
 * The quarantine listing exactly as FileStatusStore.quarantine synthesizes it off the on-disk
 * layout `<relParent>/<reason>/<filename>`: keys are `file, reason, path, size_bytes` — no
 * consignment_id and no timestamp exist there (the previous mock invented both, which made the
 * Quarantine tab's batch-keyed actions look wired offline when the server can't feed them).
 */
function quarantine(pipeline: string): Record<string, string>[] {
    return Array.from({ length: 4 }, (_, i) => {
        const reason = ['parse_error', 'schema_mismatch', 'empty_file', 'encoding_error'][i % 4];
        const file = `${pipeline}_bad_${i}.csv`;
        return {
            file,
            reason,
            path: `${pipeline}/${reason}/${file}`,
            size_bytes: String(12000 + ((i * 911) % 90000)),
        };
    });
}

const INBOX_STATUSES: Record<string, unknown> = {};
PIPELINES.forEach((name, i) => {
    INBOX_STATUSES[name] = {
        pipeline: name,
        inbox: `inboxes/${name}`,
        pending: i === 0 ? 3 : 0,
        running: i === 0,
        current:
            i === 0
                ? {
                      batchId: `${name}-b1025`,
                      file: `${name}_20260629.csv`,
                      index: 2,
                      total: 3,
                      startedAt: new Date(NOW - 5000).toISOString(),
                  }
                : null,
        step:
            i === 0
                ? {
                      consignmentId: `${name}-b1025`,
                      step: 'transform',
                      index: 2,
                      total: 4,
                      startedAt: new Date(NOW - 5000).toISOString(),
                  }
                : null,
    };
});

// ── notifications (seed pack — applied per space via seeds/default-space.seed) ──

const NOTIFICATION_PREFS = [
    {
        category: 'PIPELINE',
        label: 'Pipeline',
        critical: false,
        available: true,
        channels: { inApp: true, email: false },
    },
    { category: 'JOB', label: 'Job', critical: false, available: true, channels: { inApp: true, email: false } },
    { category: 'OPS', label: 'Operations', critical: false, available: true, channels: { inApp: true, email: false } },
    {
        category: 'COLLABORATION',
        label: 'Collaboration',
        critical: false,
        available: false,
        channels: { inApp: false, email: false },
    },
    {
        category: 'SECURITY',
        label: 'Security',
        critical: true,
        available: true,
        channels: { inApp: true, email: true },
    },
];

// ── catalog ─────────────────────────────────────────────────────────────────

const CATALOG_TABLES = [
    {
        id: 'cdr_output',
        kind: 'TABLE',
        label: 'cdr_output',
        description: { text: 'CDR records after parsing and enrichment', source: 'schema' },
        overlay: { lastSeen: new Date(NOW - 3_600_000).toISOString(), rowCount: 847_000, freshness: 'FRESH' },
    },
    {
        id: 'subscriber_master',
        kind: 'TABLE',
        label: 'subscriber_master',
        description: { text: 'Subscriber master dimension table', source: 'schema' },
        overlay: { lastSeen: new Date(NOW - 7_200_000).toISOString(), rowCount: 125_000, freshness: 'FRESH' },
    },
    {
        id: 'voucher_transactions',
        kind: 'TABLE',
        label: 'voucher_transactions',
        description: { text: 'Voucher recharge transactions', source: 'schema' },
        overlay: { lastSeen: new Date(NOW - 14_400_000).toISOString(), rowCount: 340_000, freshness: 'FRESH' },
    },
    {
        id: 'billing_events',
        kind: 'TABLE',
        label: 'billing_events',
        description: { text: 'Daily billing event feed', source: 'schema' },
        overlay: { lastSeen: new Date(NOW - 86_400_000).toISOString(), rowCount: 1_200_000, freshness: 'STALE' },
    },
    {
        id: 'fraud_scores',
        kind: 'TABLE',
        label: 'fraud_scores',
        description: { text: 'Real-time fraud scoring output', source: 'schema' },
        overlay: { lastSeen: new Date(NOW - 600_000).toISOString(), rowCount: 95_000, freshness: 'FRESH' },
    },
];

// A Stream is the Catalog's data-origin lens over a Collector (+ its Connection) — same identity, catalog view.
const PIPELINE_OUTPUT_TABLE: Record<string, string> = {
    cdr_ingest: 'cdr_output',
    subscriber_load: 'subscriber_master',
    voucher_etl: 'voucher_transactions',
    billing_daily: 'billing_events',
    fraud_events: 'fraud_scores',
};

const CATALOG_STREAMS = COLLECTORS.map((s) => ({
    id: s.id,
    kind: 'STREAM',
    label: s.id,
    description: { text: `${s.connector} collector feeding ${s.pipeline}`, source: 'source' },
    attrs: { connector: s.connector, connection: s.connection, pipeline: s.pipeline },
}));

// References are the Catalog's dimension-origin lens (REFERENCE_DATASET nodes) — lookup/master tables
// joined into pipelines, browsed by name alongside Streams.
const CATALOG_REFERENCES = [
    {
        id: 'country_codes',
        connector: 'local',
        connection: null,
        pipeline: 'subscriber_load',
        text: 'ISO country + dialing-code reference',
    },
    {
        id: 'currency_rates',
        connector: 'jdbc',
        connection: 'billing-db',
        pipeline: 'billing_daily',
        text: 'Daily FX rate reference',
    },
    {
        id: 'imsi_ranges',
        connector: 's3',
        connection: 'aws-prod',
        pipeline: 'cdr_ingest',
        text: 'IMSI operator-range dimension',
    },
    {
        id: 'fraud_watchlist',
        connector: 'local',
        connection: null,
        pipeline: 'fraud_events',
        text: 'Known-fraud subscriber watchlist',
    },
].map((r) => ({
    id: r.id,
    kind: 'REFERENCE_DATASET',
    label: r.id,
    description: { text: r.text, source: 'reference' },
    attrs: { connector: r.connector, connection: r.connection, pipeline: r.pipeline },
}));

const CATALOG_EDGES = CATALOG_STREAMS.filter((s) => PIPELINE_OUTPUT_TABLE[s.attrs.pipeline]).map((s) => ({
    from: s.id,
    to: PIPELINE_OUTPUT_TABLE[s.attrs.pipeline],
    kind: 'EMITS',
}));

const CATALOG_KPIS = {
    domain: 'telecom',
    kpis: [
        {
            id: 'arpu',
            name: 'ARPU',
            definition: 'Average Revenue Per User = total_revenue / active_subscribers',
            grain: 'monthly',
            joinKeys: ['subscriber_id'],
            inputs: ['billing_events', 'subscriber_master'],
        },
        {
            id: 'churn_rate',
            name: 'Churn Rate',
            definition: 'Subscribers lost / total subscribers in period',
            grain: 'monthly',
            joinKeys: ['subscriber_id'],
            inputs: ['subscriber_master'],
        },
        {
            id: 'fraud_rate',
            name: 'Fraud Detection Rate',
            definition: 'Flagged transactions / total transactions',
            grain: 'daily',
            joinKeys: ['transaction_id'],
            inputs: ['fraud_scores', 'cdr_output'],
        },
        {
            id: 'recharge_volume',
            name: 'Recharge Volume',
            definition: 'Sum of voucher recharge amounts',
            grain: 'daily',
            joinKeys: ['subscriber_id'],
            inputs: ['voucher_transactions'],
        },
    ],
};

// ── diagnoses ───────────────────────────────────────────────────────────────

const DIAGNOSES = Array.from({ length: 10 }, (_, i) => ({
    batchId: `${PIPELINES[i % PIPELINES.length]}-b${990 + i}`,
    pipeline: PIPELINES[i % PIPELINES.length],
    severity: ['INFO', 'WARNING', 'CRITICAL'][i % 3],
    rootCause: [
        'Schema mismatch: expected 12 columns, found 11 — likely a truncated export.',
        'Encoding error: file contains non-UTF-8 bytes at offset 4821.',
        'Parse failure: malformed CSV at row 347 — unescaped delimiter in quoted field.',
        'Empty file received — upstream export may have failed silently.',
        'Timestamp column "event_ts" contains future dates (>24h ahead), likely timezone misconfiguration.',
    ][i % 5],
    suggestedAlertRuleToon:
        i % 3 === 2
            ? 'alert.schema_mismatch { metric: "rejected_files", comparator: "gt", threshold: 0, window: "1h" }'
            : null,
    heuristicOnly: i % 4 === 0,
    epochMillis: NOW - i * 7_200_000,
    citations: [{ source: 'batch-audit', ref: `${PIPELINES[i % PIPELINES.length]}-b${990 + i}` }],
}));

// ── config specs ────────────────────────────────────────────────────────────

// Field shape mirrors the backend `com.gamma.config.spec.FieldSpec` record (label/enumValues/
// defaultValue — see the UI FieldSpec model), so the offline pane renders like the live one.
const CONFIG_SPECS: Record<string, unknown> = {
    pipeline: {
        type: 'pipeline',
        fields: [
            {
                path: 'pipeline',
                label: 'Pipeline',
                type: 'STRING',
                required: true,
                description: 'Pipeline name (unique identifier)',
            },
            {
                path: 'collector.connector',
                label: 'Connector',
                type: 'ENUM',
                required: true,
                description: 'Collector connector type',
                enumValues: ['sftp', 's3', 'local', 'jdbc', 'kafka'],
            },
            {
                path: 'collector.connection',
                label: 'Connection',
                type: 'STRING',
                required: false,
                description: 'Connection profile reference',
            },
            {
                path: 'collector.includes',
                label: 'Includes',
                type: 'LIST',
                required: true,
                description: 'File include globs',
            },
            {
                path: 'collector.excludes',
                label: 'Excludes',
                type: 'LIST',
                required: false,
                description: 'File exclude globs',
            },
            {
                path: 'parser.format',
                label: 'Format',
                type: 'ENUM',
                required: true,
                description: 'Input format',
                enumValues: ['csv', 'json', 'parquet', 'avro', 'xml', 'fixed', 'asn1', 'edi', 'custom'],
            },
            {
                path: 'parser.delimiter',
                label: 'Delimiter',
                type: 'STRING',
                required: false,
                description: 'Field delimiter (CSV)',
            },
            {
                path: 'parser.header',
                label: 'Header row',
                type: 'BOOL',
                required: false,
                description: 'First row is header',
                defaultValue: true,
            },
            {
                path: 'output.format',
                label: 'Output format',
                type: 'ENUM',
                required: true,
                description: 'Output format',
                enumValues: ['parquet', 'csv', 'json'],
            },
            {
                path: 'output.partitionBy',
                label: 'Partition by',
                type: 'LIST',
                required: false,
                description: 'Partition columns',
            },
            {
                path: 'batch.maxFiles',
                label: 'Max files',
                type: 'INT',
                required: false,
                description: 'Max files per batch',
                defaultValue: 100,
            },
        ],
        rules: [
            {
                description: 'JDBC connector requires a connection profile',
                affectedFields: ['collector.connector', 'collector.connection'],
                condition: 'collector.connector == "jdbc" => collector.connection != null',
            },
        ],
    },
};

// ── route matching & dispatch ───────────────────────────────────────────────

const HEALTH = /\/health$/;
const READY_RE = /\/ready$/;
const STATUS = /\/status$/;
// ⚠ Must be tested BEFORE STATUS: /status/problem-files does not end in /status, but keeping the
// pair adjacent is what stops a future `/status/...` route from being shadowed by a loose regex.
const PROBLEM_FILES = /\/status\/problem-files(\?|$)/;
const REPORT = /\/report$/;
const METRICS = /\/metrics$/;
const METRICS_ACQ = /\/metrics\/acquisition$/;
const COLLECTORS_RE = /\/collectors$/;
const PIPELINES_LIST = /\/runs$/;
const PIPELINE_BATCHES = /\/runs\/([^/]+)\/batches$/;
const PIPELINE_FILES = /\/runs\/([^/]+)\/files$/;
const PIPELINE_LINEAGE = /\/runs\/([^/]+)\/lineage$/;
const PIPELINE_QUARANTINE = /\/runs\/([^/]+)\/quarantine$/;
const PIPELINE_PENDING = /\/runs\/([^/]+)\/pending$/;
const PIPELINE_ERRORS = /\/runs\/([^/]+)\/errors(\?|$)/;
const PIPELINE_REPORT = /\/runs\/([^/]+)\/report$/;
const PIPELINE_COMMITS = /\/runs\/([^/]+)\/commits$/;
const PIPELINE_TRIGGER = /\/runs\/([^/]+)\/trigger$/;
const PIPELINE_PAUSE = /\/runs\/([^/]+)\/pause$/;
const PIPELINE_RESUME = /\/runs\/([^/]+)\/resume$/;
const PIPELINE_RUN_BY_ID = /\/runs\/runs\/([^/]+)$/;
const NOTIF_LIST = /\/notifications$/;
const NOTIF_UNREAD = /\/notifications\/unread-count$/;
const NOTIF_STREAM = /\/notifications\/stream$/;
const NOTIF_READ_ALL = /\/notifications\/read-all$/;
const NOTIF_READ = /\/notifications\/([^/]+)\/read$/;
const NOTIF_DELETE = /\/notifications\/([^/]+)$/;
const NOTIF_PREFS = /\/notifications\/preferences$/;
const NOTIF_CHANNELS = /\/notifications\/channels$/;
const NOTIF_CHANNEL_ONE = /\/notifications\/channels\/([^/]+)$/;
const NOTIF_RULES = /\/notifications\/rules$/;
const NOTIF_RULE_ONE = /\/notifications\/rules\/([^/]+)$/;
const NOTIF_DELIVERIES = /\/notifications\/deliveries$/;
const CATALOG_TABLES_RE = /\/catalog$/;
const CATALOG_KPIS_RE = /\/catalog\/kpis$/;
const CATALOG_STREAMS_RE = /\/catalog\/streams$/;
const CATALOG_REFERENCES_RE = /\/catalog\/references$/;
const CATALOG_RESOLVE = /\/catalog\/resolve(\?|$)/;
const CATALOG_NODE = /\/catalog\/tables\/([^/]+)$/;
const CATALOG_GRAPH = /\/catalog\/graph$/;
const DIAGNOSES_RE = /\/diagnoses$/;
const CONFIG_SPEC = /\/config\/spec\/([^/]+)$/;
const VALIDATE = /\/validate$/;

export function demoHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockDemo) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        // ── health / status / report ──
        if (method === 'GET' && HEALTH.test(url)) return json({ status: 'UP' });
        if (method === 'GET' && READY_RE.test(url)) return json(READY);
        if (method === 'GET' && PROBLEM_FILES.test(url)) return json(problemFiles(url));
        if (method === 'GET' && STATUS.test(url)) return json(STATUS_REPORT);
        if (method === 'GET' && REPORT.test(url)) return json(SERVICE_REPORT);
        if (method === 'GET' && METRICS.test(url) && !METRICS_ACQ.test(url)) return json(METRICS_TEXT);
        if (method === 'GET' && METRICS_ACQ.test(url)) return json(ACQ_METRICS);

        // ── collectors ──
        if (method === 'GET' && COLLECTORS_RE.test(url)) return json(COLLECTORS);

        // ── pipelines ──
        if (method === 'GET' && PIPELINES_LIST.test(url)) return json(PIPELINE_VIEWS);
        if (method === 'GET' && (m = match(url, PIPELINE_BATCHES))) return json(batches(m[1]));
        if (method === 'GET' && (m = match(url, PIPELINE_FILES))) return json(files(m[1]));
        if (method === 'GET' && (m = match(url, PIPELINE_LINEAGE))) return json(lineage(m[1]));
        if (method === 'GET' && (m = match(url, PIPELINE_QUARANTINE))) return json(quarantine(m[1]));
        // Rejected-row detail. Mirrors RunRoutes.rejectedRows' gates exactly (mock-never-more-lenient):
        // missing ?file= → 400, a path-ish name → 403 (never resolved), nothing recorded → 404.
        if (method === 'GET' && (m = match(url, PIPELINE_ERRORS))) {
            const pipeline = m[1];
            const file = (req.params?.['file'] ?? '').trim();
            if (!file) return error(400, "?file= is required (the input file's name)");
            if (file.includes('/') || file.includes('\\') || file.includes('..'))
                return error(403, '?file= must be a bare file name, not a path');
            const rows = rejectedRows(file);
            if (!rows.length) return error(404, `no rejected-row detail recorded for '${file}'`);
            return json({
                pipeline,
                file,
                errorsFile: `${file.replace(/\.(csv|gz|zip|txt)$/i, '')}_errors.csv`,
                rowCount: rows.length,
                truncated: false,
                rows,
            });
        }
        if (method === 'GET' && (m = match(url, PIPELINE_PENDING))) {
            const name = m[1];
            return json(
                INBOX_STATUSES[name] ?? { pipeline: name, inbox: `inboxes/${name}`, pending: 0, running: false },
            );
        }
        if (method === 'GET' && (m = match(url, PIPELINE_REPORT))) {
            const name = m[1];
            return json(SERVICE_REPORT.pipelines.find((p) => p.pipeline === name) ?? SERVICE_REPORT.pipelines[0]);
        }
        if (method === 'GET' && match(url, PIPELINE_COMMITS)) return json(['batch-1000', 'batch-1001', 'batch-1002']);
        // v1 async contract (W5b): trigger answers 202 + runId (no executor in the mock); the poll returns a
        // terminal SUCCESS run. Poll route ahead of the trigger match — `/runs/runs/{id}` is single-segment.
        if (method === 'GET' && (m = match(url, PIPELINE_RUN_BY_ID))) {
            return json({ runId: m[1], status: 'SUCCESS', total: 3, failed: 0 });
        }
        if (method === 'POST' && (m = match(url, PIPELINE_TRIGGER))) {
            return json({ runId: `run-${Date.now()}-${m[1]}`, pipeline: m[1], status: 'running' }, 202);
        }
        if (method === 'POST' && (m = match(url, PIPELINE_PAUSE))) return json({ pipeline: m[1], paused: true });
        if (method === 'POST' && (m = match(url, PIPELINE_RESUME))) return json({ pipeline: m[1], paused: false });

        // ── notifications (store-backed) ──
        if (method === 'GET' && NOTIF_STREAM.test(url)) return undefined; // SSE — let it pass (no server = silent fail)
        if (method === 'GET' && NOTIF_UNREAD.test(url)) {
            return json({ count: allNotifications(store, space).filter((n) => n.state === 'UNREAD').length });
        }
        if (method === 'GET' && NOTIF_LIST.test(url)) return json(allNotifications(store, space));
        if (method === 'POST' && NOTIF_READ_ALL.test(url)) {
            const all = allNotifications(store, space);
            for (const n of all.filter((x) => x.state === 'UNREAD')) {
                store.put(space, NOTIFICATIONS_COLL, n.id, { ...n, state: 'READ', readAt: Date.now() });
            }
            return json({ updated: all.length });
        }
        if (method === 'POST' && (m = match(url, NOTIF_READ))) {
            const n = store.get<Notification>(space, NOTIFICATIONS_COLL, m[1]);
            if (n) store.put(space, NOTIFICATIONS_COLL, n.id, { ...n, state: 'READ', readAt: Date.now() });
            return json({ ok: true });
        }
        if (method === 'DELETE' && (m = match(url, NOTIF_DELETE))) {
            store.delete(space, NOTIFICATIONS_COLL, m[1]);
            return json({ ok: true });
        }
        // Preferences persist per space; the static grid is only the never-saved default.
        if (method === 'GET' && NOTIF_PREFS.test(url)) {
            const saved = store.get<{ rows: unknown[] }>(space, NOTIFICATION_PREFS_COLL, 'grid');
            return json(saved?.rows ?? NOTIFICATION_PREFS);
        }
        if (method === 'PUT' && NOTIF_PREFS.test(url)) {
            const rows = (req.body as { preferences?: unknown[] })?.preferences ?? [];
            store.put(space, NOTIFICATION_PREFS_COLL, 'grid', { rows });
            return json(rows);
        }
        // Channels (C4) — CRUD over the store; deliveries are appended by notify.fanOut.
        if (method === 'GET' && NOTIF_CHANNELS.test(url)) {
            return json(store.list<NotificationChannel>(space, NOTIFICATION_CHANNELS_COLL));
        }
        if (method === 'POST' && NOTIF_CHANNELS.test(url)) {
            const b = (req.body ?? {}) as Partial<NotificationChannel>;
            if (!b.id || !b.kind || !b.target) return error(422, 'id, kind and target are required');
            if (store.get(space, NOTIFICATION_CHANNELS_COLL, b.id)) return error(409, `channel ${b.id} already exists`);
            const ch: NotificationChannel = {
                id: b.id,
                kind: b.kind,
                target: b.target,
                description: b.description,
                enabled: b.enabled !== false,
                createdAt: Date.now(),
            };
            return json(store.put(space, NOTIFICATION_CHANNELS_COLL, ch.id, ch));
        }
        if (method === 'PUT' && (m = match(url, NOTIF_CHANNEL_ONE))) {
            const existing = store.get<NotificationChannel>(space, NOTIFICATION_CHANNELS_COLL, m[1]);
            if (!existing) return error(404, `channel ${m[1]} not found`);
            const b = (req.body ?? {}) as Partial<NotificationChannel>;
            const next = { ...existing, ...b, id: existing.id, createdAt: existing.createdAt };
            return json(store.put(space, NOTIFICATION_CHANNELS_COLL, next.id, next));
        }
        if (method === 'DELETE' && (m = match(url, NOTIF_CHANNEL_ONE))) {
            store.delete(space, NOTIFICATION_CHANNELS_COLL, m[1]);
            return json({ deleted: m[1] });
        }
        // Authored notification rules — CRUD over the store, mirroring channels (and the real backend's
        // gates: 422 missing fields, 409 duplicate, 404 unknown; PUT is a full replace, id from the path).
        if (method === 'GET' && NOTIF_RULES.test(url)) {
            return json(store.list<NotificationRule>(space, NOTIFICATION_RULES_COLL));
        }
        if (method === 'POST' && NOTIF_RULES.test(url)) {
            const b = (req.body ?? {}) as Partial<NotificationRule>;
            if (!b.id || !b.eventType || !b.category) return error(422, 'id, eventType and category are required');
            if (store.get(space, NOTIFICATION_RULES_COLL, b.id)) return error(409, `rule ${b.id} already exists`);
            const rule: NotificationRule = {
                id: b.id,
                eventType: b.eventType,
                minLevel: b.minLevel ?? null,
                category: b.category,
                titleTemplate: b.titleTemplate,
                bodyTemplate: b.bodyTemplate,
                dedupeKeyTemplate: b.dedupeKeyTemplate,
                enabled: b.enabled !== false,
            };
            return json(store.put(space, NOTIFICATION_RULES_COLL, rule.id, rule));
        }
        if (method === 'PUT' && (m = match(url, NOTIF_RULE_ONE))) {
            if (!store.get(space, NOTIFICATION_RULES_COLL, m[1])) return error(404, `rule ${m[1]} not found`);
            const b = (req.body ?? {}) as Partial<NotificationRule>;
            if (!b.eventType || !b.category) return error(422, 'eventType and category are required');
            const next: NotificationRule = {
                id: m[1],
                eventType: b.eventType,
                minLevel: b.minLevel ?? null,
                category: b.category,
                titleTemplate: b.titleTemplate,
                bodyTemplate: b.bodyTemplate,
                dedupeKeyTemplate: b.dedupeKeyTemplate,
                enabled: b.enabled !== false,
            };
            return json(store.put(space, NOTIFICATION_RULES_COLL, next.id, next));
        }
        if (method === 'DELETE' && (m = match(url, NOTIF_RULE_ONE))) {
            if (!store.get(space, NOTIFICATION_RULES_COLL, m[1])) return error(404, `rule ${m[1]} not found`);
            store.delete(space, NOTIFICATION_RULES_COLL, m[1]);
            return json({ deleted: m[1] });
        }
        if (method === 'GET' && NOTIF_DELIVERIES.test(url)) {
            const all = store.list<ChannelDelivery>(space, NOTIFICATION_DELIVERIES_COLL).sort((a, b) => b.ts - a.ts);
            const limit = Number(req.params['limit']);
            return json(Number.isFinite(limit) && limit > 0 ? all.slice(0, limit) : all);
        }

        // ── catalog ── (streams/references also carry the store-backed onboarding drafts)
        if (method === 'GET' && CATALOG_KPIS_RE.test(url)) return json(CATALOG_KPIS);
        if (method === 'GET' && CATALOG_STREAMS_RE.test(url))
            return json([...CATALOG_STREAMS, ...draftStreamRows(store, space)]);
        if (method === 'GET' && CATALOG_REFERENCES_RE.test(url))
            return json([...CATALOG_REFERENCES, ...draftReferenceRows(store, space)]);
        if (method === 'GET' && CATALOG_TABLES_RE.test(url)) return json(CATALOG_TABLES);
        // A batch row's output_table → its catalog node, or 404 when it names no node the mock knows.
        // The 404 half is the one the UI depends on: it is what keeps the link off an unprovable store.
        if (method === 'GET' && CATALOG_RESOLVE.test(url)) {
            const table = new URLSearchParams(url.split('?')[1] ?? '').get('table') ?? '';
            const node = table ? CATALOG_TABLES.find((t) => t.label === table) : undefined;
            if (!node) return error(404, `no unique catalog node for table '${table}'`);
            return json({ id: node.id, label: node.label });
        }
        if (method === 'GET' && (m = match(url, CATALOG_NODE))) {
            const id = m[1];
            const all = [...CATALOG_TABLES, ...CATALOG_STREAMS];
            const node = all.find((t) => t.id === id) ?? all[0];
            const edges = CATALOG_EDGES.filter((e) => e.from === id || e.to === id);
            const neighborIds = new Set(edges.flatMap((e) => [e.from, e.to]));
            const nodes = all.filter((n) => neighborIds.has(n.id) && n.id !== id);
            return json({ node, neighbors: { nodes, edges } });
        }
        if (method === 'GET' && CATALOG_GRAPH.test(url)) {
            return json({ nodes: [...CATALOG_TABLES, ...CATALOG_STREAMS], edges: CATALOG_EDGES });
        }

        // ── diagnoses ──
        if (method === 'GET' && DIAGNOSES_RE.test(url)) return json(DIAGNOSES);

        // ── config ──
        if (method === 'GET' && (m = match(url, CONFIG_SPEC)))
            return json(CONFIG_SPECS[m[1]] ?? CONFIG_SPECS['pipeline']);
        if (method === 'POST' && VALIDATE.test(url))
            return json(validateBody(req.body as Record<string, unknown> | undefined));

        return undefined;
    };
}

/**
 * Mock POST /validate. Draft mode ({type, config}) checks the type's spec for missing required
 * fields (dotted paths) so the findings table is exercisable in demo; file mode ({configPath})
 * stays always-clean.
 */
function validateBody(body: Record<string, unknown> | undefined): {
    clean: boolean;
    findings: { severity: string; fieldPath: string; message: string }[];
    warnings: string[];
    safetyChecked: boolean;
} {
    const type = body?.['type'] as string | undefined;
    const config = (body?.['config'] ?? {}) as Record<string, unknown>;
    const spec = type
        ? (CONFIG_SPECS[type] as { fields?: { path: string; required?: boolean }[] } | undefined)
        : undefined;
    const findings: { severity: string; fieldPath: string; message: string }[] = [];
    for (const f of spec?.fields ?? []) {
        if (!f.required) continue;
        // walk the dotted path through the assembled (nested) config
        let cur: unknown = config;
        for (const part of f.path.split('.')) {
            cur = cur && typeof cur === 'object' ? (cur as Record<string, unknown>)[part] : undefined;
        }
        if (cur === undefined || cur === null || cur === '') {
            findings.push({ severity: 'ERROR', fieldPath: f.path, message: `Required field "${f.path}" is missing.` });
        }
    }
    return { clean: findings.length === 0, findings, warnings: [], safetyChecked: !!body?.['safety'] };
}

function allNotifications(store: MockStore, space: string): Notification[] {
    return store.list<Notification>(space, NOTIFICATIONS_COLL).sort((a, b) => b.ts - a.ts);
}

/** The notification seed rows — called from the default seed pack. */
export function seedNotifications(now: number): Notification[] {
    const cats = ['PIPELINE', 'JOB', 'OPS', 'SECURITY', 'PIPELINE'];
    const titles = [
        'Pipeline cdr_ingest batch failed',
        'Job subscriber_rollup completed',
        'High error rate on fraud_events',
        'Unauthorized route access attempt',
        'File quarantined in voucher_etl',
        'Job billing_report succeeded',
        'SLA breach on subscriber_load',
        'Pipeline fraud_events recovered',
    ];
    const bodies = [
        'Batch cdr_ingest-b1008 failed: 1 file rejected (parse_error). 0 of 1200 rows committed.',
        'Job subscriber_rollup finished in 4.2s. 18,400 rows processed.',
        'Error rate on fraud_events exceeded 5% threshold (currently 7.3%) over the last 15 minutes.',
        'POST /runs/unknown/trigger returned 404. Actor: appUser, IP: 192.168.1.42.',
        'File voucher_bad_0.csv quarantined in voucher_etl: schema_mismatch.',
        'Job billing_report completed successfully. 3 output files, 24,000 rows.',
        'Pipeline subscriber_load has not committed a batch in over 2 hours (SLA: 1h).',
        'Pipeline fraud_events resumed normal operation. Error rate dropped to 1.2%.',
    ];
    return titles.map((title, i) => {
        const ts = now - i * 1_800_000;
        return {
            id: `notif-${100 + i}`,
            ts,
            timestamp: new Date(ts).toISOString(),
            category: cats[i % cats.length],
            sourceType: i % 2 === 0 ? 'BATCH_FAILED' : 'JOB_SUCCEEDED',
            sourceId: PIPELINES[i % PIPELINES.length],
            title,
            body: bodies[i],
            state: i < 3 ? 'UNREAD' : 'READ',
            readAt: i < 3 ? null : ts + 600_000,
        };
    });
}
