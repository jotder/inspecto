/**
 * What the Link Analysis page has open, remembered for this browser tab so a Space switch (which reloads the app) can say
 * what it closed. sessionStorage: one tab, survives the reload, gone with the tab. Never a way to reopen anything — the
 * open Investigation travels in `?investigation=`, a saved view in its own menu.
 */
const OPEN_WORK_KEY = 'inspecto.la.openWork';

/** The open item's label ("Investigation Mule hub"), the Space it belongs to and the user who had it open. */
export interface OpenWork {
    space: string;
    actor: string;
    label: string;
}

/** Remember what is open now (null = nothing open). Storage blocked: nothing is remembered. */
export function rememberOpenWork(work: OpenWork | null): void {
    try {
        if (work) sessionStorage.setItem(OPEN_WORK_KEY, JSON.stringify(work));
        else sessionStorage.removeItem(OPEN_WORK_KEY);
    } catch {
        /* storage blocked */
    }
}

/**
 * What THIS user had open in ANOTHER Space before this page loaded in `space` — i.e. what a Space switch closed — and
 * forget it. Null when nothing was open, it was this Space's own (a plain reload), or another user's: a title is the
 * analyst's data, never shown to whoever signs in next in the same tab.
 */
export function takeClosedBySpaceSwitch(space: string, actor: string): OpenWork | null {
    try {
        const raw = sessionStorage.getItem(OPEN_WORK_KEY);
        const work = raw ? (JSON.parse(raw) as Partial<OpenWork>) : null;
        if (!work?.label || !work.space) return null;
        if (work.actor !== actor) {
            sessionStorage.removeItem(OPEN_WORK_KEY);
            return null;
        }
        if (work.space === space) return null;
        sessionStorage.removeItem(OPEN_WORK_KEY);
        return { space: work.space, actor, label: work.label };
    } catch {
        return null;
    }
}

/** The toast after a Space switch closed an item. */
export function closedBySpaceSwitchMessage(work: OpenWork): string {
    return `${work.label} was closed when you switched Space. It stays in Space ${work.space}; switch back to reopen it.`;
}
