import { describe, expect, it } from 'vitest';

import BIND_KINDS from 'app/inspecto/mock/bind-kinds.contract.json';

import { DERIVED_USE } from 'app/inspecto/mock/pipeline-editable';

import { bindKindFor } from './pipeline-graph';

/**
 * The client half of the bind-kind contract (AUTHOR-1(b) residual). `BindKindHomeContractTest` (Java)
 * writes the same committed JSON from `PipelineEditable.USE_HOME`, so neither side can move alone.
 *
 * `bindKindFor` is keyed on a node's CATEGORY; the save path's homes are keyed on its TYPE. They agree
 * only because PARSE holds the one type, `parser` — nothing forced that, and when it stopped being true
 * the picker offered `transform/<id>` and `sink/<id>` options that every save then refused with
 * `UNSUPPORTED_BINDING`. This is the tripwire for the next such addition.
 */
describe('bind-kind contract', () => {
    /** The one-way rule: a picker requires a home, not the reverse (a SOURCE's `connection/` has a home
     *  but is not a `ComponentType`, so the collector component owns that picker). */
    it('never offers a picker on a category the save path has no home for', () => {
        for (const category of BIND_KINDS.categories) {
            if (bindKindFor(category) !== null)
                expect(BIND_KINDS.bindableCategories).toContain(category);
        }
    });

    // Pinned so an emptied contract file cannot turn the loop above into a no-op.
    it('covers every node category the backend publishes', () => {
        expect(BIND_KINDS.categories).toEqual(['SOURCE', 'PARSE', 'TRANSFORM', 'SINK', 'CONTROL']);
        expect(BIND_KINDS.bindableCategories).toEqual(['PARSE']);
    });

    /**
     * The mock's `DERIVED_USE` against the engine's, entry for entry. This map is the one that drifts:
     * all three `ingester/` entries were added in three separate sessions and each missed the others,
     * the last (plain `parser`, 2026-08-16) leaving validate/dry-run failing `UNKNOWN_USE_KIND` on the
     * legacy `processing.ingester` shape. A missing entry makes the preview refuse a ref the product
     * itself wrote — the AUTHOR-1(b) enrichment regression, again.
     */
    it('derives the same use: prefixes the engine does', () => {
        expect(DERIVED_USE).toEqual(BIND_KINDS.derivedUse);
    });

    it('answers grammar for PARSE and null for every other category', () => {
        expect(bindKindFor('PARSE')).toBe('grammar');
        for (const category of BIND_KINDS.categories.filter((c) => c !== 'PARSE'))
            expect(bindKindFor(category)).toBeNull();
    });
});
