/**
 * The author-owned `x-` annotation keys of a stored config document (MODULE-REORG-P4-2). A form that rebuilds a save
 * body from its modelled fields spreads these in on edit, so the annotation survives the save instead of vanishing
 * behind a 200; the server keeps `x-` keys and refuses any other unmodelled key.
 */
export function authorKeys(stored: object | null | undefined): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(stored ?? {})) if (k.startsWith('x-')) out[k] = v;
    return out;
}
