import { formatNumber } from '@inspecto/core/viz/number-format';

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

export interface DraftLink {
    from: Choice<string | null>;
    to: Choice<string | null>;
    kind: Choice<string | null>;
    time: Choice<string | null>;
    amount: Choice<string | null>;
    details: Choice<string[]>;
}

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
        columns: { column: string; entityType: DraftEntityTypeChoice }[];
        primary: Choice<string | null>;
        link?: DraftLink;
        identifying?: Choice<string | null> & { entityType?: string | null };
    };
    limits: DraftLimits;
}

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
    rename?: string;
    drop?: boolean;
}

export interface CommitLink {
    from: string;
    to: string;
    kind?: string;
    time?: string;
    amount?: string;
}

/** The body of `POST /onboard/files/{draftId}/commit`; every key optional (absent = the draft's proposal). */
export interface CommitBody {
    name: string;
    description?: string;
    columns: CommitColumn[];
    primaryEntityType?: string | null;
    link?: CommitLink | null;
    identifyingColumn?: string | null;
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
    if (low(d.entityTypes.primary) && d.entityTypes.primary.value)
        out.push({ key: choiceKey.primary, label: 'The main kind of thing' });
    const l = d.entityTypes.link;
    if (l && l.from.value && (low(l.from) || low(l.to)))
        out.push({ key: choiceKey.link('from'), label: 'Which way the link goes' });
    for (const role of ['kind', 'time', 'amount'] as const)
        if (l && l[role].value && low(l[role])) out.push({ key: choiceKey.link(role), label: `The link's ${role}` });
    const id = d.entityTypes.identifying;
    if (id && id.value && low(id))
        out.push({ key: choiceKey.identifying, label: 'The column that identifies each entity' });
    return out;
}

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
}

/** What the screen holds for the link (stream) or the identifying column (reference); '' = none. */
export interface RolesState {
    primary: string;
    from: string;
    to: string;
    kind: string;
    time: string;
    amount: string;
    identifying: string;
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
        }
        col.entityType = entity;
        out.columns.push(col);
    }
    if (!opts.withEntityTypes) {
        // The step was not opened: high proposals stand (the server applies them), low ones are not applied.
        const p = d.entityTypes.primary;
        out.primaryEntityType = p.value && p.confidence === 'high' ? p.value : null;
        if (d.kind === 'stream') {
            const l = d.entityTypes.link;
            const sure = l && l.from.value && l.to.value && l.from.confidence === 'high' && l.to.confidence === 'high';
            out.link = sure
                ? stripEmpty({
                      from: l.from.value!,
                      to: l.to.value!,
                      kind: high(l.kind),
                      time: high(l.time),
                      amount: high(l.amount),
                  })
                : null;
        } else {
            const id = d.entityTypes.identifying;
            out.identifyingColumn = id?.value && id.confidence === 'high' ? id.value : null;
        }
        return out;
    }
    const keep = (v: string) => (v && kept.has(v) ? v : '');
    const heldTypes = new Set(columns.filter((c) => !c.drop && c.entityType).map((c) => c.entityType));
    out.primaryEntityType = roles.primary && heldTypes.has(roles.primary) ? roles.primary : null;
    if (d.kind === 'stream') {
        const from = keep(roles.from);
        const to = keep(roles.to);
        out.link =
            from && to && from !== to
                ? stripEmpty({ from, to, kind: keep(roles.kind), time: keep(roles.time), amount: keep(roles.amount) })
                : null;
    } else {
        out.identifyingColumn = keep(roles.identifying) || null;
    }
    return out;
}

function high(c: Choice<string | null>): string {
    return c.value && c.confidence === 'high' ? c.value : '';
}

function stripEmpty(l: { from: string; to: string; kind: string; time: string; amount: string }): CommitLink {
    const out: CommitLink = { from: l.from, to: l.to };
    if (l.kind) out.kind = l.kind;
    if (l.time) out.time = l.time;
    if (l.amount) out.amount = l.amount;
    return out;
}

/** The screen's starting state: every proposal pre-applied (OF-2). */
export function initialColumns(d: OnboardDraft): ColumnState[] {
    const entity = new Map(d.entityTypes.columns.map((c) => [c.column, c.entityType.value ?? '']));
    return d.columns.map((c) => ({
        name: c.name,
        rename: c.name,
        type: c.type.value,
        drop: false,
        entityType: entity.get(c.name) ?? '',
    }));
}

export function initialRoles(d: OnboardDraft): RolesState {
    const l = d.entityTypes.link;
    return {
        primary: d.entityTypes.primary.value ?? '',
        from: l?.from.value ?? '',
        to: l?.to.value ?? '',
        kind: l?.kind.value ?? '',
        time: l?.time.value ?? '',
        amount: l?.amount.value ?? '',
        identifying: d.entityTypes.identifying?.value ?? '',
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
    if (s.rowsIn === 0 && !s.file && (s.quarantined ?? []).length === 0) {
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
    if (s.replaced)
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
    'Reading from a database or a remote system is available in Standard and above - this build (Basic) adds files';
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
