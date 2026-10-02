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
        label: 'Geo',
        icon: 'heroicons_outline:map',
        blurb: 'Place entities and routes on a map, over any Dataset with coordinates.',
    },
    {
        path: 'entity-lists',
        label: 'Entity Lists',
        icon: 'heroicons_outline:queue-list',
        blurb: 'Keep watch lists of entities, with every change on the record.',
    },
] as const;
