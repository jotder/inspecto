import type { ExpansionSensitivity, PendingExpansion } from '@inspecto/link-analysis/api/inv.service';
import type { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { DegreeOutcome, outcomeHead, thresholdWords } from './investigate-number';

/**
 * LA-FULL-CYCLE-TEST-1 phase 5 (finding 23): the APPROVER's side of a held expand, shared by the waiting line in the
 * Investigation tab and Settings ▸ Oversight. The server stays the gate (`POST …/pending/{rid}/approve | deny`:
 * `canApproveLinkExpansions`, an authenticated Subject, never the requester); this only decides what a reader is
 * offered. No Angular, no HTTP.
 */

/** The longest deny reason the server takes (`InvestigationRoutes.decide`: 422 beyond). */
export const DENY_REASON_MAX = 200;

/**
 * May this reader decide `p`? Only a pending request, only with the approve capability, and only for a signed-in
 * reader who is not its requester: without a Subject the server cannot tell two people apart and refuses (403), and
 * four-eyes refuses the requester. Everyone else keeps the requester's waiting text.
 */
export function canDecideExpansion(p: PendingExpansion, actor: string | null, canApprove: boolean): boolean {
    return p.status === 'pending' && canApprove && !!actor && actor !== p.requestedBy;
}

const n = (v: number): string => v.toLocaleString('en');

/**
 * What a held request would do beyond the Space's four-eyes limits, as ONE plain sentence from its sealed sensitivity
 * (LA-FULL-CYCLE-TEST-1 presentability): never a Settings key, "fan-out" or "unbounded" — "It would add every neighbour
 * of each entity, more than the 2,000 allowed without a second approval." A sensitivity with no numbers falls back to
 * the server's sealed reasons put into words ({@link thresholdWords}); '' when nothing is known.
 */
export function heldLimitWords(s: ExpansionSensitivity): string {
    const parts: string[] = [];
    if (s.fourEyesBudgetAbove != null && s.budget > s.fourEyesBudgetAbove)
        parts.push(`read up to ${n(s.budget)} rows, more than the ${n(s.fourEyesBudgetAbove)} allowed`);
    if (s.fourEyesFanOutAbove != null && (s.maxFanOut == null || s.maxFanOut > s.fourEyesFanOutAbove))
        parts.push(
            s.maxFanOut == null
                ? `add every neighbour of each entity, more than the ${n(s.fourEyesFanOutAbove)} allowed`
                : `add up to ${n(s.maxFanOut)} neighbours of each entity, more than the ${n(s.fourEyesFanOutAbove)} allowed`,
        );
    if (parts.length) return `It would ${parts.join(', and ')} without a second approval.`;
    return s.exceeded?.length ? `It ${thresholdWords(s.exceeded)}.` : '';
}

/** Who asked, as the reader knows them: `nameOf` turns the requester's id into a display name ("another analyst" if none). */
export function requesterName(p: PendingExpansion, nameOf: (id: string) => string): string {
    return p.requestedBy ? nameOf(p.requestedBy) : 'another analyst';
}

/**
 * The waiting line as an approver reads it: who asks, what it would do, and that the decision is theirs. The request id
 * stays out of the sentence (it is the server's handle, not the reader's); Settings ▸ Oversight lists it.
 */
export function approverWaitingMessage(o: DegreeOutcome, p: PendingExpansion, requester: string): string {
    const limits = p.sensitivity ? heldLimitWords(p.sensitivity) : '';
    return `${outcomeHead(o)}: ${requester} asks for your approval.` + (limits ? ` ${limits}` : '');
}

/** Ask before approving: approving runs the expand at once, as the requester's step, with the approver on record. */
export function confirmApproveExpansion(
    confirm: InspectoConfirmService,
    p: PendingExpansion,
    requester: string,
): Promise<boolean> {
    const limits = p.sensitivity ? heldLimitWords(p.sensitivity) : '';
    return confirm.confirmDestructive(
        `${requester} asks to expand.` +
            (limits ? ` ${limits}` : '') +
            '\n\nApproving runs the expand now, as their step, and records you as the approver.',
        { title: 'Approve this expand?', confirmText: 'Approve', destructive: false },
    );
}
