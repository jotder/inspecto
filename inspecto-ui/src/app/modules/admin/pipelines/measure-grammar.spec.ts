import { describe, expect, it, test } from 'vitest';
import { FormControl } from '@angular/forms';

import MEASURE_CONTRACT from 'app/inspecto/mock/measure-grammar.contract.json';

import { MEASURE_AGGS, groupByError, groupByValidator, measureError, measuresValidator } from './measure-grammar';

/**
 * The client half of the measure-grammar contract. `MeasureGrammarContractTest` (Java) compares the same
 * committed JSON against `MeasureCompiler.AGGS` and `MeasureCompiler.SAFE_IDENT`, so neither side can move
 * alone.
 */
describe('measure grammar', () => {
    it('takes its aggregate list from the committed contract, not a local copy', () => {
        expect(MEASURE_AGGS).toEqual(MEASURE_CONTRACT.aggregations);
        // Pinned so a silently-emptied contract file can't turn the validator into a no-op that
        // accepts every aggregate name.
        expect(MEASURE_AGGS).toEqual(['count', 'countDistinct', 'sum', 'avg', 'min', 'max']);
    });

    it('takes its identifier pattern from the committed contract too', () => {
        expect(MEASURE_CONTRACT.identifier).toBe('[A-Za-z_][A-Za-z0-9_]*');
    });

    describe('measureError', () => {
        it('accepts the bare count literal MaterializeTask special-cases', () => {
            expect(measureError('count')).toBeNull();
        });

        it('accepts every contract aggregate over a safe identifier', () => {
            for (const agg of MEASURE_AGGS) {
                expect(measureError(`${agg}(amount_cents)`)).toBeNull();
                expect(measureError(`${agg}(_leading)`)).toBeNull();
            }
        });

        it('leaves blanks to the required validator', () => {
            expect(measureError('')).toBeNull();
            expect(measureError('   ')).toBeNull();
        });

        it('names the allowed aggregates when the function is unknown', () => {
            const error = measureError('median(x)');
            expect(error).toBe('"median" is not an aggregate — use count, countDistinct, sum, avg, min, max');
        });

        it('rejects a measure with no call brackets', () => {
            expect(measureError('sum amount')).toBe('"sum amount" must be count or agg(column) — for example sum(amount)');
            expect(measureError('total')).toBe('"total" must be count or agg(column) — for example sum(amount)');
        });

        it('rejects a field that is not a single SQL-safe identifier', () => {
            const expected = (m: string) => `"${m}" needs a single column name in the brackets, starting with a letter or _`;
            expect(measureError('sum(a, b)')).toBe(expected('sum(a, b)'));
            expect(measureError('sum(amount * 2)')).toBe(expected('sum(amount * 2)'));
            expect(measureError('sum(1amount)')).toBe(expected('sum(1amount)'));
            expect(measureError('sum("amount")')).toBe(expected('sum("amount")'));
        });

        it('rejects count() — stricter than the engine on purpose', () => {
            // MeasureCompiler skips safeIdent for `count`, so the engine would compile COUNT(*). An empty
            // field is a typo every time, and stricter-than-server is the safe direction for a form.
            expect(measureError('count()')).not.toBeNull();
        });

        it('ignores surrounding whitespace', () => {
            expect(measureError('  sum(amount)  ')).toBeNull();
        });
    });

    describe('measuresValidator', () => {
        const validator = measuresValidator();

        it('passes a list of well-formed measures', () => {
            expect(validator(new FormControl(['count', 'sum(amount)', 'min(ts)']))).toBeNull();
        });

        it('reports the first offending entry as a verbatim message', () => {
            const result = validator(new FormControl(['sum(amount)', 'median(x)', 'nope']));
            expect(result).toEqual({
                message: '"median" is not an aggregate — use count, countDistinct, sum, avg, min, max',
            });
        });

        it('ignores a null/absent value — required owns emptiness', () => {
            expect(validator(new FormControl(null))).toBeNull();
            expect(validator(new FormControl([]))).toBeNull();
        });

        it('leaves non-string entries to the spec-level list check', () => {
            expect(validator(new FormControl([42]))).toBeNull();
        });
    });

    describe('groupByError', () => {
        it('accepts a SQL-safe column name', () => {
            expect(groupByError('region')).toBeNull();
            expect(groupByError('_tier')).toBeNull();
            expect(groupByError('amount_cents_2')).toBeNull();
        });

        it('leaves blanks to the required validator', () => {
            expect(groupByError('')).toBeNull();
            expect(groupByError('   ')).toBeNull();
        });

        it('anchors the contract pattern — a leading digit is not a partial match', () => {
            // The pattern travels unanchored (Java applies `matches()`), so an unanchored `test()` here
            // would find `amount` inside `1amount` and pass. This is that regression.
            expect(groupByError('1amount')).toBe('"1amount" must be a single column name, starting with a letter or _');
        });

        it('rejects a measure pasted into the grouping field with a message that redirects', () => {
            expect(groupByError('sum(amount)')).toBe(
                '"sum(amount)" is a column to group by, not an aggregate — put aggregates in Measures',
            );
            expect(groupByError('count')).toBeNull(); // a column may legitimately be named `count`
        });

        it('rejects anything that is not one bare identifier', () => {
            const expected = (c: string) => `"${c}" must be a single column name, starting with a letter or _`;
            expect(groupByError('region, tier')).toBe(expected('region, tier'));
            expect(groupByError('"region"')).toBe(expected('"region"'));
            expect(groupByError('region name')).toBe(expected('region name'));
        });

        it('ignores surrounding whitespace', () => {
            expect(groupByError('  region  ')).toBeNull();
        });
    });

    describe('groupByValidator', () => {
        const validator = groupByValidator();

        it('passes a list of well-formed columns', () => {
            expect(validator(new FormControl(['region', '_tier']))).toBeNull();
        });

        it('reports the first offending entry as a verbatim message', () => {
            const result = validator(new FormControl(['region', 'region name', '1bad']));
            expect(result).toEqual({ message: '"region name" must be a single column name, starting with a letter or _' });
        });

        it('ignores a null/absent value — required owns emptiness', () => {
            expect(validator(new FormControl(null))).toBeNull();
            expect(validator(new FormControl([]))).toBeNull();
        });
    });
});
