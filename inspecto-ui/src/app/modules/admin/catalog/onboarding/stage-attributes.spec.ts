import { describe, expect, it } from 'vitest';
import { COLLECTOR_ATTRIBUTES, OUTPUT_ATTRIBUTES } from 'app/inspecto/component-model';
import { NODE_TYPES } from 'app/inspecto/mock/handlers/pipelines.handler';
import { REFERENCE_STAGES, STREAM_STAGES } from './onboarding-state.service';
import { parsingAttributesFor } from 'app/inspecto/grammar';
import { stageAttributesFor } from './stage-attributes';

/**
 * W4a/U-B: the guided rail is a view over the head of the SAME graph the Pipelines editor edits.
 * Two properties pin that: every stage names an engine-real node type, and the stages that render a
 * schema form resolve to the SAME shared spec tables the Pipelines dialog resolves for that node
 * type (both sides assert `toBe` against the component-model consts, so identity is transitive —
 * no cross-feature import needed, and no second table to drift the way the pre-U-D palettes did).
 */
describe('onboarding stage model (W4a)', () => {
    const ALL_STAGES = [...STREAM_STAGES, ...REFERENCE_STAGES];

    it('every stage authors an engine-real node type (BuiltinNodeType, via the pinned palette port)', () => {
        const engineTypes = new Set(NODE_TYPES.map((t) => t.type));
        for (const stage of ALL_STAGES) {
            expect(engineTypes.has(stage.nodeType), `${stage.id} → ${stage.nodeType}`).toBe(true);
        }
    });

    it('maps each stage to the config block it owns (findings attribution derives from this)', () => {
        const blocks = Object.fromEntries(STREAM_STAGES.map((s) => [s.id, s.block]));
        expect(blocks).toEqual({
            collection: 'collector',
            parsing: 'parsing',
            schema: 'processing',
            enrichment: null, // companion `<name>_enrich` config, not a block of the pipeline TOON
            publish: 'output',
        });
        // The Reference keys stage authors the same schema artifact under the same block.
        expect(REFERENCE_STAGES.find((s) => s.id === 'keys')?.block).toBe('processing');
        expect(REFERENCE_STAGES.find((s) => s.id === 'keys')?.nodeType).toBe('parser');
    });

    it('collection renders the SAME shared collector table the Pipelines acquisition node renders', () => {
        expect(stageAttributesFor('collection')).toBe(COLLECTOR_ATTRIBUTES);
    });

    /**
     * Collector-config unification (2026-08-04): file dedup executes in the acquisition poll cycle,
     * so the stage that configures collection configures dedup too — both surfaces render the whole
     * collector table, and the `transform.dedup.fingerprint` node was removed.
     */
    it('offers the duplicate-detection keys on the collection stage', () => {
        const keys = stageAttributesFor('collection')!.map((s) => s.key);
        expect(keys).toContain('duplicate__mode');
        expect(keys).toContain('duplicate__on_change');
    });

    it('publish renders the SAME shared output table the Pipelines sink nodes render', () => {
        expect(stageAttributesFor('publish')).toBe(OUTPUT_ATTRIBUTES);
    });

    it('parsing resolves the per-frontend built-in tables', () => {
        expect(stageAttributesFor('parsing', { frontend: 'json' }))
            .toEqual(parsingAttributesFor('json'));
        expect(stageAttributesFor('parsing')).toEqual(parsingAttributesFor('delimited'));
    });

    it('bespoke nested-list stages have no spec table (they render their own editors)', () => {
        expect(stageAttributesFor('schema')).toBeUndefined();
        expect(stageAttributesFor('keys')).toBeUndefined();
        expect(stageAttributesFor('enrichment')).toBeUndefined();
    });
});
