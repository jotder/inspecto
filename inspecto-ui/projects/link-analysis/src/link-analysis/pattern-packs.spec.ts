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
            if (p.stages) {
                // A branching pack runs through the branching matcher; it carries no linear steps.
                expect(p.steps, p.id).toEqual([]);
                expect(p.stages.length, p.id).toBeGreaterThan(0);
                continue;
            }
            // The start node has no incoming edge, so step 0 carries no direction by construction.
            expect(p.steps[0], p.id).toEqual({});
            expect(p.steps.length, p.id).toBeGreaterThan(1);
        }
    });

    it('ships the structuring pack with a VISIBLE threshold band, not an open-ended "under" (§2.6)', () => {
        const pack = PATTERN_PACKS.find((p) => p.id === 'structuring')!;
        expect(pack.stages![0]).toMatchObject({ shape: 'fan-in', threshold: { attr: 'AMOUNT', min: 900, max: 1000 } });
        // the split and the re-convergence are both ordered after the step before, LA-14a's rule
        expect(pack.stages!.slice(1).every((s) => s.afterPrevious && s.maxGapHours === 48)).toBe(true);
    });
});

/** LA-14b — the authored (Space) spelling of a branching pack: one flat TOON tabular row per stage. */
describe('patternPackFromContent — branching stages', () => {
    const row = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
        shape: 'fan-in',
        minBranches: 5,
        edgeKind: '',
        nodeKind: '',
        windowHours: 24,
        afterPrevious: false,
        maxGapHours: 0,
        thresholdAttr: 'AMOUNT',
        thresholdMin: 900,
        thresholdMax: 1000,
        ...over,
    });
    const pack = (stages: unknown): Record<string, unknown> => ({
        name: 'structuring',
        label: 'Structuring',
        category: 'money',
        stages,
    });

    it('maps flat rows onto stages, blanks as wildcards and 0-hour windows/gaps as none', () => {
        const p = patternPackFromContent(
            pack([
                row(),
                row({
                    shape: 'fan-out',
                    minBranches: 2,
                    windowHours: 0,
                    afterPrevious: true,
                    maxGapHours: 48,
                    thresholdAttr: '',
                    thresholdMin: '',
                    thresholdMax: '',
                }),
            ]),
        )!;
        expect(p.steps).toEqual([]);
        expect(p.stages).toEqual([
            { shape: 'fan-in', minBranches: 5, windowHours: 24, threshold: { attr: 'AMOUNT', min: 900, max: 1000 } },
            { shape: 'fan-out', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
        ]);
    });

    it('keeps a threshold bound of 0 — only a BLANK cell means "no bound"', () => {
        const p = patternPackFromContent(pack([row({ thresholdMin: 0, thresholdMax: '' })]))!;
        expect(p.stages![0].threshold).toEqual({ attr: 'AMOUNT', min: 0 });
    });

    it('refuses the WHOLE pack when any stage is unusable, rather than running half a motif', () => {
        expect(patternPackFromContent(pack([row(), row({ shape: 'sideways' })]))).toBeNull();
        expect(patternPackFromContent(pack([row({ minBranches: 0 })]))).toBeNull();
        expect(patternPackFromContent(pack([row({ thresholdMin: '', thresholdMax: '' })]))).toBeNull();
        expect(patternPackFromContent(pack([]))).toBeNull();
    });
});
