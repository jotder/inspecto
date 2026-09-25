import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BiQueryService } from 'app/inspecto/api/bi-query.service';
import { QuerySpec } from './viz-types';
import { biQueryBody, DatasetResultService } from './dataset-result.service';

const COLS = [
    { name: 'tariff', type: 'string' as const },
    { name: 'duration_s', type: 'number' as const },
];

function spec(overrides: Partial<QuerySpec> = {}): QuerySpec {
    return {
        datasetId: 'cdr_sample',
        sourceName: 'cdr',
        groupBy: ['tariff'],
        measures: [
            {
                id: 'sum_duration_s',
                expression: 'SUM("duration_s")',
                label: 'sum(duration_s)',
                agg: 'sum',
                field: 'duration_s',
            },
        ],
        filters: null,
        ...overrides,
    };
}

/** One TestBed per test (house rule); the BiQueryService stub records live-path calls. */
function setup(biRun: ReturnType<typeof vi.fn> = vi.fn()) {
    TestBed.configureTestingModule({
        providers: [{ provide: BiQueryService, useValue: { run: biRun } }],
    });
    return { svc: TestBed.inject(DatasetResultService), biRun };
}

describe('DatasetResultService', () => {
    it('dedupes: two calls with an identical spec (different object identity) share one run', () => {
        const { svc } = setup();
        const p1 = svc.run(spec());
        const p2 = svc.run(spec()); // structurally identical, but a fresh object literal
        expect(p1).toBe(p2);
    });

    it('does not dedupe across a different spec (e.g. a different filter)', () => {
        const { svc } = setup();
        const p1 = svc.run(spec());
        const p2 = svc.run(spec({ groupBy: ['msisdn'] }));
        expect(p1).not.toBe(p2);
    });

    it('still returns the cached run after it resolves (not just while in flight)', async () => {
        // A SUCCESSFUL run: a failed one is deliberately dropped from the cache (see the 429 suite).
        const { svc } = setup(vi.fn(() => of({ rows: [] })));
        const p1 = svc.run(spec());
        await p1;
        const p2 = svc.run(spec());
        expect(p1).toBe(p2);
    });

    it('clear() drops the cache, so the next identical spec runs fresh', async () => {
        const { svc } = setup();
        const p1 = svc.run(spec());
        await p1;
        svc.clear();
        const p2 = svc.run(spec());
        expect(p1).not.toBe(p2);
    });

    // ── the server run (POST /bi/query) ───────────────────────────────────────

    it('maps the spec to the wire body and returns the server rows', async () => {
        const biRun = vi.fn(() => of({ rows: [{ tariff: 'gold', sum_duration_s: 30 }] }));
        const { svc } = setup(biRun);
        const res = await svc.run(spec(), COLS);
        expect(res.ok).toBe(true);
        expect(res.rows).toEqual([{ tariff: 'gold', sum_duration_s: 30 }]);
        expect(biRun).toHaveBeenCalledWith({
            dataset: 'cdr_sample',
            measures: [{ agg: 'sum', field: 'duration_s' }],
            groupBy: ['tariff'],
        });
    });

    it.each([true, false])('carries the server statistics.truncated flag (%s) onto the result', async (truncated) => {
        const stats = { rowCount: 1, elapsedMs: 1, truncated };
        const { svc } = setup(vi.fn(() => of({ rows: [{ a: 1 }], statistics: stats })));
        expect((await svc.run(spec(), COLS)).truncated).toBe(truncated);
    });

    it('maps a server error to an ok:false result (never throws)', async () => {
        const biRun = vi.fn(() => throwError(() => ({ status: 422, error: { error: 'bad spec' } })));
        const { svc } = setup(biRun);
        const res = await svc.run(spec(), COLS);
        expect(res.ok).toBe(false);
        expect(res.error).toBeTruthy();
    });

    it('fails honestly on an unmappable spec without calling the endpoint', async () => {
        const { svc, biRun } = setup();
        // A named-measure expression has no structured {agg, field} origin — not expressible on the wire.
        const res = await svc.run(
            spec({ measures: [{ id: 'avg_cost', expression: 'SUM(x)/COUNT(*)', label: 'avg cost' }] }),
            COLS,
        );
        expect(res.ok).toBe(false);
        expect(res.error).toContain('cannot run');
        expect(biRun).not.toHaveBeenCalled();
    });
});

describe('DatasetResultService — 429 rate limiting', () => {
    const tooMany = () => throwError(() => new HttpErrorResponse({ status: 429, statusText: 'Too Many Requests' }));
    const saved = DatasetResultService.RETRY_DELAYS_MS;
    beforeEach(() => (DatasetResultService.RETRY_DELAYS_MS = [0, 0]));
    afterEach(() => (DatasetResultService.RETRY_DELAYS_MS = saved));

    it('retries a 429 and returns the rows once the server accepts', async () => {
        const biRun = vi
            .fn()
            .mockReturnValueOnce(tooMany())
            .mockReturnValueOnce(of({ rows: [{ a: 1 }] }));
        const { svc } = setup(biRun);
        const r = await svc.run(spec(), COLS);
        expect(r).toEqual({ ok: true, rows: [{ a: 1 }] });
        expect(biRun).toHaveBeenCalledTimes(2);
    });

    it('reports throttled — not a bare empty result — when every retry is refused', async () => {
        const biRun = vi.fn(() => tooMany());
        const { svc } = setup(biRun);
        const r = await svc.run(spec(), COLS);
        expect(r.ok).toBe(false);
        expect(r.throttled).toBe(true);
        expect(r.error).toContain('Rate limited');
        expect(biRun).toHaveBeenCalledTimes(3); // first try + two retries
    });

    it('a failed run is not replayed from the cache — the next identical call runs again', async () => {
        const biRun = vi
            .fn()
            .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })))
            .mockReturnValueOnce(of({ rows: [{ a: 2 }] }));
        const { svc } = setup(biRun);
        expect((await svc.run(spec(), COLS)).ok).toBe(false);
        await Promise.resolve();
        expect(await svc.run(spec(), COLS)).toEqual({ ok: true, rows: [{ a: 2 }] });
    });

    it('a non-429 error is not retried', async () => {
        const biRun = vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 })));
        const { svc } = setup(biRun);
        const r = await svc.run(spec(), COLS);
        expect(r.throttled).toBeUndefined();
        expect(biRun).toHaveBeenCalledTimes(1);
    });
});

describe('biQueryBody', () => {
    it('maps measures/groupBy/orderBy/limit and types filter values by column', () => {
        const body = biQueryBody(
            spec({
                filters: {
                    kind: 'group',
                    op: 'AND',
                    items: [
                        { kind: 'condition', field: 'tariff', operator: '=', value: 'gold' },
                        { kind: 'condition', field: 'duration_s', operator: '>=', value: '10' },
                    ],
                },
                orderBy: [{ field: 'tariff', dir: 'asc' }],
                limit: 100,
            }),
            COLS,
        );
        expect(body).toEqual({
            dataset: 'cdr_sample',
            measures: [{ agg: 'sum', field: 'duration_s' }],
            groupBy: ['tariff'],
            filters: [
                { field: 'tariff', op: '=', value: 'gold' },
                { field: 'duration_s', op: '>=', value: 10 }, // number, not '10'
            ],
            orderBy: [{ field: 'tariff', dir: 'asc' }],
            limit: 100,
        });
    });

    it('maps count without a field, contains→like, between→two terms, in→typed list', () => {
        const body = biQueryBody(
            spec({
                measures: [{ id: 'count', expression: 'COUNT(*)', label: 'count', agg: 'count' }],
                filters: {
                    kind: 'group',
                    op: 'AND',
                    items: [
                        { kind: 'condition', field: 'tariff', operator: 'contains', value: 'ol' },
                        { kind: 'condition', field: 'duration_s', operator: 'between', value: '5', value2: '50' },
                        { kind: 'condition', field: 'duration_s', operator: 'in', value: '10, 20' },
                    ],
                },
            }),
            COLS,
        );
        expect(body?.measures).toEqual([{ agg: 'count' }]);
        expect(body?.filters).toEqual([
            { field: 'tariff', op: 'like', value: '%ol%' },
            { field: 'duration_s', op: '>=', value: 5 },
            { field: 'duration_s', op: '<=', value: 50 },
            { field: 'duration_s', op: 'in', value: [10, 20] },
        ]);
    });

    it('flattens nested AND groups; a single-item OR is that item', () => {
        const body = biQueryBody(
            spec({
                filters: {
                    kind: 'group',
                    op: 'AND',
                    items: [
                        { kind: 'condition', field: 'tariff', operator: 'isNotNull' },
                        {
                            kind: 'group',
                            op: 'OR',
                            items: [{ kind: 'condition', field: 'tariff', operator: '!=', value: 'silver' }],
                        },
                    ],
                },
            }),
            COLS,
        );
        expect(body?.filters).toEqual([
            { field: 'tariff', op: 'notNull' },
            { field: 'tariff', op: '!=', value: 'silver' },
        ]);
    });

    it('is null for a real OR branch — dropping a term would change the numbers', () => {
        expect(
            biQueryBody(
                spec({
                    filters: {
                        kind: 'group',
                        op: 'OR',
                        items: [
                            { kind: 'condition', field: 'tariff', operator: '=', value: 'gold' },
                            { kind: 'condition', field: 'tariff', operator: '=', value: 'silver' },
                        ],
                    },
                }),
                COLS,
            ),
        ).toBeNull();
    });

    it("carries a grouped column's time grain to the wire, and drops one that is not grouped", () => {
        // Without this the server groups by the un-truncated timestamp while the offline preview buckets,
        // so the same widget is right in a demo and wrong live.
        expect(biQueryBody(spec({ grains: { tariff: 'month' } }), COLS)?.grains).toEqual({ tariff: 'month' });
        // A stale grain left on a channel whose field has changed would 422 the whole widget.
        expect(biQueryBody(spec({ grains: { other: 'day' } }), COLS)?.grains).toBeUndefined();
        expect(biQueryBody(spec(), COLS)?.grains).toBeUndefined();
    });

    it('is null for a named-measure expression and for an empty projection', () => {
        expect(biQueryBody(spec({ measures: [{ id: 'm', expression: 'SUM(x)', label: 'm' }] }), COLS)).toBeNull();
        expect(biQueryBody(spec({ measures: [], groupBy: [] }), COLS)).toBeNull();
    });
});
