import type { AuditRow, ComponentDef } from '@inspecto/core/api';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { dayLabel } from '@inspecto/link-analysis/link-analysis/investigate-number';
import { LinkAnalysisView, caseListIds } from '@inspecto/link-analysis/link-analysis/link-analysis.service';

/**
 * LA-ONBOARD-1 phase 7 (UC-13, UC-14, A-1b) - the pure half of the home page's **My work**: the cases (saved views with
 * the Entity Lists they use), most recent first, and **What changed in your data** (each imported Dataset's last import).
 * No Angular, no HTTP.
 */

/** One case on the home page. */
export interface CaseRow {
    id: string;
    name: string;
    description: string;
    /** "Saved 22 Oct 2026", or '' when the server did not say when. */
    saved: string;
    /** The lists it uses, in words; '' = none. */
    lists: string;
    /** Sort key: the stored file's time, 0 when unknown. */
    at: number;
}

/** The cases, most recent first (a view the server cannot date sorts last, by name). */
export function caseRows(
    defs: readonly ComponentDef[],
    decode: (id: string, content: Record<string, unknown>) => LinkAnalysisView,
    lists: readonly EntityListSummary[],
): CaseRow[] {
    const title = (id: string) => lists.find((l) => l.id === id)?.title ?? id;
    return defs
        .map((d): CaseRow => {
            const v = decode(d.name, d.content ?? {});
            const at = d.modified ? Date.parse(d.modified) : NaN;
            return {
                id: v.id,
                name: v.name,
                description: v.description ?? '',
                saved: Number.isFinite(at) ? `Saved ${dayLabel(at)}` : '',
                lists: listsLine(v, title),
                at: Number.isFinite(at) ? at : 0,
            };
        })
        .sort((a, b) => b.at - a.at || a.name.localeCompare(b.name));
}

function listsLine(v: LinkAnalysisView, title: (id: string) => string): string {
    if (!caseListIds(v).length) return '';
    const parts = [
        ...(v.lists?.start ? [`started from ${title(v.lists.start)}`] : []),
        ...(v.lists?.exclusions?.length ? [`leaves out ${v.lists.exclusions.map(title).join(', ')}`] : []),
    ];
    return `Lists: ${parts.join('; ')}`;
}

/** An imported Dataset as the home page reads it: its id and the Pipeline Add data wrote for it. */
export interface ImportedDataset {
    id: string;
    pipeline: string;
}

/** The Datasets Add data created (`origin: import` with an `importPipeline`), from the registry's `dataset` entries. */
export function importedDatasets(defs: readonly ComponentDef[]): ImportedDataset[] {
    return defs
        .filter((d) => d.content?.['origin'] === 'import' && typeof d.content?.['importPipeline'] === 'string')
        .map((d) => ({ id: d.name, pipeline: d.content['importPipeline'] as string }))
        .sort((a, b) => a.id.localeCompare(b.id));
}

const num = (v: string | undefined): number => {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
};
const rows = (n: number): string => `${n.toLocaleString('en')} ${n === 1 ? 'row' : 'rows'}`;

/**
 * One Dataset's last import in words, from its Pipeline's run ledger (`GET /runs/{pipeline}/batches`, oldest first):
 * "transfers: imported 1 Oct 2026, +2,104 rows" the first time, "re-imported" after. The ledger records what each run
 * loaded, not whether a re-import replaced the rows or added to them, so a re-import says "rows loaded", never a net
 * change. Null when the Dataset has no successful run yet.
 */
export function lastImportLine(datasetId: string, batches: readonly AuditRow[]): { text: string; at: number } | null {
    const ok = batches.filter((b) => (b['status'] ?? '').toUpperCase() === 'SUCCESS');
    const last = ok[ok.length - 1];
    if (!last) return null;
    const when = Date.parse(last['end_time'] || last['start_time'] || '');
    const day = Number.isFinite(when) ? ` ${dayLabel(when)}` : '';
    const loaded = num(last['total_output_rows']);
    const setAside = num(last['rejected_rows']);
    const aside = setAside ? `, ${rows(setAside)} set aside` : '';
    const text =
        ok.length === 1
            ? `${datasetId}: imported${day}, +${rows(loaded)}${aside}`
            : `${datasetId}: re-imported${day}, ${rows(loaded)} loaded${aside}`;
    return { text, at: Number.isFinite(when) ? when : 0 };
}
