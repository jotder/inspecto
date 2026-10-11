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
