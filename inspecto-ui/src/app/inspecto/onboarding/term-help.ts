/**
 * Plain explanations of the product terms a beginner meets (LA-ONBOARD-1 UC-15 rule 3, use cases §6 "Concepts a beginner
 * meets"). Deterministic on every edition (A-9): no assistant, no server call. The words are the GLOSSARY's canonical
 * terms; each explanation is one short sentence a first-time user can read at a glance.
 */

export type TermId = 'space' | 'dataset' | 'entity-type' | 'entity' | 'entity-list' | 'case' | 'reference' | 'link';

export interface TermHelp {
    /** The canonical word, as the screen shows it. */
    readonly term: string;
    /** One plain sentence. */
    readonly text: string;
}

export const TERM_HELP: Readonly<Record<TermId, TermHelp>> = {
    space: { term: 'Space', text: 'A place for one kind of data, holding many cases.' },
    dataset: { term: 'Dataset', text: 'A table Inspecto keeps, such as the rows of a file you added.' },
    'entity-type': {
        term: 'Entity Type',
        text: 'A kind of thing your data talks about, like an account or a phone number.',
    },
    entity: { term: 'Entity', text: 'One of them: one account, one phone number.' },
    'entity-list': {
        term: 'Entity List',
        text: 'A list of entities you keep: ones to watch, to leave out, known good, or to block.',
    },
    case: {
        term: 'case',
        text: 'A graph you saved, with the lists it uses, so you can open it again. It re-reads the data when it opens.',
    },
    reference: { term: 'Reference', text: 'Details about entities, such as names or segments, shown on the graph.' },
    link: { term: 'link', text: 'An arrow between two entities, such as who paid whom.' },
};

/** The help for a term id; unknown ids fall back to no help (the word alone). */
export function termHelp(id: string): TermHelp | null {
    return (TERM_HELP as Record<string, TermHelp>)[id] ?? null;
}
