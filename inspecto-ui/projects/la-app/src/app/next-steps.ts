import { SpaceGuideState } from '@inspecto/core/onboarding/space-guide';

/**
 * LA-ONBOARD-1 phase 8 (UC-15 rule 4) - the pure half of **What can I do next?**: from the server's state of the current
 * Space (the Space guide's {@link SpaceGuideState}) and what this person may do, the next actions in order, the first one
 * recommended. Deterministic help (A-9): no assistant. No Angular, no HTTP.
 */

export interface NextStep {
    id: string;
    /** The action's own words, as its button says them. */
    label: string;
    /** One plain line: why this is next (the reason the screen is empty, or what it adds). */
    why: string;
    /** Where it leads (an app route), or `create-space` for the Create Space dialog. */
    to: string;
    /** When set, the action is not open to this person here: shown locked with this reason (UC-15 rule 2). */
    locked?: string;
}

export interface NextStepContext {
    /** May create a Space (no sign-in, or `canAdminister`). */
    canCreateSpace: boolean;
    /** Why Add data is locked here (no right, or the Space's self-import is off), or null. */
    addDataLock: string | null;
}

/** Shown to someone who may not create a Space. */
export const ASK_FOR_A_SPACE = 'Only an administrator may create a Space: ask one to create it, or to open one to you.';

/** The next actions, at most three, the recommended one first. */
export function nextSteps(s: SpaceGuideState, ctx: NextStepContext): NextStep[] {
    const addData = (why: string): NextStep => ({
        id: 'add-data',
        label: 'Add data',
        why,
        to: '/add-data',
        ...(ctx.addDataLock ? { locked: ctx.addDataLock } : {}),
    });
    const sample: NextStep = {
        id: 'sample',
        label: 'Try it with sample data',
        why: 'See a ready graph over invented sample data first; your own data stays separate.',
        to: '/',
    };
    if (!s.hasSpace) {
        const create: NextStep = {
            id: 'create-space',
            label: 'Create a Space',
            why: 'Nothing exists yet: a Space is a place for one kind of data, such as mobile money or call records.',
            to: 'create-space',
            ...(ctx.canCreateSpace ? {} : { locked: ASK_FOR_A_SPACE }),
        };
        return ctx.canCreateSpace ? [create, sample] : [create];
    }
    if (s.datasets === 0) {
        const first = addData(
            'This Space holds no Dataset yet: add a file of events, one row per link between two things.',
        );
        return ctx.canCreateSpace ? [first, sample] : [first];
    }
    const out: NextStep[] = [];
    if (!s.columnsDescribed)
        out.push({
            id: 'columns',
            label: 'Say what the columns hold',
            why: 'No column is tied to an Entity Type yet, so Link Analysis cannot tell what links to what.',
            to: '/entities?tab=columns',
        });
    if (s.views === 0)
        out.push({
            id: 'graph',
            label: 'Draw your first graph',
            why: 'Start from one entity, grow from it, then save it as a case to come back to.',
            to: '/link-analysis',
        });
    if (s.lists === 0)
        out.push({
            id: 'list',
            label: 'Keep a list',
            why: 'Keep the entities that matter: ones to watch, or busy ones to leave out of every graph.',
            to: '/entity-lists',
        });
    if (out.length) return out.slice(0, 3);
    return [
        {
            id: 'my-work',
            label: 'Open a case',
            why: 'Your saved graphs are under My work on the home page; each re-reads the data when it opens.',
            to: '/',
        },
        {
            id: 'discover',
            label: 'Find something interesting',
            why: 'Who is busiest, which groups stick together, how one entity reaches another.',
            to: '/link-analysis',
        },
        addData('Add a file to a Dataset, or a new file as a Dataset of its own.'),
    ];
}
