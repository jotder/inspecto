import type { EntityProjection } from '@inspecto/core/graph';
import type { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import type { DegreePresets, DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import type { GrowRequest } from './grow-exclude';

/**
 * LA-UNSAVED-INVESTIGATION-1 — an Investigation the analyst is working on before Save (plan
 * `docs/superpower/la-unsaved-investigation.md`). The pure half: what Save needs, the ordered step list and its words.
 * Nothing is written to the server until Save, which creates the Investigation and replays these steps in order.
 */

/** One step taken while unsaved, as Save replays it. */
export type UnsavedStep =
    /** Start entities: one `seed` op of these values. */
    | { kind: 'seed'; ids: string[]; entityType?: string }
    /**
     * An Expand: per degree one `expand` op. `outer` starts from the Working Set's outer ring (no ids named, so a masked
     * alias never travels); `ids` starts from the named values.
     */
    | {
          kind: 'expand';
          from: 'outer' | 'ids';
          ids: string[];
          req: GrowRequest;
          /**
           * The exact values each degree expanded from on screen (capped as the screen caps them), so Save records the
           * same Working Set that was drawn. Present = replayed as these lists, one \`expand\` op per degree.
           */
          walked?: string[][];
          /**
           * LA-DEGREES-TRIM-1 (screen only, never sent): the Working Set after each degree, one per `walked` list. Lowering
           * Degrees redraws `stages[M-1]` and drops the rest, so the terminal entities leave the canvas and the step.
           */
          stages?: WorkingSet[];
      };

/** What Save binds the Investigation to (the same binding the New form used to create one at once). */
export interface UnsavedBinding {
    projection: EntityProjection;
    timeCol?: string;
    timeColZone?: string;
    eventsCol?: string;
    presets: DegreePresets;
    maxDegree: number;
    windowEnd?: string | null;
    profileId?: DomainProfileId;
}

export interface UnsavedInvestigation {
    binding: UnsavedBinding;
    /** The default Name and Purpose (`newInvestigationDefaults`), what the Save dialog opens with. */
    defaults: { name: string; purpose: string };
    /** The Save dialog's last edits, kept when it is cancelled; null = not opened yet. */
    draft: { name: string; purpose: string } | null;
    steps: UnsavedStep[];
}

/** The reason an unsaved Expand is refused: a held step needs a recorded Investigation. */
export const UNSAVED_FOUR_EYES =
    'This Expand needs approval from a second person, so it cannot run unsaved. Save first, then Expand.';

/** The same refusal for a start (its ring is an Expand): nothing to save yet, so say what to do instead. */
export const UNSAVED_FOUR_EYES_START =
    "This start's ring needs a second person's approval, so it cannot run unsaved. Save first: start from the whole Dataset, Save, then start from the selected entities.";

/** The reason the steps other than Start and Expand are not offered while unsaved. */
export const SAVE_FIRST = 'Save first: this step changes a recorded Working Set.';

function many(n: number, one: string, more: string): string {
    return `${n.toLocaleString('en-GB')} ${n === 1 ? one : more}`;
}

const DIRECTION_WORDS: Record<string, string> = {
    either: 'both ways',
    out: 'outgoing',
    in: 'incoming',
    reciprocal: 'both ways, mutual',
};

/** One step in words, as the unsaved step list and the Save outcome say it. */
export function unsavedStepLine(s: UnsavedStep): string {
    if (s.kind === 'seed') return `Start from ${many(s.ids.length, 'entity', 'entities')}`;
    const from = s.from === 'outer' ? 'the outer ring' : many(s.ids.length, 'entity', 'entities');
    const kinds = s.req.linkKinds?.length ? `, ${s.req.linkKinds.join(', ')}` : '';
    const busy = s.req.hold ? '' : ', through busy entities';
    return `Expand ${many(s.req.degrees, 'degree', 'degrees')} from ${from}, ${DIRECTION_WORDS[s.req.direction] ?? s.req.direction}${kinds}${busy}`;
}

/** What a Save did, for the toast and the tab: every step recorded, or where the replay stopped and why. */
export interface UnsavedSaveOutcome {
    /** The Investigation was created (a refused create leaves the unsaved work as it was). */
    created: boolean;
    /** Steps recorded, in order (1-based count). */
    recorded: number;
    /** The message to show; '' when every step was recorded. */
    message: string;
}

/** The replay stopped at step `at` (1-based): name it and the steps that were not recorded. */
export function replayStopMessage(
    steps: readonly UnsavedStep[],
    at: number,
    cause: 'refused' | 'held',
    reason = '',
    /** Steps recorded on the server before the stop; absent = the steps before \`at\`. */
    recorded = at - 1,
): string {
    const step = steps[at - 1];
    const what = step ? `Step ${at} (${unsavedStepLine(step)})` : `Step ${at}`;
    const head =
        cause === 'held'
            ? `Saved. ${what} is waiting for a second person's approval.`
            : `Saved with ${many(recorded, 'step', 'steps')}. ${what} was refused${reason ? `: ${reason.trim().replace(/\.?$/, '.')}` : '.'}`;
    const first = at + 1;
    if (first > steps.length) return head;
    return `${head} ${first === steps.length ? `Step ${first} was` : `Steps ${first} to ${steps.length} were`} not recorded.`;
}

/** The Degrees of an unsaved Expand the stepper can resize: the degrees it walked, when it kept what each one drew. */
export function expandStepDegrees(step: UnsavedStep | undefined): number | null {
    if (step?.kind !== 'expand' || !step.walked?.length || step.stages?.length !== step.walked.length) return null;
    return step.walked.length;
}

/**
 * LA-DEGREES-TRIM-1: the step cut to its first `degrees` degrees and the Working Set that many degrees drew (the
 * terminal entities and the links only they carried are gone). Null when it cannot be cut (not an Expand, nothing to cut).
 */
export function trimExpandStep(step: UnsavedStep | undefined, degrees: number): { step: UnsavedStep; ws: WorkingSet } | null {
    const have = expandStepDegrees(step);
    if (!have || step?.kind !== 'expand' || degrees < 1 || degrees >= have) return null;
    return {
        step: { ...step, walked: step.walked!.slice(0, degrees), stages: step.stages!.slice(0, degrees), req: { ...step.req, degrees } },
        ws: step.stages![degrees - 1],
    };
}

/** LA-DEGREES-TRIM-1: the step with further degrees walked from its outer ring appended (raising Degrees). */
export function extendExpandStep(step: UnsavedStep, more: { walked: string[][]; stages: WorkingSet[] }): UnsavedStep {
    if (step.kind !== 'expand' || !expandStepDegrees(step) || !more.walked.length) return step;
    const walked = [...step.walked!, ...more.walked];
    return { ...step, walked, stages: [...step.stages!, ...more.stages], req: { ...step.req, degrees: walked.length } };
}
