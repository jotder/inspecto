/**
 * The **Space guide** (help H1, LA-ONBOARD-1 phase 3): the per-Space checklist a beginner follows until setup is done.
 * Framework-free: the ticks are computed here from server state, so the Inspecto LA app and the console read the
 * same rules. `SpaceGuideService` loads the state; `<inspecto-space-guide>` draws it.
 */

/** The guide's steps, in order. `list` is optional: the guide is done without it. */
export type SpaceGuideStepId = 'space' | 'data' | 'columns' | 'graph' | 'list';

/** What the server says about the current Space, read defensively (an absent field reads as "not yet"). */
export interface SpaceGuideState {
    /** A Space is in force: the active Space of a multi-Space server, or the one Space of a single-Space server. */
    hasSpace: boolean;
    /** Datasets in the current Space. */
    datasets: number;
    /** Some Dataset says what a column holds (`columns[].classification`) or carries a `defaultLinkMapping`. */
    columnsDescribed: boolean;
    /** Saved Link Analysis views in the current Space (a drawn graph someone kept). */
    views: number;
    /** Entity Lists in the current Space. */
    lists: number;
}

export interface SpaceGuideStep {
    id: SpaceGuideStepId;
    label: string;
    /** One plain line: what the step means, or what is there once it is done. */
    hint: string;
    done: boolean;
    optional: boolean;
}

/** The empty state: nothing exists yet. */
export const NO_SPACE: SpaceGuideState = { hasSpace: false, datasets: 0, columnsDescribed: false, views: 0, lists: 0 };

/** The Dataset fields the guide reads: a registry `dataset` component's content, any shape. */
type DatasetContent = Record<string, unknown>;

/** Whether a Dataset says what its columns hold: a classified column, or a default link mapping naming both ends. */
export function datasetDescribesColumns(content: DatasetContent | null | undefined): boolean {
    if (!content) return false;
    const columns = content['columns'];
    if (Array.isArray(columns)) {
        for (const c of columns) {
            const cls = (c as Record<string, unknown> | null)?.['classification'];
            if (typeof cls === 'string' && cls.trim() !== '') return true;
        }
    }
    const mapping = content['defaultLinkMapping'] as Record<string, unknown> | null | undefined;
    return !!mapping && typeof mapping === 'object' && !!mapping['from'] && !!mapping['to'];
}

/** The steps with their ticks. */
export function spaceGuideSteps(s: SpaceGuideState): SpaceGuideStep[] {
    return [
        {
            id: 'space',
            label: 'Space',
            hint: s.hasSpace ? 'You have a place for your data.' : 'Create a place for one kind of data.',
            done: s.hasSpace,
            optional: false,
        },
        {
            id: 'data',
            label: 'Data',
            hint:
                s.datasets > 0
                    ? `${s.datasets} ${s.datasets === 1 ? 'Dataset' : 'Datasets'} (tables Inspecto keeps).`
                    : 'Add a file: it becomes a Dataset, a table Inspecto keeps.',
            done: s.hasSpace && s.datasets > 0,
            optional: false,
        },
        {
            id: 'columns',
            label: 'What the columns hold',
            hint: 'Say which columns hold accounts, phone numbers or other kinds of things.',
            done: s.hasSpace && s.columnsDescribed,
            optional: false,
        },
        {
            id: 'graph',
            label: 'First graph',
            hint: s.views > 0 ? 'A graph is saved in this Space.' : 'Draw the links between entities, then save it.',
            done: s.hasSpace && s.views > 0,
            optional: false,
        },
        {
            id: 'list',
            label: 'A list',
            hint: 'Keep the entities that matter: suspects, a watch list, entities to leave out.',
            done: s.hasSpace && s.lists > 0,
            optional: true,
        },
    ];
}

/** Setup is incomplete — the Getting started page shows — while there is no Space or no Dataset yet. */
export function setupIncomplete(s: SpaceGuideState): boolean {
    return !s.hasSpace || s.datasets === 0;
}

/** The guide is done when every required step is ticked; it then stops showing. */
export function guideDone(s: SpaceGuideState): boolean {
    return spaceGuideSteps(s).every((step) => step.done || step.optional);
}
