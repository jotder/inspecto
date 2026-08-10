import { describe, expect, it } from 'vitest';
import type { ReportArtifact } from '../../api/jobs.service';
import type { JobView } from '../../api/models';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { componentCollection } from './components.handler';
import { jobsHandler } from './jobs.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

/** The default space seed has no Dashboard (only the vertical template packs do) — plant one directly. */
function seededStore(): MockStore {
    const store = new MockStore();
    store.ensureSeeded('default', seedDefaultSpace);
    store.put('default', componentCollection('dashboard'), 'cdr_sample', {
        type: 'dashboard',
        name: 'cdr_sample',
        ref: 'dashboard/cdr_sample',
        content: { tiles: [{ widgetId: 'w1', span: 1 }, { widgetId: 'w2', span: 2 }] },
    });
    return store;
}

/**
 * Scheduled export ⇒ a `type:'report'` job (C6, no new entity), in the shape a job write endpoint
 * actually accepts: the flat `job:` section, with the type-specific parameters alongside the config
 * keys rather than nested under `params`.
 */
const REPORT_JOB: Record<string, unknown> = {
    name: 'daily_cdr_export',
    type: 'report',
    cron: '0 0 6 * * *',
    enabled: true,
    reportKind: 'dashboard',
    dashboardId: 'cdr_sample',
    format: 'csv',
    recipients: ['ops@x.com'],
};

/** Run state lives on the list projection, never on the detail — the detail is the config section. */
function lastStatusOf(handler: ReturnType<typeof jobsHandler>, store: MockStore, name: string): string | undefined {
    const list = handler(req('GET', '/api/jobs'), store)?.body as JobView[];
    return list.find((j) => j.name === name)?.lastStatus;
}

describe('jobsHandler — scheduled report exports (C6)', () => {
    const handler = jobsHandler({ mockJobs: true, mockOps: true, mockStudio: true });

    it('triggering a report job produces a downloadable CSV artifact and fans out REPORT_EXPORTED', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', REPORT_JOB), store);

        const result = handler(req('POST', '/api/jobs/daily_cdr_export/trigger'), store)?.body as { runId: string };
        expect(result.runId).toBeTruthy(); // v1 async contract: the trigger answers with the run id

        expect(lastStatusOf(handler, store, 'daily_cdr_export')).toBe('SUCCESS');

        const runs = handler(req('GET', '/api/jobs/daily_cdr_export/runs'), store)?.body as { runId: string }[];
        expect(runs.length).toBe(1);
        expect(runs[0].runId).toBe(result.runId);

        const artifact = handler(req('GET', `/api/jobs/daily_cdr_export/runs/${runs[0].runId}/artifact`), store)?.body as ReportArtifact;
        expect(artifact.mime).toBe('text/csv');
        expect(artifact.content).toContain('tile_index,widget_id,span');

        const notifs = store
            .list<{ sourceType: string; sourceId: string }>('default', 'notification')
            .filter((n) => n.sourceType === 'REPORT_EXPORTED');
        expect(notifs.length).toBe(1);
        expect(notifs[0].sourceId).toBe(runs[0].runId);
    });

    it('a PDF/PNG export produces a mock placeholder artifact with the right mime type', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', { ...REPORT_JOB, name: 'weekly_pdf', format: 'pdf' }), store);
        handler(req('POST', '/api/jobs/weekly_pdf/trigger'), store);
        const runs = handler(req('GET', '/api/jobs/weekly_pdf/runs'), store)?.body as { runId: string }[];
        const artifact = handler(req('GET', `/api/jobs/weekly_pdf/runs/${runs[0].runId}/artifact`), store)?.body as ReportArtifact;
        expect(artifact.mime).toBe('application/pdf');
        expect(artifact.filename).toBe('cdr_sample.pdf');
    });

    it('triggering against a deleted dashboard FAILs the run instead of raising', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs', { ...REPORT_JOB, name: 'orphaned_export', dashboardId: 'gone' }), store);
        handler(req('POST', '/api/jobs/orphaned_export/trigger'), store);
        expect(lastStatusOf(handler, store, 'orphaned_export')).toBe('FAILED');
    });

    it('a plain (non-report) job trigger is unaffected — no artifact, existing MANUAL/SUCCESS behavior', () => {
        const store = seededStore();
        handler(req('POST', '/api/jobs/cdr_ingest_daily/trigger'), store);
        expect(lastStatusOf(handler, store, 'cdr_ingest_daily')).toBe('SUCCESS');
    });
});
