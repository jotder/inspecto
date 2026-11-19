/**
 * Connection-type catalog — the schema behind the connection configuration dialog (mirrors the parser
 * `parser-types.ts` pattern). Each type maps to a backend `connector` and declares the typed attributes the
 * form should show. An attribute's {@link ConnAttr.target} says where it lands on the `ConnectionProfile`:
 * a top-level field (host/port/database/basePath/username/password) or inside the free-form `options` map.
 *
 * The SSH-tunnel (bastion) and proxy sections are shared across every type and are handled directly by the
 * dialog (they map to `profile.tunnel` / `profile.proxy`), so they are NOT part of these per-type attrs.
 *
 * NOTE: only the field NAMES are firm (they match the existing `ConnectionProfile` shape + `*_connection.toon`
 * conventions). The exact per-type option lists are a BEST-GUESS first cut — revisit as each connector's real
 * settings firm up (same caveat as the non-DSV parser types).
 *
 * The rows are drawn by `<inspecto-connection-fields>` (BASIC-ELT-PROFILE-1 U2), shared by the dialog and Add data.
 */
import { AbstractControl, FormControl, FormGroup, ValidatorFn, Validators } from '@angular/forms';
import type { ConnectionProfile } from 'app/inspecto/api';

/** Control type for a connection attribute field. */
export type ConnControl = 'text' | 'number' | 'password' | 'checkbox' | 'select';

/** Where an attribute lands on the ConnectionProfile. `option` ⇒ the free-form `options` map. */
export type ConnTarget = 'host' | 'port' | 'database' | 'basePath' | 'username' | 'password' | 'option';

export interface ConnAttr {
    /** Form-control key. For non-`option` targets this equals the ConnectionProfile field name. */
    key: string;
    label: string;
    control: ConnControl;
    target: ConnTarget;
    default?: string | number | boolean;
    options?: string[];
    hint?: string;
    required?: boolean;
    /** A form the server refuses any other value in, checked on the screen too (the server stays the authority). */
    pattern?: { regex: RegExp; message: string };
    /** A number's inclusive bounds. */
    min?: number;
    max?: number;
}

export interface ConnTypeDef {
    /** Dropdown value / UI id. */
    type: string;
    /** The `ConnectionProfile.connector` sent to the backend. */
    connector: string;
    label: string;
    description: string;
    attrs: ConnAttr[];
    /** Made elsewhere (Add data's Postgres snapshot), so the dialog offers it only when editing a saved one. */
    editOnly?: boolean;
}

export const SECRET_HINT = 'a ${ENV:VAR} reference, not a raw secret';

/**
 * The `${ENV:INSPECTO_PG_…}` form the `postgres` scheme accepts for the password (`PostgresCollectorConnector.PASSWORD_REF`):
 * only a variable an administrator named for a database, never a raw value or another secret.
 */
export const PG_PASSWORD_REFERENCE = /^\$\{ENV:INSPECTO_PG_[A-Z0-9_]{1,100}\}$/;

/** The connector of a Postgres snapshot's saved Connection (BASIC-ELT-PROFILE-1 BE-4). */
export const POSTGRES_CONNECTOR = 'postgres';

/**
 * Where a `postgres` Connection connects: the part Add data's *Choose a table* asks (U2). What to copy (`options.schema` +
 * `table`, or `query`) is the rest of the profile; Add data asks it with its own table / query picker.
 */
export const POSTGRES_CONNECTION_ATTRS: readonly ConnAttr[] = [
    { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
    {
        key: 'port',
        label: 'Port',
        control: 'number',
        target: 'port',
        default: 5432,
        required: true,
        min: 1,
        max: 65535,
    },
    { key: 'database', label: 'Database', control: 'text', target: 'database', required: true },
    {
        key: 'username',
        label: 'Username',
        control: 'text',
        target: 'username',
        required: true,
        hint: 'A read-only login: CONNECT, USAGE on the schema and SELECT on its tables.',
    },
    {
        key: 'password',
        label: 'Password',
        control: 'password',
        target: 'password',
        required: true,
        hint: 'The environment variable on the server that holds it, starting INSPECTO_PG_, as ${ENV:INSPECTO_PG_SALES}: never the password itself.',
        pattern: {
            regex: PG_PASSWORD_REFERENCE,
            message: 'Write it as ${ENV:INSPECTO_PG_…}: the variable on the server holding the password.',
        },
    },
];

/** The connection types offered in the dropdown (order as specified; `https` appended 2026-09-23). */
export const CONNECTION_TYPES: ConnTypeDef[] = [
    {
        type: 'database',
        connector: 'db',
        label: 'Database',
        description: 'Connect to a database',
        attrs: [
            {
                key: 'db_type',
                label: 'Database type',
                control: 'select',
                target: 'option',
                default: 'postgres',
                options: ['postgres', 'oracle', 'duckdb', 'mysql', 'sqlserver'],
            },
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 5432 },
            { key: 'database', label: 'Database', control: 'text', target: 'database', required: true },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'schema', label: 'Default schema', control: 'text', target: 'option' },
            {
                key: 'sslmode',
                label: 'SSL mode',
                control: 'select',
                target: 'option',
                default: 'require',
                options: ['disable', 'require', 'verify-ca', 'verify-full'],
            },
        ],
    },
    {
        type: 'ftp',
        connector: 'ftp',
        label: 'FTP',
        description: 'Connect to an FTP server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 21 },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'basePath', label: 'Base path', control: 'text', target: 'basePath' },
            { key: 'passive_mode', label: 'Passive mode', control: 'checkbox', target: 'option', default: true },
            { key: 'encoding', label: 'Encoding', control: 'text', target: 'option', default: 'UTF-8' },
        ],
    },
    {
        type: 'ftps',
        connector: 'ftps',
        label: 'FTPS',
        description: 'Connect to an FTPS (TLS) server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 990 },
            { key: 'username', label: 'Username', control: 'text', target: 'username' },
            { key: 'password', label: 'Password', control: 'password', target: 'password', hint: SECRET_HINT },
            { key: 'basePath', label: 'Base path', control: 'text', target: 'basePath' },
            { key: 'implicit_tls', label: 'Implicit TLS', control: 'checkbox', target: 'option', default: true },
            { key: 'passive_mode', label: 'Passive mode', control: 'checkbox', target: 'option', default: true },
        ],
    },
    {
        type: 'local',
        connector: 'local',
        label: 'Local',
        description: 'Files on the local host (directory with permission)',
        attrs: [
            { key: 'basePath', label: 'Directory', control: 'text', target: 'basePath', required: true },
            {
                key: 'file_pattern',
                label: 'File pattern',
                control: 'text',
                target: 'option',
                hint: 'glob, e.g. *.csv.gz',
            },
            {
                key: 'recursive',
                label: 'Recurse subdirectories',
                control: 'checkbox',
                target: 'option',
                default: false,
            },
        ],
    },
    {
        type: 'sftp',
        connector: 'sftp',
        label: 'SFTP',
        description: 'Connect to an SFTP server',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 22 },
            { key: 'username', label: 'Username', control: 'text', target: 'username', required: true },
            {
                key: 'auth_method',
                label: 'Auth method',
                control: 'select',
                target: 'option',
                default: 'password',
                options: ['password', 'key'],
            },
            {
                key: 'password',
                label: 'Password / passphrase',
                control: 'password',
                target: 'password',
                hint: SECRET_HINT,
            },
            {
                key: 'private_key_path',
                label: 'Private key path',
                control: 'text',
                target: 'option',
                hint: 'path to the private key (key auth)',
            },
            { key: 'basePath', label: 'Remote directory', control: 'text', target: 'basePath' },
            {
                key: 'strict_host_key',
                label: 'Strict host-key checking',
                control: 'checkbox',
                target: 'option',
                default: true,
            },
        ],
    },
    // The Webhook sink's target (sink.webhook). Egress is decided HERE, by whoever may onboard
    // Connections — a pipeline only names one, it never carries a URL. The engine builds
    // https://<host>[:<port>]<path> and refuses a tunnel or proxy (it does not dial through either).
    {
        type: 'https',
        connector: 'https',
        label: 'HTTPS webhook',
        description: 'An HTTPS endpoint the Webhook sink POSTs rows to as JSON',
        attrs: [
            { key: 'host', label: 'Host', control: 'text', target: 'host', required: true },
            { key: 'port', label: 'Port', control: 'number', target: 'port', default: 443 },
            {
                key: 'basePath',
                label: 'Path',
                control: 'text',
                target: 'basePath',
                hint: 'e.g. /hooks/orders',
            },
            {
                key: 'password',
                label: 'Bearer token',
                control: 'password',
                target: 'password',
                hint: SECRET_HINT,
            },
            {
                key: 'timeout_seconds',
                label: 'Request timeout (s)',
                control: 'number',
                target: 'option',
                default: 10,
            },
        ],
    },
    // A Postgres snapshot's Connection `<dataset id>_postgres` (BASIC-ELT-PROFILE-1 BE-4): Add data makes it; it shows in
    // the Connections list, and editing it here keeps its connector and what it copies.
    {
        type: 'postgres',
        connector: POSTGRES_CONNECTOR,
        label: 'Postgres table copy',
        description: 'One read-only Postgres table or query, copied by Add data',
        editOnly: true,
        attrs: [
            ...POSTGRES_CONNECTION_ATTRS,
            { key: 'schema', label: 'Schema', control: 'text', target: 'option' },
            { key: 'table', label: 'Table', control: 'text', target: 'option' },
            { key: 'query', label: 'Query', control: 'text', target: 'option', hint: 'instead of a table: one SELECT' },
        ],
    },
];

/** The type definition for {@code type}, falling back to the first type for an unknown value. */
export function connTypeDef(type: string): ConnTypeDef {
    return CONNECTION_TYPES.find((t) => t.type === type) ?? CONNECTION_TYPES[0];
}

/** The dialog type id for a saved profile's {@code connector} (defaults to the first type if unmapped). */
export function typeForConnector(connector: string): string {
    return (CONNECTION_TYPES.find((t) => t.connector === connector) ?? CONNECTION_TYPES[0]).type;
}

/** The typed attributes for {@code type}. */
export function attrsFor(type: string): ConnAttr[] {
    return connTypeDef(type).attrs;
}

/**
 * The connectors that reach a remote system (`DATA-TIERS-1` P4): Standard and above, locked in Basic's screens with the
 * `remoteSystems` reason. `local` (a folder on this machine) and `https` (a delivery target, not a Collector) stay open.
 */
export const REMOTE_CONNECTORS: ReadonlySet<string> = new Set([
    'db',
    'ftp',
    'ftps',
    'sftp',
    's3',
    'azure',
    'gcs',
    'kafka',
]);

/**
 * Why this edition withholds a NEW Connection with `connector`, or null: a remote connector takes the `remoteSystems` screen
 * lock (`screenLocks`, `/bootstrap`). `postgres` is not remote here: a Postgres snapshot is in every edition, Basic included
 * (BASIC-ONBOARD-1 P3, BASIC-ELT-PROFILE-1 D5), while the general `db` Collector stays locked. The one rule the Connection
 * dialog and Add data's *A Postgres table* both read.
 */
export function connectorLock(connector: string, locks: Readonly<Record<string, string>>): string | null {
    return REMOTE_CONNECTORS.has(connector) ? (locks['remoteSystems'] ?? null) : null;
}

/** The validators an attribute's control carries: required, its pattern, its bounds. */
function attrValidators(a: ConnAttr): ValidatorFn[] {
    const out: ValidatorFn[] = [];
    if (a.required) out.push(Validators.required);
    const p = a.pattern;
    // Checked trimmed, as it is sent: a pasted reference often carries a blank (a pen row trims; a boxed secret does not).
    if (p) out.push((c) => (!c.value || p.regex.test(String(c.value).trim()) ? null : { pattern: true }));
    if (a.min != null) out.push(Validators.min(a.min));
    if (a.max != null) out.push(Validators.max(a.max));
    return out;
}

/** The form for `attrs`, seeded from a saved `profile` (edit) or each attribute's default. */
export function connAttrsGroup(attrs: readonly ConnAttr[], profile?: ConnectionProfile): FormGroup {
    const fields = profile as unknown as Record<string, unknown> | undefined;
    const group: Record<string, FormControl> = {};
    for (const a of attrs) {
        const fallback = a.default ?? (a.control === 'checkbox' ? false : '');
        const stored = profile ? (a.target === 'option' ? profile.options?.[a.key] : fields?.[a.target]) : undefined;
        let init: unknown = stored ?? fallback;
        if (a.control === 'checkbox') init = init === true || init === 'true';
        group[a.key] = new FormControl(init, attrValidators(a));
    }
    return new FormGroup(group);
}

/** What is wrong with an attribute's value, in words, or '' (no `touched` check: the caller decides when it shows). */
export function connAttrError(a: ConnAttr, c: AbstractControl | null | undefined): string {
    if (!c) return '';
    if (c.hasError('required')) return `${a.label} is required.`;
    if (c.hasError('pattern')) return a.pattern?.message ?? '';
    if (c.hasError('min') || c.hasError('max')) return `${a.label} is ${a.min} to ${a.max}.`;
    return '';
}
