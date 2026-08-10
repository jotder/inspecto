import { describe, expect, it } from 'vitest';
import type { JobDetail, JobRunLogs } from '../../api/jobs.service';
import type { JobRun, JobView } from '../../api/models';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { jobsHandler } from './jobs.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('jobsHandler', () => {
    const handler = jobsHandler({ mockJobs: true });

    it('lists seeded jobs as JobViews (no params/catchUp) and gets the full detail', () => {
        const store = seededStore();
        const list = handler(req('GET', '/api/jobs'), store)?.body as JobView[];
        expect(list.map((j) => j.name)).toContain('cdr_ingest_daily');
        expect((list[0] as unknown as Record<string, unknown>)['params']).toBeUndefined();

        // the detail IS the flat `job:` section — params sit beside the config keys, not nested
        const detail = handler(req('GET', '/api/jobs/cdr_ingest_daily'), store)?.body as Record<string, unknown>;
        expect(detail['source']).toBe('cdr_sftp_prod');
        expect(detail['scope']).toBe('roaming');
        expect(detail['params']).toBeUndefined();
    });

    it('serves the Job Type descriptors (R3) — the list and one by id, 404 for unknown', () => {
        const store = seededStore();
        const types = handler(req('GET', '/api/jobs/types'), store)?.body as { id: string }[];
        expect(types.map((t) => t.id)).toEqual(expect.arrayContaining(['enrich', 'report', 'maintenance', 'pipeline', 'sql.template']));

        const sql = handler(req('GET', '/api/jobs/types/sql.template'), store)?.body as { parameters: { name: string; required: boolean }[] };
        expect(sql.parameters.map((p) => p.name)).toEqual(['sql', 'sink_dataset', 'sources']);
        expect(sql.parameters.find((p) => p.name === 'sql')?.required).toBe(true);

        expect(handler(req('GET', '/api/jobs/types/nope'), store)?.status).toBe(404);
    });

    it('upserts, toggles, reschedules and deletes a job (with its runs)', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', { name: 'nightly_export', type: 'flow', cron: '0 0 3 * * *', enabled: true }), store);
        expect((handler(req('GET', '/api/jobs/nightly_export'), store)?.body as JobDetail).cron).toBe('0 0 3 * * *');

        const disabled = handler(req('POST', '/api/jobs/nightly_export/disable'), store)?.body as JobDetail;
        expect(disabled.enabled).toBe(false);

        const rescheduled = handler(req('POST', '/api/jobs/nightly_export/reschedule', { cron: '0 0 4 * * *' }), store)?.body as Record<string, unknown>;
        expect(rescheduled['cron']).toBe('0 0 4 * * *');
        // the config section carries no run state — the server's own response has no nextFire either
        expect(rescheduled['nextFire']).toBeUndefined();

        handler(req('POST', '/api/jobs/nightly_export/trigger'), store);
        expect((handler(req('GET', '/api/jobs/nightly_export/runs'), store)?.body as JobRun[]).length).toBe(1);

        expect(handler(req('DELETE', '/api/jobs/nightly_export'), store)?.body).toEqual({ deleted: true });
        expect(handler(req('GET', '/api/jobs/nightly_export'), store)).toBeUndefined(); // unknown id falls through
        expect(store.list('default', 'job-run').every((r) => (r as JobRun).jobName !== 'nightly_export')).toBe(true);
    });

    // The same cases ControlApiJobCrudTest pins server-side. If these two diverge, the offline preview
    // starts greenlighting a body the real backend drops on the floor — the whole point of the mock.
    it('reads the snake_case trigger keys and flattens params, exactly as JobConfig.fromMap does', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', {
            name: 'after_ingest', type: 'maintenance', task: 'cleanup',
            on_pipeline: 'cdr_ingest', retention_days: '30',
        }), store);

        const detail = handler(req('GET', '/api/jobs/after_ingest'), store)?.body as Record<string, unknown>;
        expect(detail['on_pipeline']).toBe('cdr_ingest');
        expect(detail['retention_days']).toBe('30');

        // the LIST projection is camelCase — server-side it is a Java record, not the config section
        const row = (handler(req('GET', '/api/jobs'), store)?.body as JobView[]).find((j) => j.name === 'after_ingest')!;
        expect(row.onPipeline).toBe('cdr_ingest');
    });

    it('authors a signal-triggered job end-to-end, guard and all', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', {
            name: 'on_dataset_write', type: 'maintenance', task: 'cleanup',
            on_signal: 'dataset.write', when: "$signal.dataset == 'premium_cdr_view'",
        }), store);

        const detail = handler(req('GET', '/api/jobs/on_dataset_write'), store)?.body as Record<string, unknown>;
        expect(detail['on_signal']).toBe('dataset.write');
        expect(detail['when']).toBe("$signal.dataset == 'premium_cdr_view'");

        // and it is distinguishable from a manual job in the list
        const row = (handler(req('GET', '/api/jobs'), store)?.body as JobView[]).find((j) => j.name === 'on_dataset_write')!;
        expect(row.onSignal).toBe('dataset.write');

        // a cron supersedes it, dropping the guard with the trigger it narrowed
        const moved = handler(req('POST', '/api/jobs/on_dataset_write/reschedule', { cron: '0 0 5 * * *' }), store)?.body as Record<string, unknown>;
        expect(moved['on_signal']).toBeUndefined();
        expect(moved['when']).toBeUndefined();
    });

    it('absorbs a camelCase trigger key as an inert param, leaving the job untriggered', () => {
        const store = seededStore();
        // not an error — fromMap sweeps unknown keys into params, and so must the mock
        handler(req('POST', '/api/jobs', { name: 'camel', type: 'maintenance', onPipeline: 'cdr_ingest' }), store);

        const detail = handler(req('GET', '/api/jobs/camel'), store)?.body as Record<string, unknown>;
        expect(detail['on_pipeline']).toBeUndefined();
        expect(detail['onPipeline']).toBe('cdr_ingest');

        const row = (handler(req('GET', '/api/jobs'), store)?.body as JobView[]).find((j) => j.name === 'camel')!;
        expect(row.onPipeline).toBeNull();
    });

    it('records a MANUAL run on trigger and reflects it on the job (v1: 202 + runId)', () => {
        const store = seededStore();
        const triggered = handler(req('POST', '/api/jobs/weekly_billing/trigger'), store);
        expect(triggered?.status).toBe(202);
        const res = triggered?.body as { runId: string };
        const runs = handler(req('GET', '/api/jobs/weekly_billing/runs'), store)?.body as JobRun[];
        expect(runs[0].runId).toBe(res.runId);
        expect(runs[0].triggerType).toBe('MANUAL'); // newest first
        const logs = handler(req('GET', `/api/jobs/weekly_billing/runs/${runs[0].runId}/logs`), store)?.body as JobRunLogs;
        expect(logs.logs.some((l) => l.message.includes('Manual run'))).toBe(true);
    });

    it('appends a live heartbeat log line while a run is RUNNING', () => {
        const store = seededStore();
        const runs = handler(req('GET', '/api/jobs/cdr_ingest_daily/runs'), store)?.body as JobRun[];
        const running = runs.find((r) => r.status === 'RUNNING')!;
        const logs = handler(req('GET', `/api/jobs/cdr_ingest_daily/runs/${running.runId}/logs`), store)?.body as JobRunLogs;
        expect(logs.logs[logs.logs.length - 1].message).toContain('still running');
    });

    it('lets the reserved reporting routes fall through to the real backend', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/jobs/metrics'), store)).toBeUndefined();
        expect(handler(req('GET', '/api/jobs/failures'), store)).toBeUndefined();
        expect(jobsHandler({ mockJobs: false })(req('GET', '/api/jobs'), store)).toBeUndefined();
    });
});
