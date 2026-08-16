import { describe, expect, it } from 'vitest';
import { ENRICHMENT_DEFAULT_PARTITIONS, enrichmentWiringDefaults } from './enrichment-wiring';

/**
 * The shared derived-wiring convention (definition-surface P6-c). Both hosts author against THIS —
 * the Onboarding stage silently, the Pipelines node dialog as the seed of an asked form — so a change
 * here changes both surfaces, which is the point of it being one function.
 */
describe('enrichmentWiringDefaults', () => {
    const seed = {
        enrichName: 'orders_enrich',
        pipelineId: 'orders',
        base: 'spaces/demo',
        inputDatabase: 'spaces/demo/data/orders/database',
        inputFormat: 'parquet',
    };

    it('reads the Stage-1 output and writes under the space enriched/ convention', () => {
        const w = enrichmentWiringDefaults(seed);
        expect(w.input['database']).toBe('spaces/demo/data/orders/database');
        expect(w.output['database']).toBe('spaces/demo/data/enriched/orders_enrich');
        expect(w.triggers).toEqual({ on_pipeline: 'orders' });
    });

    it('normalizes the input format and defaults both grains', () => {
        const w = enrichmentWiringDefaults(seed);
        expect(w.input['format']).toBe('PARQUET'); // `EnrichmentConfig.fromMap` compares uppercase
        expect(w.input['partitions']).toEqual([...ENRICHMENT_DEFAULT_PARTITIONS]);
        expect(w.output['partitions']).toEqual([...ENRICHMENT_DEFAULT_PARTITIONS]);
    });

    it('honours a host that knows the real input grain', () => {
        const w = enrichmentWiringDefaults({ ...seed, inputPartitions: ['dt'] });
        expect(w.input['partitions']).toEqual(['dt']);
        expect(w.output['partitions']).toEqual([...ENRICHMENT_DEFAULT_PARTITIONS]); // the enriched store keeps its own
    });

    it('leaves the input store BLANK when the host could not resolve one', () => {
        // An invented store path would read zero rows and look like it worked; an empty required
        // field is the honest state, and the form still asks for it.
        const w = enrichmentWiringDefaults({ ...seed, inputDatabase: undefined, inputFormat: undefined });
        expect(w.input['database']).toBe('');
        expect(w.input['format']).toBe('PARQUET');
    });

    it('never shares the partition arrays between two derivations', () => {
        const a = enrichmentWiringDefaults(seed);
        const b = enrichmentWiringDefaults(seed);
        (a.output['partitions'] as string[]).push('hour');
        expect(b.output['partitions']).toEqual([...ENRICHMENT_DEFAULT_PARTITIONS]);
    });
});
