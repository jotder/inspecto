import { SPACE_STORAGE_KEY } from 'app/inspecto/api/spaces.service';
import type { G6GraphData } from 'app/inspecto/graph';

/**
 * Persisted canvas positions for one Pipeline (`PIPELINE-CONFIG-HISTORY-AND-LAYOUT-1`, layout half).
 *
 * <p>Home = this browser's `localStorage`, keyed per space + Pipeline id — deliberately NOT the
 * `*_pipeline.toon`. The editable round-trip (`GET /graph/raw` → `PUT /graph`) carries no
 * coordinates, and the save is key-preserving over the canonical config, so a position written there
 * would be a new config key the engine never reads and every other writer would have to preserve.
 * Positions are a personal VIEW preference, like the dock widths (`inspecto.split.*`) and grid layouts
 * (`inspecto.grid.*`) — so they live where those do. The cost: a layout does not follow the author to
 * another device, and a Pipeline id change starts from the automatic layout again.
 */
export type PipelinePositions = Record<string, [number, number]>;

const PREFIX = 'inspecto.pipelines.layout';

function storageKey(pipelineId: string): string {
    let space: string | null = null;
    try {
        space = localStorage.getItem(SPACE_STORAGE_KEY);
    } catch {
        // storage unavailable — fall back to the default namespace
    }
    return `${PREFIX}.${space ?? 'default'}.${pipelineId}`;
}

/** The stored positions for a Pipeline, or `null` when none (or unreadable). */
export function loadPipelineLayout(pipelineId: string | null): PipelinePositions | null {
    if (!pipelineId) return null;
    try {
        const raw = localStorage.getItem(storageKey(pipelineId));
        if (!raw) return null;
        const parsed = JSON.parse(raw) as unknown;
        if (!parsed || typeof parsed !== 'object') return null;
        const out: PipelinePositions = {};
        for (const [id, p] of Object.entries(parsed as Record<string, unknown>)) {
            if (Array.isArray(p) && p.length === 2 && p.every((v) => typeof v === 'number' && Number.isFinite(v))) {
                out[id] = [p[0], p[1]];
            }
        }
        return out;
    } catch {
        return null;
    }
}

/** Best-effort write — storage errors are swallowed (a layout is a convenience, never an error). */
export function savePipelineLayout(pipelineId: string | null, positions: PipelinePositions): void {
    if (!pipelineId) return;
    try {
        localStorage.setItem(storageKey(pipelineId), JSON.stringify(positions));
    } catch {
        // quota / privacy mode
    }
}

export function clearPipelineLayout(pipelineId: string | null): void {
    if (!pipelineId) return;
    try {
        localStorage.removeItem(storageKey(pipelineId));
    } catch {
        // ignore
    }
}

/**
 * Place every node at its stored position — but ONLY when the stored layout covers EVERY node.
 * A node the layout does not know (added by a config edit elsewhere, or on another device) has no
 * honest place to go, and a half-restored graph beside an auto-placed stranger reads worse than a
 * clean automatic layout. So partial coverage returns `null` and the caller runs the auto layout.
 * Stored ids that no longer exist are simply ignored.
 */
export function applyPipelineLayout(data: G6GraphData | null, positions: PipelinePositions | null): G6GraphData | null {
    if (!data || !positions || !data.nodes.length) return null;
    if (!data.nodes.every((n) => positions[n.id])) return null;
    return {
        ...data,
        nodes: data.nodes.map((n) => {
            const [x, y] = positions[n.id];
            const style = (n as { style?: Record<string, unknown> }).style ?? {};
            return { ...n, style: { ...style, x, y } };
        }),
    };
}
