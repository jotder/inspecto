import { describe, expect, it } from 'vitest';
import { gaugeFill, gaugeScale } from './gauge-scale';

describe('gaugeScale', () => {
    it('defaults to 0–100, each end on its own', () => {
        expect(gaugeScale()).toEqual({ min: 0, max: 100, invalid: false });
        expect(gaugeScale({ max: 500 })).toEqual({ min: 0, max: 500, invalid: false });
        expect(gaugeScale({ min: 50 })).toEqual({ min: 50, max: 100, invalid: false });
        expect(gaugeScale({ min: -10, max: 10 })).toEqual({ min: -10, max: 10, invalid: false });
    });

    it('falls back to 0–100 when min >= max or an end is not finite', () => {
        expect(gaugeScale({ min: 10, max: 10 })).toEqual({ min: 0, max: 100, invalid: true });
        expect(gaugeScale({ min: 90, max: 10 })).toEqual({ min: 0, max: 100, invalid: true });
        expect(gaugeScale({ min: 200 })).toEqual({ min: 0, max: 100, invalid: true }); // 200 >= the default max
        expect(gaugeScale({ max: Number.NaN })).toEqual({ min: 0, max: 100, invalid: true });
    });
});

describe('gaugeFill', () => {
    it('is (value − min) / (max − min) of the arc, as a percent', () => {
        expect(gaugeFill(42, { min: 0, max: 100 })).toBe(42);
        expect(gaugeFill(250, { min: 0, max: 500 })).toBe(50);
        expect(gaugeFill(75, { min: 50, max: 150 })).toBe(25);
        expect(gaugeFill(0, { min: -10, max: 10 })).toBe(50);
    });

    it('clamps a value (or target) outside the scale to its end, and fills nothing for a non-number', () => {
        expect(gaugeFill(137, { min: 0, max: 100 })).toBe(100);
        expect(gaugeFill(20, { min: 50, max: 150 })).toBe(0);
        expect(gaugeFill(Number.NaN, { min: 0, max: 100 })).toBe(0);
    });
});
