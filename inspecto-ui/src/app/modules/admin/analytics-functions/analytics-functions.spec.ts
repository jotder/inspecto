import { describe, expect, it } from 'vitest';
import { AnalyticsFunction, AnalyticsParam } from 'app/inspecto/api';
import { costLabel, formatParam, formatParams, kernelLabel } from './analytics-functions';

const param = (o: Partial<AnalyticsParam>): AnalyticsParam => ({
    name: 'p',
    type: 'text',
    default: null,
    description: '',
    ...o,
});

describe('analytics-functions helpers', () => {
    it('writes a bounded number with its range and default', () => {
        expect(formatParam(param({ name: 'q', type: 'decimal', min: 0, max: 1 }))).toBe('q: decimal 0–1');
        expect(formatParam(param({ name: 'k', type: 'decimal', min: 0, max: 100, default: '1.5' }))).toBe(
            'k: decimal 0–100 (default 1.5)',
        );
    });

    it('lists a choice parameter by its values', () => {
        expect(
            formatParam(param({ name: 'mode', type: 'choice', choices: ['standard', 'lower'], default: 'standard' })),
        ).toBe('mode: standard | lower (default standard)');
    });

    it('says "none" for a function with no parameters and one line per parameter otherwise', () => {
        const f = { params: [] } as unknown as AnalyticsFunction;
        expect(formatParams(f)).toBe('none');
        const g = {
            params: [param({ name: 'a', type: 'column' }), param({ name: 'b', type: 'columns' })],
        } as unknown as AnalyticsFunction;
        expect(formatParams(g)).toBe('a: column\nb: columns');
    });

    it('labels costs and kernels', () => {
        expect(costLabel('linearithmic')).toBe('n log n');
        expect(kernelLabel('time_series', [{ id: 'time_series', label: 'Time series' }])).toBe('Time series');
        expect(kernelLabel('later', [])).toBe('later');
    });
});
