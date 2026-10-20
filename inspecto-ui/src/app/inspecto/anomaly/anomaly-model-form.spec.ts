import { describe, expect, it } from 'vitest';
import {
    AnomalyFeatureDraft,
    anomalyModelInitial,
    baselineStrip,
    emptyFeature,
    featureDrafts,
    mapAnomalyRefusal,
    toAnomalyModelContent,
} from './anomaly-model-form';

const STORED: Record<string, unknown> = {
    id: 'usage',
    owner: 'ana',
    bucket: 'day',
    entityType: 'subscriber',
    window: 28,
    seasonality: 'weekday',
    elevatedThreshold: 60,
    highThreshold: 80,
    peers: { by: ['tariff_plan'], dataset: 'subscribers', minGroupSize: 30 },
    watchList: { list: 'usage-watch', ttlHours: 12 },
    exclusionList: 'known-heavy',
    features: [
        {
            id: 'data_mb',
            label: 'Data (MB)',
            dataset: 'cdr_data',
            key: 'msisdn',
            time: 'event_time',
            measure: 'sum(volume_mb)',
            direction: 'up',
            weight: 1,
            filters: [{ field: 'direction', op: 'eq', value: 'MO' }],
            unit: 5,
        },
    ],
};

describe('anomaly-model-form', () => {
    it('round-trips a stored model through the form shape, keeping keys the form does not model', () => {
        const top = anomalyModelInitial(STORED);
        expect(top['peersBy']).toEqual(['tariff_plan']);
        expect(top['watchTtlHours']).toBe(12);
        const out = toAnomalyModelContent('usage', top, featureDrafts(STORED), STORED);
        expect(out).toEqual(STORED);
    });

    it('carries method and forest through load, edit and save verbatim', () => {
        const stored = { ...STORED, method: 'isolation_forest', forest: { trees: 200, sampleSize: 512, seed: 7 } };
        const top = anomalyModelInitial(stored);
        expect(top['method']).toBe('isolation_forest');
        expect(top['forestTrees']).toBe(200);
        const out = toAnomalyModelContent('usage', top, featureDrafts(stored), stored);
        expect(out['method']).toBe('isolation_forest');
        expect(out['forest']).toEqual({ trees: 200, sampleSize: 512, seed: 7 });
        expect(out).toEqual(stored);
        // a partial forest stays partial; defaults are never invented
        const partial = { ...STORED, method: 'isolation_forest', forest: { seed: 3 } };
        expect(toAnomalyModelContent('usage', anomalyModelInitial(partial), featureDrafts(partial), partial)).toEqual(
            partial,
        );
        // method only: no forest key appears
        const bare = { ...STORED, method: 'isolation_forest' };
        expect(toAnomalyModelContent('usage', anomalyModelInitial(bare), featureDrafts(bare), bare)).toEqual(bare);
    });

    it('drops forest when the method goes back to robust, keeps an explicit robust_z, and places forest refusals', () => {
        const stored = { ...STORED, method: 'isolation_forest', forest: { trees: 200 } };
        const back = { ...anomalyModelInitial(stored), method: 'robust_z' };
        const out = toAnomalyModelContent('usage', back, featureDrafts(stored), stored);
        expect('method' in out).toBe(false);
        expect('forest' in out).toBe(false);
        const explicit = { ...STORED, method: 'robust_z' };
        expect(
            toAnomalyModelContent('usage', anomalyModelInitial(explicit), featureDrafts(explicit), explicit),
        ).toEqual(explicit);
        expect(mapAnomalyRefusal('anomaly-model.forest.trees must be within 1..1000, got 0')).toEqual({
            field: 'forestTrees',
        });
        expect(mapAnomalyRefusal('anomaly-model.forest.seed must be a whole number, got 1.5')).toEqual({
            field: 'forestSeed',
        });
        expect(mapAnomalyRefusal("anomaly-model.method must be robust_z or isolation_forest, got 'x'")).toEqual({
            field: 'method',
        });
    });

    it('leaves blank optionals out instead of writing nulls', () => {
        const out = toAnomalyModelContent(
            'm',
            { entityType: 'sim', window: 14, seasonality: 'none', elevatedThreshold: 50, highThreshold: 90 },
            featureDrafts({ features: [{ id: 'f', dataset: 'd', key: 'k', time: 't', measure: 'count' }] }),
        );
        expect(Object.keys(out).sort()).toEqual(
            ['elevatedThreshold', 'entityType', 'features', 'highThreshold', 'id', 'seasonality', 'window'].sort(),
        );
        expect((out['features'] as Record<string, unknown>[])[0]).toEqual({
            id: 'f',
            dataset: 'd',
            key: 'k',
            time: 't',
            measure: 'count',
            direction: 'up',
            weight: 1,
        });
    });

    it('places a server refusal on the field or Feature row it names', () => {
        expect(mapAnomalyRefusal("anomaly-model.features[1].measure 'x' is not a Measure")).toEqual({
            feature: 1,
            featureField: 'measure',
        });
        expect(mapAnomalyRefusal('anomaly-model.window must be 1..90, got 120')).toEqual({ field: 'window' });
        expect(mapAnomalyRefusal('anomaly-model thresholds need 0 < elevatedThreshold < highThreshold <= 100')).toEqual(
            {
                field: 'highThreshold',
            },
        );
        expect(mapAnomalyRefusal('anomaly-model.peers.by may not include the key')).toEqual({ field: 'peersBy' });
        expect(mapAnomalyRefusal('something else')).toEqual({});
    });

    it('reads stored filters and unit as text, keeping the stored values', () => {
        const [f] = featureDrafts({
            features: [
                {
                    id: 'f',
                    filters: [{ field: 'plan', op: 'in', value: ['POST', 'PRE'] }, 'not-a-map'],
                    unit: 5,
                    note: 'kept',
                },
            ],
        });
        expect(f.filters).toEqual([{ field: 'plan', op: 'in', value: 'POST, PRE', raw: expect.any(Object) }]);
        expect(f.unit).toBe('5');
        expect(f.extra).toEqual({ note: 'kept' });
        expect(featureDrafts({ features: [{ id: 'g' }] })[0]).toEqual(
            expect.objectContaining({ filters: [], unit: '' }),
        );
    });

    it('writes an untouched filter value back as stored, and re-types an edited one by operator', () => {
        const stored = [
            { field: 'plan', op: 'in', value: ['POST', 'PRE'] },
            { field: 'mb', op: 'gte', value: 0, note: 'kept' },
        ];
        const write = (edit: (f: AnomalyFeatureDraft) => void): unknown => {
            const [f] = featureDrafts({ features: [{ id: 'f', filters: stored }] });
            edit(f);
            return (toAnomalyModelContent('m', {}, [f])['features'] as Record<string, unknown>[])[0]['filters'];
        };
        expect(write(() => undefined)).toEqual(stored); // number 0 stays 0
        expect(write((f) => (f.filters[1].value = '12'))).toEqual([
            stored[0],
            { field: 'mb', op: 'gte', value: '12', note: 'kept' },
        ]);
        expect(write((f) => (f.filters[1].op = 'notNull'))).toEqual([
            stored[0],
            { field: 'mb', op: 'notNull', note: 'kept' },
        ]);
        expect(write((f) => Object.assign(f.filters[1], { op: 'in', value: '1, 2,,3' }))).toEqual([
            stored[0],
            { field: 'mb', op: 'in', value: ['1', '2', '3'], note: 'kept' },
        ]);
    });

    it('keeps an unchanged unit as stored, writes an edited one as a number and drops a blank one', () => {
        const save = (f: AnomalyFeatureDraft): Record<string, unknown> =>
            (toAnomalyModelContent('m', {}, [f])['features'] as Record<string, unknown>[])[0];
        const [kept] = featureDrafts({ features: [{ id: 'f', unit: '5' }] });
        expect(save(kept)['unit']).toBe('5');
        const [edited] = featureDrafts({ features: [{ id: 'f', unit: 5 }] });
        edited.unit = '0.5';
        expect(save(edited)['unit']).toBe(0.5);
        const [blank] = featureDrafts({ features: [{ id: 'f', unit: 5, filters: [{ field: 'a', op: '=' }] }] });
        blank.unit = ' ';
        blank.filters = [];
        expect(save(blank)).not.toHaveProperty('unit');
        expect(save(blank)).not.toHaveProperty('filters');
        expect(save(emptyFeature(1))).not.toHaveProperty('unit');
    });

    it('lays out the baseline strip so the observed point sits outside a tight usual range', () => {
        const s = baselineStrip(100, 5, 200)!;
        expect(s.lo).toBeLessThan(s.median);
        expect(s.hi).toBeGreaterThan(s.median);
        expect(s.observed!).toBeGreaterThan(s.hi);
        expect(s.observed!).toBeLessThanOrEqual(1);
        expect(baselineStrip(null, 1, 2)).toBeNull();
        const flat = baselineStrip(3, 0, 3)!;
        expect(flat.median).toBeCloseTo(0.5);
    });
});
