import { FeedSummary, feedSummaryLine } from './dataset-feed.model';
import { DatasetStorageState, storageStatusLine } from './dataset-storage.model';
import { formatNumber } from '@inspecto/core/viz/number-format';
import { type ColumnRole, ROLE_LABELS, hintedRole, recordPhrase } from '@inspecto/link-analysis/api/column-roles';
import type { RecordTypeGuide } from '@inspecto/link-analysis/api/entity-type-options';

/**
 * **Add data** - the contract of `POST /onboard/files` and its commit (LA-ONBOARD-1 OB-2, phase 2), and the pure rules the
 * one Add data screen applies to it (phase 4, OF-1, OF-2). No Angular here: the screen and the Dataset card call these.
 *
 * Server: `inspecto/.../OnboardRoutes.java`, `OnboardDraft.java`, `EntityShapes.java`, `OnboardImport.java`; the route doc
 * is `docs/okf/product/module/processor/api-onboarding-authoring.md` "Add data".
 */

/** Every proposed choice in a draft (OF-2): high when every sampled value agrees and the sample is at least 50 values. */
export interface Choice<T> {
    value: T;
    evidence: string;
    confidence: 'high' | 'low';
}

export type ImportKind = 'stream' | 'reference';

/** The column types the commit accepts (`OnboardImport.TYPES`). */
export type ColumnType = 'VARCHAR' | 'BIGINT' | 'DOUBLE' | 'DATE' | 'TIMESTAMP' | 'BOOLEAN';

export interface DraftColumn {
    /** The draft name - every commit choice names a column by it. */
    name: string;
    header: string | null;
    type: Choice<string> & { format?: string | null };
    sample: unknown[];
}

export interface DraftEntityTypeChoice extends Choice<string | null> {
    classification?: string | null;
}

/** The link a draft proposes: its ends, its kind, and the primary Time and Measure (LA-COLUMN-ROLES-1 P1). */
export interface DraftLink {
    from: Choice<string | null>;
    to: Choice<string | null>;
    kind: Choice<string | null>;
    primaryTime: Choice<string | null>;
    primaryMeasure: Choice<string | null>;
    details: Choice<string[]>;
}

/** The link choices beside its two ends, in screen order. */
export const LINK_PRIMARIES = ['kind', 'primaryTime', 'primaryMeasure'] as const;
export type LinkPrimary = (typeof LINK_PRIMARIES)[number];

/** The role a link choice's column takes (the kind is a Category). */
export const PRIMARY_ROLE: Record<LinkPrimary, ColumnRole> = {
    kind: 'dimension',
    primaryTime: 'temporal',
    primaryMeasure: 'measure',
};

export interface DraftLimits {
    maxBytes: number;
    maxRows: number;
    maxImports: number;
    imports: number;
    inlineMaxBytes: number;
    excel: boolean;
}

/** The answer of `POST /onboard/files`. */
export interface OnboardDraft {
    draftId: string;
    kind: ImportKind;
    file: { name: string; bytes: number; rows: number };
    /** `type` always; `delimiter`, `header` and `quote` for CSV / TSV only. */
    format: {
        type: Choice<string>;
        delimiter?: Choice<string>;
        header?: Choice<boolean>;
        quote?: Choice<string>;
        [extra: string]: Choice<unknown> | undefined;
    };
    columns: DraftColumn[];
    rows: Record<string, unknown>[];
    entityTypes: {
        /** Per column: what it holds, its proposed role, and for a text column its distinct sampled values. */
        columns: {
            column: string;
            entityType: DraftEntityTypeChoice;
            /** `base`: the role proposed without the draft's record type, when that record type changed it (ENTITY-TYPE-RECORD-ROLES-1). */
            role?: Choice<ColumnRole> & { base?: ColumnRole | null; baseConfidence?: 'high' | 'low' | null };
            distinct?: number;
        }[];
        primary: Choice<string | null>;
        link?: DraftLink;
        identifying?: Choice<string | null> & { entityType?: string | null };
        /** What one row is, a record type of the Space's packs (ENTITY-TYPE-CATALOG-1 P2); absent when the Space has none. */
        recordType?: Choice<string | null>;
    };
    limits: DraftLimits;
    /** The follow-on steps the installed applications registered (BASIC-ONBOARD-1 P1); empty: the import ends at Parquet. */
    followOns?: ImportFollowOnInfo[];
    /** A draft copied from Postgres (BASIC-ONBOARD-1 P3): where from. The password is its `${ENV:…}` reference, never the value. */
    snapshot?: PostgresSnapshotSource;
}

/** One application's follow-on step, as the draft lists it: its summary key, what it does, the entry keys it needs. */
export interface ImportFollowOnInfo {
    key: string;
    does: string;
    needs: string[];
}

/**
 * The follow-on clause of the review sentence (", and builds its link index"): each registered follow-on whose needs the
 * review meets. `hasLink` = the Dataset will carry a `defaultLinkMapping` (events with both link ends chosen).
 */
export function followOnClause(followOns: ImportFollowOnInfo[] | undefined, hasLink: boolean): string {
    const met = (followOns ?? []).filter((f) => f.needs.every((n) => n === 'defaultLinkMapping' && hasLink));
    return met.map((f) => `, and ${f.does}`).join('');
}

/** Where to connect: the password is a `${ENV:NAME}` reference to the server's environment, never the password itself. */
export interface PostgresConnection {
    host: string;
    port: number;
    database: string;
    user: string;
    password: string;
}

/** What the connection test answers: the login, and the tables it may read (at most 500). */
export interface PostgresTest {
    connected: boolean;
    user: string;
    readOnly: boolean;
    tables: { schema: string; table: string; view: boolean }[];
    truncated: boolean;
}

/** A table, or one read-only `SELECT`. */
export type PostgresPick = { schema: string; table: string } | { query: string };

export type PostgresSnapshotSource = { source: 'postgres'; connection: PostgresConnection } & Partial<{
    schema: string;
    table: string;
    query: string;
}>;

/** The import Dataset is a Postgres snapshot (its `importSource`). */
export function isSnapshot(d: { importSource?: string }): boolean {
    return d.importSource === 'postgres';
}

/** The `${ENV:INSPECTO_PG_…}` form the server accepts for the password: only a variable an administrator named for it. */
export const PASSWORD_REFERENCE = /^\$\{ENV:INSPECTO_PG_[A-Z0-9_]{1,100}\}$/;

/** The format choices a re-draft states (`?delimiter=&header=&quote=`). */
export interface FormatOverrides {
    delimiter?: string;
    header?: boolean;
    quote?: string;
}

export interface CommitColumn {
    name: string;
    type?: string;
    entityType?: string | null;
    /** Absent = the server derives it (an Entity when it holds an Entity Type, else the draft's proposal). */
    role?: ColumnRole;
    rename?: string;
    drop?: boolean;
}

export interface CommitLink {
    from: string;
    to: string;
    kind?: string;
    primaryTime?: string;
    primaryMeasure?: string;
}

/** The body of `POST /onboard/files/{draftId}/commit`; every key optional (absent = the draft's proposal). */
export interface CommitBody {
    name: string;
    description?: string;
    columns: CommitColumn[];
    primaryEntityType?: string | null;
    link?: CommitLink | null;
    identifyingColumn?: string | null;
    recordType?: string | null;
}

/** `index` of a run summary: the link index built inside the user's run (DT-16). */
export interface ImportIndexOutcome {
    result: 'BUILT' | 'UP_TO_DATE' | 'REFUSED' | 'FAILED' | 'SKIPPED' | 'NOT_AVAILABLE' | 'DEFERRED' | string;
    message?: string | null;
    edges?: number | null;
    nodes?: number | null;
}

export interface ImportExpectation {
    name: string;
    status: string | null;
    violations?: number | null;
    message?: string | null;
}

/**
 * One arrival of an import Dataset (`GET /datasets/{id}/arrivals`, LA-ARRIVAL-LEDGER-1): a file its Pipeline took. `sender`
 * `system` = a file a foreign process put in the inbox; `kept` false = a later Replace everything set its rows aside.
 */
export interface Arrival {
    file: string;
    sender: 'upload' | 'system';
    received: string | null;
    status: string | null;
    rowsRead: number | null;
    rowsRejected: number | null;
    mode: 'replace' | 'append' | 'period' | null;
    sha256: string | null;
    /** The link index outcome of the run that took this file (BUILT, UP_TO_DATE, DEFERRED, FAILED, …); null = not recorded. */
    index: string | null;
    /** How that build ran: `append` (the new file only), `full`, `compact`, `none`; null = not recorded. */
    indexMode?: string | null;
    consignment: string;
    kept: boolean;
    coversFrom: string | null;
    coversTo: string | null;
    /** LA-ARRIVAL-MODES-1: columns the file carried that the Dataset does not read (accepted, not kept). */
    newColumns?: string[] | null;
    /** A `REFUSED` arrival: why, and where the file was set aside (relative to the Dataset's folder). */
    reason?: string | null;
    setAside?: string | null;
}

export interface Arrivals {
    dataset: string;
    eventDateColumn: string | null;
    arrivals: Arrival[];
    truncated: boolean;
    reason: string | null;
    /** LA-ARRIVAL-FEEDS-1: how files come and how many wait (never the inbox path); absent on a Dataset no import made. */
    feed?: FeedSummary | null;
    /** LA-DATASET-COMPACT-1: the Dataset's files, the last compaction and what it keeps. */
    storage?: DatasetStorageState | null;
}

/** What an added file does (`POST /datasets/{id}/files?mode=`): replace everything, add rows, or replace its days. */
export type AddFileMode = 'replace' | 'append' | 'period';

/** The run summary the commit, Add a file and Run now answer (200), or a followed run's `summary`. */
export interface ImportSummary {
    dataset: string;
    pipeline: string;
    kind: ImportKind;
    file?: string;
    rowsIn: number;
    rowsRejected: number;
    rejectedDetail?: string;
    quarantined: { file: string; reason: string }[];
    rowsBefore: number;
    rowsAfter: number;
    rowsChanged: number;
    replaced: boolean;
    index?: ImportIndexOutcome;
    expectations?: ImportExpectation[];
    /** LA-ARRIVAL-MODES-1, *Replace the period it covers*: the days the file covered and the older rows it removed. */
    period?: { days: number; from: string | null; to: string | null; rowsReplaced: number; filesRewritten: number };
    /** Columns the file carried that the Dataset does not read: accepted, not kept. */
    newColumns?: string[];
    /** Waiting files refused by the columns rule and set aside (`setAside` is relative to the Dataset's folder). */
    refused?: { file: string; reason: string; setAside: string }[];
}

/** A followed (202) run: `GET /onboard/runs/{runId}`. */
export interface ImportRun {
    runId: string;
    dataset: string;
    action: string;
    state: 'running' | 'succeeded' | 'failed';
    summary?: ImportSummary;
    message?: string;
}

/** `GET /settings/data` (OB-7). */
export interface DataSettings {
    selfImport: boolean | null;
    selfImportDefault: boolean;
    selfImportInForce: boolean;
}

/** The registry entry of an imported Dataset, as far as the card reads it. */
export interface ImportDataset {
    id: string;
    kind: ImportKind;
    description?: string;
    primaryEntityType?: string | null;
    importPipeline?: string;
    /** `postgres` when Add data copies it from a Postgres table (BASIC-ONBOARD-1 P3); absent for files. */
    importSource?: string;
}

// ── Words ────────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The file kinds the route reads (DT-8); Excel only when the bundle carries the DuckDB `excel` extension. */
export const READ_FORMATS = 'CSV or TSV, Parquet, JSON or NDJSON';
/** The `accept` list of the file chooser (the server still decides). */
export const ACCEPTED_EXTENSIONS = '.csv,.tsv,.tab,.txt,.parquet,.json,.ndjson,.jsonl,.xlsx';

export const TYPE_LABELS: Record<ColumnType, string> = {
    VARCHAR: 'Text',
    BIGINT: 'Whole number',
    DOUBLE: 'Decimal number',
    DATE: 'Date',
    TIMESTAMP: 'Date and time',
    BOOLEAN: 'Yes / no',
};

/** A delimiter as a person names it. */
export function delimiterLabel(d: string | null | undefined): string {
    switch (d) {
        case ',':
            return 'Comma';
        case ';':
            return 'Semicolon';
        case '\t':
            return 'Tab';
        case '|':
            return 'Pipe ( | )';
        case ' ':
            return 'Space';
        default:
            return d ? `'${d}'` : '-';
    }
}

export function quoteLabel(q: string | null | undefined): string {
    if (q === '"') return 'Double quote ( " )';
    if (q === "'") return "Single quote ( ' )";
    return q ? `'${q}'` : 'No quoting';
}

/** Whole bytes as a person reads them: 500 MB, 1.5 GB. */
export function formatSize(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) return '-';
    const units = ['bytes', 'KB', 'MB', 'GB', 'TB'];
    let v = bytes;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return i === 0 ? `${v} bytes` : `${Number.isInteger(v) ? v : v.toFixed(1)} ${units[i]}`;
}

/** A count with thousands separators, through the one number formatter (pinned locale, never the browser's). */
export function count(n: number | null | undefined): string {
    return typeof n === 'number' && Number.isFinite(n) ? formatNumber(n, { decimals: 0 }) : '-';
}

function plural(n: number, one: string, many: string): string {
    return `${count(n)} ${n === 1 ? one : many}`;
}

/** The limits as one line, before or after an upload (`limits` of a draft, or the Space's `import.*` limits). */
export function limitsLine(l: Pick<DraftLimits, 'maxBytes' | 'maxRows' | 'maxImports'> & { imports?: number }): string {
    const used = typeof l.imports === 'number' ? ` (${count(l.imports)} so far)` : '';
    return `Up to ${formatSize(l.maxBytes)} and ${count(l.maxRows)} rows per file; ${count(l.maxImports)} imports per Space${used}.`;
}

/** What the chooser says about Excel: read when the bundle can, otherwise "save as CSV". Null = not known yet. */
export function excelLine(excel: boolean | null): string {
    if (excel === true) return 'Excel workbooks (.xlsx) are read too: the first sheet, with a header line.';
    if (excel === false) return 'Excel workbooks are not read by this installation: save the sheet as CSV first.';
    return 'Excel workbooks (.xlsx) are read when this installation includes Excel support; otherwise save the sheet as CSV.';
}

/**
 * The Dataset name the server would make from a file name (`OnboardImport.datasetId`): lower-case letters, digits and `_`,
 * starting with a letter, at most 63 characters. Pre-fills the name; the person may change it.
 */
export function suggestDatasetName(fileName: string): string {
    let id = fileName
        .replace(/\.[A-Za-z0-9]{1,8}$/, '')
        .toLowerCase()
        .replace(/[^a-z0-9_]+/g, '_')
        .replace(/^_+|_+$/g, '');
    if (!id || !/^[a-z]/.test(id)) id = 'data_' + id;
    return id.slice(0, 63).replace(/_+$/, '');
}

/** The server's Dataset name rule (`OnboardImport.ID`). */
export const DATASET_NAME = /^[a-z][a-z0-9_]{0,62}$/;
/** The server's column name rule for a rename (`OnboardImport.COLUMN`: letters, digits and `_`). */
export const COLUMN_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** Why the browser can already tell a file will not be read, or '' (the server still decides everything else). */
export function fileProblem(file: { name: string; size: number } | null, maxBytes: number | null): string {
    if (!file) return 'Choose a file first.';
    if (file.size === 0) return 'That file is empty.';
    if (/\.xls$/i.test(file.name))
        return 'An old Excel workbook (.xls) is not read: save the sheet as CSV and add that.';
    if (/\.zip$|\.gz$|\.7z$|\.rar$/i.test(file.name))
        return 'A compressed file is not read: unpack it and add the file inside.';
    if (maxBytes && file.size > maxBytes)
        return `That file is ${formatSize(file.size)}, over this Space's limit of ${formatSize(maxBytes)}. Split it into smaller files.`;
    return '';
}

// ── The confidence rule on screen (OF-2) ─────────────────────────────────────────────────────────────────────────────

/** One low-confidence choice that needs a click before Create: its key, and the words a person reads. */
export interface PendingChoice {
    key: string;
    label: string;
}

/** The keys of the choices on screen, so a confirmation names the same thing in the template and here. */
export const choiceKey = {
    format: (k: string) => `format.${k}`,
    type: (column: string) => `type.${column}`,
    entityType: (column: string) => `entity.${column}`,
    link: (role: string) => `link.${role}`,
    role: (column: string) => `role.${column}`,
    primary: 'primary',
    identifying: 'identifying',
};

/**
 * Every low-confidence choice of a draft, in screen order. `withEntityTypes` false (the console's optional step left
 * closed) leaves out the Entity Type step: those proposals are then not applied unless high (see {@link buildCommit}).
 */
export function lowChoices(
    d: OnboardDraft,
    withEntityTypes: boolean,
    dropped: ReadonlySet<string> = new Set(),
): PendingChoice[] {
    const out: PendingChoice[] = [];
    const low = (c: Choice<unknown> | undefined) => !!c && c.confidence === 'low';
    for (const k of ['type', 'delimiter', 'header', 'quote'])
        if (low(d.format[k])) out.push({ key: choiceKey.format(k), label: FORMAT_LABELS[k] ?? k });
    for (const c of d.columns)
        if (!dropped.has(c.name) && low(c.type)) out.push({ key: choiceKey.type(c.name), label: `Type of ${c.name}` });
    if (!withEntityTypes) return out;
    for (const c of d.entityTypes.columns)
        if (!dropped.has(c.column) && low(c.entityType) && c.entityType.value)
            out.push({ key: choiceKey.entityType(c.column), label: `What ${c.column} holds` });
    for (const c of d.entityTypes.columns)
        if (!dropped.has(c.column) && !c.entityType.value && c.role && low(c.role))
            out.push({ key: choiceKey.role(c.column), label: `The role of ${c.column}` });
    if (low(d.entityTypes.primary) && d.entityTypes.primary.value)
        out.push({ key: choiceKey.primary, label: 'The main kind of thing' });
    const l = d.entityTypes.link;
    if (l && l.from.value && (low(l.from) || low(l.to)))
        out.push({ key: choiceKey.link('from'), label: 'Which way the link goes' });
    for (const role of LINK_PRIMARIES)
        if (l && l[role].value && low(l[role]))
            out.push({ key: choiceKey.link(role), label: `The link's ${PRIMARY_WORDS[role]}` });
    const id = d.entityTypes.identifying;
    if (id && id.value && low(id))
        out.push({ key: choiceKey.identifying, label: 'The column that identifies each entity' });
    return out;
}

/** A link choice in the words a person reads. */
export const PRIMARY_WORDS: Record<LinkPrimary, string> = {
    kind: 'kind',
    primaryTime: 'time',
    primaryMeasure: 'measure',
};

const FORMAT_LABELS: Record<string, string> = {
    type: 'The file type',
    delimiter: 'The separator',
    header: 'The header line',
    quote: 'The quote character',
};

// ── The commit ───────────────────────────────────────────────────────────────────────────────────────────────────────

/** What the screen holds for one column. */
export interface ColumnState {
    name: string;
    rename: string;
    type: string;
    drop: boolean;
    /** '' = nothing (a detail). */
    entityType: string;
    /** The role when it holds no Entity Type (LA-COLUMN-ROLES-1 P1): Time, Measure, Category or Detail. */
    role: ColumnRole;
}

/** What the screen holds for the link (stream) or the identifying column (reference); '' = none. */
export interface RolesState {
    primary: string;
    from: string;
    to: string;
    kind: string;
    primaryTime: string;
    primaryMeasure: string;
    identifying: string;
    /** What one row is (ENTITY-TYPE-CATALOG-1 P2); '' = not said, absent = not asked. */
    recordType?: string;
}

/**
 * The commit body from what the screen holds. Columns are named by their DRAFT name; a type is sent only when changed (the
 * draft's date format then stays), an Entity Type always (so an unconfirmed low guess in the console's closed optional step
 * is sent as none rather than applied silently). A link needs both ends, else `link: null` (no graph from this Dataset).
 */
export function buildCommit(
    d: OnboardDraft,
    name: string,
    description: string,
    columns: readonly ColumnState[],
    roles: RolesState,
    opts: { withEntityTypes: boolean },
): CommitBody {
    const proposedEntity = new Map(d.entityTypes.columns.map((c) => [c.column, c.entityType]));
    const proposedRole = new Map(d.entityTypes.columns.map((c) => [c.column, c.role]));
    const draftType = new Map(d.columns.map((c) => [c.name, c.type.value]));
    const out: CommitBody = { name: name.trim(), columns: [] };
    if (description.trim()) out.description = description.trim();
    const kept = new Set(columns.filter((c) => !c.drop).map((c) => c.name));
    for (const c of columns) {
        if (c.drop) {
            out.columns.push({ name: c.name, drop: true });
            continue;
        }
        const col: CommitColumn = { name: c.name };
        if (c.type && c.type !== draftType.get(c.name)) col.type = c.type;
        if (c.rename.trim() && c.rename.trim() !== c.name) col.rename = c.rename.trim();
        let entity = c.entityType || null;
        if (!opts.withEntityTypes) {
            const p = proposedEntity.get(c.name);
            entity = p && p.value && p.confidence === 'high' ? p.value : null;
            // a guessed role is not applied either: the column stays a Detail
            const r = proposedRole.get(c.name);
            if (!entity && r && r.confidence === 'low') col.role = 'detail';
        } else if (!entity) col.role = columnRole(c);
        col.entityType = entity;
        out.columns.push(col);
    }
    if (!opts.withEntityTypes) {
        // The step was not opened: high proposals stand (the server applies them), low ones are not applied.
        const p = d.entityTypes.primary;
        out.primaryEntityType = p.value && p.confidence === 'high' ? p.value : null;
        if (d.entityTypes.recordType) out.recordType = high(d.entityTypes.recordType) || null;
        if (d.kind === 'stream') {
            const l = d.entityTypes.link;
            const sure = l && l.from.value && l.to.value && l.from.confidence === 'high' && l.to.confidence === 'high';
            out.link = sure
                ? stripEmpty({
                      from: l.from.value!,
                      to: l.to.value!,
                      kind: high(l.kind),
                      primaryTime: high(l.primaryTime),
                      primaryMeasure: high(l.primaryMeasure),
                  })
                : null;
        } else {
            const id = d.entityTypes.identifying;
            out.identifyingColumn = id?.value && id.confidence === 'high' ? id.value : null;
        }
        return out;
    }
    const keep = (v: string) => (v && kept.has(v) ? v : '');
    // a primary names a column of its role (LA-COLUMN-ROLES-1 P1): one changed to another role since is no primary
    const byName = new Map(columns.map((c) => [c.name, c]));
    const keepAs = (v: string, role: ColumnRole) => {
        const c = byName.get(keep(v));
        return c && columnRole(c) === role ? c.name : '';
    };
    const heldTypes = new Set(columns.filter((c) => !c.drop && c.entityType).map((c) => c.entityType));
    out.primaryEntityType = roles.primary && heldTypes.has(roles.primary) ? roles.primary : null;
    if (roles.recordType !== undefined) out.recordType = roles.recordType || null;
    if (d.kind === 'stream') {
        const from = keep(roles.from);
        const to = keep(roles.to);
        out.link =
            from && to && from !== to
                ? stripEmpty({
                      from,
                      to,
                      kind: keep(roles.kind),
                      primaryTime: keepAs(roles.primaryTime, 'temporal'),
                      primaryMeasure: keepAs(roles.primaryMeasure, 'measure'),
                  })
                : null;
    } else {
        out.identifyingColumn = keep(roles.identifying) || null;
    }
    return out;
}

function high(c: Choice<string | null>): string {
    return c.value && c.confidence === 'high' ? c.value : '';
}

function stripEmpty(l: { from: string; to: string } & Record<LinkPrimary, string>): CommitLink {
    const out: CommitLink = { from: l.from, to: l.to };
    for (const k of LINK_PRIMARIES) if (l[k]) out[k] = l[k];
    return out;
}

/** The column types a Measure may have: numbers (the server refuses a Measure on any other). */
export const MEASURE_TYPES: ReadonlySet<string> = new Set(['DOUBLE', 'BIGINT']);

/**
 * The role a column takes: an Entity when it holds an Entity Type; else the one picked - never Entity without a type, and
 * a Measure only while the column holds numbers (one whose type was changed to text is a Detail again).
 */
export function columnRole(c: ColumnState): ColumnRole {
    if (c.entityType) return 'entity';
    if (c.role === 'entity' || (c.role === 'measure' && !MEASURE_TYPES.has(c.type))) return 'detail';
    return c.role;
}

/** The screen's starting state: every proposal pre-applied (OF-2). */
export function initialColumns(d: OnboardDraft): ColumnState[] {
    const entity = new Map(d.entityTypes.columns.map((c) => [c.column, c.entityType.value ?? '']));
    const role = new Map(d.entityTypes.columns.map((c) => [c.column, c.role?.value ?? 'detail']));
    return d.columns.map((c) => ({
        name: c.name,
        rename: c.name,
        type: c.type.value,
        drop: false,
        entityType: entity.get(c.name) ?? '',
        role: role.get(c.name) ?? 'detail',
    }));
}

/**
 * ENTITY-TYPE-RECORD-ROLES-1: a column's role proposal under `recordType` (an id; '' = none). Under the record type the
 * draft proposed, its own proposal stands (the server applied that record type's usual roles). Under another, the role
 * proposed without a record type (`base`) - changed to the new record type's usual role where its hints name the column
 * and the column's type can hold it. A column holding an Entity Type, or the link's kind, time or measure, keeps its role.
 */
export function roleUnder(
    d: OnboardDraft,
    column: string,
    recordType: string,
    recordTypes: readonly RecordTypeGuide[],
): Choice<ColumnRole> | undefined {
    const drafted = d.entityTypes.columns.find((c) => c.column === column)?.role;
    if (!drafted || recordType === (d.entityTypes.recordType?.value ?? '')) return drafted;
    const l = d.entityTypes.link;
    if (drafted.value === 'entity' || (l && LINK_PRIMARIES.some((p) => l[p].value === column))) return drafted;
    const base: Choice<ColumnRole> = drafted.base
        ? {
              value: drafted.base,
              evidence: `${column} reads as a ${ROLE_LABELS[drafted.base]} by its values`,
              confidence: drafted.baseConfidence ?? 'high',
          }
        : drafted;
    const rt = recordTypes.find((r) => r.id === recordType);
    const hinted = hintedRole(column, d.columns.find((c) => c.name === column)?.type.value, rt?.roleHints);
    if (!rt || !hinted || hinted.role === base.value) return base;
    return {
        value: hinted.role,
        evidence: `${recordPhrase(rt.label)} usually has a ${ROLE_LABELS[hinted.role]} named like '${hinted.hint}'`,
        confidence: 'high',
    };
}

export function initialRoles(d: OnboardDraft): RolesState {
    const l = d.entityTypes.link;
    return {
        primary: d.entityTypes.primary.value ?? '',
        from: l?.from.value ?? '',
        to: l?.to.value ?? '',
        kind: l?.kind.value ?? '',
        primaryTime: l?.primaryTime.value ?? '',
        primaryMeasure: l?.primaryMeasure.value ?? '',
        identifying: d.entityTypes.identifying?.value ?? '',
        recordType: d.entityTypes.recordType?.value ?? '',
    };
}

// ── The result in plain words (H3) ───────────────────────────────────────────────────────────────────────────────────

export interface SummaryLine {
    tone: 'success' | 'info' | 'warning' | 'error';
    text: string;
}

/** A run summary as a few sentences a person reads: rows in, rows set aside and why, the index, the Expectations. */
export function summaryLines(s: ImportSummary): SummaryLine[] {
    const out: SummaryLine[] = [];
    const kept = Math.max(0, s.rowsIn - s.rowsRejected);
    const from = s.file ? ` from ${s.file}` : '';
    // LA-ARRIVAL-MODES-1: waiting files refused by the columns rule were set aside before the run
    const refused: SummaryLine[] = (s.refused ?? []).map((r) => ({
        tone: 'warning',
        text: `${r.file} was set aside in ${r.setAside}: ${r.reason}`,
    }));
    if (s.rowsIn === 0 && !s.file && (s.quarantined ?? []).length === 0) {
        if (refused.length) return [...refused, ...indexAndExpectations(s)];
        // Run now with nothing waiting: say so, not "0 rows were read".
        out.push({
            tone: 'info',
            text: `No new file was waiting; the Dataset holds ${plural(s.rowsAfter, 'row', 'rows')}.`,
        });
        return [...out, ...indexAndExpectations(s)];
    }
    out.push({
        tone: 'success',
        text: `${plural(s.rowsIn, 'row was', 'rows were')} read${from}; ${plural(kept, 'row was', 'rows were')} kept.`,
    });
    if (s.rowsRejected > 0)
        out.push({
            tone: 'warning',
            text: `${plural(s.rowsRejected, 'row was', 'rows were')} set aside because ${s.rowsRejected === 1 ? 'it' : 'they'} did not match the column types.`,
        });
    for (const q of s.quarantined ?? []) out.push({ tone: 'warning', text: `${q.file} was set aside: ${q.reason}.` });
    out.push(...refused);
    if (s.newColumns?.length)
        out.push({
            tone: 'info',
            text: `New ${s.newColumns.length === 1 ? 'column' : 'columns'} not kept: ${s.newColumns.join(', ')}. The Dataset reads the columns it was made with.`,
        });
    if (s.period)
        out.push({
            tone: 'info',
            text: s.period.days
                ? `It replaced ${coversText(s.period.from, s.period.to)} (${plural(s.period.days, 'day', 'days')}): ${plural(s.period.rowsReplaced, 'older row was', 'older rows were')} removed, ${plural(s.rowsAfter, 'row', 'rows')} now.`
                : `It covers no day, so nothing older was replaced: ${plural(s.rowsAfter, 'row', 'rows')} now.`,
        });
    else if (s.replaced)
        out.push({
            tone: 'info',
            text: `This replaced the previous data: ${plural(s.rowsBefore, 'row', 'rows')} before, ${plural(s.rowsAfter, 'row', 'rows')} now.`,
        });
    else if (s.rowsBefore > 0)
        out.push({
            tone: 'info',
            text: `${plural(s.rowsChanged, 'row was', 'rows were')} added: ${plural(s.rowsAfter, 'row', 'rows')} now.`,
        });
    return [...out, ...indexAndExpectations(s)];
}

/** The follow-on work of the run (DT-16): the link index and the Expectations. */
function indexAndExpectations(s: ImportSummary): SummaryLine[] {
    const out: SummaryLine[] = [];
    const ix = s.index;
    // Details about entities have no links, so "no index" is expected and not worth a line.
    if (ix && !(s.kind === 'reference' && ix.result === 'SKIPPED')) {
        switch (ix.result) {
            case 'BUILT':
                out.push({
                    tone: 'success',
                    text:
                        typeof ix.edges === 'number' && typeof ix.nodes === 'number'
                            ? `The link index was built: ${plural(ix.edges, 'link', 'links')} between ${plural(ix.nodes, 'entity', 'entities')}.`
                            : 'The link index was built.',
                });
                break;
            case 'UP_TO_DATE':
                out.push({ tone: 'success', text: 'The link index is up to date.' });
                break;
            case 'SKIPPED':
            case 'NOT_AVAILABLE':
            case 'DEFERRED':
                out.push({ tone: 'info', text: `No link index was built: ${ix.message ?? 'nothing to build'}.` });
                break;
            default:
                out.push({ tone: 'warning', text: `The link index was not built: ${ix.message ?? ix.result}.` });
        }
    }
    for (const e of s.expectations ?? []) {
        const status = (e.status ?? '').toUpperCase();
        if (status === 'PASS' || status === 'PASSED' || status === 'OK')
            out.push({ tone: 'success', text: `Expectation ${e.name}: met.` });
        else if (status === 'ERROR')
            out.push({
                tone: 'error',
                text: `Expectation ${e.name} could not be checked: ${e.message ?? 'unknown error'}.`,
            });
        else
            out.push({
                tone: 'warning',
                text: `Expectation ${e.name}: not met${typeof e.violations === 'number' ? ` (${plural(e.violations, 'row', 'rows')})` : ''}.`,
            });
    }
    return out;
}

// ── Locks (A-15, A-11, DT-4, DT-5) ───────────────────────────────────────────────────────────────────────────────────

/** Why Add data is locked in this Space, in the server's words (`OnboardRoutes.requireSelfImport`). */
export const SELF_IMPORT_OFF =
    'Add data is turned off in this Space: its data comes from Pipelines - ask an administrator to turn it on';
export const NO_UPLOAD_RIGHT = 'Adding data needs the right to upload data in this Space - ask an administrator';
/** Fallbacks until `DATA-TIERS-1` phase 2 serves `featureReasons.remoteSystems` / `pipelineAutomation`. */
export const REMOTE_FALLBACK =
    'Reading from another database or a remote system is available in Standard and above - this build (Basic) adds files and copies Postgres tables';
export const AUTOMATION_FALLBACK =
    'Keeping a Dataset up to date by itself is available in Standard and above - this build (Basic) runs an import when you add a file or press Run now';

/**
 * Whether an edition feature is withheld here, and why: locked when `/bootstrap` says the feature is false, or when the
 * server does not name it yet and this is Basic (DATA-TIERS-1 phase 2 adds the flags). Null = available.
 */
export function editionLock(
    feature: string,
    features: Readonly<Record<string, boolean>>,
    reasons: Readonly<Record<string, string>>,
    edition: string,
    fallback: string,
): string | null {
    const flag = features[feature];
    const locked = flag === false || (flag === undefined && edition === 'basic');
    return locked ? (reasons[feature] ?? fallback) : null;
}

// ── LA-ARRIVAL-LEDGER-1: the arrivals of an import Dataset in words ──────────────────────────────────────────

/** A ledger time (no zone) or an event value as shown: date and minutes, or the date alone. */
function when(v: string | null, dateOnly = false): string {
    if (!v) return '';
    const t = v.replace('T', ' ');
    return dateOnly ? t.slice(0, 10) : t.slice(0, 16);
}

/** The period an arrival (or the Dataset) covers, `2026-09-01 – 2026-09-28`; '' when its event dates are unknown. */
export function coversText(from: string | null, to: string | null): string {
    if (!from || !to) return '';
    const a = when(from, true);
    const b = when(to, true);
    return a === b ? a : `${a} – ${b}`;
}

/**
 * Every line of a Dataset's **Data status** (`<inspecto-la-data-status>`), the three clocks first. ⚠ Extension point: a
 * later line (files waiting in the inbox, storage) is one more entry here from a field of `GET /datasets/{id}/arrivals`.
 */
export function dataStatusLines(read: Arrivals | null): string[] {
    const clocks = dataStatusLine(read?.arrivals ?? []);
    const lines = clocks ? [clocks] : [];
    const feed = feedSummaryLine(read?.feed ?? null); // LA-ARRIVAL-FEEDS-1
    if (feed) lines.push(feed);
    const storage = storageStatusLine(read?.storage ?? null); // LA-DATASET-COMPACT-1
    if (storage) lines.push(storage);
    return lines;
}

const INDEXED = new Set(['BUILT', 'UP_TO_DATE']);

/** What the graph holds, from the arrivals' recorded index outcomes, newest first; '' when nothing was recorded. */
function graphClock(list: readonly Arrival[]): string {
    const newest = list[0];
    if (newest?.index === 'DEFERRED') return "the graph is updated by the Space's Jobs";
    const k = list.findIndex((a) => !!a.index && INDEXED.has(a.index));
    if (k === 0) return 'graph up to date';
    // the arrivals after the newest one the graph took, counted only when their own outcome was recorded
    const behind = (k < 0 ? list : list.slice(0, k)).filter(
        (a) => !!a.index && a.index !== 'SKIPPED' && a.index !== 'NOT_AVAILABLE',
    ).length;
    if (k > 0 && behind) return `graph ${plural(behind, 'file', 'files')} behind`;
    if (newest?.index === 'FAILED' || newest?.index === 'REFUSED') return 'the graph was not updated';
    return '';
}

/**
 * The Dataset's **Data status** in one line, from its arrivals (newest first): **Received** (when data last arrived) ·
 * **covers** (the period the data still kept covers) · the **graph** (up to date, or how many files behind) - the graph
 * part only when the runs recorded an index outcome.
 */
export function dataStatusLine(all: readonly Arrival[]): string | null {
    const list = all.filter((a) => a.status !== 'REFUSED'); // a refused file never arrived in the data
    const newest = list[0];
    if (!newest) return null;
    const parts = [`Received ${when(newest.received)}`];
    const kept = list.filter((a) => a.kept);
    const froms = kept.map((a) => a.coversFrom).filter((v): v is string => !!v);
    const tos = kept.map((a) => a.coversTo).filter((v): v is string => !!v);
    const covers =
        froms.length && tos.length
            ? coversText(
                  froms.reduce((x, y) => (y < x ? y : x)),
                  tos.reduce((x, y) => (y > x ? y : x)),
              )
            : '';
    if (covers) parts.push(`covers ${covers}`);
    const graph = graphClock(list);
    if (graph) parts.push(graph);
    return parts.join(' · ');
}

/** One arrival in words: who sent it, what it did, its rows and period, and whether its rows are still kept. */
export function arrivalLine(a: Arrival): string {
    const parts = [a.sender === 'upload' ? 'uploaded' : 'from a system'];
    if (a.status === 'REFUSED') {
        parts.push(`refused: ${a.reason ?? 'its columns do not match'}`);
        if (a.setAside) parts.push(`set aside in ${a.setAside}`);
        return parts.join(' · ');
    }
    if (a.mode === 'replace') parts.push('replaced the data');
    else if (a.mode === 'append') parts.push('added its rows');
    else if (a.mode === 'period') parts.push('replaced the days it covers');
    if (typeof a.rowsRead === 'number')
        parts.push(
            a.rowsRejected
                ? `${plural(a.rowsRead, 'row', 'rows')} (${a.rowsRejected} rejected)`
                : plural(a.rowsRead, 'row', 'rows'),
        );
    const covers = coversText(a.coversFrom, a.coversTo);
    if (covers) parts.push(`covers ${covers}`);
    if (a.newColumns?.length) parts.push(`not kept: ${a.newColumns.join(', ')}`);
    if (!a.kept) parts.push('set aside by a later replace');
    return parts.join(' · ');
}
