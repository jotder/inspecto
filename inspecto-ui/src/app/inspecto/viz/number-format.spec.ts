import { describe, expect, it } from 'vitest';
import { formatAxisTick, formatNumber, isRawNumberColumn } from './number-format';
import { tableColDefs } from './table-columns';

const plain = (n: number, d = 2) => new Intl.NumberFormat('en', { maximumFractionDigits: d }).format(n);

describe('number format (UIE-4)', () => {
    it('by default groups and caps at two decimals — no floating-point noise', () => {
        expect(formatNumber(13175.369999999999)).toBe(plain(13175.37));
        expect(formatNumber(41970.9999999999)).toBe(plain(41971));
    });

    it('currency uses the ISO code, compact abbreviates', () => {
        expect(formatNumber(7664957.18, { style: 'currency', currency: 'SAR', compact: true })).toBe('SAR 7.7M');
        expect(formatNumber(254729.26, { style: 'currency', currency: 'SAR', decimals: 0 })).toMatch(/^SAR 254,?729$/);
    });

    it('percent is already in points and is not multiplied', () => {
        expect(formatNumber(12.5, { style: 'percent', decimals: 1 })).toBe('12.5 %');
    });

    it('an unknown currency falls back to the number rather than to nothing', () => {
        expect(formatNumber(10, { style: 'currency', currency: 'NOT-A-CODE' })).toBe('10');
    });

    it('a missing value is an em dash, never a zero', () => {
        expect(formatNumber(Number.NaN)).toBe('—');
    });

    it('axis ticks are always compact', () => {
        expect(formatAxisTick(2_500_000)).toBe('2.5M');
    });

    it('ids and calendar parts are raw columns', () => {
        expect(isRawNumberColumn('case_id')).toBe(true);
        expect(isRawNumberColumn('year')).toBe(true);
        expect(isRawNumberColumn('msisdn')).toBe(true);
        expect(isRawNumberColumn('sum_exposure_sar')).toBe(false);
    });

    it('table cells format numbers, honour a column format and leave ids alone', () => {
        const [amount, id, text] = tableColDefs(['sum_exposure_sar', 'case_id', 'owner'], {
            columnFormats: { sum_exposure_sar: { style: 'currency', currency: 'SAR', decimals: 0 } },
        });
        const fmt = amount.valueFormatter as (p: { value: unknown }) => string;
        expect(fmt({ value: 13175.37 })).toMatch(/^SAR 13,?175$/);
        expect(id.valueFormatter).toBeUndefined();
        expect((text.valueFormatter as (p: { value: unknown }) => string)({ value: 'M. Otaibi' })).toBe('M. Otaibi');
    });
});
