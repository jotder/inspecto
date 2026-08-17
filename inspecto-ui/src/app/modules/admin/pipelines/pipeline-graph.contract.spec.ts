import { describe, expect, it } from 'vitest';

import BIND_KINDS from 'app/inspecto/mock/bind-kinds.contract.json';
import { DERIVED_USE, SUBTYPE_FRONTENDS } from 'app/inspecto/mock/pipeline-editable';

import { bindKindFor } from './pipeline-graph';
import { PARSE_NODE_FRONTENDS } from './pipeline-parse-definition.component';

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

    it('answers grammar for PARSE and null for every other category', () => {
        expect(bindKindFor('PARSE')).toBe('grammar');
        for (const category of BIND_KINDS.categories.filter((c) => c !== 'PARSE'))
            expect(bindKindFor(category)).toBeNull();
    });
});

/**
 * The DERIVED_USE half of the same contract. Unlike `bindableCategories` above — which this side
 * DERIVES from `bindKindFor` — the mock re-declares `DERIVED_USE` as its own literal map, so a missing
 * entry is invisible until a real config trips it: the ref names no component kind, and validate 422s
 * an untouched pipeline with `UNKNOWN_USE_KIND`.
 *
 * It drifted exactly that way three times. `parser.asn1`, `parser.plugin` and plain `parser` each
 * arrived in a separate change, and the plain type — reached by the legacy `processing.ingester` key
 * with no `parsing.frontend` literal, so it never retypes — was missed by both of the others.
 */
describe('derived-use contract', () => {
    it('matches the map the engine publishes, entry for entry', () => {
        expect(DERIVED_USE).toEqual(BIND_KINDS.derivedUse);
    });

    // Pinned so an emptied contract file cannot turn the comparison above into a vacuous pass.
    it('still calls all three ingester-bearing parser types derived', () => {
        expect(BIND_KINDS.derivedUse).toEqual({
            enrichment: 'enrichment/',
            parser: 'ingester/',
            'parser.asn1': 'ingester/',
            'parser.plugin': 'ingester/',
        });
    });
});

/**
 * The parse-subtype vocabulary exists TWICE and the two copies must list the same types:
 * `PARSE_NODE_FRONTENDS` (which drawer a parse node opens, and — via `isParseNodeType` — whether the
 * palette lets you add one) and the lowering's `SUBTYPE_FRONTENDS` (which types occupy the single parse
 * slot, i.e. what `MULTI_PARSER` refuses).
 *
 * They are deliberately NOT merged: the lowering module must not import from a feature component, and the
 * two maps answer different questions (one maps a subtype to its editor frontend, the other to every
 * spelling the engine accepts — fixed width has two). But if a seventh format lands in only one of them
 * the failure mode is the ugly one: the palette lets a builder add a Step that Save then refuses. This is
 * the tripwire. Same remedy as the DERIVED_USE pin above, for the same reason.
 */
describe('parse-subtype vocabulary contract', () => {
    it('lists the same subtypes on the editor side and the lowering side', () => {
        expect(Object.keys(PARSE_NODE_FRONTENDS).sort()).toEqual(Object.keys(SUBTYPE_FRONTENDS).sort());
    });

    /** Pinned so emptying both maps cannot turn the comparison above into a vacuous pass. */
    it('still holds the six built-in parse subtypes', () => {
        expect(Object.keys(PARSE_NODE_FRONTENDS).sort()).toEqual([
            'parser.asn1',
            'parser.delimited',
            'parser.fixedwidth',
            'parser.json',
            'parser.plugin',
            'parser.text_regex',
        ]);
    });
});
