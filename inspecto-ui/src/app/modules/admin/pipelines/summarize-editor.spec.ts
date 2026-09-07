import { describe, expect, it } from 'vitest';
import { MEASURE_AGGS } from './measure-grammar';
import { formatMeasure, formatMeasures, measureRowError, needsField, parseMeasures } from './summarize-editor';

describe('parseMeasures / formatMeasures', () => {
    it('round-trips every shape the grammar accepts, byte for byte', () => {
        const stored = ['count', 'sum(amount)', 'countDistinct(msisdn)', 'count(id)'];
        expect(formatMeasures(parseMeasures(stored))).toEqual(stored);
    });

    it('splits the shorthand into the two fields the table edits', () => {
        expect(parseMeasures(['sum(amount)'])).toEqual([{ agg: 'sum', field: 'amount' }]);
        expect(parseMeasures(['count'])).toEqual([{ agg: 'count', field: '' }]);
    });

    it('KEEPS a value it cannot parse instead of dropping it', () => {
        // 🔴 The data-loss case: a hand-written TOON measure, or an aggregate the engine grows before the
        // UI does. Opening the pane and applying it unchanged must not delete the author's value.
        const odd = ['median(x)', 'weird', 'sum(a) + 1'];
        const rows = parseMeasures(odd);
        expect(rows.every((r) => r.raw !== undefined)).toBe(true);
        expect(formatMeasures(rows)).toEqual(odd);
    });

    it('drops only genuinely blank rows — an unfinished new row is not a stored measure', () => {
        expect(formatMeasures([{ agg: '', field: '' }])).toEqual([]);
        expect(
            formatMeasures([
                { agg: 'sum', field: 'a' },
                { agg: '', field: '' },
            ]),
        ).toEqual(['sum(a)']);
    });

    it('handles a missing list rather than throwing', () => {
        expect(parseMeasures(undefined)).toEqual([]);
    });
});

describe('measureRowError', () => {
    it('is silent on a well-formed row and on an empty new row', () => {
        expect(measureRowError({ agg: 'sum', field: 'amount' })).toBeNull();
        expect(measureRowError({ agg: 'count', field: '' })).toBeNull();
        expect(measureRowError({ agg: '', field: '' })).toBeNull();
    });

    it('names the missing column before the grammar gets a chance to phrase it worse', () => {
        expect(measureRowError({ agg: 'sum', field: '  ' })).toBe('sum needs a column');
    });

    it('reports an unparsed row through the SAME message the list validator uses', () => {
        expect(measureRowError({ agg: '', field: '', raw: 'median(x)' })).toContain('is not an aggregate');
    });

    it('rejects a column name the engine would refuse', () => {
        expect(measureRowError({ agg: 'sum', field: '1amount' })).toContain('single column name');
    });
});

describe('needsField', () => {
    it('is false only for count — the one aggregate the engine compiles without a column', () => {
        expect(needsField('count')).toBe(false);
        for (const agg of MEASURE_AGGS.filter((a) => a !== 'count')) expect(needsField(agg)).toBe(true);
    });
});
