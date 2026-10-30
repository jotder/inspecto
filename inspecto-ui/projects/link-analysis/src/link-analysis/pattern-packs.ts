import { BranchStage, LegThreshold } from '@inspecto/link-analysis/graph/branching-pattern-engine';
import { PatternStep } from '@inspecto/link-analysis/graph/graph-analysis';
import { DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import { NEEDS_TIME_COLUMN } from './disabled-reasons';

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
    /** The motif loaded into the builder. Blank kinds are wildcards to be refined per graph. Empty on a branching pack. */
    steps: PatternStep[];
    /**
     * LA-14b — a BRANCHING motif (fan-in / fan-out stages), run by `matchBranchingPattern` instead of the
     * linear matcher. Present ⇒ `steps` is empty. Its thresholds are shown and editable in the toolbox.
     */
    stages?: BranchStage[];
    /** A better-fit analysis tool for this shape, shown as a hint (no motif can express it as a path). */
    tool?: 'cycles' | 'cohesion' | 'similarity';
}

const CATEGORIES = new Set<PatternPack['category']>(['money', 'telecom', 'identity']);
const TOOLS = new Set<NonNullable<PatternPack['tool']>>(['cycles', 'cohesion', 'similarity']);
const DIRECTIONS = new Set<NonNullable<PatternStep['direction']>>(['out', 'in', 'both']);

/** One authored kind field, kept only when it is a non-blank string — blank is the wildcard spelling. */
function kindOf(value: unknown, key: 'nodeKind' | 'edgeKind'): Partial<PatternStep> {
    return typeof value === 'string' && value.trim() ? { [key]: value.trim() } : {};
}

/**
 * LA-14a temporal fields off one authored step. A tabular TOON array must give EVERY row a value for
 * every declared column, so the un-ordered rows spell their flag as `false` and their gap as `0` — and
 * `0` must therefore mean "no ceiling", never "a ceiling of zero hours", which would match nothing at
 * all while looking authored. Anything non-positive or unparseable is treated as absent, and a gap is
 * only carried when the step is actually ordered.
 */
function temporalOf(step: Record<string, unknown>): Partial<PatternStep> {
    const ordered = step['afterPrevious'] === true || step['afterPrevious'] === 'true';
    if (!ordered) return {};
    const raw = step['maxGapHours'];
    const gap = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() ? Number(raw) : Number.NaN;
    return Number.isFinite(gap) && gap > 0 ? { afterPrevious: true, maxGapHours: gap } : { afterPrevious: true };
}

/**
 * Map an authored `pattern-pack` component's content onto a {@link PatternPack}, or `null` when it is not
 * usable. Deliberately defensive: pack content is free-form TOON (no backend `validateKind` branch), so a
 * hand-edited file must be skipped rather than drawn as a broken option.
 *
 * ⚠ A step's `direction` is the EMPTY STRING for the start node, not an absent key — TOON cannot encode `{}`
 * as a list element (JToon writes a bare `-` and then fails to decode its own output), so the persisted
 * shape spells the wildcard as a blank and it maps back to `undefined` here. The same blank-means-wildcard
 * rule carries the `nodeKind`/`edgeKind` fields.
 */
export function patternPackFromContent(content: Record<string, unknown>): PatternPack | null {
    const id = typeof content['name'] === 'string' ? content['name'] : '';
    const label = typeof content['label'] === 'string' ? content['label'] : '';
    const category = content['category'] as PatternPack['category'];
    if (!id || !label || !CATEGORIES.has(category)) return null;
    const description = typeof content['description'] === 'string' ? content['description'] : '';
    if (content['stages'] !== undefined) {
        const stages = stagesOf(content['stages']);
        return stages ? { id, label, category, description, steps: [], stages } : null;
    }
    const rawSteps = content['steps'];
    if (!Array.isArray(rawSteps) || rawSteps.length === 0) return null;

    const steps: PatternStep[] = rawSteps.map((s) => {
        const step = (s as Record<string, unknown>) ?? {};
        const direction = step['direction'];
        return {
            // ⚠ Carried, not dropped. A PatternStep is {nodeKind, edgeKind, direction}, and reading only
            // the direction turned an authored pack that PINS its kinds — the domain-seeded case this
            // file's own header anticipates — into an all-wildcard motif. It still drew as a valid
            // option with the right label, length and arrows, and just quietly matched far more of the
            // graph than it was authored to, which is the worst way for this to be wrong.
            ...kindOf(step['nodeKind'], 'nodeKind'),
            ...kindOf(step['edgeKind'], 'edgeKind'),
            ...(DIRECTIONS.has(direction as NonNullable<PatternStep['direction']>)
                ? { direction: direction as PatternStep['direction'] }
                : {}), // blank / unknown ⇒ wildcard, the start node's shape
            // LA-14a. TOON has no boolean-in-a-list problem here, but an authored pack may spell the
            // flag as a string; accept both and treat anything else as absent. Dropping these silently
            // is the same class of bug the kind fields hit above — the motif would still draw with the
            // right arrows and quietly match unordered paths.
            ...temporalOf(step),
        };
    });
    const tool = content['tool'] as NonNullable<PatternPack['tool']>;
    return {
        id,
        label,
        category,
        steps,
        description,
        ...(TOOLS.has(tool) ? { tool } : {}),
    };
}

/** A TOON cell as a number, or `undefined` when blank / absent / unparseable. `0` is a real number here. */
function numberOf(value: unknown): number | undefined {
    if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
    if (typeof value !== 'string' || !value.trim()) return undefined;
    const n = Number(value);
    return Number.isFinite(n) ? n : undefined;
}

/**
 * LA-14b — an authored branching pack's `stages`, or `null` when ANY stage is unusable (a half-read motif
 * would match something other than what was authored, which is worse than not offering the pack).
 *
 * The persisted shape is one FLAT tabular row per stage, because a TOON tabular array cannot nest an object:
 * `stages[3]{shape,minBranches,edgeKind,nodeKind,windowHours,afterPrevious,maxGapHours,thresholdAttr,thresholdMin,thresholdMax}` (+ an optional `closesToStart` column).
 * Blank strings are wildcards / absent; `windowHours` and `maxGapHours` of `0` mean "none" (the LA-14a
 * convention — a zero-hour window would match nothing while looking authored), but a threshold bound of
 * `0` is a real bound and only a blank cell means "no bound".
 */
function stagesOf(raw: unknown): BranchStage[] | null {
    if (!Array.isArray(raw) || raw.length === 0) return null;
    const stages: BranchStage[] = [];
    for (const s of raw) {
        const row = (s as Record<string, unknown>) ?? {};
        const shape = row['shape'];
        const minBranches = numberOf(row['minBranches']);
        if ((shape !== 'fan-in' && shape !== 'fan-out') || minBranches === undefined || minBranches < 1) return null;
        const window = numberOf(row['windowHours']);
        const attr = typeof row['thresholdAttr'] === 'string' ? row['thresholdAttr'].trim() : '';
        let threshold: LegThreshold | undefined;
        if (attr) {
            const min = numberOf(row['thresholdMin']);
            const max = numberOf(row['thresholdMax']);
            if (min === undefined && max === undefined) return null; // a threshold with no bound is not a threshold
            threshold = { attr, ...(min !== undefined ? { min } : {}), ...(max !== undefined ? { max } : {}) };
        }
        stages.push({
            shape,
            minBranches: Math.floor(minBranches),
            ...kindOf(row['edgeKind'], 'edgeKind'),
            ...kindOf(row['nodeKind'], 'nodeKind'),
            ...(window !== undefined && window > 0 ? { windowHours: window } : {}),
            ...temporalOf(row),
            ...(row['closesToStart'] === true || row['closesToStart'] === 'true' ? { closesToStart: true } : {}),
            ...(threshold ? { threshold } : {}),
        });
    }
    return stages;
}

export const PATTERN_PACKS: PatternPack[] = [
    {
        id: 'layering-chain',
        label: 'Layering chain',
        category: 'money',
        description:
            'Money relayed through a chain of accounts (A → B → C → D) to hide where it came from. Each hop follows the one before within 48 hours.',
        steps: [
            {},
            { direction: 'out' },
            // LA-14a: a layering chain is a FLOW claim. Without ordering, A→B→C matched even when B
            // paid C long before A ever paid B, which is a topology claim wearing the same words.
            { direction: 'out', afterPrevious: true, maxGapHours: 48 },
            { direction: 'out', afterPrevious: true, maxGapHours: 48 },
        ],
    },
    {
        id: 'pass-through',
        label: 'Pass-through intermediary',
        category: 'money',
        description:
            'One account receives money and forwards it within 48 hours (A → M → B). Check the middle account.',
        steps: [
            {},
            { direction: 'out' },
            // "receives THEN forwards" is the whole pattern — it was not being checked.
            { direction: 'out', afterPrevious: true, maxGapHours: 48 },
        ],
    },
    {
        id: 'inbound-collector',
        label: 'Inbound collector',
        category: 'money',
        description:
            'Many parties sending into one account. Start from the collector and follow incoming links.',
        steps: [{}, { direction: 'in' }, { direction: 'in' }],
    },
    {
        id: 'forwarding-relay',
        label: 'Relay chain (any link kind)',
        category: 'telecom',
        description:
            'A relay A → B → C over any link kind, on the graph on screen. Set link kinds to narrow it.',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
    },
    {
        id: 'circular-flow',
        label: 'Circular flow',
        category: 'money',
        description:
            'Value or contacts returning to where they started (A → … → A).',
        steps: [{}, { direction: 'out' }, { direction: 'out' }],
        tool: 'cycles',
    },
    {
        id: 'shared-associates',
        label: 'Shared associates',
        category: 'identity',
        description:
            'Different identities that share devices or accounts. Use Similarity from one node, or Cohesive groups for the whole ring.',
        steps: [{}, { direction: 'both' }],
        tool: 'similarity',
    },
    {
        id: 'structuring',
        label: 'Structuring (smurfing)',
        category: 'money',
        description:
            'Many deposits just under a reporting threshold reach one collector within a day, then split and merge again on one exit within 48 hours. Set the value band to your reporting limit, and do not filter out small amounts first.',
        steps: [],
        // LA-14b. The band is the point: `max` is the reporting threshold (a deposit AT it is reported) and
        // `min` keeps the ordinary long tail of small payments out. 900–1 000 matches the demo corpus; a real
        // deployment edits it to its own limit in the toolbox, where it is shown, not buried.
        stages: [
            { shape: 'fan-in', minBranches: 5, threshold: { attr: 'AMOUNT', min: 900, max: 1000 }, windowHours: 24 },
            { shape: 'fan-out', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
            { shape: 'fan-in', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
        ],
    },
    {
        id: 'layering-network',
        label: 'Layering network (split and merge)',
        category: 'money',
        description:
            'Money split from one account across three or more accounts within a day, then merged into one account within 48 hours.',
        steps: [],
        // LA-LAYERING-1. No threshold on purpose: layering is about the SHAPE and its time order, not an amount band.
        stages: [
            { shape: 'fan-out', minBranches: 3, windowHours: 24 },
            { shape: 'fan-in', minBranches: 3, afterPrevious: true, maxGapHours: 48 },
        ],
    },
    {
        id: 'circular-financing',
        label: 'Circular financing (branching)',
        category: 'money',
        description:
            'Money split and merged twice, ending back at the account that first split it, each step within 48 hours. For a single loop, use the Cycles tool.',
        steps: [],
        // LA-CIRCFIN-1 + LA-CIRCFIN-2: the last stage `closesToStart`, so its collector must be stage 0's splitter.
        stages: [
            { shape: 'fan-out', minBranches: 2, windowHours: 24 },
            { shape: 'fan-in', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
            { shape: 'fan-out', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
            { shape: 'fan-in', minBranches: 2, afterPrevious: true, maxGapHours: 48, closesToStart: true },
        ],
    },
];

/** The pack category a domain profile foregrounds; a profile not listed keeps the catalog's first pack. */
const PROFILE_PACK_CATEGORY: Partial<Record<DomainProfileId, PatternPack['category']>> = {
    'mobile-money': 'money',
    telecom: 'telecom',
};

/**
 * A profile's preferred opening pack, by id, when the Space carries it: the telecom storyboard starts with the
 * forwarding chain, and the Space packs arrive in name order (Subscription ring could come first).
 */
const PROFILE_PREFERRED_PACK: Partial<Record<DomainProfileId, readonly string[]>> = {
    telecom: ['telecom-forwarding-chain', 'call-forwarding-chain'],
};

/**
 * The pack the Pattern tool opens on: the profile's preferred pack when present, else the first pack of the active profile's category — a Space-authored one
 * before a shipped built-in, since it pins that Space's own kinds — else the catalog's first pack.
 */
export function defaultPatternPack(
    packs: readonly PatternPack[],
    profileId: DomainProfileId | null | undefined,
): PatternPack | null {
    const category = profileId ? PROFILE_PACK_CATEGORY[profileId] : undefined;
    const matching = category ? packs.filter((p) => p.category === category) : [];
    for (const id of (profileId && PROFILE_PREFERRED_PACK[profileId]) || []) {
        const preferred = matching.find((p) => p.id === id);
        if (preferred) return preferred;
    }
    return matching.find((p) => !PATTERN_PACKS.includes(p)) ?? matching[0] ?? packs[0] ?? null;
}

// ── Columns a pack needs (review: telecom packs only worked on a `link_kind` / `first_seen` projection) ──

/**
 * The columns a domain's pattern packs are authored against, keyed by domain profile. Telecom's packs pin
 * `edgeKind`s that live in `telecom_links.link_kind` and order their legs by `first_seen` — NOT the profile's
 * Investigation `timeCol` (`last_seen`), which is the recency a degree expansion windows on, while a pattern
 * orders legs by when each link STARTED. See docs/superpower/la-telecom-demo-data.md §5a.
 */
export const PATTERN_PACK_COLUMNS: Partial<Record<DomainProfileId, { kindCol?: string; timeCol?: string }>> = {
    telecom: {
        kindCol: 'link_kind',
        timeCol: 'first_seen',
    },
};

/** Which columns a motif cannot be evaluated without: a link kind (any pinned `edgeKind`) and a time (any window / ordering). */
export function patternColumnNeeds(
    steps: PatternStep[],
    stages: BranchStage[] | null | undefined,
): { kind: boolean; time: boolean } {
    if (stages) {
        return {
            kind: stages.some((s) => !!s.edgeKind),
            time: stages.some((s, i) => !!s.windowHours || (i > 0 && !!s.afterPrevious)),
        };
    }
    return { kind: steps.some((s) => !!s.edgeKind), time: steps.some((s, i) => i > 0 && !!s.afterPrevious) };
}

/** The columns a pattern run will use, which of them came from the domain profile, and why it cannot run. */
export interface PatternColumnPlan {
    kindCol?: string;
    timeCol?: string;
    /** Set when a column was pre-filled from the profile: "Using link_kind / first_seen for this pack". */
    notice?: string;
    /** Set when a needed column is still missing — Run is disabled and this sentence says what to set. */
    blocked?: string;
}

/**
 * Fill a needed-but-blank kind / time column from the active domain profile's {@link PATTERN_PACK_COLUMNS}, and
 * refuse (with a plain reason) when it is still missing — a run without them either fails (a window or an ordering
 * with no time column) or silently matches nothing (every link's kind defaults to `link` with no kind column).
 */
export function resolvePatternColumns(
    needs: { kind: boolean; time: boolean },
    current: { kindCol?: string; timeCol?: string },
    profileId: DomainProfileId | null | undefined,
): PatternColumnPlan {
    const defaults = (profileId && PATTERN_PACK_COLUMNS[profileId]) || {};
    const kindCol = current.kindCol || (needs.kind ? defaults.kindCol : undefined);
    const timeCol = current.timeCol || (needs.time ? defaults.timeCol : undefined);
    const filled = [
        ...(needs.kind && !current.kindCol && kindCol ? [kindCol] : []),
        ...(needs.time && !current.timeCol && timeCol ? [timeCol] : []),
    ];
    const missing: string[] = [];
    if (needs.time && !timeCol) missing.push(patternColumnRefusal('time', defaults.timeCol));
    if (needs.kind && !kindCol) missing.push(patternColumnRefusal('kind', defaults.kindCol));
    return {
        ...(kindCol ? { kindCol } : {}),
        ...(timeCol ? { timeCol } : {}),
        ...(filled.length ? { notice: usingColumnsNotice(filled) } : {}),
        ...(missing.length ? { blocked: missing.join(' ') } : {}),
    };
}

/** The plain refusal for a column the pattern needs and does not have, naming the profile's column when there is one. */
export function patternColumnRefusal(what: 'time' | 'kind', suggested: string | undefined): string {
    return what === 'time'
        ? `${NEEDS_TIME_COLUMN}${suggested ? ` Use ${suggested}.` : ''}`
        : `Needs a link kind column: set Link kind column${suggested ? ` to ${suggested}` : ''} in Settings ▸ Graph model.`;
}

/** The notice for columns a pattern run fills in by itself: "Using the first_seen column for this pattern." */
export function usingColumnsNotice(cols: readonly string[]): string {
    return cols.length > 1
        ? `Using the ${cols.slice(0, -1).join(', ')} and ${cols[cols.length - 1]} columns for this pattern.`
        : `Using the ${cols[0]} column for this pattern.`;
}

/**
 * `LA-PRODUCT-NEXT-1` phase 5b — a motif as `pattern-pack` component content, the shape {@link patternPackFromContent}
 * reads back. Every step (and every stage) carries every column, blank where absent, because TOON writes a list of
 * maps as a tabular array and a ragged row is not one. `maxGapHours` / `windowHours` of 0 mean none.
 */
export function patternPackToContent(pack: Omit<PatternPack, 'tool'>): Record<string, unknown> {
    // A band with neither bound is no band: written as one, the reader would refuse the whole pack.
    const band = (t: LegThreshold | undefined) => (t && (t.min !== undefined || t.max !== undefined) ? t : undefined);
    const head = {
        id: pack.id,
        name: pack.id,
        label: pack.label,
        category: pack.category,
        description: pack.description,
    };
    if (pack.stages?.length)
        return {
            ...head,
            stages: pack.stages.map((s) => ({
                shape: s.shape,
                minBranches: s.minBranches,
                edgeKind: s.edgeKind ?? '',
                nodeKind: s.nodeKind ?? '',
                windowHours: s.windowHours ?? 0,
                afterPrevious: !!s.afterPrevious,
                maxGapHours: s.maxGapHours ?? 0,
                closesToStart: !!s.closesToStart,
                thresholdAttr: band(s.threshold)?.attr ?? '',
                thresholdMin: band(s.threshold)?.min ?? '',
                thresholdMax: band(s.threshold)?.max ?? '',
            })),
        };
    return {
        ...head,
        steps: pack.steps.map((s) => ({
            direction: s.direction ?? '',
            nodeKind: s.nodeKind ?? '',
            edgeKind: s.edgeKind ?? '',
            afterPrevious: !!s.afterPrevious,
            maxGapHours: s.maxGapHours ?? 0,
        })),
    };
}

/** A pack id from its name: lower-case words joined by `-` (the component id rule); blank when nothing is left. */
export function patternPackId(label: string): string {
    return label
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 60);
}
