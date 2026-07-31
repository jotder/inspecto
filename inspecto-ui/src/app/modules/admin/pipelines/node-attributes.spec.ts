import { describe, expect, it } from 'vitest';
import { byTier, COLLECTOR_ATTRIBUTES } from 'app/inspecto/component-model';
import { nodeAttributesFor } from './node-attributes';

/**
 * W2/U-D reshaped this: the map is now keyed by the engine's own `BuiltinNodeType` strings, and a type
 * only gets a schema when the backend really reads those keys. The previous suite asserted schemas for
 * `collector.file`/`sink.file`/`sink.database` — types the backend has never had — over keys it never
 * reads, so it passed while the editor authored nodes `PipelineCompiler` silently dropped.
 */
describe('node-attributes', () => {
    it('returns a tiered schema for a known node type', () => {
        const specs = nodeAttributesFor('sink.persistent');
        expect(specs).toBeDefined();
        const grouped = byTier(specs!);
        expect(grouped.required.map((s) => s.key)).toEqual(['format']);
        expect(grouped.advanced.map((s) => s.key)).toEqual(['compression']);
    });

    it('classifies every attribute of every known type into a tier', () => {
        for (const type of ['acquisition', 'transform.filter', 'transform.route',
            'sink.persistent', 'sink.materialized', 'sink.view']) {
            for (const s of nodeAttributesFor(type)!) {
                expect(['required', 'optional', 'advanced']).toContain(s.tier);
            }
        }
    });

    /** U-D's whole point: one table per concern, so the acquisition node cannot drift from Onboarding. */
    it('authors the collector block with the SAME shared table Onboarding uses', () => {
        expect(nodeAttributesFor('acquisition')).toBe(COLLECTOR_ATTRIBUTES);
    });

    it('offers the engine-real collector keys, not the old best-guess ones', () => {
        const keys = nodeAttributesFor('acquisition')!.map((s) => s.key);
        expect(keys).toContain('recursive_depth');
        expect(keys).toContain('stability__window');
        // `recursive` (boolean) and `min_age_seconds` are read nowhere in the backend.
        expect(keys).not.toContain('recursive');
        expect(keys).not.toContain('min_age_seconds');
    });

    /** All three sink kinds write the same `output:` block — the kind is behaviour, not a config shape. */
    it('gives every sink kind the same output-block schema', () => {
        const persistent = nodeAttributesFor('sink.persistent');
        expect(nodeAttributesFor('sink.materialized')).toBe(persistent);
        expect(nodeAttributesFor('sink.view')).toBe(persistent);
    });

    it('drops the sink keys the backend never read', () => {
        const keys = nodeAttributesFor('sink.persistent')!.map((s) => s.key);
        for (const dead of ['partition_by', 'table', 'mode', 'key_columns']) {
            expect(keys).not.toContain(dead);
        }
    });

    it('returns undefined for a type with no specced shape (free-form fallback)', () => {
        // Deliberately unspecced rather than guessed — the remaining transform shapes are not
        // specced server-side, and a best-guess table that looks authoritative is what U-D removed.
        expect(nodeAttributesFor('transform.map')).toBeUndefined();
        expect(nodeAttributesFor('parser')).toBeUndefined();
        expect(nodeAttributesFor('alert')).toBeUndefined();
        expect(nodeAttributesFor('acme.custom')).toBeUndefined();
        expect(nodeAttributesFor(undefined)).toBeUndefined();
    });

    /** The retired fiction must not creep back via this map either. */
    it('has no schema under any of the invented type names', () => {
        for (const fiction of ['collector.file', 'collector.database', 'collector.stream',
            'sink.file', 'sink.database', 'transform.record', 'transform.aggregate', 'transform.alert']) {
            expect(nodeAttributesFor(fiction)).toBeUndefined();
        }
    });
});
