import { describe, expect, it } from 'vitest';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { demoHandler } from './demo.handler';

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

describe('demoHandler', () => {
    const handler = demoHandler({ mockDemo: true });

    it('round-trips channels (C4) and enforces the duplicate-id 409', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/notifications/channels'), store)?.body).toEqual([]);

        handler(
            req('POST', '/api/notifications/channels', { id: 'ops_email', kind: 'EMAIL', target: 'ops@x.com' }),
            store,
        );
        const dup = handler(
            req('POST', '/api/notifications/channels', { id: 'ops_email', kind: 'EMAIL', target: 'b@x.com' }),
            store,
        );
        expect(dup?.status).toBe(409);

        handler(req('PUT', '/api/notifications/channels/ops_email', { enabled: false }), store);
        const list = handler(req('GET', '/api/notifications/channels'), store)?.body as Array<{ enabled: boolean }>;
        expect(list[0].enabled).toBe(false);

        handler(req('DELETE', '/api/notifications/channels/ops_email'), store);
        expect(handler(req('GET', '/api/notifications/channels'), store)?.body).toEqual([]);
    });

    it('persists the preference grid per space (was a static no-op)', () => {
        const store = seededStore();
        const before = handler(req('GET', '/api/notifications/preferences'), store)?.body as Array<{
            category: string;
            channels: { email: boolean };
        }>;
        const edited = before.map((r) =>
            r.category === 'PIPELINE' ? { ...r, channels: { ...r.channels, email: true } } : r,
        );
        handler(req('PUT', '/api/notifications/preferences', { preferences: edited }), store);
        const after = handler(req('GET', '/api/notifications/preferences'), store)?.body as typeof before;
        expect(after.find((r) => r.category === 'PIPELINE')?.channels.email).toBe(true);
    });

    /**
     * Pin the audit-row spellings to the server's (mock-never-more-lenient): the files rows are the
     * `_status_` CSV header verbatim (BatchAuditWriter) and the quarantine rows are what
     * FileStatusStore.quarantine synthesizes. The mock's own `file_name`/`rows`/`received_at`/
     * `quarantined_at` inventions hid the real spellings through an offline rehearsal.
     */
    it('serves /runs/{n}/files rows with the exact status-ledger header keys', () => {
        const rows = handler(req('GET', '/api/runs/cdr_ingest/files'), seededStore())?.body as Record<string, string>[];
        expect(Object.keys(rows[0])).toEqual([
            'start_time',
            'end_time',
            'filename',
            'status',
            'parsed_rows',
            'error_rows',
            'output_paths',
            'output_sizes_bytes',
            'duration_ms',
            'error',
            'consignment_id',
            // Appended by the unpack stage: the archive/compressed original a member came out of,
            // then that inbox file's extension-insensitive IDENTITY (cdr.csv.gz / cdr.Z / bare cdr
            // are ONE logical file). Readers parse this ledger by header NAME, so appending cannot
            // break older files — an absent column reads blank, never a shifted value.
            'origin',
            'logical_name',
        ]);
        // per-file status is SUCCESS or QUARANTINED_* — there is no per-file FAILED (MemberAudit)
        const statuses = new Set(rows.map((r) => r['status']));
        for (const s of statuses) expect(s).toMatch(/^(SUCCESS|QUARANTINED_(UNREADABLE|MISMATCH|EMPTY))$/);
        // the ledger's timestamp format is "yyyy-MM-dd HH:mm:ss", not ISO
        expect(rows[0]['start_time']).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    });

    /**
     * Pin the batches-ledger spellings the same way: the rows are the BatchAuditWriter `batches` CSV
     * header verbatim, and batch status is the IngestOutcome vocabulary — `COMMITTED` (the mock's old
     * invention) was never an engine batch status.
     */
    it('serves /runs/{n}/batches rows with the exact batches-ledger header keys', () => {
        const rows = handler(req('GET', '/api/runs/cdr_ingest/batches'), seededStore())?.body as Record<
            string,
            string
        >[];
        expect(Object.keys(rows[0])).toEqual([
            'consignment_id',
            'pipeline',
            'schema_name',
            'output_table',
            'start_time',
            'end_time',
            'status',
            'member_count',
            'rejected_count',
            'total_input_rows',
            'total_output_rows',
            'output_file_count',
            'total_output_bytes',
            'duration_ms',
            'error',
            'cast_failures',
        ]);
        // batch status is exactly the IngestOutcome vocabulary (SUCCESS | EMPTY | FAILED)
        const statuses = new Set(rows.map((r) => r['status']));
        for (const s of statuses) expect(s).toMatch(/^(SUCCESS|EMPTY|FAILED)$/);
        // the ledger's timestamp format is "yyyy-MM-dd HH:mm:ss", not ISO
        expect(rows[0]['end_time']).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
        // -1 = "not measured" is a BLANK cast_failures cell, never "-1"
        expect(rows.some((r) => r['cast_failures'] === '')).toBe(true);
        expect(rows.every((r) => r['cast_failures'] !== '-1')).toBe(true);
    });

    it('serves /runs/{n}/quarantine rows with only the keys the server synthesizes', () => {
        const rows = handler(req('GET', '/api/runs/cdr_ingest/quarantine'), seededStore())?.body as Record<
            string,
            string
        >[];
        expect(Object.keys(rows[0])).toEqual(['file', 'reason', 'path', 'size_bytes']);
    });

    it('serves the health / status surface', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/health'), store)?.body).toEqual({ status: 'UP' });
        const status = handler(req('GET', '/api/status'), store)?.body as { pipelineCount: number };
        expect(status.pipelineCount).toBe(5);
    });

    /**
     * `GET /status/problem-files` — the mock must be NO MORE LENIENT than the route: same verdict
     * rule (FULL = non-SUCCESS, PARTIAL = SUCCESS with rejected rows, clean files omitted), same
     * key names, newest-first, and PRE-limit summary counts. Derived from the same `files()` fixture
     * Run Detail shows, so the two offline views cannot disagree.
     */
    it('serves cross-pipeline problem files, derived from the per-pipeline ledger', () => {
        const store = seededStore();
        type Page = {
            rows: { pipeline: string; filename: string; verdict: string; errorRows: number; time: string }[];
            total: number;
            truncated: boolean;
            fullCount: number;
            partialCount: number;
            warningCount: number;
            pipelinesWithProblems: number;
        };
        const page = handler(req('GET', '/api/status/problem-files'), store)?.body as Page;

        expect(page.rows.length).toBeGreaterThan(0);
        expect(Object.keys(page.rows[0])).toEqual([
            'pipeline',
            'filename',
            'verdict',
            'status',
            'parsedRows',
            'errorRows',
            'error',
            'consignmentId',
            'time',
            'origin',
            'logicalName',
        ]);
        // Every row is a real problem, and clean files never appear.
        expect(page.rows.every((r) => r.verdict === 'FULL' || r.verdict === 'PARTIAL')).toBe(true);
        expect(page.rows.every((r) => r.verdict === 'FULL' || r.errorRows > 0)).toBe(true);
        expect(page.fullCount + page.partialCount).toBe(page.total);
        // It spans pipelines — the whole point of the route.
        expect(new Set(page.rows.map((r) => r.pipeline)).size).toBeGreaterThan(1);
        expect(page.pipelinesWithProblems).toBeGreaterThan(1);
        // Newest first.
        const times = page.rows.map((r) => r.time);
        expect([...times].sort().reverse()).toEqual(times);

        // ?limit= bounds the page while total/counts stay pre-limit.
        const one = handler(req('GET', '/api/status/problem-files?limit=1'), store)?.body as Page;
        expect(one.rows).toHaveLength(1);
        expect(one.truncated).toBe(true);
        expect(one.total).toBe(page.total);
        expect(one.fullCount).toBe(page.fullCount);
    });

    it('round-trips notification reads and deletes through the store', () => {
        const store = seededStore();
        const unread = (): number =>
            (handler(req('GET', '/api/notifications/unread-count'), store)?.body as { count: number }).count;
        expect(unread()).toBe(3);

        handler(req('POST', '/api/notifications/notif-100/read'), store);
        expect(unread()).toBe(2);

        handler(req('POST', '/api/notifications/read-all'), store);
        expect(unread()).toBe(0);

        handler(req('DELETE', '/api/notifications/notif-101'), store);
        const list = handler(req('GET', '/api/notifications'), store)?.body as Array<{ id: string }>;
        expect(list.length).toBe(7);
        expect(list.some((n) => n.id === 'notif-101')).toBe(false);
    });

    it('validates a draft against the spec — missing required fields become ERROR findings', () => {
        const store = seededStore();
        const bad = handler(req('POST', '/api/validate', { type: 'pipeline', config: {} }), store)?.body as {
            clean: boolean;
            findings: { severity: string; fieldPath: string }[];
        };
        expect(bad.clean).toBe(false);
        expect(bad.findings.map((f) => f.fieldPath)).toContain('pipeline');
        expect(bad.findings.every((f) => f.severity === 'ERROR')).toBe(true);

        const ok = handler(
            req('POST', '/api/validate', {
                type: 'pipeline',
                config: { pipeline: 'cdr', source: { connector: 'sftp' } },
                safety: true,
            }),
            store,
        )?.body as { clean: boolean; findings: { fieldPath: string }[]; safetyChecked: boolean };
        expect(ok.findings.filter((f) => f.fieldPath === 'pipeline')).toEqual([]);
        expect(ok.safetyChecked).toBe(true);

        // file mode stays always-clean
        const file = handler(req('POST', '/api/validate', { configPath: 'configs/cdr.toon' }), store)?.body as {
            clean: boolean;
        };
        expect(file.clean).toBe(true);
    });

    it('lets the SSE stream fall through and gates on mockDemo', () => {
        const store = seededStore();
        expect(handler(req('GET', '/api/notifications/stream'), store)).toBeUndefined();
        expect(demoHandler({})(req('GET', '/api/health'), store)).toBeUndefined();
    });
});
