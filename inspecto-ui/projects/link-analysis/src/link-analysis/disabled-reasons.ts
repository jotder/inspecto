/**
 * The fixed phrases a disabled Link Analysis control gives as its reason (LA-UI-TEXT-PASS-1 X6). A disabled control
 * stays focusable (`[disabledInteractive]`), carries the phrase as its tooltip, and shows it as one visible line under
 * it, so every user learns why. One phrase per cause: never write a new wording for the same reason.
 */
export const DISABLED_REASON = {
    pickNode: 'Pick a node first.',
    pickFromTo: 'Pick a From and a To node first.',
    selectEntities: 'Select entities first.',
    fillRequired: 'Fill the required fields to run.',
    setWindow: 'Set a window first.',
    searching: 'Searching…',
    openInvestigation: 'Open an Investigation first.',
} as const;

/** "Needs <permission>." (G14): the permission's plain name, never an id or the word "capability". */
export const needsPermission = (permission: string): string => `Needs ${permission}.`;

/** The one sentence for a tool that needs a time column it does not have (X8). */
export const NEEDS_TIME_COLUMN =
    'Needs a time column: add one in Settings ▸ Graph model, then pick it in the toolbar’s time filter.';
