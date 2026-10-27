/**
 * The Analysis catalogue (LA-STUDIO-UI-REORG-1 §5.8, UIR-D10): every browser tool group and every server algorithm
 * (`GET /inv/graph/algorithms`) appears ONCE, grouped by the question it answers. A Browser | Server toggle sets where
 * it runs; a row is badged `browser`, `server` or `both` and states its cap. Framework-free: the toolbox renders it.
 */

/** Where a catalogue row can run. */
export type RunsOn = 'browser' | 'server' | 'both';
export type CatalogueWhere = 'browser' | 'server';

export type CatalogueQuestionId = 'connected' | 'important' | 'groups' | 'unusual';

export const CATALOGUE_QUESTIONS: readonly { id: CatalogueQuestionId; label: string }[] = [
    { id: 'connected', label: 'How are they connected?' },
    { id: 'important', label: 'Who matters most?' },
    { id: 'groups', label: 'What groups and weak points are there?' },
    { id: 'unusual', label: 'What looks unusual?' },
];

/**
 * One toolbox group in the catalogue: its question, the server algorithms that answer the same question (a non-empty
 * list makes it `both`), and `where` it is listed — `browser` (a browser run, with its server twin under Server),
 * `server` (server-only: listed under Server alone), `any` (a Dataset read that is not a graph algorithm: listed under
 * both, since it does not run on the drawn graph).
 */
export interface CatalogueTool {
    question: CatalogueQuestionId;
    server: readonly string[];
    listed: 'browser' | 'server' | 'any';
    runsOn: RunsOn;
}

const tool = (
    question: CatalogueQuestionId,
    server: readonly string[] = [],
    listed: CatalogueTool['listed'] = 'browser',
    runsOn?: RunsOn,
): CatalogueTool => ({ question, server, listed, runsOn: runsOn ?? (server.length ? 'both' : 'browser') });

/** The toolbox groups, by id (the toolbox's `AnalysisTab`). */
export const CATALOGUE_TOOLS: Readonly<Record<string, CatalogueTool>> = {
    path: tool('connected', ['shortestPath', 'weightedShortestPath']),
    'all-paths': tool('connected', ['allPaths']),
    'server-paths': tool('connected', [], 'any', 'server'),
    flow: tool('connected', ['maxFlow', 'maximumSpanningForest']), // vocab-allow: the max-flow graph algorithm id
    similarity: tool('connected', ['jaccardSimilarity', 'linkPrediction']),
    explain: tool('important'),
    centrality: tool('important', [
        'degreeCentrality',
        'pageRank',
        'betweennessCentrality',
        'closenessCentrality',
        'eigenvectorCentrality',
        'katzCentrality',
        'hits',
    ]),
    communities: tool('groups', ['louvainCommunities', 'detectCommunities']),
    components: tool('groups', ['connectedComponents']),
    'cut-points': tool('groups', ['articulationPoints', 'bridges']),
    cohesion: tool('groups', ['kCore', 'triangleCount', 'cliques']),
    cycles: tool('unusual', ['findCycles']),
    pattern: tool('unusual', [], 'browser', 'both'),
    scoring: tool('unusual', ['suspicionScore']),
    'value-measures': tool('unusual', [], 'any', 'server'),
    temporal: tool('unusual', [], 'any', 'server'),
    'index-build': tool('connected', [], 'server', 'server'),
};

/** The question of a server-only algorithm (one no browser group answers); anything unknown lands under "unusual". */
const SERVER_ONLY_QUESTION: Readonly<Record<string, CatalogueQuestionId>> = {
    neighborhood: 'connected',
    egoNetwork: 'connected',
    descendants: 'connected',
    isForest: 'groups',
    propagatedRisk: 'unusual',
};

/** The browser group whose server twin `algorithm` is, or null for a server-only algorithm. */
export function browserTwinOf(algorithm: string): string | null {
    for (const [id, t] of Object.entries(CATALOGUE_TOOLS)) if (t.server.includes(algorithm)) return id;
    return null;
}

export function serverQuestionOf(algorithm: string): CatalogueQuestionId {
    const twin = browserTwinOf(algorithm);
    return twin ? CATALOGUE_TOOLS[twin].question : (SERVER_ONLY_QUESTION[algorithm] ?? 'unusual');
}

/**
 * Is toolbox group `id` listed under `where`? A `both` group is listed under Server too while it hands its run over
 * (`handingOver`: the graph is above its browser cap, so its own control IS the server run).
 */
export function listedUnder(id: string, where: CatalogueWhere, handingOver = false): boolean {
    const t = CATALOGUE_TOOLS[id];
    if (!t) return where === 'browser';
    return t.listed === 'any' || t.listed === where || (where === 'server' && t.runsOn === 'both' && handingOver);
}

/** One server algorithm row under Server. */
export interface ServerCatalogueRow {
    id: string;
    label: string;
    runsOn: RunsOn;
    /** The largest Working Set the server answers inline; above it the run is a job the panel polls. */
    inlineNodeCeiling: number;
}

export interface CatalogueSection<T> {
    id: CatalogueQuestionId;
    label: string;
    tools: T[];
    server: ServerCatalogueRow[];
}

/**
 * The catalogue for one side of the toggle: each question with its toolbox groups and (under Server) its server
 * algorithms, the empty questions left out. A server algorithm whose browser twin is listed under Server too (none
 * today) would still appear once: the toolbox groups are browser runs, the server rows are the catalogue.
 */
export function catalogueSections<T extends { id: string }>(
    tools: readonly T[],
    algorithms: readonly { id: string; label: string; inlineNodeCeiling: number }[],
    where: CatalogueWhere,
    handingOver: (id: string) => boolean = () => false,
): CatalogueSection<T>[] {
    return CATALOGUE_QUESTIONS.map((q) => ({
        ...q,
        tools: tools.filter((t) => listedUnder(t.id, where, handingOver(t.id)) && (CATALOGUE_TOOLS[t.id]?.question ?? 'unusual') === q.id),
        server:
            where === 'server'
                ? algorithms
                      .filter((a) => serverQuestionOf(a.id) === q.id)
                      .map((a) => ({
                          id: a.id,
                          label: a.label,
                          inlineNodeCeiling: a.inlineNodeCeiling,
                          runsOn: browserTwinOf(a.id) ? ('both' as const) : ('server' as const),
                      }))
                : [],
    })).filter((s) => s.tools.length || s.server.length);
}
