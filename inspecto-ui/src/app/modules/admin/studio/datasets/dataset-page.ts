import {
    Arrivals,
    ImportDataset,
    ImportKind,
    count,
    editionLock,
} from '@inspecto/link-analysis/add-data/add-data.model';
import { Dataset } from './dataset-types';

/**
 * The console **Dataset page** (ONBOARDING-FIRST-RUN-1 B4, slice C5): what an imported Dataset holds, and **What this
 * Dataset can feed** (H9, console UC-8). Pure: the Datasets pane and its spec share these words and rules.
 */

/** An Add data Dataset (`origin: import`) as the shared import card reads it; null for any other Dataset. */
export function importDatasetOf(d: Dataset): ImportDataset | null {
    const x = d.extra ?? {};
    if (x['origin'] !== 'import') return null;
    const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined);
    return {
        id: d.id,
        kind: ((d.kind as string) === 'reference' ? 'reference' : 'stream') as ImportKind,
        description: d.description,
        primaryEntityType: d.primaryEntityType ?? null,
        importPipeline: str(x['importPipeline']) ?? d.id,
        importSource: str(x['importSource']),
    };
}

const ID_LIKE = /(^id$|_id$|^key$|_key$|_no$|_number$)/i;

/** The column a Dataset's rows are dated by: the link's time, the import's event date, else the first date column. */
export function timeColumn(d: Dataset): string | null {
    const named = d.defaultLinkMapping?.primaryTime ?? (d.extra?.['eventDate'] as string | undefined);
    if (named) return named;
    const cols = d.columns ?? [];
    return (
        cols.find((c) => c.role === 'temporal')?.name ??
        cols.find((c) => ['date', 'timestamp'].includes(c.type as string))?.name ??
        null
    );
}

/** The column that holds an amount: the link's measure, else the first Measure, else the first number that is no id. */
export function amountColumn(d: Dataset): string | null {
    if (d.defaultLinkMapping?.primaryMeasure) return d.defaultLinkMapping.primaryMeasure;
    const cols = d.columns ?? [];
    return (
        cols.find((c) => c.role === 'measure')?.name ??
        cols.find((c) => c.type === 'number' && c.role !== 'entity' && !ID_LIKE.test(c.name))?.name ??
        null
    );
}

/** The `POST /bi/templates/trend-monitor/apply` column map for a Dataset: its date and its amount. */
export function starterColumns(d: Dataset): Record<string, string> | null {
    const time = timeColumn(d);
    const amount = amountColumn(d);
    return time && amount ? { event_date: time, amount } : null;
}

/** The starter template the offer applies, and the board it makes for a Dataset (`<id>_trend_board`). */
export const STARTER_TEMPLATE = 'trend-monitor';
export const starterBoard = (datasetId: string): string => `${datasetId}_trend_board`;

/** One offer of *What this Dataset can feed*. */
export interface FeedOffer {
    id: 'network' | 'network-widget' | 'starter-dashboard' | 'enrich';
    /** The other Dataset the offer is about (the Reference of `enrich`). */
    target?: string;
    /** The action, as its button says it. */
    action: string;
    /** Why it is offered, from the Dataset's columns. */
    because: string;
    /** Why it cannot be done here (edition, rights, install); null = it can. */
    lock: string | null;
}

/** What decides the locks: the host's `/bootstrap` facts and the person's right to author. */
export interface FeedContext {
    flags: Readonly<Record<string, unknown>>;
    reasons: Readonly<Record<string, string>>;
    /** People sign in here, so rights are checked. */
    signIn: boolean;
    canAuthor: boolean;
    /** `/bootstrap` edition: a feature it does not name is locked on Basic (`editionLock`). */
    edition?: string;
}

export const NO_LINK_ANALYSIS = 'Link Analysis is not part of this installation.';
export const LA_WIDGETS_FALLBACK = 'Link Analysis views on dashboards are available in Standard and above.';
export const NO_WRITE_ROOT = 'Dashboards cannot be saved on this server: it has no place to write them.';
export const NO_AUTHOR_RIGHT = 'Making a dashboard needs the right to author in this Space - ask an administrator.';

/**
 * What a Dataset can feed, from its columns (UC-8). An offer the edition or the person cannot fulfil stays in the list,
 * locked with its reason (H4), so the way up stays visible. Empty when nothing fits.
 */
export function feedOffers(d: Dataset, ctx: FeedContext, others: readonly Dataset[] = []): FeedOffer[] {
    const out: FeedOffer[] = [];
    const link = d.defaultLinkMapping;
    if (link) {
        const time = link.primaryTime ? `, ${link.primaryTime}` : '';
        const rt = d.extra?.['recordType'];
        const what = typeof rt === 'string' && rt ? `${rt.replace(/[_-]+/g, ' ')} records` : 'links between entities';
        const because = `Looks like ${what}: ${link.from} → ${link.to}${time}.`;
        const la = ctx.flags['geoLink'] === true ? null : NO_LINK_ANALYSIS;
        out.push({ id: 'network', action: 'Explore as a network in Link Analysis', because, lock: la });
        out.push({
            id: 'network-widget',
            action: 'Show the network on a dashboard',
            because,
            lock: la ?? (ctx.flags['laWidgets'] === true ? null : (ctx.reasons['laWidgets'] ?? LA_WIDGETS_FALLBACK)),
        });
    }
    const cols = starterColumns(d);
    if (cols) {
        out.push({
            id: 'starter-dashboard',
            action: 'Make a starter dashboard',
            because: `Has a date (${cols['event_date']}) and an amount (${cols['amount']}): records and amount per month.`,
            lock:
                ctx.flags['authoring'] === false
                    ? NO_WRITE_ROOT
                    : ctx.signIn && !ctx.canAuthor
                      ? NO_AUTHOR_RIGHT
                      : null,
        });
    }
    for (const l of lookupsOf(d, others)) {
        const name = l.reference.id;
        out.push({
            id: 'enrich',
            target: name,
            action: `Add the details from ${name} to each row`,
            because: `${name} describes the entities in ${l.column}: its details can be looked up for every row.`,
            lock:
                editionLock(
                    'pipelineTransform',
                    ctx.flags as Readonly<Record<string, boolean>>,
                    ctx.reasons,
                    ctx.edition ?? '',
                    ENRICH_FALLBACK,
                ) ?? (ctx.signIn && !ctx.canAuthor ? NO_ENRICH_RIGHT : null),
        });
    }
    return out;
}

/** Why enrichment is locked when `/bootstrap` does not say (DT-6), with the way Basic does it instead. */
export const ENRICH_FALLBACK =
    "Adding a lookup's columns to each row (enrichment) is available in Standard and above. Here, join the two in a Studio query.";

export const NO_ENRICH_RIGHT =
    "Adding a lookup's columns to each row needs the right to author in this Space - ask an administrator.";

/** A Reference (details about entities): the column that identifies each one, and the Entity Type it holds. */
export function referenceOf(d: Dataset): { identifying: string; entityType: string | null } | null {
    if ((d.kind as string) !== 'reference') return null;
    const id = d.extra?.['identifyingColumn'];
    return typeof id === 'string' && id ? { identifying: id, entityType: d.primaryEntityType ?? null } : null;
}

/**
 * The References that describe a Dataset's entities (B5): a column of `d` holding the Entity Type a Reference's
 * identifying column holds, or else named as that column. One per Reference, the first column that fits.
 */
export function lookupsOf(d: Dataset, others: readonly Dataset[]): { reference: Dataset; column: string }[] {
    if (referenceOf(d)) return [];
    const out: { reference: Dataset; column: string }[] = [];
    for (const r of others) {
        const ref = referenceOf(r);
        if (!ref || r.id === d.id) continue;
        // columns hold an Entity Type through their classification (`MSISDN`), not its id (`msisdn`): compare those
        const cls = r.columns.find((c) => c.name === ref.identifying)?.classification?.toUpperCase();
        const col =
            (cls && d.columns.find((c) => c.classification?.toUpperCase() === cls)) ||
            d.columns.find((c) => c.name.toLowerCase() === ref.identifying.toLowerCase());
        if (col) out.push({ reference: r, column: col.name });
    }
    return out;
}

/** The `POST /bi/query` body that reads a Reference's rows (masked like every BI read), filtered by one detail. */
export function detailsQuery(
    d: Dataset,
    filter: { column: string; value: string } | null,
    limit = DETAILS_LIMIT,
): { dataset: string; groupBy: string[]; limit: number; filters?: { field: string; op: string; value: string }[] } {
    const body = { dataset: d.id, groupBy: d.columns.map((c) => c.name), limit };
    const v = filter?.value.trim();
    return filter && v ? { ...body, filters: [{ field: filter.column, op: 'like', value: `%${v}%` }] } : body;
}

/** How many of a Reference's rows the details read shows. */
export const DETAILS_LIMIT = 200;

/** The `/bi/query` answer of {@link factsQuery}: row count and, with a time column, its first and last value. */
export interface DatasetFacts {
    rows: number | null;
    from: string | null;
    to: string | null;
}

/** The `POST /bi/query` body that counts a Dataset's rows and spans its time column (when it has one). */
export function factsQuery(d: Dataset): { dataset: string; measures: { agg: string; field?: string }[] } {
    const time = timeColumn(d);
    return {
        dataset: d.id,
        measures: [
            { agg: 'count' },
            ...(time
                ? [
                      { agg: 'min', field: time },
                      { agg: 'max', field: time },
                  ]
                : []),
        ],
    };
}

/** The facts from a `/bi/query` answer: the measures in the order {@link factsQuery} asked them. */
export function factsOf(row: Record<string, unknown> | undefined, columns: readonly string[]): DatasetFacts {
    const at = (i: number): unknown => (row && columns[i] !== undefined ? row[columns[i]] : null);
    const n = Number(at(0));
    const day = (v: unknown): string | null =>
        v === null || v === undefined ? null : String(v).replace('T', ' ').slice(0, 10);
    return { rows: Number.isFinite(n) && at(0) !== null ? n : null, from: day(at(1)), to: day(at(2)) };
}

/** *Time span* in words. */
export function spanWords(f: DatasetFacts | null, hasTime: boolean): string {
    if (!hasTime) return 'No date column';
    if (!f?.from || !f.to) return 'Not known yet';
    return f.from === f.to ? f.from : `${f.from} – ${f.to}`;
}

/** *Rows* in words. */
export function rowsWords(f: DatasetFacts | null): string {
    return f?.rows === null || f?.rows === undefined ? 'Not counted' : count(f.rows);
}

/**
 * *Set aside* (DT-15): the rows the kept files' runs rejected, and the files refused whole, each with why. Empty list =
 * nothing set aside (or the arrivals are not read).
 */
export function setAsideLines(a: Arrivals | null): string[] {
    const list = a?.arrivals ?? [];
    const rejected = list
        .filter((x) => x.kept && x.status !== 'REFUSED')
        .reduce((s, x) => s + (x.rowsRejected ?? 0), 0);
    const out: string[] = [];
    if (rejected > 0)
        out.push(
            `${count(rejected)} ${rejected === 1 ? 'row was' : 'rows were'} set aside: they did not match the column types.`,
        );
    for (const x of list.filter((r) => r.status === 'REFUSED'))
        out.push(`${x.file} was refused: ${x.reason ?? 'its columns do not match'}.`);
    return out;
}
