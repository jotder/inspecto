/**
 * Remove (Delete): the picked nodes leave the canvas, and so does every neighbour the removal leaves with no link at all
 * (an orphan), whichever way its links ran. Unlike Exclude it keeps nothing out: a later Expand that reaches a removed
 * node draws it again. Only the picked nodes' own neighbours are checked - the removal does not cascade further.
 */
export function withOrphans(ids: readonly string[], links: readonly { source: string; target: string }[]): string[] {
    const gone = new Set(ids);
    const touched = new Set<string>();
    for (const l of links) {
        if (gone.has(l.source) && !gone.has(l.target)) touched.add(l.target);
        if (gone.has(l.target) && !gone.has(l.source)) touched.add(l.source);
    }
    const orphans = [...touched].filter((id) => !links.some((l) => (l.source === id && !gone.has(l.target)) || (l.target === id && !gone.has(l.source))));
    return [...gone, ...orphans];
}
