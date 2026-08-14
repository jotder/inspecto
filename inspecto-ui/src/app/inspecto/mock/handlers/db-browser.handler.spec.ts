import { describe, expect, it } from 'vitest';
import { MockStore } from '../mock-store';
import { MockRequest, MockResponse } from '../mock-http';
import { SAMPLE_SOURCES } from '../sample-sources';
import { dbBrowserHandler } from './db-browser.handler';

const handler = dbBrowserHandler({ mockDb: true } as never);
const store = new MockStore();

function call(method: string, url: string, params: Record<string, string> = {}, body: unknown = null): MockResponse {
    const req: MockRequest = { method, url, params, body, space: 'default' };
    const res = handler(req, store);
    expect(res, `${method} ${url} was not handled`).toBeDefined();
    return res as MockResponse;
}

interface DbBody {
    columns: { name: string; type: string; role: string | null; cardinality: number | null }[];
    rows: Record<string, unknown>[];
    statistics: { rowCount: number; truncated: boolean };
}

describe('dbBrowserHandler — the catalog is the sample stores', () => {
    it('lists every sample store, so the offline catalog and the Dataset picker agree', () => {
        const body = call('GET', '/api/db/catalog').body as { groups: { id: string; tables: { name: string }[] }[] };
        const stores = body.groups.find((g) => g.id === 'stores');
        expect(stores?.tables.map((t) => t.name)).toEqual(Object.keys(SAMPLE_SOURCES));
        // The operational group stays, and stays separate — a Dataset cannot name one of its tables.
        expect(body.groups.some((g) => g.id.startsWith('ops:'))).toBe(true);
    });

    it('is disabled when mockDb is off, so a real backend is never shadowed', () => {
        const off = dbBrowserHandler({ mockDb: false } as never);
        expect(off({ method: 'GET', url: '/api/db/catalog', params: {}, body: null, space: 'default' }, store)).toBe(
            undefined,
        );
    });
});

describe('dbBrowserHandler — /db/table pages a real store', () => {
    it('serves the named store‘s rows with derived DuckDB types and roles', () => {
        const body = call('GET', '/api/db/table', { name: 'cdr' }).body as DbBody;
        expect(body.rows[0]).toEqual(SAMPLE_SOURCES['cdr'][0]);
        const duration = body.columns.find((c) => c.name === 'duration_s');
        // Derived exactly as ResultSetDescriptor does: a non-id number is a measure, and carries no
        // cardinality (that is counted for dimensions only).
        expect(duration?.role).toBe('measure');
        expect(duration?.cardinality).toBeNull();
        expect(body.columns.find((c) => c.name === 'tariff')?.role).toBe('dimension');
        expect(body.columns.find((c) => c.name === 'tariff')?.cardinality).toBeGreaterThan(0);
        // An `*_id` column is NOT a measure even though it is numeric.
        expect(body.columns.find((c) => c.name === 'id')?.role).toBe('dimension');
    });

    it('honours limit + offset and reports truncation the way the server does', () => {
        const all = SAMPLE_SOURCES['cdr'];
        const first = call('GET', '/api/db/table', { name: 'cdr', limit: '2' }).body as DbBody;
        expect(first.rows).toHaveLength(2);
        expect(first.rows[0]).toEqual(all[0]);
        expect(first.statistics.truncated).toBe(true); // the store held more than this page

        const rest = call('GET', '/api/db/table', { name: 'cdr', limit: '500', offset: '1' }).body as DbBody;
        expect(rest.rows).toHaveLength(all.length - 1);
        expect(rest.statistics.truncated).toBe(false); // the page reached the end
    });

    it('sorts on a single `field:dir` term, like parseSort', () => {
        const body = call('GET', '/api/db/table', { name: 'cdr', sort: 'duration_s:desc' }).body as DbBody;
        const durations = body.rows.map((r) => Number(r['duration_s']));
        expect(durations).toEqual([...durations].sort((a, b) => b - a));
    });

    it('404s an unknown store and 400s a missing name — never an empty success', () => {
        expect(call('GET', '/api/db/table', { name: 'not_a_store' }).status).toBe(404);
        expect(call('GET', '/api/db/table', {}).status).toBe(400);
    });
});

describe('dbBrowserHandler — /db/query is never more lenient than SqlGuard', () => {
    it('422s a mutating statement, so a refusal the server makes is rehearsed offline', () => {
        const res = call('POST', '/api/db/query', {}, { table: 'cdr', sql: 'DROP TABLE cdr' });
        expect(res.status).toBe(422);
        expect((res.body as { findings: string[] }).findings.join(' ')).toContain('SELECT');
    });

    it('422s a chained statement', () => {
        const res = call('POST', '/api/db/query', {}, { table: 'cdr', sql: 'SELECT 1; DROP TABLE cdr' });
        expect(res.status).toBe(422);
    });

    it('422s a missing sql or table, matching the server‘s own required-field refusals', () => {
        expect(call('POST', '/api/db/query', {}, { table: 'cdr' }).status).toBe(422);
        expect(call('POST', '/api/db/query', {}, { sql: 'SELECT 1' }).status).toBe(422);
    });

    it('501s valid SQL rather than answering with rows it did not actually select', () => {
        // The old stub returned three fixed rows for ANY sql — a query that "worked" offline and proved
        // nothing. Refusing is the honest degrade; the in-browser SQL editor still answers.
        const res = call('POST', '/api/db/query', {}, { table: 'cdr', sql: 'SELECT * FROM "cdr"' });
        expect(res.status).toBe(501);
        expect(String((res.body as { error: string }).error)).toMatch(/SQL editor/);
    });

    it('404s SQL against an unknown store, before deciding anything about the SQL itself', () => {
        expect(call('POST', '/api/db/query', {}, { table: 'nope', sql: 'SELECT 1' }).status).toBe(404);
    });
});
