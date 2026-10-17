import { describe, expect, it } from 'vitest';
import { ColumnMeta, emptyGroup } from 'app/inspecto/query';
import { aggExpression, buildMeasure, compileSpec, measureId } from './query-spec';
import { Aggregation, QuerySpec } from './viz-types';
import MEASURE_CONTRACT from 'app/inspecto/contracts/measure-grammar.contract.json';
import { MEASURE_AGGS } from 'app/inspecto/query/measure-grammar';
import { runSql } from 'app/inspecto/data-table/sql/sql-run';

const COLS: ColumnMeta[] = [
    { name: 'tariff', type: 'string' },
    { name: 'duration_s', type: 'number' },
];

describe('aggExpression / measureId / buildMeasure', () => {
    it('compiles aggregations with quoted identifiers', () => {
        expect(aggExpression('sum', 'duration_s')).toBe('SUM("duration_s")');
        expect(aggExpression('avg', 'duration_s')).toBe('AVG("duration_s")');
        expect(aggExpression('count', 'duration_s')).toBe('COUNT(*)');
        expect(aggExpression('countDistinct', 'tariff')).toBe('COUNT(DISTINCT "tariff")');
    });

    it('compiles the statistics to DuckDB-named sample spread and continuous quantiles (D-AN5)', () => {
        expect(aggExpression('median', 'd')).toBe('QUANTILE_CONT("d", 0.5)');
        expect(aggExpression('stddev', 'd')).toBe('STDDEV_SAMP("d")');
        expect(aggExpression('variance', 'd')).toBe('VAR_SAMP("d")');
        expect(aggExpression('p05', 'd')).toBe('QUANTILE_CONT("d", 0.05)');
        expect(aggExpression('p25', 'd')).toBe('QUANTILE_CONT("d", 0.25)');
        expect(aggExpression('p75', 'd')).toBe('QUANTILE_CONT("d", 0.75)');
        expect(aggExpression('p90', 'd')).toBe('QUANTILE_CONT("d", 0.9)');
        expect(aggExpression('p95', 'd')).toBe('QUANTILE_CONT("d", 0.95)');
        expect(aggExpression('p99', 'd')).toBe('QUANTILE_CONT("d", 0.99)');
        expect(measureId('p95', 'latency_ms')).toBe('p95_latency_ms');
    });

    /**
     * The twin half of `MeasureStatisticsExecutionTest`: the offline engine returns the SAME golden values DuckDB
     * does, read from the one committed contract file — so a widget reads the same number offline and live.
     */
    it('runs every statistic offline to the contract goldens DuckDB is held to', async () => {
        const stats = MEASURE_CONTRACT.statistics;
        const rows = stats.values.map((amount) => ({ amount }));
        const expected = stats.expected as Record<string, number>;
        expect(Object.keys(expected)).toEqual(MEASURE_AGGS.slice(MEASURE_AGGS.indexOf('median')));
        for (const [agg, value] of Object.entries(expected)) {
            const res = await runSql(`SELECT ${aggExpression(agg as Aggregation, 'amount')} AS v FROM "t"`, 't', rows);
            expect(res.ok, `${agg}: ${res.error}`).toBe(true);
            expect(res.rows[0]['v'], agg).toBeCloseTo(value, 9);
        }
        const even = await runSql(
            `SELECT ${aggExpression('median', 'amount')} AS v FROM "t"`,
            't',
            stats.evenValues.map((amount) => ({ amount })),
        );
        expect(even.rows[0]['v']).toBe(stats.evenMedian);
    });

    it('groups a statistic per category offline, NULL for a sample spread over one value', async () => {
        const rows = [
            { k: 'a', amount: 10 },
            { k: 'a', amount: 30 },
            { k: 'b', amount: 7 },
        ];
        const sql = `SELECT k, ${aggExpression('p95', 'amount')} AS p, ${aggExpression('stddev', 'amount')} AS s FROM "t" GROUP BY k`;
        const res = await runSql(sql, 't', rows);
        expect(res.ok, res.error).toBe(true);
        const byKey = Object.fromEntries(res.rows.map((r) => [r['k'], r]));
        expect(byKey['a']['p']).toBeCloseTo(29, 9);
        expect(byKey['a']['s']).toBeCloseTo(Math.sqrt(200), 9);
        expect(byKey['b']['p']).toBe(7);
        expect(byKey['b']['s']).toBeNull();
    });

    it('produces identifier-safe measure ids', () => {
        expect(measureId('sum', 'duration_s')).toBe('sum_duration_s');
        expect(measureId('count', 'x')).toBe('count');
    });

    it('builds a measure with id/expression/label plus the structured {agg, field} origin (M2)', () => {
        const m = buildMeasure('sum', 'duration_s');
        expect(m).toEqual({
            id: 'sum_duration_s',
            expression: 'SUM("duration_s")',
            label: 'sum(duration_s)',
            agg: 'sum',
            field: 'duration_s',
        });
        // `count` takes no column — the stamp carries the agg only.
        expect(buildMeasure('count', 'x')).toEqual({
            id: 'count',
            expression: 'COUNT(*)',
            label: 'count(x)',
            agg: 'count',
        });
    });
});

describe('compileSpec', () => {
    it('aggregates with GROUP BY when measures + groupBy are present', () => {
        const spec: QuerySpec = {
            datasetId: 'cdr',
            sourceName: 'cdr',
            groupBy: ['tariff'],
            measures: [buildMeasure('sum', 'duration_s')],
        };
        const sql = compileSpec(spec, COLS);
        expect(sql).toContain('SELECT "tariff", SUM("duration_s") AS "sum_duration_s"');
        expect(sql).toContain('FROM "cdr"');
        expect(sql).toContain('GROUP BY "tariff"');
    });

    it('projects raw group-by columns when there are no measures (no GROUP BY)', () => {
        const spec: QuerySpec = { datasetId: 'cdr', sourceName: 'cdr', groupBy: ['tariff'], measures: [] };
        const sql = compileSpec(spec);
        expect(sql).toContain('SELECT "tariff"');
        expect(sql).not.toContain('GROUP BY');
    });

    it('emits WHERE from filters, ORDER BY and LIMIT', () => {
        const where = emptyGroup('AND');
        where.items.push({ kind: 'condition', field: 'tariff', operator: '=', value: 'premium' });
        const spec: QuerySpec = {
            datasetId: 'cdr',
            sourceName: 'cdr',
            groupBy: ['tariff'],
            measures: [buildMeasure('sum', 'duration_s')],
            filters: where,
            orderBy: [{ field: 'sum_duration_s', dir: 'desc' }],
            limit: 10,
        };
        const sql = compileSpec(spec, COLS);
        expect(sql).toContain(`WHERE "tariff" = 'premium'`);
        expect(sql).toContain('ORDER BY "sum_duration_s" DESC');
        expect(sql).toContain('LIMIT 10');
    });
});
