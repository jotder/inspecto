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
        /** Link Analysis supernode suppression: an Investigation flagged this entity high connectivity (shown, not
         *  expanded further). Drawn with a wide halo ring; absent on every other node. */
        highConnectivity?: boolean;
        /** With `highConnectivity`: the flagged entity's sealed contact count (Working Set `contacts`), shown in the
         *  hover text. Absent when the server sent none. */
        contacts?: number;
        /** Link Analysis: an Investigation's Working Set has this entity but no effective expand has followed its links
         *  yet (a flagged hub is excluded: its halo already says "not expanded"). Drawn with a dashed outline; absent otherwise. */
        unexpanded?: boolean;
        /** Link Analysis Grow (LA-GROW-EXCLUDE-1): a busy entity held back from growing — drawn with a "held" badge. */
        held?: boolean;
        /** Link Analysis Collapse (§5.5): how many pendant neighbours this node folds — drawn as a `+n` badge. */
        folded?: number;
        /** The Incident/Case this node represents, when the source could resolve one — lights the
         *  detail dialog's "Open record" pivot (ui-design-review R8). Structurally = `ElementObjectRef`. */
        objectRef?: { id: string; type: 'INCIDENT' | 'CASE' };
        /** Link Analysis: the Entity Type of an Investigation's typed entity node, whose `kind` is then that type (not
         *  'entity'). Marks the node as an Entity for every "is this an entity" check; absent on every other node. */
        entityType?: string;
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
        /**
         * LA-08: the values of a node mapping's `attributes` columns (RAW, keyed by column), merged across the
         * mappings that named this entity. Present only when a mapping asked for attributes.
         */
        attrs?: Record<string, string | null>;
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
