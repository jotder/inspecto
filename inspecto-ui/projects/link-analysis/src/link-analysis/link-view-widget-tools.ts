import type { G6GraphData } from '@inspecto/core/graph';
import type { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import {
    analysisNodeCapValue,
    articulationPoints,
    connectedComponents,
    degreeCentrality,
    filterByKinds,
    filterByTime,
    findCycles,
    louvainCommunities,
    shortestPath,
} from '@inspecto/link-analysis/graph/graph-analysis';

/**
 * The read-only Widget toolset (LA-STUDIO-UI-REORG-1 §5.13, UIR-D4): what a viewer of a dashboard tile may do to the
 * drawn graph. Everything here is pure and runs in the browser over the projection the tile already read; nothing is
 * saved, recorded or sent.
 */
export type WidgetAnalysisId = 'shortestPath' | 'centrality' | 'communities' | 'components' | 'cycles' | 'cutPoints';

export const WIDGET_ANALYSES: readonly { id: WidgetAnalysisId; label: string }[] = [
    { id: 'shortestPath', label: 'Shortest path' },
    { id: 'centrality', label: 'Centrality' },
    { id: 'communities', label: 'Communities' },
    { id: 'components', label: 'Components' },
    { id: 'cycles', label: 'Cycles' },
    { id: 'cutPoints', label: 'Cut points' },
];

/** How many of the most central entities Centrality marks. */
export const WIDGET_CENTRALITY_TOP = 10;
/** How many cycles Cycles marks at most. */
export const WIDGET_CYCLE_LIMIT = 20;

/** The viewer's session-only narrowing of the tile's graph. */
export interface WidgetGraphFilter {
    /** Node kinds left out (empty = every kind). */
    hiddenKinds: string[];
    /** Entities the viewer hid. */
    hiddenNodes: string[];
    /** Edge attribute holding a date, and the cutoff (epoch millis); both set or no time filter. */
    timeColumn: string;
    timeCutoff: number | null;
}

/** The tile's graph after the viewer's kind, time and Hide filters. */
export function widgetGraph(g: G6GraphData, f: WidgetGraphFilter): G6GraphData {
    const kinds = [...new Set(g.nodes.map((n) => n.data.kind))];
    let out = f.hiddenKinds.length
        ? filterByKinds(
              g,
              kinds.filter((k) => !f.hiddenKinds.includes(k)),
              [],
          )
        : g;
    if (f.timeColumn && f.timeCutoff != null) out = filterByTime(out, f.timeColumn, f.timeCutoff);
    if (f.hiddenNodes.length) {
        const hidden = new Set(f.hiddenNodes);
        out = {
            nodes: out.nodes.filter((n) => !hidden.has(n.id)),
            edges: out.edges.filter((e) => !hidden.has(e.source) && !hidden.has(e.target)),
        };
    }
    return out;
}

/** Every edge attribute that holds at least one parseable date: the Filter-by-time column choices. */
export function timeColumnsOf(g: G6GraphData | null): string[] {
    if (!g) return [];
    const cols = new Set<string>();
    for (const e of g.edges)
        for (const [k, v] of Object.entries(e.data.attrs ?? {}))
            if (v != null && Number.isFinite(Date.parse(v))) cols.add(k);
    return [...cols].sort();
}

/** Why an analysis cannot run on this graph and selection, or null when it can. */
export function widgetAnalysisBlocked(id: WidgetAnalysisId, g: G6GraphData, selection: string[]): string | null {
    const cap = analysisNodeCapValue();
    if (g.nodes.length > cap) return `Over ${cap} entities: open the view in Link Analysis to analyse it.`;
    if (id === 'shortestPath' && selection.length !== 2) return 'Select exactly two entities first.';
    return null;
}

export interface WidgetAnalysisResult {
    emphasis: GraphEmphasis | null;
    /** One line said under the toolbar. */
    summary: string;
}

/** Run one browser algorithm over the drawn graph. */
export function runWidgetAnalysis(id: WidgetAnalysisId, g: G6GraphData, selection: string[]): WidgetAnalysisResult {
    switch (id) {
        case 'shortestPath': {
            const path = shortestPath(g, selection[0], selection[1]);
            return path
                ? { emphasis: path, summary: `Shortest path: ${path.edgeIds.length} link(s).` }
                : { emphasis: null, summary: 'No path joins the two entities.' };
        }
        case 'centrality': {
            const top = degreeCentrality(g)
                .filter((s) => s.score > 0)
                .slice(0, WIDGET_CENTRALITY_TOP);
            return {
                emphasis: { nodeIds: top.map((s) => s.id) },
                summary: top.length ? `Most connected: ${top.map((s) => s.label).join(', ')}.` : 'No links to rank.',
            };
        }
        case 'communities':
            return groups(louvainCommunities(g), 'community', 'communities');
        case 'components': {
            const map = new Map<string, string>();
            connectedComponents(g).forEach((ids, i) => ids.forEach((n) => map.set(n, String(i))));
            return groups(map, 'component', 'components');
        }
        case 'cycles': {
            const cycles = findCycles(g, { limit: WIDGET_CYCLE_LIMIT });
            return {
                emphasis: cycles.length
                    ? {
                          nodeIds: [...new Set(cycles.flatMap((c) => c.nodeIds))],
                          edgeIds: [...new Set(cycles.flatMap((c) => c.edgeIds))],
                      }
                    : null,
                summary: cycles.length
                    ? `${cycles.length}${cycles.length === WIDGET_CYCLE_LIMIT ? '+' : ''} cycle(s).`
                    : 'No cycles.',
            };
        }
        case 'cutPoints': {
            const cut = articulationPoints(g);
            return {
                emphasis: cut.length ? { nodeIds: cut } : null,
                summary: cut.length ? `${cut.length} cut point(s): removing one splits the graph.` : 'No cut points.',
            };
        }
    }
}

function groups(map: Map<string, string>, one: string, many: string): WidgetAnalysisResult {
    const count = new Set(map.values()).size;
    return {
        emphasis: count ? { nodeIds: [...map.keys()], groups: map } : null,
        summary: `${count} ${count === 1 ? one : many}.`,
    };
}
