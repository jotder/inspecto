import { PatternStep } from 'app/inspecto/graph';

/**
 * **Link Analysis — pattern packs** (V2). A pattern pack is a named, parameterized investigation
 * template: a motif that pre-fills the pattern-match builder so an investigator starts from a known
 * fraud/network shape instead of a blank motif. Because node/edge **kinds** are data-specific, the
 * shipped packs are *structural* starting points (direction + length); the investigator specializes
 * the kind dropdowns to their graph. Domain-seeded packs (bound to a specific Space's dataset kinds)
 * are a follow-on — see docs/BACKLOG.md.
 *
 * Some shapes are better served by a dedicated tool than by a path motif — those carry a `tool` hint
 * pointing the investigator at it (circular flow ⇒ the Cycles tool, dense rings ⇒ Cohesive groups).
 */
export interface PatternPack {
    id: string;
    label: string;
    category: 'money' | 'telecom' | 'identity';
    description: string;
    /** The motif loaded into the builder. Blank kinds are wildcards to be refined per graph. */
    steps: PatternStep[];
    /** A better-fit analysis tool for this shape, shown as a hint (no motif can express it as a path). */
    tool?: 'cycles' | 'cohesion' | 'similarity';
}

const CATEGORIES = new Set<PatternPack['category']>(['money', 'telecom', 'identity']);
const TOOLS = new Set<NonNullable<PatternPack['tool']>>(['cycles', 'cohesion', 'similarity']);
const DIRECTIONS = new Set<NonNullable<PatternStep['direction']>>(['out', 'in', 'both']);

/**
 * Map an authored `pattern-pack` component's content onto a {@link PatternPack}, or `null` when it is not
 * usable. Deliberately defensive: pack content is free-form TOON (no backend `validateKind` branch), so a
 * hand-edited file must be skipped rather than drawn as a broken option.
 *
 * ⚠ A step's `direction` is the EMPTY STRING for the start node, not an absent key — TOON cannot encode `{}`
 * as a list element (JToon writes a bare `-` and then fails to decode its own output), so the persisted
 * shape spells the wildcard as a blank and it maps back to `undefined` here.
 */
export function patternPackFromContent(content: Record<string, unknown>): PatternPack | null {
    const id = typeof content['name'] === 'string' ? content['name'] : '';
    const label = typeof content['label'] === 'string' ? content['label'] : '';
    const category = content['category'] as PatternPack['category'];
    const rawSteps = content['steps'];
    if (!id || !label || !CATEGORIES.has(category) || !Array.isArray(rawSteps) || rawSteps.length === 0) return null;

    const steps: PatternStep[] = rawSteps.map((s) => {
        const direction = (s as Record<string, unknown>)?.['direction'];
        return DIRECTIONS.has(direction as NonNullable<PatternStep['direction']>)
            ? { direction: direction as PatternStep['direction'] }
            : {};   // blank / unknown ⇒ wildcard, the start node's shape
    });
    const tool = content['tool'] as NonNullable<PatternPack['tool']>;
    return {
        id, label, category, steps,
        description: typeof content['description'] === 'string' ? content['description'] : '',
        ...(TOOLS.has(tool) ? { tool } : {}),
    };
}

export const PATTERN_PACKS: PatternPack[] = [
    {
        id: 'layering-chain',
        label: 'Layering chain',
        category: 'money',
        description: 'Funds relayed through a chain of intermediaries (A → B → C → D) to obscure origin — classic placement/layering.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'pass-through',
        label: 'Pass-through intermediary',
        category: 'money',
        description: 'A single intermediary that receives then forwards (A → M → B). Inspect the middle node — it is the mule/shell to scrutinize.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'inbound-collector',
        label: 'Inbound collector',
        category: 'money',
        description: 'Many parties converging INTO one account (they → M → target). Start from the collector and follow incoming links.',
        steps: [{}, { direction: 'in' }, { direction: 'in' }],
    },
    {
        id: 'forwarding-relay',
        label: 'Call-forwarding relay',
        category: 'telecom',
        description: 'A relay hop A → B → C — the building block of call-forwarding abuse and SIM-box relaying.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'circular-flow',
        label: 'Circular flow',
        category: 'money',
        description: 'Value or calls returning to their origin (A → … → A). Use the Cycles tool — a closed loop is not a simple path motif.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
        tool: 'cycles',
    },
    {
        id: 'shared-associates',
        label: 'Shared associates',
        category: 'identity',
        description: 'Distinct identities that share the same devices/accounts. Use Similarity from a seed node, or Cohesive groups for the whole ring.',
        steps: [{}, { direction: 'both' }],
        tool: 'similarity',
    },
];
