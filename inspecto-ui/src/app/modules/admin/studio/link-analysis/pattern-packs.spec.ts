import { describe, expect, it } from 'vitest';
import { PATTERN_PACKS, patternPackFromContent } from './pattern-packs';

/**
 * `patternPackFromContent` parses free-form authored TOON — a `pattern-pack` component has no backend
 * `validateKind` branch — so it is the only thing standing between a hand-edited file and a broken
 * option in the investigator's toolbox. It had no coverage at all.
 */
describe('patternPackFromContent', () => {
    const valid = {
        name: 'mule-ring',
        label: 'Mule ring',
        category: 'money',
        description: 'Funds fanned through mules.',
        steps: [{ direction: '' }, { direction: 'out' }],
    };

    it('maps a well-formed pack', () => {
        const p = patternPackFromContent(valid)!;
        expect(p).toMatchObject({ id: 'mule-ring', label: 'Mule ring', category: 'money' });
        // The start node's blank direction is the wildcard — it must NOT become a literal ''.
        expect(p.steps).toEqual([{}, { direction: 'out' }]);
    });

    /**
     * ⚠ The regression this file was written for. A PatternStep is {nodeKind, edgeKind, direction}, and
     * the mapper read only the direction — so a pack that PINS its kinds loaded as an all-wildcard motif
     * that still looked right (same label, same length, same arrows) and silently matched far more of
     * the graph than authored.
     */
    it('carries authored node and edge kinds instead of widening them to wildcards', () => {
        const p = patternPackFromContent({
            ...valid,
            steps: [
                { direction: '', nodeKind: 'account' },
                { direction: 'out', nodeKind: 'account', edgeKind: 'transfer' },
            ],
        })!;
        expect(p.steps).toEqual([
            { nodeKind: 'account' },
            { nodeKind: 'account', edgeKind: 'transfer', direction: 'out' },
        ]);
    });

    it('treats a blank or non-string kind as the wildcard, not as a literal', () => {
        const p = patternPackFromContent({
            ...valid,
            steps: [
                { nodeKind: '   ', edgeKind: 42 },
                { direction: 'in', nodeKind: ' account ' },
            ],
        })!;
        expect(p.steps[0]).toEqual({});
        expect(p.steps[1]).toEqual({ nodeKind: 'account', direction: 'in' }); // trimmed
    });

    it('skips a pack it cannot use rather than drawing a broken option', () => {
        expect(patternPackFromContent({ ...valid, name: '' })).toBeNull();
        expect(patternPackFromContent({ ...valid, label: '' })).toBeNull();
        expect(patternPackFromContent({ ...valid, category: 'weather' })).toBeNull();
        expect(patternPackFromContent({ ...valid, steps: [] })).toBeNull();
        expect(patternPackFromContent({ ...valid, steps: 'nope' })).toBeNull();
        expect(patternPackFromContent({})).toBeNull();
    });

    it('keeps a recognised tool hint and drops an unknown one', () => {
        expect(patternPackFromContent({ ...valid, tool: 'cycles' })!.tool).toBe('cycles');
        expect(patternPackFromContent({ ...valid, tool: 'telepathy' })!.tool).toBeUndefined();
        expect(patternPackFromContent(valid)!.tool).toBeUndefined();
    });

    it('defaults a missing description to blank rather than undefined', () => {
        const { description: _dropped, ...noDesc } = valid;
        expect(patternPackFromContent(noDesc)!.description).toBe('');
    });
});

describe('PATTERN_PACKS', () => {
    it('every shipped pack round-trips its own invariants', () => {
        expect(PATTERN_PACKS.length).toBeGreaterThan(0);
        const ids = PATTERN_PACKS.map((p) => p.id);
        expect(new Set(ids).size).toBe(ids.length); // ids address the packs — duplicates would shadow
        for (const p of PATTERN_PACKS) {
            // The start node has no incoming edge, so step 0 carries no direction by construction.
            expect(p.steps[0], p.id).toEqual({});
            expect(p.steps.length, p.id).toBeGreaterThan(1);
        }
    });
});
