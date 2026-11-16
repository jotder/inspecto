import type { ExpansionSensitivity, PendingExpansion } from '@inspecto/link-analysis/api/inv.service';
import type { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { DegreeOutcome, outcomeHead } from './investigate-number';

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

/** The four-eyes limits a request crossed, in words, from its sealed sensitivity (no Settings keys). */
export function heldLimitWords(s: ExpansionSensitivity): string {
    const parts: string[] = [];
    if (s.fourEyesBudgetAbove != null && s.budget > s.fourEyesBudgetAbove)
        parts.push(`row budget ${n(s.budget)} (limit ${n(s.fourEyesBudgetAbove)})`);
    if (s.fourEyesFanOutAbove != null && (s.maxFanOut == null || s.maxFanOut > s.fourEyesFanOutAbove))
        parts.push(
            `${s.maxFanOut == null ? 'fan-out unbounded' : `fan-out ${n(s.maxFanOut)}`} (limit ${n(s.fourEyesFanOutAbove)})`,
        );
    return parts.length ? parts.join('; ') : (s.exceeded ?? []).join('; ');
}

/** The waiting line as an approver reads it: whose request, what it crosses, and that the decision is theirs. */
export function approverWaitingMessage(o: DegreeOutcome, p: PendingExpansion): string {
    const limits = p.sensitivity ? heldLimitWords(p.sensitivity) : '';
    return (
        `${outcomeHead(o)}: request ${p.id} from ${p.requestedBy ?? 'another analyst'} waits for your approval` +
        (limits ? `. It crosses the Space's four-eyes limit: ${limits}.` : '.')
    );
}

/** Ask before approving: approving runs the expand at once, as the requester's step, with the approver on record. */
export function confirmApproveExpansion(confirm: InspectoConfirmService, p: PendingExpansion): Promise<boolean> {
    const limits = p.sensitivity ? heldLimitWords(p.sensitivity) : '';
    return confirm.confirmDestructive(
        `Request ${p.id} from ${p.requestedBy ?? 'another analyst'}` +
            (limits ? ` crosses the Space's four-eyes limit: ${limits}.` : '.') +
            '\n\nApproving runs the expand now, as their step, and records you as the approver.',
        { title: 'Approve this expand?', confirmText: 'Approve', destructive: false },
    );
}
