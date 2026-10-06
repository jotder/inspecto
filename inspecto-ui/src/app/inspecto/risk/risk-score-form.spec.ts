import { describe, expect, it } from 'vitest';
import {
    factorDrafts,
    mapRiskRefusal,
    perEntityAlertRuleParams,
    riskScoreInitial,
    toRiskScoreContent,
} from './risk-score-form';

const STORED = {
    id: 'sim_box',
    name: 'SIM box',
    owner: 'ana',
    entityType: 'subscriber',
    highThreshold: 70,
    watchList: { list: 'watch_subs', ttlHours: 12 },
    retainRuns: 30,
    factors: [
        {
            id: 'short_calls',
            dataset: 'cdr',
            key: 'a_number',
            measure: 'count',
            weight: 3,
            cap: 40,
            filters: [
                { field: 'duration', op: '<', value: 10 },
                { field: 'cell', op: 'in', value: ['a', 'b'] },
            ],
            evidence: ['cell'],
        },
    ],
};

describe('risk-score-form', () => {
    it('round-trips a stored model through the form shapes, keeping envelope keys', () => {
        const out = toRiskScoreContent('sim_box', riskScoreInitial(STORED), factorDrafts(STORED), STORED);
        expect(out).toEqual(STORED);
    });

    it('drops the authored keys the author cleared (no watch list, no retention, no cap)', () => {
        const top = { ...riskScoreInitial(STORED), watchList: '', retention: '' };
        const f = factorDrafts(STORED).map((x) => ({ ...x, cap: '', filters: [], evidence: [] }));
        const out = toRiskScoreContent('sim_box', top, f, STORED);
        expect(out['watchList']).toBeUndefined();
        expect(out['retainRuns']).toBeUndefined();
        expect(out['retainDays']).toBeUndefined();
        expect((out['factors'] as Record<string, unknown>[])[0]).toEqual({
            id: 'short_calls',
            dataset: 'cdr',
            key: 'a_number',
            measure: 'count',
            weight: 3,
        });
    });

    it('keeps a non-numeric weight as text so the server names it (no client rule)', () => {
        const f = factorDrafts(STORED).map((x) => ({ ...x, weight: 'abc' }));
        const out = toRiskScoreContent('sim_box', riskScoreInitial(STORED), f);
        expect((out['factors'] as Record<string, unknown>[])[0]['weight']).toBe('abc');
    });

    it('maps a refusal onto the factor row and field it names', () => {
        expect(mapRiskRefusal('risk-score.factors[2].cap must be >= 0, got -1')).toEqual({
            factor: 2,
            factorField: 'cap',
        });
        expect(mapRiskRefusal("risk-score.factors[0]: unknown key 'x'")).toEqual({ factor: 0 });
        expect(mapRiskRefusal('risk-score.highThreshold must be in (0, 100], got 0')).toEqual({
            field: 'highThreshold',
        });
        expect(mapRiskRefusal('risk-score.retainDays must be a whole number >= 1, got 0')).toEqual({
            field: 'retainValue',
        });
        expect(mapRiskRefusal("risk-score id 'a b' must be letters, digits and '_'")).toEqual({ field: 'id' });
    });

    it('leaves an unplaceable refusal for the banner', () => {
        expect(mapRiskRefusal("risk-score column(s) [x] are not in the Schema of dataset 'cdr'")).toEqual({});
        expect(mapRiskRefusal('risk-score.scoresDataset is not authorable')).toEqual({});
    });

    it('prefills the per-entity Alert Rule from the OKF recipe', () => {
        expect(perEntityAlertRuleParams('sim_box', 70)).toEqual({
            newRule: '1',
            dataset: 'risk_scores_sim_box_latest',
            measure: 'max(score)',
            by: 'model,entity_key',
            comparator: 'gte',
            threshold: '70',
        });
        expect(perEntityAlertRuleParams('x', null)['threshold']).toBeUndefined();
    });
});
