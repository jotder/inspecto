/**
 * The terms the Link Analysis header's About explains, in the order the analyst meets them, with the Studio's own
 * definition of each (distilled from `docs/GLOSSARY.md`). A hand-written definition here always wins over the raw
 * glossary row, which is written for engineers (LA-UI-TEXT-PASS-1 X2); each is one short, plain paragraph.
 */
export const LA_ABOUT_TERMS: readonly string[] = [
    'Entity',
    'Link',
    'Dataset',
    'Graph model',
    'Expand',
    'Saved view',
    'Investigation',
    'Working Set',
    'Draft',
    'Dossier',
];

export const LA_ABOUT_DEFINITIONS: Readonly<Record<string, string>> = {
    Entity: 'A thing on the graph: a caller, an account, a device. Each one has an Entity Type.',
    Link: 'A call or transaction between two Entities, with attributes such as its time and amount.',
    Dataset: 'A table of records. Link Analysis draws its graph from the rows of a Dataset.',
    'Graph model': "How a Dataset's rows become a graph: which column is the From Entity, which the To Entity, and which give the Link kind and attributes.",
    Expand: 'Adds the Entities linked to the ones you pick, one degree at a time.',
    'Saved view':
        'A named graph you keep: what it was drawn from, its display and the Entity Lists it uses. Opening it re-reads live data, so it is a starting point, not evidence.',
    Investigation:
        'A recorded line of enquiry: an ordered, replayable log of your steps (start, expand, exclude, hide, keep), each with who, when and why.',
    'Working Set': 'What an Investigation currently shows: the Entities and Links its step log evaluates to.',
    Draft: "Your private copy of an Investigation's work. You fork a Draft, explore, then promote it back.",
    Dossier: 'The sealed report of an Investigation: every step, exclusion and reason, with a chain of custody.',
};
