import type { ComponentDef } from '../../api/components.service';
import type { JobDetail, JobExpressionDecl, JobParameterDecl, JobRunLogs } from '../../api/jobs.service';
import type { JobRun, JobView } from '../../api/models';
import { componentCollection } from './components.handler';
import { MockFlags } from '../mock-flags';
import { error, json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { fanOut } from '../notify';

/**
 * The Scheduler mock domain — the port of the old `jobs-mock` interceptor onto the persistent
 * {@link MockStore}. The backend already serves `GET /jobs`, `GET /jobs/{name}/runs` and
 * `POST /jobs/{name}/trigger` but not the management actions (create / edit / delete / enable /
 * disable / reschedule) or per-run logs, so the whole surface is mocked while `mockJobs` is on.
 *
 * **Falls through** for the DuckDB reporting endpoints (`/jobs/metrics`, `/jobs/runs`,
 * `/jobs/failures`) and unknown job ids, so the reporting view keeps its real-backend behavior.
 * RUNNING runs are advanced to completion by the liveness simulator (`../simulator.ts`).
 */

export const JOBS_COLL = 'job';
export const JOB_RUNS_COLL = 'job-run';
export const JOB_RUN_LOGS_COLL = 'job-run-log';
export const REPORT_ARTIFACTS_COLL = 'report-artifact';

/** A generated export (C6) — the mock-only payload behind a report job's completed run. */
export interface ReportArtifact {
    runId: string;
    filename: string;
    mime: string;
    /** CSV: the raw text. PDF/PNG: a short placeholder string (no real rendering in mock). */
    content: string;
}

// /jobs/<reserved> are real routes, not job ids — the server registers each as a fixed sub-path BEFORE
// the single-segment /jobs/{name} regex, and `expressions` is one of them (step 6).
const RESERVED = new Set(['metrics', 'runs', 'failures', 'types', 'expressions']);

/**
 * One parameter declaration in the EXACT shape `ParameterDecl.toMap()` serves: every key of the
 * rendering + validation contract present, unset ones as `''` / `[]` / `null`.
 *
 * <p>⚠ It exists so this mock cannot drift into being a different contract from the server. The
 * built-ins it mirrors all declare through `required(...)`/`optional(...)`, i.e. every rendering
 * component at its default — so this helper supplies exactly those defaults and each descriptor below
 * overrides only what the real declaration overrides. Do NOT enrich a parameter here (a `group`, an
 * `options` list) unless the Java declaration grew one: a mock that renders a nicer form than the
 * server can produce is the same failure as one that accepts more than the server accepts.
 */
function decl(p: Partial<JobParameterDecl> & { name: string; type: string }): JobParameterDecl {
    const required = p.required ?? false;
    return {
        required,
        deduce: '',
        default: '',
        description: '',
        label: '',
        tier: required ? 'REQUIRED' : 'OPTIONAL',
        options: [],
        pattern: '',
        min: null,
        max: null,
        placeholder: '',
        group: '',
        multi: false,
        secret: false,
        expressions: true,
        ...p,
    };
}

/**
 * Job Type descriptors (R3, GET /jobs/types[/{id}]) — mirrors the backend registry so the authoring
 * form is descriptor-driven offline too. `type` values are the framework `ParamType` names.
 */
const JOB_TYPE_DESCRIPTORS = [
    {
        id: 'enrich',
        title: 'Enrichment',
        description: 'Runs a Stage-2 enrichment once (full recompute) and publishes a chain commit.',
        parameters: [
            decl({ name: 'config', type: 'STRING', required: true, description: 'Path to the enrichment .toon' }),
        ],
        emits: ['pipeline.commit'],
        artifacts: [],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.JobService',
        source: 'builtin',
        version: '',
    },
    {
        id: 'consignment.process',
        title: 'Consignment Processor',
        description:
            'Runs a ConsignmentProcessor over one committed Consignment, with a read-only view of the ' +
            'files it wrote and §7.2-guarded summary emission.',
        parameters: [
            decl({
                name: 'consignment_id',
                type: 'STRING',
                required: true,
                deduce: '$signal.batchId',
                description:
                    'The Consignment to process. Deduced from the firing pipeline.commit Signal; ' +
                    'bind it explicitly for a manual run.',
            }),
            decl({
                name: 'processor',
                type: 'STRING',
                required: true,
                description:
                    'The id() of the ConsignmentProcessor to run, or an ordered comma-separated chain ' +
                    'of them (mask,rollup,report). Each step sees the Consignment as the previous one ' +
                    'left it, including the tables it registered.',
            }),
            // The vocabulary's only nested shape. Carried here so the JSON widget + its validity refusal
            // can be rehearsed offline — without this the type did not exist in the mock at all, and the
            // preview could neither confirm nor refute the field.
            decl({
                name: 'chain_config',
                type: 'JSON',
                tier: 'ADVANCED',
                description:
                    'Per-step configuration for a processor chain, reachable as ProcessorContext.config(): ' +
                    'a JSON array of {"config": {...}} objects, one per chain step in the same order (a ' +
                    'two-step chain needs a two-element array). Absent or empty gives every step no ' +
                    "config; declared, its length must match the chain's.",
            }),
        ],
        emits: [],
        artifacts: [],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.ConsignmentProcessJobType',
        source: 'builtin',
        version: '',
    },
    {
        id: 'report',
        title: 'Report',
        description: 'Computes a report (status / batch / dataset export) and optionally delivers it.',
        parameters: [
            decl({ name: 'scope', type: 'STRING', default: 'status', description: 'status | batch | dataset' }),
            decl({
                name: 'out_dir',
                type: 'STRING',
                description: 'Delivery directory (enables artifact + REPORT_READY)',
            }),
            decl({ name: 'format', type: 'STRING', description: 'json | csv' }),
            decl({ name: 'dataset', type: 'DATASET_REF', description: 'Dataset id (scope=dataset)' }),
        ],
        emits: [],
        artifacts: [{ name: 'report', kind: 'report' }],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.JobService',
        source: 'builtin',
        version: '',
    },
    {
        id: 'maintenance',
        title: 'Maintenance',
        description: 'Built-in housekeeping task (cleanup / ledger_prune / db_maintenance / compact / materialize).',
        parameters: [
            decl({ name: 'task', type: 'STRING', default: 'cleanup', description: 'Which maintenance task' }),
            decl({ name: 'dir', type: 'STRING', description: 'Target directory (cleanup / compact)' }),
            decl({ name: 'retention_days', type: 'INTEGER', default: '7', description: 'Age threshold in days' }),
            decl({ name: 'store', type: 'STRING', description: 'Store(s) a delete task targets (fenced)' }),
        ],
        emits: [],
        artifacts: [],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.JobService',
        source: 'builtin',
        version: '',
    },
    {
        id: 'pipeline',
        title: 'Pipeline',
        description: 'Runs an authored Pipeline over data at rest; emits a commit downstream jobs can chain on.',
        parameters: [
            decl({ name: 'flow', type: 'STRING', required: true, description: 'Authored Pipeline id to run' }),
            decl({ name: 'incremental_column', type: 'STRING', description: 'Watermark column for incremental runs' }),
        ],
        emits: ['pipeline.commit'],
        artifacts: [],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.JobService',
        source: 'builtin',
        version: '',
    },
    {
        id: 'sql.template',
        title: 'Templated SQL',
        description:
            'Runs an authored SQL template over source Datasets and materializes the result as a queryable Dataset.',
        parameters: [
            // TEXT + expressions:false, exactly as SqlTemplateJobType declares it — the SQL body owns its
            // own $-namespace, and TEXT is what retires the old `name === 'sql'` multiline sniff.
            decl({
                name: 'sql',
                type: 'TEXT',
                required: true,
                expressions: false,
                description: 'SQL SELECT template; its $name tokens are the runtime parameters',
            }),
            decl({
                name: 'sink_dataset',
                type: 'STRING',
                required: true,
                description: 'Output Dataset (store dir under the data root)',
            }),
            decl({ name: 'sources', type: 'STRING', description: 'CSV of source store names to register as views' }),
        ],
        emits: ['job.dataset.produced'],
        artifacts: [{ name: 'output', kind: 'dataset' }],
        // Provenance (§7.3): the registry assembles these, and every mock type is a built-in.
        implClass: 'com.gamma.job.JobService',
        source: 'builtin',
        version: '',
    },
    {
        // Mirrors `MailSendJobType.DESCRIPTOR` field for field (pinned server-side by
        // `MailSendJobTypeTest`). It is here because it is the only built-in declaring EMAIL, `multi` and
        // `group` — so without it the offline form can never rehearse a grouped, chip-edited, token-bearing
        // field, which is exactly the shape §9 authors.
        id: 'mail.send',
        title: 'Send Mail',
        description: 'Composes and sends an email to the configured recipients.',
        parameters: [
            decl({
                name: 'to',
                type: 'EMAIL',
                required: true,
                label: 'To',
                multi: true,
                group: 'Recipients',
                description: 'Recipient addresses',
            }),
            decl({
                name: 'cc',
                type: 'EMAIL',
                label: 'Cc',
                multi: true,
                group: 'Recipients',
                description: 'Additional recipients',
            }),
            decl({
                name: 'subject',
                type: 'STRING',
                required: true,
                label: 'Subject',
                group: 'Message',
                description: 'Message subject line',
            }),
            decl({
                name: 'body',
                type: 'TEXT',
                required: true,
                label: 'Body',
                group: 'Message',
                description: 'Message body',
            }),
        ],
        emits: ['mail.sent'],
        artifacts: [],
        requires: ['mail'],
        implClass: 'com.gamma.job.MailSendJobType',
        source: 'builtin',
        version: '',
    },
];

/** Every Trigger kind, lowercase and sorted exactly as `ExpressionDecl.toMap` sorts `availableIn`. */
const ALL_TRIGGERS = ['cron', 'manual', 'on_pipeline', 'on_signal'];

/** Zero-padded to two digits, for the date previews below. */
const pad = (n: number): string => String(n).padStart(2, '0');

/**
 * The fire date shifted the way `LocalDate.plusMonths`/`plusYears` shift it — day-of-month CLAMPED to the
 * target month's length, not rolled over as JS `setMonth` would (the 31st minus one month is the 28th/30th,
 * never the 3rd). Only the three date functions need it, and only for their `$unit(-1)` preview.
 */
function shiftedDate(days = 0, months = 0, years = 0): string {
    const now = new Date();
    const d = new Date(now.getFullYear() + years, now.getMonth() + months, 1);
    d.setDate(Math.min(now.getDate(), new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
    d.setDate(d.getDate() + days);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * One Expression declaration in the EXACT shape `ExpressionDecl.toMap(preview)` serves.
 *
 * <p>`preview` defaults to `example`, which IS the rule for a context-bound token: there is no firing Run
 * at request time, so the catalog shows the declared worked sample rather than fabricating a value the
 * author's Job will never see. A `contextFree` token overrides it with its evaluation.
 */
function expr(
    p: Partial<JobExpressionDecl> & { token: string; yields: string; description: string; example: string },
): JobExpressionDecl {
    return {
        form: 'LITERAL',
        availableIn: ALL_TRIGGERS,
        contextFree: true,
        preview: p.preview ?? p.example,
        ...p,
    };
}

/**
 * The Expression vocabulary (`GET /jobs/expressions`, §4.3) — the token picker's source, mirroring the
 * fifteen tokens `BuiltinExpressions` declares, key for key with `ExpressionDecl.toMap()`.
 *
 * <p>⚠ A **function**, not a const, because the server evaluates each `contextFree` preview at request
 * time with the same evaluator a Run uses. Computing dates here is not the client-side evaluator §4.3
 * forbids — this file *is* the offline server, and a mock returning a canned `"2026-08-07"` where the
 * server returns today would make the picker look right offline and stale against a real backend.
 *
 * <p>⚠ Do NOT add a token here that the engine does not declare. The picker's whole promise is that what
 * it offers is what a Run will resolve; a mock-only token is an offline rehearsal of a Run that fails
 * `REJECTED` with "unknown expression".
 */
function expressionCatalog(): JobExpressionDecl[] {
    return [
        expr({
            token: '$today',
            yields: 'DATE',
            description: "The date at fire time, in the Job's zone",
            example: '2026-08-07',
            preview: shiftedDate(),
        }),
        expr({
            token: '$yesterday',
            yields: 'DATE',
            description: 'The day before the fire date',
            example: '2026-08-06',
            preview: shiftedDate(-1),
        }),
        expr({
            token: '$tomorrow',
            yields: 'DATE',
            description: 'The day after the fire date',
            example: '2026-08-08',
            preview: shiftedDate(1),
        }),
        expr({
            token: '$now',
            yields: 'INSTANT',
            description: 'The fire-time instant',
            example: '2026-08-07T06:00:00Z',
            preview: new Date().toISOString(),
        }),
        expr({
            token: '$now.epoch_seconds',
            yields: 'INTEGER',
            description: 'Fire time as epoch seconds',
            example: '1785045600',
            preview: String(Math.floor(Date.now() / 1000)),
        }),
        expr({
            token: '$now.epoch_millis',
            yields: 'INTEGER',
            description: 'Fire time as epoch milliseconds',
            example: '1785045600000',
            preview: String(Date.now()),
        }),
        // These three need a firing Run/Job, so they keep their sample as the preview (contextFree: false).
        expr({
            token: '$run.id',
            yields: 'STRING',
            description: "This Run's id",
            example: 'run-20260807-060000-1',
            contextFree: false,
        }),
        expr({
            token: '$run.fire_time',
            yields: 'INSTANT',
            description: 'When this Run fired',
            example: '2026-08-07T06:00:00Z',
            preview: new Date().toISOString(),
        }),
        expr({
            token: '$run.actor',
            yields: 'STRING',
            description: 'Who or what triggered this Run',
            example: 'cron',
            contextFree: false,
        }),
        expr({
            token: '$job.last_success_time',
            yields: 'INSTANT',
            contextFree: false,
            description: "This Job's success watermark — the incremental-window anchor; unset before the first success",
            example: '2026-08-06T06:00:04Z',
        }),
        // $signal.* is meaningless on a cron fire — the picker filters on exactly this.
        expr({
            token: '$signal.',
            form: 'PREFIX',
            yields: 'STRING',
            availableIn: ['on_signal'],
            contextFree: false,
            description: "A dotted field of the firing Signal's payload",
            example: '$signal.dataset',
        }),
        expr({
            token: '$day(n)',
            form: 'FUNCTION',
            yields: 'DATE',
            description: 'The fire date shifted by n days (negative = past)',
            example: '$day(-1)',
            preview: shiftedDate(-1),
        }),
        expr({
            token: '$month(n)',
            form: 'FUNCTION',
            yields: 'DATE',
            description: 'The fire date shifted by n months (negative = past)',
            example: '$month(-1)',
            preview: shiftedDate(0, -1),
        }),
        expr({
            token: '$year(n)',
            form: 'FUNCTION',
            yields: 'DATE',
            description: 'The fire date shifted by n years (negative = past)',
            example: '$year(-1)',
            preview: shiftedDate(0, 0, -1),
        }),
        expr({
            token: '$upstream(<job>).artifact(<name>).<attr>',
            form: 'FUNCTION',
            yields: 'STRING',
            contextFree: false,
            description:
                "An attribute (ref | rows | bytes | watermark | event_time_min | event_time_max) of a predecessor Job's latest Run Artifact",
            example: '$upstream(loader).artifact(output).ref',
        }),
    ];
}

const JOBS = /\/jobs$/;
const JOB_EXPRESSIONS = /\/jobs\/expressions$/;
const JOB_TYPES = /\/jobs\/types$/;
const JOB_TYPE_ONE = /\/jobs\/types\/([^/]+)$/;
const JOB_RUN_LOGS = /\/jobs\/([^/]+)\/runs\/([^/]+)\/logs$/;
const JOB_RUN_ARTIFACT = /\/jobs\/([^/]+)\/runs\/([^/]+)\/artifact$/;
const JOB_ARTIFACTS_LATEST = /\/jobs\/([^/]+)\/artifacts\/latest$/;
const JOB_RUNS = /\/jobs\/([^/]+)\/runs$/;
const JOB_TRIGGER = /\/jobs\/([^/]+)\/trigger$/;
const JOB_TOGGLE = /\/jobs\/([^/]+)\/(enable|disable)$/;
const JOB_RESCHEDULE = /\/jobs\/([^/]+)\/reschedule$/;
const JOB_ONE = /\/jobs\/([^/]+)$/;

export function jobsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockJobs) return undefined;
        const { method, url, space } = req;
        let m: string[] | null;

        if (method === 'GET' && (m = match(url, JOB_RUN_ARTIFACT))) {
            const artifact = store.get<ReportArtifact>(space, REPORT_ARTIFACTS_COLL, m[2]);
            return artifact ? json(artifact) : error(404, `no artifact for run ${m[2]}`);
        }
        // Run Artifacts of the latest successful run (R7) — feeds the Maintenance Overview (MNT-11).
        // Name-keyed demo shapes: a *backup* job shows an archive, a *storage* job the axis series.
        if (method === 'GET' && (m = match(url, JOB_ARTIFACTS_LATEST))) {
            const job = m[1];
            const base = {
                runId: `${job}-latest`,
                job,
                at: new Date().toISOString(),
                rows: 0,
                ref: null as string | null,
            };
            if (job.includes('backup')) {
                return json([
                    {
                        ...base,
                        seq: 1,
                        name: 'backup',
                        kind: 'file',
                        ref: `data/backups/${job}_20260712.zip`,
                        bytes: 48_213,
                    },
                ]);
            }
            if (job.includes('storage')) {
                return json([
                    { ...base, seq: 1, name: 'axis:config', kind: 'file', ref: 'config', bytes: 182_000 },
                    { ...base, seq: 2, name: 'axis:data', kind: 'file', ref: 'data', bytes: 9_412_000 },
                    { ...base, seq: 3, name: 'axis:audit', kind: 'file', ref: 'audit', bytes: 731_000 },
                ]);
            }
            return json([]);
        }
        if (method === 'GET' && JOB_EXPRESSIONS.test(url)) return json(expressionCatalog());
        if (method === 'GET' && JOB_TYPES.test(url)) return json(JOB_TYPE_DESCRIPTORS);
        if (method === 'GET' && (m = match(url, JOB_TYPE_ONE))) {
            const d = JOB_TYPE_DESCRIPTORS.find((t) => t.id === m![1]);
            return d ? json(d) : error(404, `no job type ${m[1]}`);
        }
        if (method === 'GET' && (m = match(url, JOB_RUN_LOGS))) return json(runLogs(store, space, m[2]));
        if (method === 'GET' && (m = match(url, JOB_RUNS))) return json(runsOf(store, space, m[1]));
        if (method === 'POST' && (m = match(url, JOB_TRIGGER))) return json(trigger(store, space, m[1]), 202);
        if (method === 'POST' && (m = match(url, JOB_TOGGLE)))
            return json(setEnabled(store, space, m[1], m[2] === 'enable'));
        if (method === 'POST' && (m = match(url, JOB_RESCHEDULE))) {
            return json(reschedule(store, space, m[1], (req.body as { cron?: string })?.cron ?? ''));
        }
        if (method === 'GET' && JOBS.test(url)) return json(store.list<JobDetail>(space, JOBS_COLL).map(toView));
        if (method === 'POST' && JOBS.test(url)) return json(upsert(store, space, req.body as Record<string, unknown>));
        if (method === 'PUT' && (m = match(url, JOB_ONE))) {
            return json(upsert(store, space, req.body as Record<string, unknown>, m[1]));
        }
        if (method === 'DELETE' && (m = match(url, JOB_ONE)) && store.has(space, JOBS_COLL, m[1])) {
            return json(deleteJob(store, space, m[1]));
        }
        if (method === 'GET' && (m = match(url, JOB_ONE)) && !RESERVED.has(m[1]) && store.has(space, JOBS_COLL, m[1])) {
            return json(storedToWire(store.get<JobDetail>(space, JOBS_COLL, m[1])!));
        }
        return undefined; // reporting endpoints + unknown job ids fall through to the real backend
    };
}

/** Per-job run history, newest first. */
export function runsOf(store: MockStore, space: string, job: string): JobRun[] {
    return store
        .list<JobRun>(space, JOB_RUNS_COLL)
        .filter((r) => r.jobName === job)
        .sort((a, b) => (b.startTime ?? '').localeCompare(a.startTime ?? ''));
}

/** A run's logs; for a still-RUNNING run, append a fresh heartbeat line each call so live-tail visibly updates. */
function runLogs(store: MockStore, space: string, runId: string): JobRunLogs {
    const base = store.get<JobRunLogs>(space, JOB_RUN_LOGS_COLL, runId) ?? { logs: [], events: [] };
    const run = store.get<JobRun>(space, JOB_RUNS_COLL, runId);
    if (run?.status === 'RUNNING') {
        const now = new Date();
        return {
            ...base,
            logs: [
                ...base.logs,
                { ts: now.toISOString(), level: 'INFO', message: `…still running (${now.toLocaleTimeString()})` },
            ],
        };
    }
    return base;
}

/** Record one run + its logs (also used by the seed pack and the liveness simulator). */
export function recordRun(
    store: MockStore,
    space: string,
    job: string,
    trigger: string,
    status: string,
    startedAt: number,
    durationMs: number,
    message: string,
): JobRun {
    const id = `run-${startedAt}-${job}`;
    const running = status === 'RUNNING';
    const run: JobRun = {
        jobName: job,
        runId: id,
        status,
        triggerType: trigger,
        startTime: new Date(startedAt).toISOString(),
        endTime: running ? undefined : new Date(startedAt + durationMs).toISOString(),
        durationMs: running ? undefined : durationMs,
        error: status === 'FAILED' ? message : null,
    };
    store.put(space, JOB_RUNS_COLL, id, run);
    store.put(space, JOB_RUN_LOGS_COLL, id, {
        logs: [
            { ts: run.startTime, level: 'INFO', message: `Job "${job}" started (trigger=${trigger}).` },
            { ts: run.startTime, level: 'INFO', message },
            ...(status === 'FAILED' ? [{ ts: run.endTime!, level: 'ERROR' as const, message }] : []),
            ...(running ? [] : [{ ts: run.endTime!, level: 'INFO' as const, message: `Completed: ${status}.` }]),
        ],
        events: [{ ts: run.startTime, type: 'JOB_STARTED', message: `${job} fired by ${trigger}` }],
    } satisfies JobRunLogs);
    return run;
}

/** v1 async contract (W5): the trigger answers 202 + the submitted run's id (the run itself is
 *  recorded synchronously here — the mock has no executor to wait on). */
function trigger(store: MockStore, space: string, name: string): { runId: string } {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    if (!job) return { runId: `run-unknown-${name}` };
    // Only C6 dashboard-export jobs (identified by `params.dashboardId`) get the export treatment —
    // `type: 'report'` predates C6 and also covers other report jobs (e.g. the seeded billing report).
    const run = job.params?.['dashboardId']
        ? runReportExport(store, space, job)
        : recordRun(store, space, name, 'MANUAL', 'SUCCESS', Date.now(), 1_200, `Manual run of "${name}".`);
    store.put(space, JOBS_COLL, name, { ...job, lastStatus: run.status, lastRunTime: run.startTime });
    return { runId: run.runId };
}

/**
 * Run a scheduled Dashboard export (C6): CSV is a real serialization of the dashboard's tiles; PDF/PNG
 * are mock placeholders (no rendering engine here). Stores the artifact for download and fans out
 * REPORT_EXPORTED — the same shared notification core as Alerts/Expectations.
 */
function runReportExport(store: MockStore, space: string, job: JobDetail): JobRun {
    const dashboardId = String(job.params?.['dashboardId'] ?? '');
    const format = String(job.params?.['format'] ?? 'csv');
    const dashboard = store.get<ComponentDef>(space, componentCollection('dashboard'), dashboardId);
    const tiles = (dashboard?.content?.['tiles'] as unknown[] | undefined) ?? [];
    const startedAt = Date.now();

    if (!dashboard) {
        return recordRun(
            store,
            space,
            job.name,
            'MANUAL',
            'FAILED',
            startedAt,
            400,
            `Dashboard "${dashboardId}" no longer exists.`,
        );
    }

    const run = recordRun(
        store,
        space,
        job.name,
        'MANUAL',
        'SUCCESS',
        startedAt,
        800,
        `Exported "${dashboardId}" as ${format.toUpperCase()} (${tiles.length} tile(s)).`,
    );

    const artifact: ReportArtifact =
        format === 'csv'
            ? {
                  runId: run.runId,
                  filename: `${dashboardId}.csv`,
                  mime: 'text/csv',
                  content: [
                      'tile_index,widget_id,span',
                      ...tiles.map(
                          (t, i) =>
                              `${i},${(t as { widgetId?: string }).widgetId ?? ''},${(t as { span?: number }).span ?? 1}`,
                      ),
                  ].join('\n'),
              }
            : {
                  runId: run.runId,
                  filename: `${dashboardId}.${format}`,
                  mime: format === 'pdf' ? 'application/pdf' : 'image/png',
                  content: `Mock ${format.toUpperCase()} snapshot of dashboard "${dashboardId}" (${tiles.length} tile(s)) — no rendering engine in mock mode.`,
              };
    store.put(space, REPORT_ARTIFACTS_COLL, run.runId, artifact);

    const recipients = (job.params?.['recipients'] as string[] | undefined) ?? [];
    fanOut(
        store,
        space,
        'REPORT_EXPORTED',
        'OPS',
        `Report ready: ${job.name}`,
        `"${dashboardId}" exported as ${format.toUpperCase()}${recipients.length ? ` for ${recipients.join(', ')}` : ''}.`,
        run.runId,
    );
    return run;
}

function setEnabled(
    store: MockStore,
    space: string,
    name: string,
    enabled: boolean,
): Record<string, unknown> | undefined {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    return job ? storedToWire(store.put(space, JOBS_COLL, name, { ...job, enabled })) : undefined;
}

function reschedule(store: MockStore, space: string, name: string, cron: string): Record<string, unknown> | undefined {
    const job = store.get<JobDetail>(space, JOBS_COLL, name);
    if (!job) return undefined;
    // A cron schedule supersedes an event or signal trigger.
    return storedToWire(
        store.put(space, JOBS_COLL, name, {
            ...job,
            cron,
            onPipeline: null,
            onSignal: null,
            when: null,
            nextFire: nextFireFor(cron),
        }),
    );
}

/**
 * The job keys the server treats as config; everything else in a job body is a type-specific parameter.
 * Mirrors `JobConfig.fromMap`'s known-key set.
 */
const WIRE_CONFIG_KEYS = new Set([
    'name',
    'type',
    'cron',
    'on_pipeline',
    'on_signal',
    'when',
    'enabled',
    'catch_up',
    'args',
    'bind',
]);

/**
 * Parse a job write body exactly as `JobConfig.fromMap` does: keys are **snake_case**, parameters are
 * **flat** alongside them, and an unrecognised key is swept into the parameters rather than rejected —
 * so a camelCase `onPipeline` leaves the job with no trigger, offline just as in a deployment.
 *
 * ⚠ Deliberately NOT reusing the client's own `jobFromWire`/`jobToWire`: this stands in for the *server*,
 * and a mock that speaks the client's adapter back to it would agree with any bug in that adapter and
 * keep the preview green. It mirrors the Java, independently. Pinned by `jobs.handler.spec.ts` against
 * the same cases as `ControlApiJobCrudTest`.
 */
function wireToStored(raw: Record<string, unknown>): Omit<JobDetail, 'lastStatus' | 'lastRunTime' | 'nextFire'> {
    const params: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(raw ?? {})) if (!WIRE_CONFIG_KEYS.has(k)) params[k] = v;
    return {
        name: String(raw?.['name'] ?? ''),
        type: String(raw?.['type'] ?? '') as JobDetail['type'],
        cron: (raw?.['cron'] as string) || null,
        onPipeline: (raw?.['on_pipeline'] as string) || null,
        onSignal: (raw?.['on_signal'] as string) || null,
        when: (raw?.['when'] as string) || null,
        enabled: raw?.['enabled'] !== false,
        catchUp: raw?.['catch_up'] === true || raw?.['catch_up'] === 'true',
        params,
    };
}

/** The inverse (`JobConfig.toMap`): the flat snake_case `job:` section the detail endpoint returns, with
 *  parameters flattened in beside the config keys and blank/default optionals omitted. */
function storedToWire(j: JobDetail): Record<string, unknown> {
    const out: Record<string, unknown> = { name: j.name, type: j.type };
    if (j.cron) out['cron'] = j.cron;
    if (j.onPipeline) out['on_pipeline'] = j.onPipeline;
    if (j.onSignal) out['on_signal'] = j.onSignal;
    if (j.when) out['when'] = j.when;
    out['enabled'] = j.enabled;
    if (j.catchUp) out['catch_up'] = true;
    for (const [k, v] of Object.entries(j.params ?? {})) if (!WIRE_CONFIG_KEYS.has(k)) out[k] = v;
    return out;
}

function upsert(
    store: MockStore,
    space: string,
    body: Record<string, unknown>,
    name?: string,
): Record<string, unknown> {
    const parsed = wireToStored(name ? { ...body, name } : body);
    const existing = store.get<JobDetail>(space, JOBS_COLL, parsed.name);
    const job: JobDetail = {
        ...parsed,
        lastStatus: existing?.lastStatus,
        lastRunTime: existing?.lastRunTime,
        nextFire: nextFireFor(parsed.cron),
    };
    return storedToWire(store.put(space, JOBS_COLL, job.name, job));
}

function deleteJob(store: MockStore, space: string, name: string): { deleted: boolean } {
    store.delete(space, JOBS_COLL, name);
    for (const run of store.list<JobRun>(space, JOB_RUNS_COLL).filter((r) => r.jobName === name)) {
        store.delete(space, JOB_RUNS_COLL, run.runId);
        store.delete(space, JOB_RUN_LOGS_COLL, run.runId);
    }
    return { deleted: true };
}

/** Project the full record onto the list `JobView` (drop params/catchUp/when — the list endpoint omits
 *  them). Unlike the detail view this one IS camelCase: server-side it is a Java record, not the config
 *  section, so `onSignal` keeps its camel spelling here. */
function toView(j: JobDetail): JobView {
    return {
        name: j.name,
        type: j.type,
        cron: j.cron,
        onPipeline: j.onPipeline,
        onSignal: j.onSignal ?? null,
        enabled: j.enabled,
        lastStatus: j.lastStatus,
        lastRunTime: j.lastRunTime,
        nextFire: j.nextFire,
    };
}

/** A plausible next-fire for a cron job (mock — the real backend uses CronExpression); null when not cron. */
function nextFireFor(cron: string | null | undefined): string | null {
    return cron ? new Date(Date.now() + 3_600_000).toISOString() : null;
}
