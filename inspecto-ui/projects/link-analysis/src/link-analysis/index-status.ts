import type { LinkIndexState } from '@inspecto/core/add-data/add-data-link-analysis';
import type {
    LinkIndexBuildMode,
    LinkIndexBuildRequest,
    LinkIndexList,
    LinkIndexSummary,
} from '@inspecto/link-analysis/api/graph-runs.service';

/**
 * LA-FULL-CYCLE-TEST-1 phase 4 (finding 21) - the ONE answer to "is this Dataset's link index up to date?", shared by the
 * Data page (the Graph fact and *Data status*), the Investigation's data strip and its Rebuild action. Framework-free.
 *
 * The answer is the server's: `GET /inv/index` judges every listed index with `IndexStaleness` (the same judgement a read
 * makes before the Expand serves from the index) and states why in plain words (`reason`). Nothing here re-judges it; this
 * only words it and names the build that makes it current. Before this, the Data page read the outcome its import RECORDED
 * ("graph up to date") while the header read `GET /inv/index` ("index v1 (stale)"): two answers to one question.
 */
// The state union is core's: Add data (core) reads it through `ADD_DATA_LINK_ANALYSIS` (BASIC-ELT-PROFILE-1 UI-0).
export type { LinkIndexState };

export interface LinkIndexStatus {
    state: LinkIndexState;
    /** The Dataset's newest index version (the one an out-of-date answer is about). */
    version: number;
    /** Why it is out of date, in the server's plain words; '' when it is not. */
    why: string;
    /** The build that makes it current - the index's own mapping in the mode the server advises; null when current. */
    rebuild: LinkIndexBuildRequest | null;
}

/**
 * The status of `dataset`'s link index, or null when nothing can be said: `GET /inv/index` has not answered (or cannot, as
 * on Basic), or the Dataset has no index. One out-of-date mapping makes the Dataset's index out of date. An index whose
 * input files cannot be compared (`fingerprint: unknown`, a view-backed Dataset) is never called out of date by the server,
 * so it is `unknown` here, never `current`.
 */
export function datasetIndexStatus(list: LinkIndexList | null | undefined, dataset: string): LinkIndexStatus | null {
    const mine = (list?.indexes ?? []).filter((i) => i.dataset === dataset);
    if (!mine.length) return null;
    const stale = mine.find((i) => i.stale);
    if (stale)
        return {
            state: 'out_of_date',
            version: stale.version,
            why: stale.reason ?? '',
            rebuild: rebuildRequest(stale),
        };
    const newest = mine.reduce((a, b) => (b.version > a.version ? b : a));
    const known = mine.every((i) => i.fingerprint !== 'unknown');
    return { state: known ? 'current' : 'unknown', version: newest.version, why: '', rebuild: null };
}

/** The status in a few words, after "link index": `up to date`, `out of date`; '' when it cannot be said. */
export function indexStateWords(state: LinkIndexState): string {
    return state === 'current' ? 'up to date' : state === 'out_of_date' ? 'out of date' : '';
}

/** The full sentence for a tooltip or a screen reader: "The link index is out of date: <why>. Rebuild it to read it again." */
export function indexStatusSentence(s: LinkIndexStatus): string {
    if (s.state === 'current') return 'The link index is up to date.';
    if (s.state === 'unknown') return 'The link index is built; its files cannot be compared with the Dataset now.';
    const why = s.why ? `: ${s.why}` : '';
    return `The link index is out of date${why}. Until it is rebuilt, the graph is read from the Dataset, which is slower.`;
}

/** An out-of-date index rebuilt with its OWN mapping, in the mode the server advises (`plan.recommended`; `full` when none). */
export function rebuildRequest(ix: LinkIndexSummary): LinkIndexBuildRequest {
    const m = ix.mapping;
    const advised = ix.plan?.recommended;
    const mode: LinkIndexBuildMode = advised && advised !== 'none' ? advised : 'full';
    return {
        dataset: ix.dataset,
        sourceCol: m.sourceCol,
        targetCol: m.targetCol,
        ...(m.kindCol ? { kindCol: m.kindCol } : {}),
        ...(m.timeCol ? { timeCol: m.timeCol } : {}),
        ...(m.timeColZone ? { timeColZone: m.timeColZone } : {}),
        ...(m.weightCol ? { weightCol: m.weightCol } : {}),
        ...(m.attrCols?.length ? { attrCols: m.attrCols } : {}),
        mode,
    };
}
