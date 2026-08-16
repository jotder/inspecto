import { AuthoredNode, AuthoredPipeline } from 'app/inspecto/api';
import { NodeStatus, PipelineFinding } from './pipeline-graph';

/**
 * The guided **stage model** (definition-surface P6-d) — the wizard's five-step data path expressed
 * over the graph the editor already holds.
 *
 * <p>⛔ This is NOT a port of `OnboardingStateService.stageStatus`. That one answers "is this stage
 * configured?" by looking for a **block** in the server-held draft (`'collector' in cfg`,
 * `processing.schema_file`, …); the editor has no draft in hand, it has an `AuthoredPipeline`. So the
 * same question is answered from the nodes — and, crucially, from the **existing** node-status model
 * ({@link NodeStatus}) rather than a second opinion about readiness, which is exactly how a chip and
 * the canvas card underneath it would come to disagree.
 *
 * <p>Pure: no injection, no signals. The host passes its own `statusOf` (it owns `validRefs` and the
 * run-to-here `tested` map) and the findings it has already computed.
 */

export type PipelineStageId = 'collect' | 'parse' | 'schema' | 'enrich' | 'publish';

/** Mirrors the wizard's vocabulary so the two surfaces read identically while both exist. */
export type StageStatus = 'empty' | 'configured' | 'validated' | 'blocked';

export interface StageChip {
    id: PipelineStageId;
    label: string;
    status: StageStatus;
    /** Node-attributed findings for this stage's nodes — the count the chip shows. */
    findings: number;
    /** The node a click opens; `null` for an empty stage (there is nothing to configure yet). */
    nodeId: string | null;
    optional: boolean;
}

/** Stage labels are GLOSSARY-canonical: the acquisition step is **Collect**, never 'Source'. */
const LABELS: Record<PipelineStageId, string> = {
    collect: 'Collect',
    parse: 'Parse',
    schema: 'Schema',
    enrich: 'Enrich',
    publish: 'Publish',
};

/** The one optional stage — an enrichment companion is a choice, every other stage is the data path. */
const OPTIONAL: ReadonlySet<PipelineStageId> = new Set<PipelineStageId>(['enrich']);

/** Keys whose presence on the parse node means the output schema has been authored. */
const SCHEMA_KEYS = ['schema_file', 'schemas', 'ingester'];

/** Whether the parse node carries an authored output schema (or a plugin's per-segment schemas). */
function hasSchemaArtifact(parse: AuthoredNode | undefined): boolean {
    const cfg = parse?.config ?? {};
    if (SCHEMA_KEYS.some((k) => !!cfg[k])) return true;
    const parsing = cfg['parsing'];
    if (!parsing || typeof parsing !== 'object') return false;
    return Object.values(parsing as Record<string, unknown>).some(
        (v) => !!v && typeof v === 'object' && !!(v as Record<string, unknown>)['segments'],
    );
}

/**
 * The guided checklist for a graph: one chip per stage, in data-path order.
 *
 * `statusOf` is the host's own per-node status (it owns `validRefs` + the tested map). A node that is
 * `unconfigured` or `dangling` makes its stage **blocked** — the same thing the canvas warns about,
 * said once. `tested` promotes a stage to `validated`; `rejects` deliberately does NOT (rows were
 * dropped, so a ✓ would be a lie), and its warning surfaces in the chip's finding count instead.
 */
export function stageChecklist(
    model: AuthoredPipeline | null,
    typeCat: ReadonlyMap<string, string>,
    statusOf: (node: AuthoredNode) => NodeStatus,
    findings: readonly PipelineFinding[],
): StageChip[] {
    const nodes = model?.nodes ?? [];
    const cat = (n: AuthoredNode): string => typeCat.get(n.type) ?? '';
    const authored = (n: AuthoredNode): boolean => !!n.config && Object.keys(n.config).length > 0;
    // 🔴 A `transform.map` node is on EVERY lifted graph whether or not anything authored it (the
    // server emits one per branch — MOCK-1), so the Schema stage keys on AUTHORED evidence: an
    // artifact named by the parse node, or a map node carrying config. Taking the derived node's
    // status instead would show `blocked` on every pipeline, including ones whose schema is fine.
    const byStage: Record<PipelineStageId, AuthoredNode[]> = {
        collect: nodes.filter((n) => cat(n) === 'SOURCE'),
        parse: nodes.filter((n) => cat(n) === 'PARSE'),
        schema: nodes.filter((n) => n.type === 'transform.map' && authored(n)),
        enrich: nodes.filter((n) => n.type === 'enrichment'),
        publish: nodes.filter((n) => cat(n) === 'SINK'),
    };
    const parse = byStage.parse[0];
    const schemaAuthored = hasSchemaArtifact(parse) || byStage.schema.length > 0;

    return (Object.keys(LABELS) as PipelineStageId[]).map((id) => {
        const own = byStage[id];
        // Schema is the one stage whose evidence can live on ANOTHER stage's node, so it falls back to
        // the parse node — that is where the artifact it names is carried.
        const host = own[0] ?? (id === 'schema' && schemaAuthored ? parse : undefined);
        const statuses = own.map(statusOf);
        let status: StageStatus;
        if (!host || (id === 'schema' && !schemaAuthored)) status = 'empty';
        else if (statuses.some((s) => s === 'unconfigured' || s === 'dangling')) status = 'blocked';
        // No preview thread in the editor yet (the per-tab sample is its own scheduled item), so the
        // schema stage tops out at `configured` rather than claiming a validation it never ran.
        else if (id !== 'schema' && statuses.some((s) => s === 'tested')) status = 'validated';
        else status = 'configured';

        const ids = new Set(own.map((n) => n.id));
        return {
            id,
            label: LABELS[id],
            status,
            findings: findings.filter((f) => f.nodeId && ids.has(f.nodeId)).length,
            nodeId: status === 'empty' ? null : (host?.id ?? null),
            optional: OPTIONAL.has(id),
        };
    });
}

/**
 * Draft → Ready → Live, the wizard's lifecycle over the same chips. A `blocked` stage is no more
 * Ready than an empty one; an optional stage never holds the pipeline back.
 */
export function pipelineLifecycle(chips: readonly StageChip[], active: boolean): 'Draft' | 'Ready' | 'Live' {
    if (active) return 'Live';
    return incompleteStages(chips).length === 0 ? 'Ready' : 'Draft';
}

/**
 * The required stages that are not ready — what a refused go-live names, so a blocked activation is
 * never a silent dead end (the wizard's `blockedStages`, which P6-b deliberately left behind).
 */
export function incompleteStages(chips: readonly StageChip[]): string[] {
    return chips.filter((c) => !c.optional && (c.status === 'empty' || c.status === 'blocked')).map((c) => c.label);
}
