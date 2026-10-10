import { describe, expect, it } from 'vitest';
import {
    anomalyModelInitial,
    baselineStrip,
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
