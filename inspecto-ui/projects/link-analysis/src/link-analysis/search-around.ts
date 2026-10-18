import { PatternStep } from '@inspecto/link-analysis/graph/graph-analysis';

/**
 * **Link Analysis - visual query (search-around) model.** A front end over the EXISTING motif matcher
 * (`matchPattern`): the analyst picks a start entity type, then adds conditions, each "follow a link
 * kind in a direction to a neighbour type". It compiles to a linear `PatternStep[]`; it adds no op and
 * no backend route (docs/superpower/la-visual-query-plan.md). Blank kind = wildcard, as in the motif builder.
 */
export interface SearchAroundCondition {
    direction: 'out' | 'in' | 'both';
    /** Link kind to follow; blank/undefined = any. */
    linkKind?: string;
    /** Neighbour entity type to arrive at; blank/undefined = any. */
    neighbourType?: string;
}

export interface SearchAroundSpec {
    /** Start entity type; blank/undefined = any. */
    startType?: string;
    conditions: SearchAroundCondition[];
}

const clean = (v: string | undefined): string | undefined => (v && v.trim() ? v.trim() : undefined);

/** Compile to motif steps: step 0 is the start node, each condition one further hop. */
export function searchAroundToSteps(spec: SearchAroundSpec): PatternStep[] {
    const start: PatternStep = {};
    const startType = clean(spec.startType);
    if (startType) start.nodeKind = startType;
    const hops = spec.conditions.map((c): PatternStep => {
        const step: PatternStep = { direction: c.direction };
        const edgeKind = clean(c.linkKind);
        const nodeKind = clean(c.neighbourType);
        if (edgeKind) step.edgeKind = edgeKind;
        if (nodeKind) step.nodeKind = nodeKind;
        return step;
    });
    return [start, ...hops];
}

/** The inverse, for a plain (non-temporal) path motif such as a pattern pack; null if it carries a temporal constraint. */
export function stepsToSearchAround(steps: PatternStep[]): SearchAroundSpec | null {
    if (steps.length === 0 || steps.some((s, i) => i > 0 && (s.afterPrevious || s.maxGapHours !== undefined))) {
        return null;
    }
    return {
        startType: steps[0].nodeKind,
        conditions: steps.slice(1).map((s) => ({
            direction: s.direction ?? 'out',
            linkKind: s.edgeKind,
            neighbourType: s.nodeKind,
        })),
    };
}

/** Refusals shown inline; empty = runnable. A bare start type is a filter, not a search, so one condition is required. */
export function searchAroundIssues(spec: SearchAroundSpec): string[] {
    return spec.conditions.length === 0 ? ['Add at least one condition.'] : [];
}

const WORD = { out: 'outgoing', in: 'incoming', both: 'any-direction' } as const;

/** Plain-language numbered steps, one line per hop. */
export function describeSearchAround(spec: SearchAroundSpec): string[] {
    const lines = [`Start from ${clean(spec.startType) ?? 'any entity'}`];
    spec.conditions.forEach((c, i) =>
        lines.push(
            `${i + 1}. Follow ${WORD[c.direction]} ${clean(c.linkKind) ?? 'any'} links to ${clean(c.neighbourType) ?? 'any entity'}`,
        ),
    );
    return lines;
}
