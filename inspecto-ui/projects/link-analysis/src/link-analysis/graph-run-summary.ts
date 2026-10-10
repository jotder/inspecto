import { GraphRunResult, GraphScoreView } from '@inspecto/link-analysis/api/graph-runs.service';
import { ServerIdMap } from './graph-run-apply';

/** A server algorithm answer in words, plus the canvas ids it can emphasise (what the generic *All algorithms* panel shows). */
export interface GraphRunSummary {
    headline: string;
    lines: string[];
    nodeIds: string[];
    edgeIds: string[];
}

const TOP = 10;

function plural(n: number, one: string, many = one + 's'): string {
    return `${n} ${n === 1 ? one : many}`;
}

/**
 * Describe ANY completed Graph Run by its `kind` alone - one reading per result shape, so a new algorithm needs no
 * panel. Ids are translated to canvas ids by `map`; one the canvas does not draw is simply not emphasised.
 */
export function summarizeGraphRun(r: GraphRunResult, map: ServerIdMap | null): GraphRunSummary {
    const nodeOf = (ids: string[]) => ids.map((i) => map?.node(i)).filter((i): i is string => !!i);
    const edgeOf = (ids: string[]) => ids.map((i) => map?.edge(i)).filter((i): i is string => !!i);
    const out = (headline: string, lines: string[] = [], nodeIds: string[] = [], edgeIds: string[] = []) => ({
        headline,
        lines,
        nodeIds,
        edgeIds,
    });
    switch (r.kind) {
        case 'SCORES':
        case 'SUSPICION':
        case 'PROPAGATED_RISK': {
            const s = (r.scores ?? []) as GraphScoreView[];
            return out(
                `${plural(s.length, 'node')} scored.`,
                s.slice(0, TOP).map((x) => `${x.label}: ${x.score}`),
                nodeOf(s.slice(0, TOP).map((x) => x.id)),
            );
        }
        case 'HITS': {
            const h = r.hubs ?? [];
            const a = r.authorities ?? [];
            return out(
                `${plural(h.length, 'hub')} and ${plural(a.length, 'authority', 'authorities')} scored.`,
                [
                    ...h.slice(0, 5).map((x) => `Hub ${x.label}: ${x.score}`),
                    ...a.slice(0, 5).map((x) => `Authority ${x.label}: ${x.score}`),
                ],
                nodeOf([...h.slice(0, 5), ...a.slice(0, 5)].map((x) => x.id)),
            );
        }
        case 'SELECTION': {
            const s = r.selection;
            return s
                ? out(
                      `${plural(s.nodeIds.length, 'node')} and ${plural(s.edgeIds.length, 'link')} selected.`,
                      [],
                      nodeOf(s.nodeIds),
                      edgeOf(s.edgeIds),
                  )
                : out('Nothing selected: no such path or selection exists.');
        }
        case 'SELECTIONS': {
            const all = r.selections ?? [];
            return out(
                `${plural(all.length, 'selection')} found.`,
                all.slice(0, TOP).map((s, i) => `${i + 1}. ${plural(s.nodeIds.length, 'node')}`),
                nodeOf(all.flatMap((s) => s.nodeIds)),
                edgeOf(all.flatMap((s) => s.edgeIds)),
            );
        }
        case 'GROUPS': {
            const g = r.groups ?? [];
            return out(
                `${plural(g.length, 'group')} found.`,
                g.slice(0, TOP).map((m, i) => `Group ${i + 1}: ${plural(m.length, 'node')}`),
                nodeOf(g.flat()),
            );
        }
        case 'COMMUNITIES': {
            const c = r.communities ?? [];
            const sizes = new Map<string, number>();
            for (const p of c) sizes.set(p.community, (sizes.get(p.community) ?? 0) + 1);
            return out(
                `${plural(sizes.size, 'community', 'communities')} over ${plural(c.length, 'node')}.`,
                [...sizes.values()]
                    .sort((a, b) => b - a)
                    .slice(0, TOP)
                    .map((n, i) => `Largest ${i + 1}: ${plural(n, 'node')}`),
                nodeOf(c.map((p) => p.id)),
            );
        }
        case 'IDS': {
            const ids = r.ids ?? [];
            // Bridges answer with link ids, every other IDS algorithm with node ids.
            const links = r.algorithm === 'bridges';
            return out(
                `${links ? plural(ids.length, 'link') : plural(ids.length, 'node')} found.`,
                ids.slice(0, TOP),
                links ? [] : nodeOf(ids),
                links ? edgeOf(ids) : [],
            );
        }
        case 'FLAG':
            return out(r.value ? 'Yes.' : 'No.');
        case 'FLOW': {
            const cut = r.minCut;
            return out(
                `Maximum capacity between the two nodes: ${r.value ?? 0}.`,
                cut ? [`Minimum cut: ${plural(cut.edgeIds.length, 'link')}.`] : [],
                cut ? nodeOf(cut.nodeIds) : [],
                cut ? edgeOf(cut.edgeIds) : [],
            );
        }
        case 'LINKS': {
            const l = r.links ?? [];
            return out(
                `${plural(l.length, 'predicted link')}.`,
                l.slice(0, TOP).map((x) => `${x.sourceLabel} - ${x.targetLabel}: ${x.score}`),
                nodeOf(l.flatMap((x) => [x.source, x.target])),
            );
        }
        case 'GRAPH': {
            const n = r.nodes ?? [];
            const e = r.edges ?? [];
            return out(
                `${plural(n.length, 'node')} and ${plural(e.length, 'link')} reached.`,
                n.slice(0, TOP).map((x) => x.label),
                nodeOf(n.map((x) => x.id)),
                edgeOf(e.map((x) => x.id)),
            );
        }
    }
}
