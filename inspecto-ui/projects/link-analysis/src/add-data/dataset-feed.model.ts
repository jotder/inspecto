import type { PickerOption } from '@inspecto/core/components/option-picker.component';

/**
 * How files reach an import Dataset (`LA-ARRIVAL-FEEDS-1`, `GET /datasets/{id}/feed`): its inbox on the server, the files
 * waiting there, and its feed - `uploads` (a person adds files; nothing runs on its own), `folder` (the inbox is watched)
 * or `sftp` (a Connection is read). Pure: the wording the Dataset card and its tests share.
 */
/** `uploads`, `folder` or `sftp`; a Pipeline edited elsewhere may name another connector, said as it is. */
export type FeedKind = 'uploads' | 'folder' | 'sftp' | (string & {});

export interface Feed {
    kind: FeedKind;
    every?: string;
    connection?: string;
    readyMarker?: string;
}

export interface WaitingFile {
    name: string;
    size: number;
    modified: string;
    /** The import took a file of this name before, so Run now skips it: rename it to import it. */
    alreadyImported?: boolean;
}

/** The `la.index.build` Job the server proposes with *Keep this up to date*; saved through `POST /jobs`. */
export type IndexJob = Record<string, string> & { name: string; type: string };

export interface DatasetFeedState {
    dataset: string;
    /** The inbox's absolute path on the server: where another system writes files for this Dataset. */
    inbox: string;
    waiting: number;
    /** How many of the waiting files Run now would skip (taken before under the same name). */
    alreadyImported?: number;
    files: WaitingFile[];
    truncated: boolean;
    /** This installation runs feeds on their own (Standard and above); Basic imports waiting files with Run now. */
    automatic: boolean;
    feed: Feed;
    /** BASIC-ONBOARD-1 P2: *Watch this folder* is on - the inbox is looked at every 30 seconds. */
    watch?: boolean;
    /** *Watch this folder* is offered: not a Reference, and no schedule keeps the Dataset up to date. */
    watchable?: boolean;
    /** Why the poll's last import of this inbox failed; absent once one succeeds. */
    watchError?: string;
    /** Only in the answer to *Keep this up to date*: the Job that keeps the graph up to date, or null without a link. */
    indexJob?: IndexJob | null;
}

/** The body of `PUT /datasets/{id}/feed`. */
export interface KeepUpToDate {
    kind: 'folder' | 'sftp';
    every: string;
    connection?: string;
    readyMarker?: string;
}

export const EVERY_OPTIONS: PickerOption[] = [
    { value: '5m', label: 'Every 5 minutes' },
    { value: '15m', label: 'Every 15 minutes' },
    { value: '1h', label: 'Every hour' },
    { value: '1d', label: 'Every day' },
];

export const FEED_KIND_OPTIONS: PickerOption[] = [
    {
        value: 'folder',
        label: 'Files written to this inbox',
        hint: 'Another system copies its files into the inbox shown above.',
    },
    { value: 'sftp', label: 'Files on an SFTP server', hint: 'Read through a Connection of this Space.' },
];

/** `15m` → `every 15 minutes`, `1h` → `every hour`, `1d` → `every day`; the value itself when it is another one. */
export function everyWords(every: string | undefined): string {
    const m = /^(\d+)([smhd])$/.exec(every ?? '');
    if (!m) return every ? `every ${every}` : '';
    const n = Number(m[1]);
    const unit = { s: 'second', m: 'minute', h: 'hour', d: 'day' }[m[2] as 's' | 'm' | 'h' | 'd'];
    return n === 1 ? `every ${unit}` : `every ${n} ${unit}s`;
}

/** `3 files waiting`; files Run now would skip are said with what to do about them. */
export function waitingWords(n: number, alreadyImported = 0): string {
    if (!n) return 'No files waiting';
    const files = n === 1 ? '1 file waiting' : `${n} files waiting`;
    if (!alreadyImported) return files;
    const same =
        alreadyImported === 1
            ? '1 has the name of a file already imported'
            : `${alreadyImported} have the names of files already imported`;
    return `${files} (${same}: rename to import again)`;
}

/** One waiting file: its name, and why Run now would skip it. */
export function waitingFileLine(f: WaitingFile): string {
    return f.alreadyImported ? `${f.name} - already imported under this name` : f.name;
}

/** Where the files come from, in words: `a watched folder`, `SFTP (Connection partner_box)`. */
export function feedWords(f: Feed): string {
    if (f.kind === 'folder') return 'the inbox is watched';
    if (f.kind === 'sftp') return f.connection ? `SFTP, Connection ${f.connection}` : 'SFTP';
    if (f.kind === 'uploads') return 'uploads';
    return f.connection ? `${f.kind}, Connection ${f.connection}` : f.kind;
}

/** The feed as the arrivals answer carries it (one read for every *Data status*): no inbox path. */
export type FeedSummary = Feed & {
    waiting: number | null;
    alreadyImported?: number;
    watch?: boolean;
    watchError?: string;
};

/** *Watch this folder* in words: one interval, fixed (BASIC-ONBOARD-1 P2). */
export const WATCH_WORDS = 'Inbox watched, checked every 30 seconds';

/**
 * The ONE *Data status* line about the feed (D-1): on a kept-up-to-date Dataset how and how often, else only the files
 * waiting in the inbox; null when there is nothing to say (uploads, no file waiting).
 */
export function feedSummaryLine(f: FeedSummary | null): string | null {
    if (!f) return null;
    const waiting = f.waiting ? waitingWords(f.waiting, f.alreadyImported) : '';
    if (f.kind === 'uploads' && f.watch) {
        const parts = [WATCH_WORDS];
        if (waiting) parts.push(waiting);
        if (f.watchError) parts.push(`the last import failed: ${f.watchError}`);
        return parts.join(' · ');
    }
    if (f.kind === 'uploads') return waiting ? `Inbox: ${waiting}` : null;
    const how = `Kept up to date: ${feedWords(f)}${f.every ? `, ${everyWords(f.every)}` : ''}`;
    return waiting ? `${how} · ${waiting}` : how;
}

/** {@link feedSummaryLine} over the feed route's own answer. */
export function feedStatusLine(s: DatasetFeedState | null): string | null {
    return s
        ? feedSummaryLine({
              ...s.feed,
              waiting: s.waiting,
              alreadyImported: s.alreadyImported,
              watch: s.watch,
              watchError: s.watchError,
          })
        : null;
}
