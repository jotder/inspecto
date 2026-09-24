/**
 * Whether a node occupies the projection SLOT `PipelineLift` fills between parser and sink — a
 * Record Transformer (`transform.sql`) whose id follows the lift's grammar (`map`, `map_<key>`). A chain
 * sql step is never named that way (`sql`, `sql__s2`). Mirrors `PipelineEditable.isProjectionSlot`.
 *
 * ⚠ This is all that is left of the offline mock's TS lift/lower port (dead since the mock was removed
 * in `f1553136`, deleted 2026-09-24 with `STEP-TYPES-DEAD-CLIENT-MIRRORS-1`). The server's
 * `PipelineEditable` / `PipelineLift` are the only lift/lower; the editor reads and saves through them.
 */
export function isProjectionSlot(n: { id: string; type: string }): boolean {
    return n.type === 'transform.sql' && (n.id === 'map' || n.id.startsWith('map_'));
}
