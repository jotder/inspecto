import { apiErrorMessage } from '@inspecto/core/api';
import { limitRefusalMessage } from './limit-refusal';

/** The ten refusal codes of `StandingDetection` (stable: they ride the response and the audit trail). */
export const STANDING_REFUSAL_CODES = [
    'NOT_ENABLED',
    'NO_OWNER',
    'BINDING_CHANGED',
    'DATASET_GONE',
    'DATASET_NOT_SHARED',
    'ROLE_SHARE_ONLY',
    'NOT_LEAD',
    'MASKING_TIGHTENED',
    'POLICY_DENIED',
    'UNDECIDABLE',
] as const;
export type StandingRefusalCode = (typeof STANDING_REFUSAL_CODES)[number];

/** What a refusal means and what the analyst does about it, in plain language (one entry per code). */
export const STANDING_REFUSAL_HELP: Record<StandingRefusalCode, { means: string; action: string }> = {
    NOT_ENABLED: {
        means: 'Standing detection is not switched on for this Alert Rule, so nothing reads the Dataset for it.',
        action: 'The Investigation owner presses "Enable standing detection".',
    },
    NO_OWNER: {
        means: 'The recorded authority names no owner, so there is nobody whose access could be checked.',
        action: 'Re-enable standing detection as the Investigation owner; if it stays refused, ask an administrator.',
    },
    BINDING_CHANGED: {
        means: 'The Investigation now has a different owner or a different Dataset than when standing detection was enabled.',
        action: 'The current owner re-enables standing detection over the Dataset now in use.',
    },
    DATASET_GONE: {
        means: 'The Dataset this rule reads no longer exists.',
        action: 'Restore or re-register the Dataset, or delete the rule and bind a new one over an existing Dataset.',
    },
    DATASET_NOT_SHARED: {
        means: 'The owner can no longer view the Dataset, so the sweep may not read it either.',
        action: 'Ask the Dataset owner to share it with the Investigation owner again, then re-enable standing detection.',
    },
    ROLE_SHARE_ONLY: {
        means: 'The Dataset is shared to the owner only through a role, and a sweep running without a signed-in user cannot check a role.',
        action: 'Ask the Dataset owner to share it with the Investigation owner by user name, then re-enable.',
    },
    NOT_LEAD: {
        means: 'The owner is no longer a lead of the Investigation.',
        action: 'Restore the owner as a lead of the Investigation, then re-enable standing detection.',
    },
    MASKING_TIGHTENED: {
        means: 'More data is masked now than when standing detection was enabled (a higher masking mode or a newly masked column). A sweep never reads more than it was allowed to.',
        action: 'Re-enable standing detection to accept the stricter masking, after checking the rule still makes sense.',
    },
    POLICY_DENIED: {
        means: 'An access policy now denies the owner this Investigation.',
        action: 'Ask your administrator why the policy denies it; the sweep resumes once the policy allows the owner again and standing detection is re-enabled.',
    },
    UNDECIDABLE: {
        means: 'The server could not decide whether the owner still has access, so it fails closed and does not read.',
        action: 'Try again shortly; if it persists, ask an administrator to check the server log.',
    },
};

/** The refusal code a 422 carries (`standing detection refused [CODE]: …`), or null. */
export function standingRefusalCode(err: unknown): StandingRefusalCode | null {
    const m = /standing detection refused \[([A-Z_]+)\]/.exec(apiErrorMessage(err, ''));
    return m && (STANDING_REFUSAL_CODES as readonly string[]).includes(m[1]) ? (m[1] as StandingRefusalCode) : null;
}

/** Enabling standing detection failed — in the analyst's words; a coded refusal gets its plain-language help. */
export function standingDetectionErrorMessage(err: unknown): string {
    const limit = limitRefusalMessage(err);
    if (limit) return limit;
    const code = standingRefusalCode(err);
    if (code) {
        const h = STANDING_REFUSAL_HELP[code];
        return `Refused (${code}). ${h.means} ${h.action}`;
    }
    const status = (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, 'Could not enable standing detection.');
    switch (status) {
        case 403:
            return (
                'Only the Investigation owner, holding the Alert Rule authoring capability, can enable standing detection. Server: ' +
                server
            );
        case 404:
            return (
                'That Alert Rule is not bound to this Investigation (or the Investigation is not yours). Server: ' +
                server
            );
        case 409:
            return (
                'The rule was edited after it was bound. Delete it and bind it again, then enable. Server: ' + server
            );
        case 503:
            return 'Standing detection needs the alert engine and a write root. Server: ' + server;
        default:
            return server;
    }
}

/** Aggregate of the sweep audit events: counts only — an event names an Investigation, and this never shows one. */
export interface StandingSweepSummary {
    swept: number;
    refused: number;
    refusedByCode: { code: string; count: number }[];
    /** The event page was full, so the counts are a lower bound. */
    capped: boolean;
}

export function summariseStandingEvents(
    swept: { attributes?: Record<string, string> }[],
    refused: { attributes?: Record<string, string> }[],
    limit: number,
): StandingSweepSummary {
    const byCode = new Map<string, number>();
    for (const e of refused) {
        const c = e.attributes?.['code'] ?? 'UNKNOWN';
        byCode.set(c, (byCode.get(c) ?? 0) + 1);
    }
    return {
        swept: swept.length,
        refused: refused.length,
        refusedByCode: [...byCode].map(([code, count]) => ({ code, count })).sort((a, b) => b.count - a.count),
        capped: swept.length >= limit || refused.length >= limit,
    };
}

/** A refusal code in words for the Monitoring list; an unknown code is shown as sent. */
export function standingCodeMeans(code: string): string {
    return (STANDING_REFUSAL_HELP as Record<string, { means: string }>)[code]?.means ?? 'An unrecognised reason code.';
}
