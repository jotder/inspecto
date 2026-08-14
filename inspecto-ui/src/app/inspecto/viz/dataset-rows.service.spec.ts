import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { DbBrowserService, DbResult } from 'app/inspecto/api/db-browser.service';
import { QueryModel } from 'app/inspecto/query';
import { SAMPLE_SOURCES } from 'app/inspecto/mock/sample-sources';
import { DatasetRowsService, RowSourceRef } from './dataset-rows.service';

const WAS_MOCK_STUDIO = environment.mockStudio;

/** A virtual dataset's model — one complete leaf, so it survives `evaluateRows`' incomplete-leaf filter. */
function premiumOnly(): QueryModel {
    return {
        projection: '*',
        where: {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'tariff', operator: '=', value: 'premium' }],
        },
    };
}

function dbResult(over: Partial<DbResult> = {}): DbResult {
    return {
        columns: [
            { name: 'msisdn', type: 'VARCHAR', role: 'dimension' },
            { name: 'duration_s', type: 'DOUBLE', role: 'measure' },
        ],
        rows: [{ msisdn: '880', duration_s: 12 }],
        statistics: { rowCount: 1, elapsedMs: 1, truncated: false },
        ...over,
    };
}

/** One TestBed per test (house rule); the DbBrowserService stub records every live-path call. */
function setup(over: Partial<Record<'table' | 'query', unknown>> = {}) {
    const table = (over.table as ReturnType<typeof vi.fn>) ?? vi.fn(() => of(dbResult()));
    const query = (over.query as ReturnType<typeof vi.fn>) ?? vi.fn(() => of(dbResult()));
    TestBed.configureTestingModule({
        providers: [{ provide: DbBrowserService, useValue: { table, query } }],
    });
    return { svc: TestBed.inject(DatasetRowsService), table, query };
}

const CDR: RowSourceRef = { sourceName: 'cdr' };

afterEach(() => {
    environment.mockStudio = WAS_MOCK_STUDIO;
});

describe('DatasetRowsService — offline (mockStudio)', () => {
    it('serves the store‘s sample rows with inferred columns and never calls the backend', async () => {
        environment.mockStudio = true;
        const { svc, table, query } = setup();
        const res = await svc.rows(CDR);
        expect(res.rows).toBe(SAMPLE_SOURCES['cdr']);
        expect(res.columns.map((c) => c.name)).toContain('msisdn');
        expect(res.truncated).toBe(false);
        expect(table).not.toHaveBeenCalled();
        expect(query).not.toHaveBeenCalled();
    });

    it('applies the dataset‘s own model, so a virtual dataset previews its filtered rows', async () => {
        environment.mockStudio = true;
        const { svc } = setup();
        const res = await svc.rows({ sourceName: 'cdr', query: premiumOnly() });
        expect(res.rows.length).toBeGreaterThan(0);
        expect(res.rows.length).toBeLessThan(SAMPLE_SOURCES['cdr'].length);
        expect(res.rows.every((r) => r['tariff'] === 'premium')).toBe(true);
    });

    it('says WHY a store is empty rather than resolving silently to no rows', async () => {
        environment.mockStudio = true;
        const { svc } = setup();
        const res = await svc.rows({ sourceName: 'a_real_store_with_no_sample' });
        expect(res.rows).toEqual([]);
        expect(res.error).toContain('a_real_store_with_no_sample');
    });
});

describe('DatasetRowsService — live', () => {
    it('reads a plain store over /db/table, mapping DuckDB types to Query Core types', async () => {
        environment.mockStudio = false;
        const { svc, table, query } = setup();
        const res = await svc.rows(CDR, 250);
        expect(table).toHaveBeenCalledWith({ name: 'cdr', limit: 250 });
        expect(query).not.toHaveBeenCalled();
        expect(res.columns).toEqual([
            { name: 'msisdn', type: 'string' },
            { name: 'duration_s', type: 'number' },
        ]);
        expect(res.rows).toEqual([{ msisdn: '880', duration_s: 12 }]);
    });

    it('compiles a virtual dataset‘s model to SQL over the named store and posts it to /db/query', async () => {
        environment.mockStudio = false;
        const { svc, table, query } = setup();
        await svc.rows({ sourceName: 'cdr', query: premiumOnly(), columns: [{ name: 'tariff', type: 'string' }] }, 10);
        expect(table).not.toHaveBeenCalled();
        const body = query.mock.calls[0][0] as { table: string; sql: string; limit: number };
        expect(body.table).toBe('cdr');
        expect(body.limit).toBe(10);
        expect(body.sql).toContain('FROM "cdr"');
        expect(body.sql).toContain(`"tariff" = 'premium'`);
    });

    it('reports the page as truncated when the server says the store held more', async () => {
        environment.mockStudio = false;
        const { svc } = setup({
            table: vi.fn(() => of(dbResult({ statistics: { rowCount: 1, elapsedMs: 1, truncated: true } }))),
        });
        expect((await svc.rows(CDR)).truncated).toBe(true);
    });

    it('maps an unknown store (404) to an explained empty result and never throws', async () => {
        environment.mockStudio = false;
        const { svc } = setup({
            table: vi.fn(() => throwError(() => ({ status: 404, error: { error: "no store 'cdr'" } }))),
        });
        const res = await svc.rows(CDR);
        expect(res.rows).toEqual([]);
        expect(res.error).toBeTruthy();
    });

    it('does not cache a failed read, so the next call retries instead of replaying the error', async () => {
        environment.mockStudio = false;
        const table = vi
            .fn()
            .mockReturnValueOnce(throwError(() => ({ status: 503 })))
            .mockReturnValueOnce(of(dbResult()));
        const { svc } = setup({ table });
        expect((await svc.rows(CDR)).error).toBeTruthy();
        expect((await svc.rows(CDR)).rows.length).toBe(1);
        expect(table).toHaveBeenCalledTimes(2);
    });
});

describe('DatasetRowsService — authored SQL', () => {
    it('live: posts the SQL to /db/query against the named store', async () => {
        environment.mockStudio = false;
        const { svc, query } = setup();
        const res = await svc.sql('cdr', 'SELECT * FROM "cdr"', 50);
        expect(query).toHaveBeenCalledWith({ table: 'cdr', sql: 'SELECT * FROM "cdr"', limit: 50 });
        expect(res.rows).toEqual([{ msisdn: '880', duration_s: 12 }]);
    });

    it('live: a rejected statement comes back as an error result, never a throw', async () => {
        environment.mockStudio = false;
        const { svc } = setup({
            query: vi.fn(() => throwError(() => ({ status: 422, error: { error: 'SQL failed the check' } }))),
        });
        const res = await svc.sql('cdr', 'DROP TABLE cdr');
        expect(res.rows).toEqual([]);
        expect(res.error).toBeTruthy();
    });

    it('offline: runs in-browser over the store‘s sample page, with no request', async () => {
        environment.mockStudio = true;
        const { svc, query } = setup();
        const res = await svc.sql('cdr', 'SELECT tariff FROM cdr');
        expect(query).not.toHaveBeenCalled();
        expect(res.rows.length).toBeGreaterThan(0);
        expect(Object.keys(res.rows[0])).toEqual(['tariff']);
    });

    it('offline: a store with no sample says so instead of running over nothing', async () => {
        environment.mockStudio = true;
        const { svc } = setup();
        const res = await svc.sql('a_real_store_with_no_sample', 'SELECT 1');
        expect(res.error).toContain('a_real_store_with_no_sample');
    });
});

describe('DatasetRowsService — caching and columns', () => {
    it('dedupes an identical request and clear() drops it', async () => {
        environment.mockStudio = false;
        const { svc, table } = setup();
        const p1 = svc.rows(CDR);
        expect(svc.rows(CDR)).toBe(p1);
        await p1;
        expect(svc.rows(CDR)).toBe(p1); // still cached after it resolves, not only while in flight
        svc.clear();
        expect(svc.rows(CDR)).not.toBe(p1);
        expect(table).toHaveBeenCalledTimes(2);
    });

    it('does not share a page across different limits — a 1-row probe is not the drill-through page', async () => {
        environment.mockStudio = false;
        const { svc } = setup();
        expect(svc.rows(CDR, 1)).not.toBe(svc.rows(CDR, 1000));
    });

    it('columns(): declared columns win, with no round-trip', async () => {
        environment.mockStudio = false;
        const { svc, table } = setup();
        const cols = await svc.columns({ sourceName: 'cdr', columns: [{ name: 'tariff', type: 'string' }] });
        expect(cols).toEqual([{ name: 'tariff', type: 'string' }]);
        expect(table).not.toHaveBeenCalled();
    });

    it('columns(): with none declared, probes the store for one row', async () => {
        environment.mockStudio = false;
        const { svc, table } = setup();
        const cols = await svc.columns(CDR);
        expect(table).toHaveBeenCalledWith({ name: 'cdr', limit: 1 });
        expect(cols.map((c) => c.name)).toEqual(['msisdn', 'duration_s']);
    });
});
