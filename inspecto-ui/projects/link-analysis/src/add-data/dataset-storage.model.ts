import type { PickerOption } from '@inspecto/core/components/option-picker.component';
import { formatSize } from './add-data.model';

/**
 * An import Dataset's storage (`LA-DATASET-COMPACT-1`, `GET /datasets/{id}/storage`): its Parquet files, the last
 * compaction and the retention policy. Pure: the wording the card, the Data status line and their tests share.
 */
export interface Retention {
    days?: number;
    arrivals?: number;
}

export interface DatasetStorageState {
    dataset: string;
    files: number;
    bytes: number;
    /** More files than this and an import compacts on its own. */
    threshold: number;
    compactedAt: string | null;
    /** Arrivals held by the merged file. */
    compactedArrivals: number;
    /** Arrivals a retention policy removed. */
    removedArrivals: number;
    retention: Retention | null;
    /** Standard and up: a retention policy can be set here. */
    retentionAvailable: boolean;
    /** Only in the answer to *Compact now*. */
    compaction?: { result: string; filesBefore: number; filesAfter: number; rowsRemoved: number; message: string };
}

export type RetentionKind = 'all' | 'days' | 'arrivals';

export const RETENTION_OPTIONS: PickerOption[] = [
    { value: 'all', label: 'Every arrival', hint: 'Nothing is removed; the files are only merged.' },
    {
        value: 'days',
        label: 'The last N days',
        hint: 'By the date of each row (its When column), else by when the file came.',
    },
    { value: 'arrivals', label: 'The last N arrivals', hint: 'The oldest files are removed first.' },
];

function plural(n: number, one: string, many: string): string {
    return `${n} ${n === 1 ? one : many}`;
}

/** `keeps the last 90 days` / `keeps the last 12 arrivals` / `keeps every arrival`. */
export function retentionWords(r: Retention | null): string {
    if (r?.days) return `keeps the last ${plural(r.days, 'day', 'days')}`;
    if (r?.arrivals) return `keeps the last ${plural(r.arrivals, 'arrival', 'arrivals')}`;
    return 'keeps every arrival';
}

/** `3 files · 1.2 MB · compacted 2026-11-05 10:02 (21 arrivals)`; the last part only once compacted. */
export function storageWords(s: DatasetStorageState): string {
    const parts = [`${plural(s.files, 'file', 'files')} · ${formatSize(s.bytes)}`];
    if (s.compactedAt) {
        const at = s.compactedAt.replace('T', ' ').slice(0, 16);
        parts.push(
            s.compactedArrivals
                ? `compacted ${at} (${plural(s.compactedArrivals, 'arrival', 'arrivals')})`
                : `compacted ${at}`,
        );
    }
    return parts.join(' · ');
}

/**
 * The ONE *Data status* line about storage (D-6, D-8): how many files and, on Standard, what the policy keeps; null
 * while nothing was read.
 */
export function storageStatusLine(s: DatasetStorageState | null): string | null {
    if (!s) return null;
    const line = `Storage: ${storageWords(s)}`;
    return s.retentionAvailable ? `${line} · ${retentionWords(s.retention)}` : line;
}

export function retentionKind(r: Retention | null): RetentionKind {
    return r?.days ? 'days' : r?.arrivals ? 'arrivals' : 'all';
}
