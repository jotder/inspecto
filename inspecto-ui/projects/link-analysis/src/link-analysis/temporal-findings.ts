import { EntityIdMapping, G6GraphData, resolveEntityId } from '@inspecto/core/graph';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { TemporalFinding, TemporalPatternResult, TemporalSeries } from '@inspecto/link-analysis/api/inv.service';

/** One finding placed on the canvas: the finding itself plus what it highlights (empty when the canvas does not draw it). A link finding highlights its endpoints and links, an entity finding its node. */
export interface PlacedTemporalFinding {
    finding: TemporalFinding;
    /** The endpoints and the drawn link(s) between them, in canvas ids. Empty `edgeIds` = not on the canvas. */
    selection: GraphSelection;
    onCanvas: boolean;
}

/** What a burst / periodicity run found, placed on the working set. Nothing is added to the graph. */
export interface TemporalFindingsState {
    mode: 'burst' | 'periodicity';
    series: TemporalSeries;
    findings: PlacedTemporalFinding[];
    /** How many findings the canvas does not draw (the scan ran over the whole Dataset, not the loaded slice). */
    offCanvas: number;
    truncated: boolean;
    rowCapped: boolean;
    skippedNoTime: number;
    timeNote: string;
}

/**
 * Place a `POST /inv/pattern/temporal` answer on the canvas. A finding names one directed source→target link by RAW
 * Dataset values, so each endpoint is minted by {@link resolveEntityId} (preferring its own end, D-M6) and the finding
 * highlights the drawn links running source→target. Unlike the server-path / branching mappers this NEVER adds nodes
 * or links: a finding the working set does not draw is counted ({@link TemporalFindingsState.offCanvas}), not invented.
 */
export function temporalResultToState(
    res: TemporalPatternResult,
    base: G6GraphData,
    mappings: readonly (EntityIdMapping | undefined)[] = [],
): TemporalFindingsState {
    const nodeIds = new Set(base.nodes.map((n) => n.id));
    const findings = res.results.map((finding): PlacedTemporalFinding => {
        if (finding.entity !== undefined) {
            // an entity finding highlights the entity's node (it is minted from either end of the mapping)
            const raw = String(finding.entity).trim();
            const n =
                resolveEntityId(mappings, raw, (x) => nodeIds.has(x), 'source') ??
                resolveEntityId(mappings, raw, (x) => nodeIds.has(x), 'target');
            const onCanvas = !!n && nodeIds.has(n);
            return { finding, onCanvas, selection: { nodeIds: onCanvas ? [n!] : [], edgeIds: [] } };
        }
        const s = resolveEntityId(mappings, String(finding.source ?? '').trim(), (x) => nodeIds.has(x), 'source');
        const t = resolveEntityId(mappings, String(finding.target ?? '').trim(), (x) => nodeIds.has(x), 'target');
        const edgeIds = s && t ? base.edges.filter((e) => e.source === s && e.target === t).map((e) => e.id) : [];
        const onCanvas = edgeIds.length > 0;
        return { finding, onCanvas, selection: { nodeIds: onCanvas ? [s!, t!] : [], edgeIds } };
    });
    return {
        mode: res.mode,
        series: res.series,
        findings,
        offCanvas: findings.filter((f) => !f.onCanvas).length,
        truncated: res.truncated,
        rowCapped: res.rowCapped,
        skippedNoTime: res.skippedNoTime,
        timeNote: res.timeNote,
    };
}

/** Every drawn finding at once, for the "Highlight all" action. */
export function allTemporalSelection(state: TemporalFindingsState): GraphSelection {
    const placed = state.findings.filter((f) => f.onCanvas);
    return {
        nodeIds: [...new Set(placed.flatMap((f) => f.selection.nodeIds))],
        edgeIds: [...new Set(placed.flatMap((f) => f.selection.edgeIds))],
    };
}
