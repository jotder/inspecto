import type { Insight } from 'app/inspecto/api/insights.service';
import { bandBadge, sliceLabel } from './insight-profile-form';

/**
 * The evidence an Insight carries into a Case or an Action Request (BI-INSIGHTS-1 I-17, second half). Everything
 * comes from the stored Insight row as served: the slice stays exactly as stored (an alias stays an alias — nothing
 * here looks up a raw value), numbers are not re-rounded, and the feed link points back at the Profile's feed.
 */
export interface InsightEvidence {
    insightId: string;
    profileId: string;
    sentence: string | null;
    type: string;
    slice: string;
    period: string;
    value: number | null;
    expected: number | null;
    delta: number | null;
    score: number;
    band: string;
    feedLink: string;
}

/** The feed's address for one Insight: `/studio/insights?profile=…&insight=…` (the page honours `profile`). */
export function insightFeedPath(profileId: string, insightId: string): string {
    return `/studio/insights?profile=${encodeURIComponent(profileId)}&insight=${encodeURIComponent(insightId)}`;
}

export function insightEvidence(i: Insight, profileId: string, origin: string): InsightEvidence {
    return {
        insightId: i.insight_id,
        profileId,
        sentence: i.explanation?.sentence || null,
        type: i.insight_type,
        slice: sliceLabel(i),
        period: i.period,
        value: i.value ?? null,
        expected: i.expected ?? null,
        delta: i.delta ?? null,
        score: i.score,
        band: i.band,
        feedLink: origin + insightFeedPath(profileId, i.insight_id),
    };
}

const num = (n: number | null): string => (n === null ? '—' : String(n));

/** A Case title: the sentence when there is one, else type and slice; cut to 200 characters. */
export function insightCaseTitle(e: InsightEvidence): string {
    const t = e.sentence ?? `${e.type} on ${e.slice} (${e.period})`;
    return t.length > 200 ? t.slice(0, 199) + '…' : t;
}

/** The Case description: every piece of the evidence, one per line. */
export function insightCaseDescription(e: InsightEvidence): string {
    return [
        `Opened from Insight ${e.insightId} of Insight Profile ${e.profileId}.`,
        `Sentence: ${e.sentence ?? 'none was stored'}`,
        `Insight Type: ${e.type}`,
        `Slice: ${e.slice}`,
        `Period: ${e.period}`,
        `Value: ${num(e.value)} · Expected: ${num(e.expected)} · Delta: ${num(e.delta)}`,
        `Score: ${e.score} · Band: ${bandBadge(e.band).label}`,
        `Insight feed: ${e.feedLink}`,
    ].join('\n');
}

/**
 * The Case member standing for the Insight (`POST /cases/from-entities`): key `insight:<id>` in the Profile's
 * Insights Dataset, so a second Case from the same Insight reuses the Incident minted the first time.
 */
export function insightCaseMember(e: InsightEvidence): { id: string; dataset: string; label: string } {
    return { id: `insight:${e.insightId}`, dataset: `insights_${e.profileId}`, label: insightCaseTitle(e) };
}

/** The Action Request payload template: the Case it was raised from (interpolated server-side). */
export function insightActionPayload(_e: InsightEvidence): Record<string, unknown> {
    return { case: '{{case.id}}' };
}

/** The Action Request `payloadData`: the evidence as one JSON object, sent verbatim - `{{x}}` in an Insight's sentence is never interpolated. */
export function insightActionData(e: InsightEvidence): Record<string, unknown> {
    return { insight: { ...e } };
}
