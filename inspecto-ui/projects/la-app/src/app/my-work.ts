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

/** An imported Dataset as the home page reads it: its id, the Pipeline Add data wrote for it, and its last import. */
export interface ImportedDataset {
    id: string;
    pipeline: string;
    /** The entry's `lastImport` (LA-ONBOARD-RESIDUALS-1 item 7); absent on imports made before it was recorded. */
    last?: LastImport;
}

/** What the last commit or Add a file did, as the server keeps it on the Dataset's registry entry. */
export interface LastImport {
    at: number;
    replaced: boolean;
    rowsBefore: number;
    rowsAfter: number;
    /** The ledger's `consignment_id` of the run that made it. */
    batch: string;
}

function lastImport(v: unknown): LastImport | undefined {
    if (!v || typeof v !== 'object') return undefined;
    const r = v as Record<string, unknown>;
    const at = Date.parse(String(r['at'] ?? ''));
    const before = Number(r['rowsBefore']);
    const after = Number(r['rowsAfter']);
    const batch = typeof r['batch'] === 'string' ? r['batch'] : '';
    if (!batch || !Number.isFinite(at) || !Number.isFinite(before) || !Number.isFinite(after)) return undefined;
    return { at, replaced: r['replaced'] === true, rowsBefore: before, rowsAfter: after, batch };
}

/** The Datasets Add data created (`origin: import` with an `importPipeline`), from the registry's `dataset` entries. */
export function importedDatasets(defs: readonly ComponentDef[]): ImportedDataset[] {
    return defs
        .filter((d) => d.content?.['origin'] === 'import' && typeof d.content?.['importPipeline'] === 'string')
        .map((d) => {
            const last = lastImport(d.content['lastImport']);
            return { id: d.name, pipeline: d.content['importPipeline'] as string, ...(last ? { last } : {}) };
        })
        .sort((a, b) => a.id.localeCompare(b.id));
}

const num = (v: string | undefined): number => {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
};
const rows = (n: number): string => `${n.toLocaleString('en')} ${n === 1 ? 'row' : 'rows'}`;
const signed = (n: number): string => `${n < 0 ? '−' : '+'}${Math.abs(n).toLocaleString('en')}`;

/**
 * One Dataset's last import in words. With the entry's `lastImport` (item 7) a re-import says whether it replaced the
 * rows or added to them, and the net change: "transfers: replaced 3 Oct 2026, 2,104 → 2,300 rows (+196)", "transfers:
 * appended 3 Oct 2026, +196 rows (now 2,300)". The run ledger (`GET /runs/{pipeline}/batches`, oldest first) gives the
 * rows set aside, and wins when its last run is not `lastImport.batch` (Run now records no `lastImport`; matched by
 * id, since the ledger's times carry no zone) or the import has no record: then a re-import says "rows loaded", never a net change. Null when the Dataset has no successful run yet.
 */
export function lastImportLine(
    datasetId: string,
    batches: readonly AuditRow[],
    last?: LastImport,
): { text: string; at: number } | null {
    const ok = batches.filter((b) => (b['status'] ?? '').toUpperCase() === 'SUCCESS');
    const latest = ok[ok.length - 1];
    if (!latest) return null;
    const when = Date.parse(latest['end_time'] || latest['start_time'] || '');
    const setAside = num(latest['rejected_rows']);
    const aside = setAside ? `, ${rows(setAside)} set aside` : '';
    if (last && ok.length > 1 && latest['consignment_id'] === last.batch) {
        const net = last.rowsAfter - last.rowsBefore;
        const text = last.replaced
            ? `${datasetId}: replaced ${dayLabel(last.at)}, ${last.rowsBefore.toLocaleString('en')} → ${rows(last.rowsAfter)} (${signed(net)})${aside}`
            : `${datasetId}: appended ${dayLabel(last.at)}, ${signed(net)} ${Math.abs(net) === 1 ? 'row' : 'rows'} (now ${last.rowsAfter.toLocaleString('en')})${aside}`;
        return { text, at: last.at };
    }
    const day = Number.isFinite(when) ? ` ${dayLabel(when)}` : '';
    const loaded = num(latest['total_output_rows']);
    const text =
        ok.length === 1
            ? `${datasetId}: imported${day}, +${rows(loaded)}${aside}`
            : `${datasetId}: re-imported${day}, ${rows(loaded)} loaded${aside}`;
    return { text, at: Number.isFinite(when) ? when : 0 };
}
