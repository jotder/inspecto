import { describe, expect, it } from 'vitest';
import { buildWidget, isKpiBound } from './widget-types';

describe('buildWidget', () => {
    it('uses the name as id and carries dataset/viz/controls', () => {
        const controls = { x: [{ field: 'tariff' }], y: [{ field: 'duration_s', agg: 'sum' as const }] };
        const w = buildWidget('dur_by_tariff', 'cdr_sample', 'bar', controls);
        expect(w.id).toBe('dur_by_tariff');
        expect(w.datasetId).toBe('cdr_sample');
        expect(w.vizType).toBe('bar');
        expect(w.controls).toBe(controls);
    });
});

describe('isKpiBound (LIVEFIX2 #2)', () => {
    it('is a KPI tile with a kpiId and no Dataset — anything else is dataset- or view-bound', () => {
        const kpi = { vizType: 'kpi', datasetId: '', options: { kpi: { kpiId: 'leakage_found' } } };
        expect(isKpiBound(kpi)).toBe(true);
        expect(isKpiBound({ ...kpi, datasetId: 'ds' })).toBe(false);
        expect(isKpiBound({ ...kpi, options: {} })).toBe(false);
        expect(isKpiBound({ ...kpi, vizType: 'bar' })).toBe(false);
    });
});
