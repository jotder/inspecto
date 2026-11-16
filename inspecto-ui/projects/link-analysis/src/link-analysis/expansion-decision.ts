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
 * of each entity, more than the 2,000 allowed without a second approval." Both limits crossed: what it would do, then
 * both limits in one clause, so "without a second approval" cannot read as belonging to the last one only. A sensitivity
 * with no numbers falls back to the server's sealed reasons put into words ({@link thresholdWords}); '' when nothing is
 * known.
 */
export function heldLimitWords(s: ExpansionSensitivity): string {
    const budget =
        s.fourEyesBudgetAbove != null && s.budget > s.fourEyesBudgetAbove
            ? { does: `read up to ${n(s.budget)} rows`, limit: `${n(s.fourEyesBudgetAbove)} rows` }
            : null;
    const fanOut =
        s.fourEyesFanOutAbove != null && (s.maxFanOut == null || s.maxFanOut > s.fourEyesFanOutAbove)
            ? {
                  does:
                      s.maxFanOut == null
                          ? 'add every neighbour of each entity'
                          : `add up to ${n(s.maxFanOut)} neighbours of each entity`,
                  limit: `${n(s.fourEyesFanOutAbove)} neighbours of each entity`,
              }
            : null;
    if (budget && fanOut)
        return (
            `It would ${budget.does} and ${fanOut.does}; without a second approval the limits are ` +
            `${budget.limit} and ${fanOut.limit}.`
        );
    if (budget)
        return `It would ${budget.does}, more than the ${n(s.fourEyesBudgetAbove!)} allowed without a second approval.`;
    if (fanOut)
        return `It would ${fanOut.does}, more than the ${n(s.fourEyesFanOutAbove!)} allowed without a second approval.`;
    return s.exceeded?.length ? `It is held: ${thresholdWords(s.exceeded)}.` : '';
}

/** Who asked, as the reader knows them: `nameOf` turns the requester's id into a display name ("another analyst" if none). */
export function requesterName(p: PendingExpansion, nameOf: (id: string) => string): string {
    return p.requestedBy ? nameOf(p.requestedBy) : 'another analyst';
}

/**
 * The Expand that already ran with the same head as the held line `o` (the latest before it that read data: not held,
 * not denied), or null. A held request that repeats it ("Expand one hop from every entity" twice) is told apart by what
 * it asks differently ({@link requestDifference}).
 */
export function recordedBefore(outcomes: readonly DegreeOutcome[], o: DegreeOutcome): DegreeOutcome | null {
    const at = outcomes.indexOf(o);
    const head = outcomeHead(o);
    for (let i = (at < 0 ? outcomes.length : at) - 1; i >= 0; i--) {
        const r = outcomes[i];
        const ran = !r.awaitingApproval || r.decision === 'approved';
        if (ran && outcomeHead(r) === head) return r;
    }
    return null;
}

/**
 * How a held request differs from the recorded Expand it repeats, in the recorded line's own words ("Only the 6
 * strongest links of each entity were added", "The read stopped at its budget of 500 rows"), from the request's sealed
 * sensitivity against the step's rung: `links` = how many links of each entity, `rows` = the read budget. Empty when
 * nothing known differs.
 */
export function requestDifference(
    s: ExpansionSensitivity,
    ran: DegreeOutcome,
): { words: string[]; links: boolean; rows: boolean } {
    const words: string[] = [];
    let links = false;
    let rows = false;
    if (ran.maxFanOut != null && s.maxFanOut !== ran.maxFanOut) {
        links = true;
        words.push(
            s.maxFanOut == null
                ? `adding every link of each entity, not only the ${n(ran.maxFanOut)} strongest`
                : `adding up to ${n(s.maxFanOut)} links of each entity, not ${n(ran.maxFanOut)}`,
        );
    }
    if (ran.budget != null && s.budget !== ran.budget) {
        rows = true;
        words.push(`reading up to ${n(s.budget)} rows, not ${n(ran.budget)}`);
    }
    return { words, links, rows };
}

/**
 * The waiting line as an approver reads it: who asks, what it would do, and that the decision is theirs. The request id
 * stays out of the sentence (it is the server's handle, not the reader's); Settings ▸ Oversight lists it.
 * LA-EXEC-DEMO-POLISH-1: it opens "Requested next, not yet run:", because it sits under the recorded line of the Expand
 * already run ("… (step 2): 6 new entities, 7 links added."). When the request repeats that Expand (`ran`, the same
 * head), it says how it differs, from the request's parameters against the step's: "Requested next, not yet run: Expand
 * one hop from every entity again, this time adding every link of each entity, not only the 6 strongest. Demo FM Analyst
 * asks for your approval. That is more than the 2,000 allowed without a second approval." Otherwise the two read as one
 * Expand that both ran and was over the limit.
 */
export function approverWaitingMessage(
    o: DegreeOutcome,
    p: PendingExpansion,
    requester: string,
    ran: DegreeOutcome | null = null,
): string {
    const s = p.sensitivity;
    const diff = s && ran ? requestDifference(s, ran) : null;
    if (s && diff?.words.length) {
        const fanOut = s.fourEyesFanOutAbove != null && (s.maxFanOut == null || s.maxFanOut > s.fourEyesFanOutAbove);
        const budget = s.fourEyesBudgetAbove != null && s.budget > s.fourEyesBudgetAbove;
        // the limit crossed is the one the difference already describes: name only its number, not the change again
        const limit =
            fanOut && !budget && diff.links
                ? `That is more than the ${n(s.fourEyesFanOutAbove!)} allowed without a second approval.`
                : budget && !fanOut && diff.rows
                  ? `That is more than the ${n(s.fourEyesBudgetAbove!)} allowed without a second approval.`
                  : heldLimitWords(s);
        return (
            `Requested next, not yet run: ${outcomeHead(o)} again, this time ${diff.words.join(' and ')}. ` +
            `${requester} asks for your approval.` +
            (limit ? ` ${limit}` : '')
        );
    }
    const limits = s ? heldLimitWords(s) : '';
    return (
        `Requested next, not yet run: ${outcomeHead(o)}. ${requester} asks for your approval.` +
        (limits ? ` ${limits}` : '')
    );
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
