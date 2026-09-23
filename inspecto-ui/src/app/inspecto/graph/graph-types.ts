import { NodeKind } from 'app/inspecto/api';

/**
 * Shared AntV G6 graph-data shape, lifted out of `catalog/catalog-graph.ts` so framework-agnostic libraries
 * (the component-model reuse-graph) can target the existing `GraphViewComponent` host **without importing a
 * feature**. `kind` is a {@link NodeKind} — a known metadata kind or any free string (a `ComponentKind` id) —
 * which the host keys node shape / outline colour off. `iconSrc`/`color` are set when a configurable icon is
 * resolved (pipeline views); `missing` marks a dangling-reference ghost node (the reuse-graph). Colours
 * come from tokens, never hardcoded here.
 */
export interface G6Node {
    id: string;
    data: {
        label: string;
        kind: NodeKind;
        iconSrc?: string;
        color?: string;
        missing?: boolean;
        /** The Incident/Case this node represents, when the source could resolve one — lights the
         *  detail dialog's "Open record" pivot (ui-design-review R8). Structurally = `ElementObjectRef`. */
        objectRef?: { id: string; type: 'INCIDENT' | 'CASE' };
        /**
         * LA-06: the real node ids this one STANDS IN FOR. Present only on a synthetic super-node
         * ({@link SUPER_NODE_KIND}) produced by `aggregateSuperNodes`; absent on every real node.
         * ⛔ A node carrying this is NOT an entity — it must never carry an `objectRef`, because an
         * analyst who could "open the record" of a stand-in for 200 accounts would be opening one real
         * account while believing it represented all of them.
         */
        superMembers?: string[];
        /**
         * D-S4: the distinct RAW spellings folded into this entity by `normalizeEntityKey`, in first-seen
         * order. Present only on projected entity nodes; `label` is the first of them. Two or more is what
         * the split-identity notice reports.
         */
        spellings?: string[];
        /**
         * LA-08: the Datasets this entity was projected from (`__provenance_dataset`), first-seen order. Two or
         * more means rows from different Datasets normalised to one key and were merged into this node.
         */
        provenance?: string[];
    };
}

/** A G6 edge — endpoints as source/target (the API graph uses from/to); `kind` labels the edge. */
export interface G6Edge {
    id: string;
    source: string;
    target: string;
    data: {
        kind: string;
        /** Extra per-edge attributes (entity-projection `attrCols`), keyed by source column name. */
        attrs?: Record<string, string | null>;
        /** LA-08: the Datasets this link was projected from (`__provenance_dataset`). */
        provenance?: string[];
        /** A derived display-only Pipeline edge (the companion-enrichment line): styled apart, not selectable. */
        derived?: true;
    };
}

export interface G6GraphData {
    nodes: G6Node[];
    edges: G6Edge[];
}
