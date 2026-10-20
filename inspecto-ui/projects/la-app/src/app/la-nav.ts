/** What the shell's nav and the landing page offer, in order. One list, so the nav and the routes cannot drift apart. */
export const LA_APP_NAV = [
    {
        path: 'link-analysis',
        label: 'Link Analysis',
        icon: 'heroicons_outline:share',
        blurb: 'Investigate how entities link, one expand step at a time.',
    },
    {
        path: 'geo-map',
        /** The `/bootstrap` feature that must be true for the area to be offered: the Geo map is Standard and up (D-LAE5). */
        feature: 'geoMap',
        label: 'Geo',
        icon: 'heroicons_outline:map',
        blurb: 'Place entities and routes on a map, over any Dataset with coordinates.',
    },
    {
        path: 'entities',
        label: 'Entities',
        icon: 'heroicons_outline:identification',
        blurb: 'See what the data says about each Entity Type and each Entity: how many, where, how fresh.',
    },
    {
        path: 'entity-lists',
        label: 'Entity Lists',
        icon: 'heroicons_outline:queue-list',
        blurb: 'Keep watch lists of entities, with every change on the record.',
    },
    {
        path: 'alerts',
        label: 'Alerts',
        icon: 'heroicons_outline:bell-alert',
        blurb: 'See fired Alerts, and open the Investigation behind a standing detection.',
    },
] as const;

/** The areas `features` (`/bootstrap`) allows: an area that names a `feature` is offered only when it is true, so Basic never shows a screen that only 503s. */
export function visibleLaNav(features: Record<string, boolean>) {
    return LA_APP_NAV.filter((n) => !('feature' in n) || features[n.feature] === true);
}

/**
 * The rail link to Settings: shown to every signed-in user, because the page always has a section for them (their own
 * limits) and shows the others read-only unless they may change them. Not on the landing page, which offers the areas.
 */
export const LA_APP_SETTINGS_NAV = {
    path: 'settings',
    label: 'Settings',
    icon: 'heroicons_outline:cog-6-tooth',
} as const;
