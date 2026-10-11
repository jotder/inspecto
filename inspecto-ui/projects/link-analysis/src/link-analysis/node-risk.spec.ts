import { describe, expect, it } from 'vitest';
import { factorLine, indexByKey, keyChunks, nodeScoresFor, parseWeights, riskMappingFor } from './node-risk';

describe('node-risk', () => {
    it('maps the telecom profile to its indicators Dataset and nothing for the generic one', () => {
        expect(riskMappingFor('telecom')!.indicators).toMatchObject({
            dataset: 'telecom_msisdn_indicators_dataset',
            keyCol: 'msisdn',
            scoreCol: 'indicator_score',
        });
        expect(riskMappingFor('generic')).toBeNull();
    });

    it('asks distinct trimmed keys in chunks of the server cap and indexes rows by the key as asked', () => {
        const keys = Array.from({ length: 1001 }, (_, i) => `k${i}`);
        expect(keyChunks([...keys, ' k0 ', '']).map((c) => c.length)).toEqual([500, 500, 1]);
        const by = indexByKey([
            { key: 'masked:a', row: { msisdn: 'masked:a', s: 1 } },
            { key: 'masked:a', row: { msisdn: 'masked:a', s: 2 } },
        ]);
        expect([...by.keys()]).toEqual(['masked:a']);
        expect(by.get('masked:a')).toEqual({ msisdn: 'masked:a', s: 1 });
    });

    it('builds nodeScores from positive scores, capped at 100 and at the entry cap', () => {
        const ind = new Map<string, Record<string, unknown>>([
            ['a', { s: 150 }],
            ['b', { s: 0 }],
            ['c', { s: '12.5' }],
            ['d', { s: 3 }],
        ]);
        expect(nodeScoresFor(['a', 'b', 'c', 'd', 'z'], ind, 's', 2)).toEqual({
            scores: { a: 100, c: 12.5 },
            matched: 2,
            dropped: 1,
        });
    });

    it('words a factor and parses a weight list', () => {
        expect(factorLine({ origin: 'o', distance: 3, weight: 0.35, contribution: 28.04 }, '99979100001')).toBe(
            '+28 from 99979100001, 3 hops, weight 0.35',
        );
        expect(factorLine({ origin: 'o', distance: 1, weight: 1, contribution: 5 }, 'x')).toContain('1 hop,');
        expect(parseWeights('1, 0.6 0.35')).toEqual({ weights: [1, 0.6, 0.35] });
        expect(parseWeights('')).toHaveProperty('error');
        expect(parseWeights('1,1,1,1,1,1,1')).toHaveProperty('error');
        expect(parseWeights('1, -1')).toHaveProperty('error');
    });
});
