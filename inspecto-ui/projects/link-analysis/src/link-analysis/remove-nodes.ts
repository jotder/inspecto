/**
 * Remove (Delete): the picked nodes leave the canvas, and so does every neighbour the removal leaves with no link at all
 * (an orphan), whichever way its links ran. Unlike Exclude it keeps nothing out: a later Expand that reaches a removed
 * node draws it again. Only the picked nodes' own neighbours are checked - the removal does not cascade further.
 */
export function withOrphans(ids: readonly string[], links: readonly { source: string; target: string }[]): string[] {
    const gone = new Set(ids);
    const touched = new Set<string>();
    /** Links each node keeps to something that stays. */
    const kept = new Map<string, number>();
    for (const l of links) {
        const s = gone.has(l.source);
        const t = gone.has(l.target);
        if (s && !t) touched.add(l.target);
        if (t && !s) touched.add(l.source);
        if (!s && !t) {
            kept.set(l.source, (kept.get(l.source) ?? 0) + 1);
            kept.set(l.target, (kept.get(l.target) ?? 0) + 1);
        }
    }
    return [...gone, ...[...touched].filter((id) => !kept.get(id))];
}
