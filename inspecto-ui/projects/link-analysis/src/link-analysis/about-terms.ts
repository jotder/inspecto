/**
 * The terms the Link Analysis header's About explains, in the order the analyst meets them, with the Studio's own
 * definition of each (distilled from `docs/GLOSSARY.md`). The About asks the glossary first; these answer when it has
 * no entry the lookup can match - several LA entries carry an annotation (`**Entity / Link** *(business graphs only)*`)
 * the lookup does not read - so the About never falls back to unrelated documentation (UI test 2026-10-29).
 */
export const LA_ABOUT_TERMS: readonly string[] = [
    'Entity',
    'Link',
    'Dataset',
    'Entity Projection',
    'Saved view',
    'Investigation',
    'Working Set',
];

export const LA_ABOUT_DEFINITIONS: Readonly<Record<string, string>> = {
    Entity: 'A business node on the graph: a caller, an account, a device. Each one has an Entity Type.',
    Link: 'A business edge between two Entities - a call, a transaction - carrying typed attributes such as its time and amount.',
    Dataset: 'A queryable table of records. Link Analysis draws its graph from the rows of a Dataset.',
    'Entity Projection':
        "The mapping that folds a Dataset's rows into a graph: one column is the source Entity, one the target Entity, and optional columns give the Link type and attributes.",
    'Saved view':
        'A named graph you keep: the query or start it was drawn from, its display and the Entity Lists it uses. Opening it re-reads live data, so it is a starting point, not evidence.',
    Investigation:
        'A recorded line of enquiry: an ordered, replayable log of your steps (seed, expand, exclude, hide, keep), each with who, when and why.',
    'Working Set': "What an Investigation currently shows: the Entities and Links its log evaluates to, recomputed by replaying the log.",
};
