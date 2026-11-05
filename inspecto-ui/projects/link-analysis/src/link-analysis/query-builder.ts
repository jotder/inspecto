import type { PickerOption } from '@inspecto/core/components/option-picker.component';
import type { BuilderQuery, PresetList } from '@inspecto/link-analysis/api/inv.service';

/**
 * LA-QUERY-BUILDER-1 - the pure half of the query builder: the form's raw values to a {@link BuilderQuery} (blank fields left
 * out, so the server applies only what was asked), the pickers' options from the Dataset's role columns, and a saved preset's
 * id. No Angular, no HTTP. The server validates every key again; this only keeps the obvious mistakes off the wire.
 */

/** The form's values as typed (pen fields and pickers hold text). */
export interface BuilderForm {
    by: string | null;
    rank: string | null;
    sumColumn: string | null;
    from: string | null;
    to: string | null;
    kinds: string | null;
    timeColumn: string | null;
    after: string | null;
    before: string | null;
    measureColumn: string | null;
    measureMin: string | null;
    measureMax: string | null;
    min: string | null;
    top: string | null;
}

const blank = (v: string | null | undefined): boolean => v === null || v === undefined || v.trim() === '';

/** The query to send: only the fields that were set; numbers as numbers, kinds as a list. */
export function builderQuery(f: BuilderForm): BuilderQuery {
    const q: BuilderQuery = { by: f.by === 'to' ? 'to' : 'from' };
    if (f.rank === 'counterparts' || f.rank === 'sum') q.rank = f.rank;
    if (q.rank === 'sum' && !blank(f.sumColumn)) q.sumColumn = f.sumColumn!.trim();
    if (!blank(f.from)) q.from = f.from!.trim();
    if (!blank(f.to)) q.to = f.to!.trim();
    const kinds = (f.kinds ?? '')
        .split(',')
        .map((k) => k.trim())
        .filter((k) => k !== '');
    if (kinds.length) q.kinds = [...new Set(kinds)];
    if (!blank(f.after)) q.after = f.after!.trim();
    if (!blank(f.before)) q.before = f.before!.trim();
    if ((q.after || q.before) && !blank(f.timeColumn)) q.timeColumn = f.timeColumn!.trim();
    const range = !blank(f.measureMin) || !blank(f.measureMax);
    if (range && !blank(f.measureColumn)) q.measureColumn = f.measureColumn!.trim();
    if (!blank(f.measureMin)) q.measureMin = Number(f.measureMin);
    if (!blank(f.measureMax)) q.measureMax = Number(f.measureMax);
    if (!blank(f.min)) q.min = Number(f.min);
    if (!blank(f.top)) q.top = Number(f.top);
    return q;
}

/** A question in words, for the saved preset's description when none is typed. */
export function builderSentence(q: BuilderQuery): string {
    const end = q.by === 'to' ? 'receivers' : 'senders';
    const rank =
        q.rank === 'sum'
            ? `the largest total of ${q.sumColumn}`
            : q.rank === 'counterparts'
              ? 'the most counterparts'
              : 'the most links';
    const parts = [`The ${end} with ${rank}`];
    if (q.from) parts.push(`sent by ${q.from}`);
    if (q.to) parts.push(`received by ${q.to}`);
    if (q.kinds?.length) parts.push(`over ${q.kinds.join(', ')}`);
    if (q.after) parts.push(`on or after ${q.after}`);
    if (q.before) parts.push(`before ${q.before}`);
    if (q.measureColumn && q.measureMin !== undefined) parts.push(`${q.measureColumn} at least ${q.measureMin}`);
    if (q.measureColumn && q.measureMax !== undefined) parts.push(`${q.measureColumn} at most ${q.measureMax}`);
    return parts.join(' ') + '.';
}

export const BY_OPTIONS: PickerOption[] = [
    { value: 'from', label: 'Senders (From)' },
    { value: 'to', label: 'Receivers (To)' },
];

export const RANK_OPTIONS: PickerOption[] = [
    { value: 'links', label: 'Most links' },
    { value: 'counterparts', label: 'Most distinct counterparts' },
    { value: 'sum', label: 'Largest total of a Measure' },
];

/** The Measure pickers' options, with the unit when the Dataset states one. */
export function measureOptions(list: PresetList | null): PickerOption[] {
    return (list?.columns?.measure ?? []).map((m) => ({
        value: m.column,
        label: m.unit ? `${m.column} (${m.unit})` : m.column,
    }));
}

/** The Time picker's options (the primary Time first). */
export function timeOptions(list: PresetList | null): PickerOption[] {
    return (list?.columns?.time ?? []).map((t) => ({ value: t, label: t }));
}

/** A saved preset's id from its name: lower case, letters and digits joined by `-`; '' when nothing is left. */
export function savedPresetId(label: string): string {
    return label
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 60);
}
