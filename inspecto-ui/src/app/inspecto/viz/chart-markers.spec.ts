import { describe, expect, it } from 'vitest';
import {
    average,
    kpiBands,
    markerAltText,
    needsKpi,
    ordinal,
    percentile,
    ResolvedMarker,
    resolveMarkers,
} from './chart-markers';

const VALUES = [10, 20, 30, 40, 50];

describe('chart markers — statistics', () => {
    it('percentile interpolates between the closest ranks (DuckDB quantile_cont)', () => {
        expect(percentile(VALUES, 50)).toBe(30);
        expect(percentile(VALUES, 90)).toBe(46);
        expect(percentile([5], 90)).toBe(5);
        expect(percentile([], 90)).toBeNull();
        expect(percentile([3, Number.NaN, 1], 50)).toBe(2);
    });

    it('average ignores non-finite values and is null for none', () => {
        expect(average(VALUES)).toBe(30);
        expect(average([Number.NaN])).toBeNull();
    });

    it('ordinal words a percentile', () => {
        expect([1, 2, 3, 11, 12, 13, 22, 90].map(ordinal)).toEqual([
            '1st',
            '2nd',
            '3rd',
            '11th',
            '12th',
            '13th',
            '22nd',
            '90th',
        ]);
    });
});

describe('resolveMarkers', () => {
    it('words every line with its value, in the widget format', () => {
        const out = resolveMarkers(
            [{ kind: 'constant', value: 25, label: 'Budget' }, { kind: 'average' }, { kind: 'percentile', p: 90 }],
            { values: VALUES, format: { style: 'currency', currency: 'SAR' } },
        );
        expect(out.map((m) => m.text)).toEqual(['Budget SAR 25.00', 'Average SAR 30.00', '90th percentile SAR 46.00']);
        expect(out.every((m) => m.type === 'line' && m.dashed)).toBe(true);
    });

    it('a target line takes its own value, else the KPI target, else the Widget target — solid, info tone', () => {
        const own = resolveMarkers([{ kind: 'target', value: 7 }], { values: [], target: 5, kpi: { target: 6 } });
        const kpi = resolveMarkers([{ kind: 'target' }], { values: [], target: 5, kpi: { target: 6 } });
        const widget = resolveMarkers([{ kind: 'target' }], { values: [], target: 5 });
        expect([own[0], kpi[0], widget[0]].map((m) => (m.type === 'line' ? m.value : null))).toEqual([7, 6, 5]);
        expect(widget[0]).toMatchObject({ type: 'line', dashed: false, tone: 'info', text: 'Target 5' });
    });

    it('drops a marker it cannot place — never drawn at a guess', () => {
        const out = resolveMarkers(
            [
                { kind: 'target' },
                { kind: 'average' },
                { kind: 'percentile', p: 0 },
                { kind: 'percentile', p: 12.5 },
                { kind: 'band', tone: 'success' },
                { kind: 'band', from: 5, to: 5, tone: 'success' },
                { kind: 'rag' },
            ],
            { values: [] },
        );
        expect(out).toEqual([]);
    });

    it('a band orders its ends and words an open side', () => {
        const [closed, open] = resolveMarkers(
            [
                { kind: 'band', from: 90, to: 80, tone: 'warning', label: 'Watch' },
                { kind: 'band', from: 95, tone: 'success' },
            ],
            { values: [] },
        );
        expect(closed).toEqual({ type: 'band', from: 80, to: 90, tone: 'warning', text: 'Watch 80–90' });
        expect(open).toEqual({ type: 'band', from: 95, to: null, tone: 'success', text: 'Band from 95' });
    });

    it('rag draws the bound KPI bands, prefixed with its label', () => {
        const out = resolveMarkers([{ kind: 'rag', label: 'SLA' }], {
            values: [],
            kpi: { direction: 'up', bands: { green: 95, amber: 90 } },
        });
        expect(out.map((m) => m.text)).toEqual(['SLA: Red below 90', 'SLA: Amber 90–95', 'SLA: Green at or above 95']);
    });
});

describe('kpiBands — the server rule (KpiDefinition.rag)', () => {
    const tones = (ms: ResolvedMarker[]) => ms.map((m) => (m.type === 'band' ? [m.from, m.to, m.tone] : null));

    it('up: red below amber, amber to green, green above', () => {
        expect(tones(kpiBands({ direction: 'up', bands: { green: 95, amber: 90 } }))).toEqual([
            [null, 90, 'error'],
            [90, 95, 'warning'],
            [95, null, 'success'],
        ]);
    });

    it('down: green below green, amber to amber, red above', () => {
        expect(tones(kpiBands({ direction: 'down', bands: { green: 2, amber: 5 } }))).toEqual([
            [null, 2, 'success'],
            [2, 5, 'warning'],
            [5, null, 'error'],
        ]);
    });

    it('band: green inside green, amber inside amber, red outside — a zero-width zone is dropped', () => {
        expect(tones(kpiBands({ direction: 'band', bands: { green: [40, 60], amber: [30, 60] } }))).toEqual([
            [null, 30, 'error'],
            [30, 40, 'warning'],
            [40, 60, 'success'],
            [60, null, 'error'],
        ]);
    });

    it('drops the empty amber zone when green equals amber (the server allows it)', () => {
        expect(tones(kpiBands({ direction: 'up', bands: { green: 95, amber: 95 } }))).toEqual([
            [null, 95, 'error'],
            [95, null, 'success'],
        ]);
    });

    it('none without bands', () => {
        expect(kpiBands({ direction: 'up', target: 9 })).toEqual([]);
        expect(kpiBands(null)).toEqual([]);
    });
});

describe('chart markers — text and KPI need', () => {
    it('states every marker in one sentence', () => {
        const out = resolveMarkers(
            [
                { kind: 'constant', value: 3 },
                { kind: 'band', to: 2, tone: 'error' },
            ],
            {
                values: [],
            },
        );
        expect(markerAltText(out)).toBe('Markers: Reference 3; Band below 2.');
        expect(markerAltText([])).toBe('');
    });

    it('needs the KPI only for rag and a target line without its own value', () => {
        expect(needsKpi([{ kind: 'target', value: 1 }, { kind: 'average' }])).toBe(false);
        expect(needsKpi([{ kind: 'target' }])).toBe(true);
        expect(needsKpi([{ kind: 'rag' }])).toBe(true);
        expect(needsKpi(undefined)).toBe(false);
    });
});
