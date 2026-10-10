import type { IndexSource } from '@inspecto/link-analysis/api/inv.service';

/**
 * DR-U4 (`LA-DEMO-INDEX-1`) - where an index-capable answer came from, in words. Framework-free.
 *
 * The server says it on `source` (`POST /inv/projection/neighbors`, `POST /inv/traversal/recursive-paths`) and on the
 * sealed expand read (`read.index` / `read.fallback`). The reasons are the CLOSED list of `IndexedRead.Reason`; each is
 * phrased to follow "because ". An unknown code is shown verbatim rather than hidden - a new server reason must never
 * read as no reason at all.
 */
export const FALLBACK_REASON_TEXT: Record<string, string> = {
    index_disabled: 'the link index is switched off for this Space',
    no_index: 'this Dataset has no link index yet',
    mapping_not_indexed: 'the link index was built over different columns',
    column_not_indexed: 'the link index was not built with a column this read needs',
    time_zone_not_servable: 'the link index cannot answer in this time zone',
    filter_not_indexed: 'the link index cannot apply this filter',
    index_stale_refused: 'the link index is out of date for this Dataset and may hold rows that were removed',
    depth_over_index_cap: 'the walk is deeper than the link index serves',
    frontier_over_index_cap: 'too many entities were expanded at once for the link index',
    index_read_failed: 'the link index could not be read',
    rung_not_indexable: 'this kind of expand is not answered by the link index',
};

/** `Answered from the Dataset because ‹reason›` - the flat read, with the closed reason in plain words. */
export function datasetSourceNote(reason: string, details?: string): string {
    return `Answered from the Dataset because ${FALLBACK_REASON_TEXT[reason] ?? reason}${details ? ` (${details})` : ''}.`;
}

/** `Answered from the link index v3` (+ a stale warning), or the Dataset sentence; null when the server said nothing. */
export function indexSourceNote(source: IndexSource | undefined | null): string | null {
    if (!source) return null;
    if (source.kind === 'dataset') return datasetSourceNote(source.reason, source.details);
    const stale = source.stale ? ` (stale: ${source.staleReason ?? 'rows were added to the Dataset after the build'})` : '';
    return `Answered from the link index v${source.version}${stale}.`;
}

/** The same sentence for a sealed expand's `read`: `index` = the index answered, `fallback` = the Dataset did. */
export function readSourceNote(
    read:
        | {
              index?: { version: number; stale: boolean };
              fallback?: { reason: string; details?: string };
          }
        | undefined
        | null,
): string | null {
    if (read?.index) return indexSourceNote({ kind: 'index', ...read.index });
    if (read?.fallback) return datasetSourceNote(read.fallback.reason, read.fallback.details);
    return null;
}

/** A log/Dossier step line's suffix for a sealed expand: `read from link index v3` (null when the Dataset answered). */
export function stepReadSuffix(read: { index?: { version: number } } | undefined | null): string | null {
    return read?.index ? `read from link index v${read.index.version}` : null;
}

/**
 * One source for a call that fans out into several reads (a multi-mapping expand): any Dataset answer wins, because
 * "from the link index" would overstate a mixed read; all-index reports the OLDEST version read.
 */
export function combineSources(sources: (IndexSource | undefined)[]): IndexSource | undefined {
    const known = sources.filter((s): s is IndexSource => !!s);
    if (!known.length) return undefined;
    const flat = known.find((s) => s.kind === 'dataset');
    if (flat) return flat;
    const idx = known.filter((s): s is Extract<IndexSource, { kind: 'index' }> => s.kind === 'index');
    return idx.reduce((a, b) => (b.version < a.version ? b : a));
}
