import type { ComponentDef } from '../../api/components.service';
import type { IconMap } from '../../api/icon-map.service';
import { NODE_KIND_COLORS } from '../../theme/chart-tokens';
import { componentCollection } from '../handlers/components.handler';
import { MockStore } from '../mock-store';

/** Shared helpers for seed packs (the default pack + the W5 Space-Template packs). */

/** Store one `ComponentDef` of `kind` under the component collection (dataset/widget/dashboard/…). */
export function putComponent(
    store: MockStore,
    space: string,
    kind: string,
    name: string,
    content: Record<string, unknown>,
): void {
    const def: ComponentDef = { type: kind, name, ref: `${kind}/${name}`, content };
    store.put(space, componentCollection(kind), name, def);
}

/**
 * The shipped Link-Analysis pattern packs as authored `pattern-pack` components (V2 (c)) — the same six the
 * `PATTERN_PACKS` const carries, so the seeded mock matches `spaces/*\/config/registry/pattern-packs/*.toon`.
 * ⚠ Every step carries a `direction`, the start node's being the EMPTY STRING: TOON cannot encode `{}` as a
 * list element, so the backend seed spells the wildcard as a blank and the toolbox maps blank → undefined.
 */
export function seedPatternPacks(store: MockStore, space: string): void {
    const packs: { name: string; label: string; category: string; description: string; steps: string[]; tool?: string }[] = [
        { name: 'layering-chain', label: 'Layering chain', category: 'money', steps: ['', 'out', 'out', 'out'],
            description: 'Funds relayed through a chain of intermediaries (A → B → C → D) to obscure origin — classic placement/layering.' },
        { name: 'pass-through', label: 'Pass-through intermediary', category: 'money', steps: ['', 'out', 'out'],
            description: 'A single intermediary that receives then forwards (A → M → B). Inspect the middle node — it is the mule/shell to scrutinize.' },
        { name: 'inbound-collector', label: 'Inbound collector', category: 'money', steps: ['', 'in', 'in'],
            description: 'Many parties converging INTO one account (they → M → target). Start from the collector and follow incoming links.' },
        { name: 'forwarding-relay', label: 'Call-forwarding relay', category: 'telecom', steps: ['', 'out', 'out'],
            description: 'A relay hop A → B → C — the building block of call-forwarding abuse and SIM-box relaying.' },
        { name: 'circular-flow', label: 'Circular flow', category: 'money', steps: ['', 'out', 'out'], tool: 'cycles',
            description: 'Value or calls returning to their origin (A → … → A). Use the Cycles tool — a closed loop is not a simple path motif.' },
        { name: 'shared-associates', label: 'Shared associates', category: 'identity', steps: ['', 'both'], tool: 'similarity',
            description: 'Distinct identities that share the same devices/accounts. Use Similarity from a seed node, or Cohesive groups for the whole ring.' },
    ];
    for (const p of packs) {
        const content: Record<string, unknown> = {
            name: p.name, label: p.label, category: p.category, description: p.description,
            steps: p.steps.map((direction) => ({ direction })),
        };
        if (p.tool) content['tool'] = p.tool;
        putComponent(store, space, 'pattern-pack', p.name, content);
    }
}

/** The processor icon map (category defaults + sub-type overrides) every space needs for the Pipelines canvas. */
export function seedIconMap(store: MockStore, space: string): void {
    const C = NODE_KIND_COLORS; // category accent colours, sourced from the canvas token owner
    const iconMap: IconMap = {
        SOURCE: { glyph: 'arrow-in', color: C.STREAM},
        PARSE: { glyph: 'lines', color: C.SCHEMA },
        TRANSFORM: { glyph: 'transform', color: C.ENRICHMENT },
        SINK: { glyph: 'cylinder', color: C.TABLE },
        CONTROL: { glyph: 'bell', color: C.KPI },
        'collector.file': { glyph: 'file', color: C.STREAM},
        'collector.database': { glyph: 'database', color: C.STREAM},
        'collector.stream': { glyph: 'stream', color: C.STREAM},
        'transform.filter': { glyph: 'filter', color: C.ENRICHMENT },
        'transform.route': { glyph: 'route', color: C.ENRICHMENT },
        'transform.aggregate': { glyph: 'sigma', color: C.ENRICHMENT },
        'transform.alert': { glyph: 'bell', color: C.KPI },
        'sink.file': { glyph: 'write', color: C.TABLE },
        'sink.database': { glyph: 'database', color: C.TABLE },
    };
    store.put(space, 'config', 'icon-map', iconMap);
}
