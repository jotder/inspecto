import { describe, expect, it } from 'vitest';
import { AuthoredNode, AuthoredPipeline } from 'app/inspecto/api';
import { NodeStatus, PipelineFinding } from './pipeline-graph';
import { incompleteStages, pipelineLifecycle, StageChip, stageChecklist } from './pipeline-stages';

const TYPE_CAT = new Map<string, string>([
    ['acquisition', 'SOURCE'],
    ['parser.delimited', 'PARSE'],
    ['transform.sql', 'TRANSFORM'],
    ['enrichment', 'TRANSFORM'],
    ['sink.persistent', 'SINK'],
]);

function flow(nodes: AuthoredNode[]): AuthoredPipeline {
    return { name: 'demo', active: false, nodes, edges: [] };
}

/** Every node configured unless the test says otherwise. */
const CONFIGURED = (): NodeStatus => 'configured';

function chip(chips: StageChip[], id: string): StageChip {
    return chips.find((c) => c.id === id)!;
}

/** A full, ordinary Stream graph — the shape most of these tests vary one node at a time. */
const FULL: AuthoredNode[] = [
    { id: 'src', type: 'acquisition', config: { poll: 'in/' } },
    { id: 'parse', type: 'parser.delimited', config: { schema_file: 'demo_schema.toon' } },
    { id: 'map', type: 'transform.sql', config: { columns: [] } },
    { id: 'out', type: 'sink.persistent', config: { database: 'd/db' } },
];

describe('stageChecklist', () => {
    it('reads the wizard stages off the graph, in data-path order', () => {
        const chips = stageChecklist(flow(FULL), TYPE_CAT, CONFIGURED, []);
        expect(chips.map((c) => c.id)).toEqual(['collect', 'parse', 'schema', 'enrich', 'publish']);
        expect(chips.map((c) => c.status)).toEqual(['configured', 'configured', 'configured', 'empty', 'configured']);
    });

    it('an empty graph is five empty chips and no node to open', () => {
        const chips = stageChecklist(flow([]), TYPE_CAT, CONFIGURED, []);
        expect(chips.every((c) => c.status === 'empty')).toBe(true);
        expect(chips.every((c) => c.nodeId === null)).toBe(true);
    });

    it('a null model does not throw', () => {
        expect(stageChecklist(null, TYPE_CAT, CONFIGURED, [])).toHaveLength(5);
    });

    it('an unconfigured or dangling node BLOCKS its stage — the same thing the canvas warns about', () => {
        const chips = stageChecklist(flow(FULL), TYPE_CAT, (n) => (n.id === 'parse' ? 'dangling' : 'configured'), []);
        expect(chip(chips, 'parse').status).toBe('blocked');
        expect(chip(chips, 'collect').status).toBe('configured');
    });

    it('a tested node validates its stage, and `rejects` deliberately does NOT', () => {
        const tested = stageChecklist(flow(FULL), TYPE_CAT, (n) => (n.id === 'src' ? 'tested' : 'configured'), []);
        expect(chip(tested, 'collect').status).toBe('validated');
        // Rows were dropped — a ✓ would be a lie; the warning shows up as the chip's finding count.
        const rejects = stageChecklist(flow(FULL), TYPE_CAT, (n) => (n.id === 'src' ? 'rejects' : 'configured'), []);
        expect(chip(rejects, 'collect').status).toBe('configured');
    });

    // 🔴 The server puts a projection-slot `transform.sql` node on EVERY lifted graph (one per branch) whether or not
    // anything authored it. Keying the Schema stage on that node's presence — or on its status, which
    // is `unconfigured` — would show every pipeline as blocked or configured regardless of the truth.
    it('ignores a DERIVED, config-less slot node', () => {
        const nodes = [
            FULL[0],
            { id: 'parse', type: 'parser.delimited', config: {} },
            { id: 'map', type: 'transform.sql' },
        ];
        const chips = stageChecklist(
            flow(nodes),
            TYPE_CAT,
            (n) => (n.id === 'map' ? 'unconfigured' : 'configured'),
            [],
        );
        expect(chip(chips, 'schema').status).toBe('empty');
        expect(chip(chips, 'schema').nodeId).toBeNull();
    });

    it('counts the schema artifact on the PARSE node as the Schema stage, and opens that node', () => {
        const nodes = [FULL[0], FULL[1]]; // parse carries schema_file; no map node at all
        const chips = stageChecklist(flow(nodes), TYPE_CAT, CONFIGURED, []);
        expect(chip(chips, 'schema').status).toBe('configured');
        expect(chip(chips, 'schema').nodeId).toBe('parse');
    });

    it('counts a plugin parser`s per-segment schemas as the artifact', () => {
        const nodes = [
            FULL[0],
            { id: 'parse', type: 'parser.delimited', config: { parsing: { asn1: { segments: { call: 's.toon' } } } } },
        ];
        expect(chip(stageChecklist(flow(nodes), TYPE_CAT, CONFIGURED, []), 'schema').status).toBe('configured');
    });

    it('an AUTHORED map node is the Schema stage even with no artifact, and is what a click opens', () => {
        const nodes = [FULL[0], { id: 'parse', type: 'parser.delimited', config: {} }, FULL[2]];
        const chips = stageChecklist(flow(nodes), TYPE_CAT, CONFIGURED, []);
        expect(chip(chips, 'schema').status).toBe('configured');
        expect(chip(chips, 'schema').nodeId).toBe('map');
    });

    it('counts only findings attributed to the stage`s own nodes', () => {
        const findings: PipelineFinding[] = [
            { severity: 'error', nodeId: 'src', message: 'a' },
            { severity: 'info', nodeId: 'src', message: 'b' },
            { severity: 'warning', nodeId: 'out', message: 'c' },
            { severity: 'warning', message: 'No writer/sink.' }, // global: belongs to no chip
        ];
        const chips = stageChecklist(flow(FULL), TYPE_CAT, CONFIGURED, findings);
        expect(chip(chips, 'collect').findings).toBe(2);
        expect(chip(chips, 'publish').findings).toBe(1);
        expect(chip(chips, 'parse').findings).toBe(0);
    });

    it('marks enrichment optional and nothing else', () => {
        const chips = stageChecklist(flow(FULL), TYPE_CAT, CONFIGURED, []);
        expect(chips.filter((c) => c.optional).map((c) => c.id)).toEqual(['enrich']);
    });

    it('an enrichment node fills its stage', () => {
        const chips = stageChecklist(
            flow([...FULL, { id: 'enr', type: 'enrichment', config: {}, use: 'enrichment/demo_enrich' }]),
            TYPE_CAT,
            CONFIGURED,
            [],
        );
        expect(chip(chips, 'enrich').status).toBe('configured');
        expect(chip(chips, 'enrich').nodeId).toBe('enr');
    });
});

describe('pipelineLifecycle / incompleteStages', () => {
    const full = (): StageChip[] => stageChecklist(flow(FULL), TYPE_CAT, CONFIGURED, []);

    it('a complete inactive pipeline is Ready; active is Live', () => {
        expect(pipelineLifecycle(full(), false)).toBe('Ready');
        expect(pipelineLifecycle(full(), true)).toBe('Live');
    });

    it('an optional stage never holds the pipeline back', () => {
        expect(incompleteStages(full())).toEqual([]); // Enrich is empty and that is fine
    });

    it('names every required stage that is empty OR blocked', () => {
        const chips = stageChecklist(
            flow([FULL[1], FULL[2]]), // no source, no sink
            TYPE_CAT,
            (n) => (n.id === 'parse' ? 'unconfigured' : 'configured'),
            [],
        );
        expect(incompleteStages(chips)).toEqual(['Collect', 'Parse', 'Publish']);
        expect(pipelineLifecycle(chips, false)).toBe('Draft');
    });

    it('an ACTIVE pipeline reads Live even when a stage regressed — the deployment is the fact', () => {
        expect(pipelineLifecycle(stageChecklist(flow([]), TYPE_CAT, CONFIGURED, []), true)).toBe('Live');
    });
});
