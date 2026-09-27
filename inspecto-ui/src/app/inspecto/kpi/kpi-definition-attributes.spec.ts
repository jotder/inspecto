import { describe, expect, it } from 'vitest';
import { fromKpiContent, kpiIdFor, toKpiContent } from './kpi-definition-attributes';

describe('KPI definition form mapping (ASSURE-KPI-DEFINITIONS-1)', () => {
    const base = {
        title: ' Refund exposure ',
        dataset: 'orders',
        measure: 'sum(amount)',
        timeField: 'order_date',
        grain: 'month',
    };

    it('writes threshold bands for up/down only when both ends are given', () => {
        expect(toKpiContent({ ...base, direction: 'down', green: 5, amber: 8, target: '' })).toEqual({
            title: 'Refund exposure',
            dataset: 'orders',
            measure: 'sum(amount)',
            timeField: 'order_date',
            grain: 'month',
            comparison: 'previous',
            direction: 'down',
            bands: { green: 5, amber: 8 },
        });
        expect(toKpiContent({ ...base, green: 5 }).bands).toBeUndefined();
    });

    it('writes interval bands for a band direction and round-trips them', () => {
        const c = toKpiContent({
            ...base,
            direction: 'band',
            greenLo: 95,
            greenHi: 105,
            amberLo: 90,
            amberHi: 110,
            target: 100,
        });
        expect(c.bands).toEqual({ green: [95, 105], amber: [90, 110] });
        expect(c.target).toBe(100);
        expect(toKpiContent(fromKpiContent(c))).toEqual(c);
    });

    it('derives a store-safe id from the title', () => {
        expect(kpiIdFor('Refund exposure (SAR)')).toBe('refund_exposure_sar');
        expect(kpiIdFor('%%')).toBe('kpi');
    });
});
