import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DbBrowserService, DbResult } from 'app/inspecto/api/db-browser.service';
import { QueryModel } from 'app/inspecto/query';
import { DatasetRowsService, RowSourceRef } from './dataset-rows.service';

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

const CATALOG = {
    groups: [
        { id: 'stores', label: 'Data Stores', kind: 'parquet', tables: [{ name: 'switch_cdr' }, { name: 'billing' }] },
        // Operational tables are the control plane's own; a Dataset cannot name them.
        { id: 'ops:objects', label: 'Objects', kind: 'operational', tables: [{ name: 'inspecto_ops_objects' }] },
    ],
};

/** One TestBed per test (house rule); the DbBrowserService stub records every live-path call. */
function setup(over: Partial<Record<'table' | 'query' | 'catalog', unknown>> = {}) {
    const table = (over.table as ReturnType<typeof vi.fn>) ?? vi.fn(() => of(dbResult()));
    const query = (over.query as ReturnType<typeof vi.fn>) ?? vi.fn(() => of(dbResult()));
    const catalog = (over.catalog as ReturnType<typeof vi.fn>) ?? vi.fn(() => of(CATALOG));
    TestBed.configureTestingModule({
        providers: [{ provide: DbBrowserService, useValue: { table, query, catalog } }],
    });
    return { svc: TestBed.inject(DatasetRowsService), table, query, catalog };
}

const CDR: RowSourceRef = { sourceName: 'cdr' };

describe('DatasetRowsService — rows', () => {
    it('reads a plain store over /db/table, mapping DuckDB types to Query Core types', async () => {
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
        const { svc } = setup({
            table: vi.fn(() => of(dbResult({ statistics: { rowCount: 1, elapsedMs: 1, truncated: true } }))),
        });
        expect((await svc.rows(CDR)).truncated).toBe(true);
    });

    it('maps an unknown store (404) to an explained empty result and never throws', async () => {
        const { svc } = setup({
            table: vi.fn(() => throwError(() => ({ status: 404, error: { error: "no store 'cdr'" } }))),
        });
        const res = await svc.rows(CDR);
        expect(res.rows).toEqual([]);
        expect(res.error).toBeTruthy();
    });

    it('does not cache a failed read, so the next call retries instead of replaying the error', async () => {
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

describe('DatasetRowsService — a Dataset with no source', () => {
    // MOCK-GONE-1(b): a blank sourceName used to build `GET /db/table?limit=1` with NO `name`.
    it('refuses without a request, and says why instead of rendering an empty grid', async () => {
        const { svc, table, query } = setup();
        const res = await svc.rows({ sourceName: '' });
        expect(table).not.toHaveBeenCalled();
        expect(query).not.toHaveBeenCalled();
        expect(res.rows).toEqual([]);
        expect(res.error).toMatch(/names no store/i);
    });

    it('whitespace is not a source name either', async () => {
        const { svc, table } = setup();
        expect((await svc.rows({ sourceName: '   ' })).error).toBeTruthy();
        expect(table).not.toHaveBeenCalled();
    });

    it('still offers the declared columns, so a picker built from them keeps working', async () => {
        const { svc } = setup();
        const cols = await svc.columns({ sourceName: '', columns: [{ name: 'tariff', type: 'string' }] });
        expect(cols).toEqual([{ name: 'tariff', type: 'string' }]);
    });
});

describe('DatasetRowsService — the store list', () => {
    it('lists the business stores and EXCLUDES the operational groups', async () => {
        const { svc } = setup();
        expect(await svc.stores()).toEqual({ names: ['billing', 'switch_cdr'] });
    });

    it('an unreadable catalog is explained, not reported as "this space has no stores"', async () => {
        const { svc } = setup({ catalog: vi.fn(() => throwError(() => ({ status: 503 }))) });
        const res = await svc.stores();
        expect(res.names).toEqual([]);
        expect(res.error).toBeTruthy();
    });
});

describe('DatasetRowsService — authored SQL', () => {
    it('posts the SQL to /db/query against the named store', async () => {
        const { svc, query } = setup();
        const res = await svc.sql('cdr', 'SELECT * FROM "cdr"', 50);
        expect(query).toHaveBeenCalledWith({ table: 'cdr', sql: 'SELECT * FROM "cdr"', limit: 50 });
        expect(res.rows).toEqual([{ msisdn: '880', duration_s: 12 }]);
    });

    it('a rejected statement comes back as an error result, never a throw', async () => {
        const { svc } = setup({
            query: vi.fn(() => throwError(() => ({ status: 422, error: { error: 'SQL failed the check' } }))),
        });
        const res = await svc.sql('cdr', 'DROP TABLE cdr');
        expect(res.rows).toEqual([]);
        expect(res.error).toBeTruthy();
    });
});

describe('DatasetRowsService — caching and columns', () => {
    it('dedupes an identical request and clear() drops it', async () => {
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
        const { svc } = setup();
        expect(svc.rows(CDR, 1)).not.toBe(svc.rows(CDR, 1000));
    });

    it('columns(): declared columns win, with no round-trip', async () => {
        const { svc, table } = setup();
        const cols = await svc.columns({ sourceName: 'cdr', columns: [{ name: 'tariff', type: 'string' }] });
        expect(cols).toEqual([{ name: 'tariff', type: 'string' }]);
        expect(table).not.toHaveBeenCalled();
    });

    it('columns(): with none declared, probes the store for one row', async () => {
        const { svc, table } = setup();
        const cols = await svc.columns(CDR);
        expect(table).toHaveBeenCalledWith({ name: 'cdr', limit: 1 });
        expect(cols.map((c) => c.name)).toEqual(['msisdn', 'duration_s']);
    });
});
