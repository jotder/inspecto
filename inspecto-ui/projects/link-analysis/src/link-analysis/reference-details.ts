import type { LaDataset } from '@inspecto/link-analysis/la-host';
import type { KeyedDataset } from './node-risk';

/**
 * **A Reference's details in Link Analysis** (ONBOARDING-FIRST-RUN-1 C6a step 4; A-5, every edition): the details an Add
 * data Reference holds about an entity, shown in the node detail dialog, and the graph filtered by them. Keyed by the
 * Reference's identifying Entity Type: a node of that type looks its key up in the Reference's identifying column through
 * `POST /inv/reference-rows`, which masks like every Link Analysis read. Nothing joins or builds data (DT-6). Pure.
 */

/** A Reference a node of some Entity Type can look its details up in. */
export interface NodeReference extends KeyedDataset {
    /** The Reference's name. */
    name: string;
    /** The Entity Type its identifying column holds. */
    entityType: string;
    /** Its detail columns (every column but the identifying one), as declared. */
    columns: string[];
}

/** Every Reference (an Add data Dataset of kind `reference` with an identifying column and its Entity Type). */
export function nodeReferences(datasets: readonly LaDataset[]): NodeReference[] {
    const out: NodeReference[] = [];
    for (const d of datasets) {
        if (d.kind !== 'reference' || !d.identifyingColumn || !d.primaryEntityType) continue;
        out.push({
            dataset: d.id,
            keyCol: d.identifyingColumn,
            name: d.name || d.id,
            entityType: d.primaryEntityType,
            columns: (d.columns ?? []).map((c) => c.name).filter((c) => c !== d.identifyingColumn),
        });
    }
    return out;
}

/** The References that describe entities of `entityType` (none when the type is unknown). */
export function referencesFor(refs: readonly NodeReference[], entityType: string | null | undefined): NodeReference[] {
    return entityType ? refs.filter((r) => r.entityType === entityType) : [];
}

/** A detail row as label / value lines, the key column left out (the node already names it). */
export function detailLines(
    row: Record<string, unknown> | null | undefined,
    keyCol: string,
): { name: string; value: string }[] {
    if (!row) return [];
    return Object.entries(row)
        .filter(([k]) => k !== keyCol)
        .map(([name, v]) => ({ name, value: v === null || v === undefined || v === '' ? '—' : String(v) }));
}

/** Whether a detail value contains the text, ignoring case and surrounding blanks. */
export function detailContains(value: unknown, text: string): boolean {
    const t = text.trim().toLowerCase();
    if (!t) return true;
    return value !== null && value !== undefined && String(value).toLowerCase().includes(t);
}

/** One drawn node a Reference can describe: its canvas id and the key it is looked up by. */
export interface DrawnNode {
    id: string;
    key: string;
}

/**
 * The drawn nodes a details filter hides: those whose row's `column` does not contain `text`, and - with `hideMissing` -
 * those the Reference has no row for (they cannot be said to match; keys the server could not resolve have none). A
 * cut read passes `hideMissing` false: a missing row may lie beyond the cut.
 */
export function nodesNotMatching(
    nodes: readonly DrawnNode[],
    byKey: ReadonlyMap<string, Record<string, unknown>>,
    column: string,
    text: string,
    hideMissing = true,
): string[] {
    return nodes
        .filter((n) => {
            const row = byKey.get(n.key);
            return row ? !detailContains(row[column], text) : hideMissing;
        })
        .map((n) => n.id);
}
