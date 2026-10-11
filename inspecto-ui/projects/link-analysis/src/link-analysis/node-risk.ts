import { DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import { GraphPropagatedRiskView, GraphRunResult } from '@inspecto/link-analysis/api/graph-runs.service';

/**
 * Node risk + enrichment for the node detail dialog and the *All algorithms* panel (telecom demo S2 UI, storyboard W4 /
 * W11). Framework-free: which reference Datasets a domain reads its per-entity indicators and enrichment from, how the
 * keys asked of them are chunked, and the words a propagated-risk factor is shown in.
 */

/** A reference Dataset keyed by one column: one row per entity. */
export interface KeyedDataset {
    dataset: string;
    keyCol: string;
}

/** Per-entity indicators: an overall 0..100 score and the factor columns that make it up. */
export interface IndicatorMapping extends KeyedDataset {
    scoreCol: string;
    factorCols: string[];
}

export interface RiskMapping {
    indicators: IndicatorMapping;
    /** CRM/KYC-style attributes shown under Enrichment; absent = no enrichment Dataset is mapped. */
    enrichment?: KeyedDataset;
}

/**
 * The telecom profile's mapping (`docs/superpower/la-telecom-demo-data.md` §5). `crm_kyc` is a raw store of the demo
 * Space, not a registered Dataset: when the Space cannot read it the Enrichment section says so and nothing else fails.
 */
const RISK_MAPPINGS: Partial<Record<DomainProfileId, RiskMapping>> = {
    telecom: {
        indicators: {
            dataset: 'telecom_msisdn_indicators',
            keyCol: 'msisdn',
            scoreCol: 'indicator_score',
            factorCols: [
                'on_blocklist',
                'alarms_recent',
                'recent_activation_high_intl',
                'shares_imei_with_flagged',
                'premium_destination_share',
                'short_call_ratio',
                'one_way_ratio',
            ],
        },
        enrichment: { dataset: 'crm_kyc', keyCol: 'msisdn' },
    },
};

/** The risk mapping of a domain profile, or null when the profile reads no indicators. */
export function riskMappingFor(profile: DomainProfileId | null | undefined): RiskMapping | null {
    return (profile && RISK_MAPPINGS[profile]) || null;
}

/** The most keys one `POST /inv/reference-rows` call takes; more are asked in chunks of this size. */
export const REFERENCE_KEY_CAP = 500;

/** Distinct, trimmed, non-blank keys split into chunks of at most `size`. */
export function keyChunks(keys: string[], size = REFERENCE_KEY_CAP): string[][] {
    const distinct = [...new Set(keys.map((k) => k.trim()).filter(Boolean))];
    const out: string[][] = [];
    for (let i = 0; i < distinct.length; i += size) out.push(distinct.slice(i, i + size));
    return out;
}

/** `/inv/reference-rows` rows (each tagged with the key AS ASKED) by that key; the first row of a key wins. */
export function indexByKey(rows: { key: string; row: Record<string, unknown> }[]): Map<string, Record<string, unknown>> {
    const out = new Map<string, Record<string, unknown>>();
    for (const r of rows) if (!out.has(r.key)) out.set(r.key, r.row);
    return out;
}

/** A finite number from a cell, else null. */
export function numberOf(v: unknown): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
}

/** `+28 from 99979100001, 3 hops, weight 0.35` - one contributing origin of a propagated score. */
export function factorLine(f: GraphPropagatedRiskView['factors'][number], originLabel: string): string {
    const hops = f.distance === 1 ? '1 hop' : `${f.distance} hops`;
    return `+${round1(f.contribution)} from ${originLabel}, ${hops}, weight ${f.weight}`;
}

export function round1(n: number): number {
    return Math.round(n * 10) / 10;
}

/** The propagated-risk entry of `serverId` in a completed result, or null. */
export function riskOf(result: GraphRunResult | null | undefined, serverId: string): GraphPropagatedRiskView | null {
    if (!result || result.kind !== 'PROPAGATED_RISK') return null;
    return ((result.scores ?? []) as GraphPropagatedRiskView[]).find((s) => s.id === serverId) ?? null;
}

/**
 * `propagatedRisk`'s `nodeScores`: each server node id whose raw key has a positive indicator score. `maxSize` is the
 * catalogue's entry cap; nodes past it are left out and counted.
 */
export function nodeScoresFor(
    serverIds: string[],
    indicators: Map<string, Record<string, unknown>>,
    scoreCol: string,
    maxSize = Number.MAX_SAFE_INTEGER,
): { scores: Record<string, number>; matched: number; dropped: number } {
    const scores: Record<string, number> = {};
    let matched = 0;
    let dropped = 0;
    for (const id of serverIds) {
        const s = numberOf(indicators.get(id)?.[scoreCol]);
        if (s === null || s <= 0) continue;
        if (matched >= maxSize) {
            dropped++;
            continue;
        }
        scores[id] = Math.min(100, s);
        matched++;
    }
    return { scores, matched, dropped };
}

/** Parse a comma/space separated weight list: 1..6 numbers in [0,1], else the reason it is refused. */
export function parseWeights(text: string): { weights: number[] } | { error: string } {
    const parts = text
        .split(/[\s,;]+/)
        .map((p) => p.trim())
        .filter(Boolean);
    if (!parts.length || parts.length > 6) return { error: 'Give 1 to 6 weights, one per hop.' };
    const weights = parts.map(Number);
    if (weights.some((w) => !Number.isFinite(w) || w < 0 || w > 1))
        return { error: 'Each weight is a number from 0 to 1.' };
    return { weights };
}
