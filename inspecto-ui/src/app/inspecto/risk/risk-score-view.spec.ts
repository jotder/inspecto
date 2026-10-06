import { describe, expect, it } from 'vitest';
import { displayEntityKey, riskModelView } from './risk-score-view';

describe('riskModelView', () => {
    it('summarises factors, retention and the derived outputs', () => {
        const v = riskModelView('file_name', {
            id: 'sim_box',
            entityType: 'msisdn',
            highThreshold: 70,
            retainRuns: 30,
            watchList: { list: 'watch', ttlHours: 6 },
            factors: [
                {
                    id: 'calls',
                    dataset: 'cdr',
                    key: 'a',
                    measure: 'count',
                    filters: [{}, {}],
                    weight: 2,
                    evidence: ['b'],
                },
            ],
        });
        expect(v.id).toBe('sim_box');
        expect(v.factors[0]).toMatchObject({ label: 'calls', filterCount: 2, weight: 2, cap: null, evidence: ['b'] });
        expect(v.retention).toBe('30 runs');
        expect(v.watchList).toBe('watch (6 h)');
        expect(v.outputs).toEqual(['risk_scores_sim_box', 'risk_scores_sim_box_latest']);
        expect(v.invalid).toBeNull();
    });

    it('flags a model with no factors and falls back to the component name', () => {
        const v = riskModelView('orphan', { factors: 'nope' });
        expect(v.id).toBe('orphan');
        expect(v.invalid).not.toBeNull();
        expect(v.retention).toBe('Kept forever');
    });
});

describe('displayEntityKey', () => {
    it('keeps only the last four characters', () => {
        expect(displayEntityKey('966501234567')).toBe('••••••••4567');
        expect(displayEntityKey('966501234567')).not.toContain('9665');
    });
    it('fully masks a short key and passes blank through', () => {
        expect(displayEntityKey('abc')).toBe('•••');
        expect(displayEntityKey('')).toBe('');
    });
});
