import { DatasetFilesPage } from '@inspecto/link-analysis/api/inv.service';
import { indexStateWords, LinkIndexState } from './index-status';

/**
 * LA-DATA-TRUST-1 S2 - the framework-free half of the Data strip: what the one-line summary says, the tone of its
 * badge, and the per-day strip. 🔴 A gap in the data looks exactly like innocence, so the words here never claim
 * more than was measured: an unassessed read says "not assessed", an unknown index state says "unknown".
 */

export type DataTrustTone = 'success' | 'warning' | 'error' | 'neutral';

/** The facts the strip is built from; each is null when its read has not answered (or could not). */
export interface DataTrustFacts {
    /** `GET /datasets/{id}/freshness` `dataAsOf` (ISO instant) or null. */
    dataAsOf: string | null;
    /** From the coverage read: expected / covered / missing days; null when the read needs a window the Investigation lacks. */
    days: { expected: number; covered: number; missing: string[] } | null;
    /** From `GET /inv/index` for the Dataset, as {@link datasetIndexStatus} answers it (the ONE answer, phase 4). */
    index: { version: number; state: LinkIndexState } | null;
    /** The first Files page (its `summary` spans every File). */
    files: DatasetFilesPage | null;
    /** Days corrected after the analyst last looked (S5); omitted/empty = nothing to say. */
    correctedDays?: string[];
}

export interface DataTrustStrip {
    /** A plain sentence (or two): how current and complete the data is, then only what needs a look. */
    text: string;
    tone: DataTrustTone;
    /** The badge's words - the tone is never conveyed by colour alone. */
    label: string;
    /** The tooltip: the link index (version and state) and what could not be matched to it. Data details has the rest. */
    detail: string;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * `2026-09-05T10:00:00Z` -> `5 Sep 2026` (UTC, so the same instant reads the same for every analyst). An instant at exactly
 * UTC midnight is how the server states "covered through the end of a DATE" (the newest event day 2026-09-04 reads
 * `2026-09-05T00:00:00Z`), so it names the day BEFORE: showing 5 Sep would claim a day with no data (live drive 2026-10-14).
 */
export function formatDay(iso: string | null | undefined): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
    if (!m) return '';
    const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
    if (/T00:00:00(\.0+)?Z$/.test(iso as string)) d.setUTCDate(d.getUTCDate() - 1);
    return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/**
 * A day-bucket START (`2026-08-18T00:00:00Z` = the events of 18 Aug) -> `18 Aug 2026`, UTC, never shifted: a profile's
 * first/last seen and an Entity Type's earliest/latest event name the day itself. Use {@link formatDay} only for an
 * end-exclusive "covered through" instant. Blank when unreadable.
 */
export function formatDayStart(iso: string | null | undefined): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
    if (!m) return '';
    const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
    return `${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]} ${d.getUTCFullYear()}`;
}

/**
 * The window the Files themselves span: [first day 00:00Z, day after the last 00:00Z). Lets the strip judge days for an
 * Investigation that has no window of its own (the coverage read refuses an unbounded one). Null when no day is known.
 */
export function daysWindow(days: { day: string }[] | null | undefined): { from: string; to: string } | null {
    const ds = (days ?? [])
        .map((d) => d.day)
        .filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d))
        .sort();
    if (!ds.length) return null;
    const last = new Date(`${ds[ds.length - 1]}T00:00:00Z`);
    last.setUTCDate(last.getUTCDate() + 1);
    return { from: `${ds[0]}T00:00:00Z`, to: last.toISOString().replace('.000Z', 'Z') };
}

const plural = (n: number, one: string, many = one + 's') => `${n} ${n === 1 ? one : many}`;

/**
 * Splits the coverage read's missing days into real HOLES (a missing day inside the span of the delivered Files: the feed skipped it or sent nothing) and days NOT
 * DELIVERED YET (before the first File or after the newest one: nothing is late, the window just reaches past the data).
 * With no delivered day known nothing can be judged, so every missing day stays a hole - never "not delivered yet" by default.
 */
export function splitMissing(
    missing: string[],
    fileDays: { day: string; files: number }[] | null | undefined,
): { holes: string[]; notDelivered: string[] } {
    const ds = (fileDays ?? [])
        .filter((d) => d.files > 0 && /^\d{4}-\d{2}-\d{2}$/.test(d.day))
        .map((d) => d.day)
        .sort();
    if (!ds.length) return { holes: [...missing], notDelivered: [] };
    const [first, last] = [ds[0], ds[ds.length - 1]];
    const holes = missing.filter((d) => d >= first && d <= last);
    return { holes, notDelivered: missing.filter((d) => d < first || d > last) };
}

/**
 * The strip as a plain sentence (LA-FULL-CYCLE-TEST-1 presentability): "Data current to 4 Sep 2026 (3 of 3 days)." and,
 * only when something needs a look, a second sentence naming it ("1 missing day; the link index is out of date."). The
 * link index's version and an index that is merely up to date are no news to the reader: they go to {@link
 * DataTrustStrip.detail} (the tooltip) and Data details. The badge says what the tone means in two words, never a bare
 * "OK". LA-EXEC-DEMO-POLISH-1 item 19: the green badge is "All clear", not "Data OK" (a status code to a reader): it
 * says only what green means here, that nothing in the strip needs a look, the plain partner of "Check data". Not "Data
 * current": the sentence beside it already opens "Data current to …", and green does not promise the window is complete
 * (days not delivered yet stay green). The judgement is unchanged: the same tones from the same facts; the index state is
 * {@link datasetIndexStatus}'s.
 */
export function buildStrip(f: DataTrustFacts): DataTrustStrip {
    const notes: string[] = [];
    let tone: DataTrustTone = 'success';
    const worse = (t: DataTrustTone) => {
        if (t === 'error' || (t === 'warning' && tone !== 'error') || (t === 'neutral' && tone === 'success')) tone = t;
    };

    const asOf = formatDay(f.dataAsOf);
    let head = asOf ? `Data current to ${asOf}` : 'Data date not known';
    if (!asOf) worse('neutral');

    if (f.days) {
        head += ` (${f.days.covered} of ${plural(f.days.expected, 'day')})`;
        const { holes, notDelivered } = splitMissing(f.days.missing, f.files?.assessed ? f.files.days : null);
        if (holes.length) {
            notes.push(plural(holes.length, 'missing day'));
            worse('warning');
        }
        if (notDelivered.length) notes.push(`${plural(notDelivered.length, 'day')} not delivered yet`);
    } else head += ' (days not assessed)';

    const s = f.files?.assessed ? f.files.summary : undefined;
    if (s && s.problem > 0) {
        notes.push(`${plural(s.problem, 'File')} quarantined, partial or failed`);
        worse('warning');
    }

    const corrected = new Set(f.correctedDays ?? []).size;
    if (corrected > 0) {
        notes.push(`${plural(corrected, 'day')} corrected since you last looked`);
        worse('warning');
    }

    // LA-FULL-CYCLE-TEST-1 phase 4: the Glossary's word (link index) and the same state words as the Data page
    let detail = 'No link index for this Dataset.';
    if (f.index) {
        const state = indexStateWords(f.index.state);
        detail = `Link index version ${f.index.version}${state ? `, ${state}` : ''}.`;
        if (f.index.state === 'out_of_date') {
            notes.push(`the link index is ${indexStateWords('out_of_date')}`);
            worse('warning');
        }
        if (s) {
            if (s.waiting > 0) {
                notes.push(`${plural(s.waiting, 'file')} waiting for the link index`);
                worse('warning');
            } else if (s.unknown > 0) detail += ` ${plural(s.unknown, 'file')} could not be matched to it.`;
        }
    }

    const note = notes.join('; ');
    return {
        text: `${head}.` + (note ? ` ${note.charAt(0).toUpperCase()}${note.slice(1)}.` : ''),
        tone,
        label:
            tone === 'success'
                ? 'All clear'
                : tone === 'warning'
                  ? 'Check data'
                  : tone === 'error'
                    ? 'Data problem'
                    : 'Not known',
        detail,
    };
}

/** One cell of the per-day strip. `missing` = no rows and no File between delivered days; `pending` = no File yet, outside the delivered span; `empty` = Files arrived but held no rows. */
export interface DayCell {
    date: string;
    rows: number;
    state: 'ok' | 'missing' | 'empty' | 'pending';
}

/**
 * The per-day strip from the coverage read's `perDay` (every expected day, `rows: 0` for both a gap and an empty day) and
 * the Files' event days: a zero-row day that some File names is `empty` (the feed delivered, the day holds nothing);
 * a zero-row day no File names is `missing`.
 */
export function dayCells(
    perDay: { date: string; rows: number }[],
    fileDays: { day: string; files: number }[],
): DayCell[] {
    const delivered = new Set(fileDays.filter((d) => d.files > 0).map((d) => d.day));
    const pending = new Set(
        splitMissing(
            perDay.filter((d) => d.rows === 0 && !delivered.has(d.date)).map((d) => d.date),
            fileDays,
        ).notDelivered,
    );
    return perDay.map((d) => ({
        date: d.date,
        rows: d.rows,
        state: d.rows > 0 ? 'ok' : delivered.has(d.date) ? 'empty' : pending.has(d.date) ? 'pending' : 'missing',
    }));
}

/** The badge a File's outcome reads as. */
export function outcomeTone(outcome: string): string {
    return outcome === 'SUCCESS' ? 'SUCCESS' : outcome === 'PARTIAL' || outcome === 'QUARANTINED' ? 'WARNING' : 'ERROR';
}
