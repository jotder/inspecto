import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { isSeedKey, jobSeedFromQuery } from './job-seed';

const q = (o: Record<string, string>) => convertToParamMap(o);

describe('jobSeedFromQuery', () => {
    it('reads a Job Type and its param.* values', () => {
        expect(
            jobSeedFromQuery(q({ create: '1', type: 'recon.run', 'param.reconciliation': 'switch_vs_billing' })),
        ).toEqual({ type: 'recon.run', params: { reconciliation: 'switch_vs_billing' } });
    });

    it('is undefined without a type, so a plain ?create=1 opens an empty dialog', () => {
        expect(jobSeedFromQuery(q({ create: '1' }))).toBeUndefined();
    });

    it('drops a type that is not an identifier', () => {
        for (const type of ['', ' recon.run', 'recon run', '../x', 'a'.repeat(80), '1abc', '<script>'])
            expect(jobSeedFromQuery(q({ type }))).toBeUndefined();
    });

    it('keeps a type with no params as a seed without a params key', () => {
        expect(jobSeedFromQuery(q({ type: 'recon.run' }))).toEqual({ type: 'recon.run' });
    });

    it('drops a param with a bad name, an over-long value or a control character, and keeps the rest', () => {
        const seed = jobSeedFromQuery(
            q({
                type: 'recon.run',
                'param.reconciliation': 'ok',
                'param.bad name': 'x',
                'param.': 'x',
                'param.long': 'v'.repeat(201),
                'param.ctl': 'a\nb',
                'param.1bad': 'x',
                other: 'ignored',
            }),
        );
        expect(seed).toEqual({ type: 'recon.run', params: { reconciliation: 'ok' } });
    });

    it('knows which query keys it owns, so the caller strips exactly those', () => {
        expect(['type', 'param.reconciliation', 'param.day'].every(isSeedKey)).toBe(true);
        expect(['create', 'q', 'typed', 'params.x'].some(isSeedKey)).toBe(false);
    });
});
