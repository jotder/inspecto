import { MockFlags } from '../mock-flags';
import { SAMPLE_SOURCES } from '../sample-sources';
import { json, error, MockHandler, MockResponse } from '../mock-http';

/**
 * Offline mock for the raw table browser ({@code /db/catalog}, {@code /db/table}, {@code /db/query}).
 * The business "stores" group IS {@link SAMPLE_SOURCES} — the same rows every offline surface resolves
 * a Dataset to — so the Data Browser, the Dataset editor's store picker and the seeded space templates
 * all describe one reality. Plus one live operational group ("ops:objects") so the operational section
 * is explorable too. Gated on {@code mockDb}. Regexes are tail-anchored so they match through the
 * {@code /api/v1} prefix (mocks run before the space rewrite).
 *
 * ⚠ It must never be more lenient than `DbBrowserRoutes`: the same 200-default / 5000-max limit clamp,
 * the same `truncated` semantics (the store held more than `limit`), a 404 for an unknown store, and a
 * 422 for SQL that is not a single read-only statement — offline, a mutating statement that appeared to
 * succeed would be a rehearsal of a failure the server refuses.
 *
 * ⚠ `/db/query` **refuses valid SQL with a 501** rather than executing it. Executing would mean awaiting
 * the lazily-imported SQL engine, and {@link MockHandler} is synchronous by design — widening it for one
 * endpoint broke the direct-call contract 17 handler specs rely on. Refusing is the honest degrade: the
 * previous stub answered every query with the same three fixed rows whatever the SQL said, which is the
 * passing-rehearsal failure this layer exists to prevent. Offline, the pro-tier table's in-browser SQL
 * (AlaSQL, the same engine) still answers, and so does {@link DatasetRowsService.sql}, which never
 * reaches the wire offline.
 */
const CATALOG = /\/db\/catalog$/;
const TABLE = /\/db\/table$/;
const QUERY = /\/db\/query$/;

/** Mirrors `DbBrowserRoutes.DEFAULT_LIMIT` / `MAX_LIMIT`. */
const DEFAULT_LIMIT = 200;
const MAX_LIMIT = 5000;

const OPS_COLUMNS = [
    { name: 'id', type: 'VARCHAR', role: null, cardinality: null },
    { name: 'object_type', type: 'VARCHAR', role: null, cardinality: null },
    { name: 'title', type: 'VARCHAR', role: null, cardinality: null },
    { name: 'status', type: 'VARCHAR', role: null, cardinality: null },
    { name: 'severity', type: 'VARCHAR', role: null, cardinality: null },
];
const OPS_ROWS = [
    { id: 'inc-1001', object_type: 'INCIDENT', title: 'Late nightly batch', status: 'IDENTIFIED', severity: 'HIGH' },
    { id: 'alt-2002', object_type: 'ALERT', title: 'Schema drift on orders', status: 'OPEN', severity: 'MEDIUM' },
];

function isOps(group: unknown): boolean {
    return typeof group === 'string' && group.startsWith('ops:');
}

/** `DbBrowserRoutes.clampLimit` — 1 ≤ limit ≤ 5000, defaulting when absent or unparseable. */
function clampLimit(raw: string | undefined): number {
    const n = Number(raw);
    if (!raw || Number.isNaN(n)) return DEFAULT_LIMIT;
    return Math.max(1, Math.min(MAX_LIMIT, Math.trunc(n)));
}

/** The DuckDB type name a value would report — the inverse of the UI's `dbColumnType`. */
function duckType(values: unknown[]): string {
    const v = values.find((x) => x != null && x !== '');
    if (typeof v === 'number') return Number.isInteger(v) ? 'BIGINT' : 'DOUBLE';
    if (typeof v === 'boolean') return 'BOOLEAN';
    const s = String(v ?? '');
    if (/^-?\d+$/.test(s)) return 'BIGINT';
    if (/^-?\d+\.\d+$/.test(s)) return 'DOUBLE';
    if (/\d{4}/.test(s) && /[-/:T]/.test(s) && !isNaN(Date.parse(s))) return 'TIMESTAMP';
    return 'VARCHAR';
}

/**
 * Port of the backend `ResultSetDescriptor`: role is DERIVED (date → temporal, non-id number → measure,
 * else dimension) and `cardinality` is counted only for dimensions, over the returned page.
 */
function describe(rows: Record<string, unknown>[]): Record<string, unknown>[] {
    if (!rows.length) return [];
    return Object.keys(rows[0]).map((name) => {
        const values = rows.map((r) => r[name]);
        const type = duckType(values);
        const role = /DATE|TIME/.test(type)
            ? 'temporal'
            : /INT|DOUBLE|DECIMAL|REAL/.test(type) && !/(^|_)id$/i.test(name)
              ? 'measure'
              : 'dimension';
        return {
            name,
            type,
            role,
            cardinality: role === 'dimension' ? new Set(values.map((v) => String(v))).size : null,
        };
    });
}

function result(columns: unknown[], rows: Record<string, unknown>[], truncated = false) {
    return { columns, rows, statistics: { rowCount: rows.length, elapsedMs: 1, truncated } };
}

/** One page of a sample store, sorted/offset/limited exactly as `/db/table` pages a real one. */
function page(store: Record<string, unknown>[], params: Record<string, string>): MockResponse {
    const limit = clampLimit(params['limit']);
    const offset = Math.max(0, Number(params['offset']) || 0);
    const sorted = sortRows(store, params['sort']);
    const window = sorted.slice(offset, offset + limit);
    // `truncated` means the store held MORE than this page — the same probe the executor does with limit+1.
    return json(result(describe(window), window, sorted.length > offset + limit));
}

/** `field:asc|desc`, one term only — `DbBrowserRoutes.parseSort` supports no more. */
function sortRows(rows: Record<string, unknown>[], sort: string | undefined): Record<string, unknown>[] {
    if (!sort) return rows;
    const [field, dir] = sort.split(':');
    if (!field || !(field in (rows[0] ?? {}))) return rows;
    const sign = dir === 'desc' ? -1 : 1;
    return [...rows].sort((a, b) => {
        const x = a[field];
        const y = b[field];
        if (x === y) return 0;
        return (Number(x) || String(x)) > (Number(y) || String(y)) ? sign : -sign;
    });
}

/**
 * The shape of `SqlGuard`: a single read-only statement. Not its full allow-list (the blocked-function
 * regex is the server's job), but enough that a mutating statement cannot appear to succeed offline.
 */
function guardFindings(sql: string): string[] {
    const text = sql.replace(/--[^\n]*/g, '').trim();
    const findings: string[] = [];
    if (text.replace(/;\s*$/, '').includes(';')) findings.push('Only a single statement may run.');
    if (!/^(select|with)\b/i.test(text)) findings.push('Only SELECT / WITH statements may run.');
    return findings;
}

export function dbBrowserHandler(flags: MockFlags): MockHandler {
    return (req) => {
        if (!flags.mockDb) return undefined;
        const { method, url, params, body } = req;
        if (method === 'GET' && CATALOG.test(url)) {
            return json({
                groups: [
                    {
                        id: 'stores',
                        label: 'Data Stores',
                        kind: 'parquet',
                        tables: Object.keys(SAMPLE_SOURCES).map((name) => ({ name, format: 'PARQUET' })),
                    },
                    {
                        id: 'ops:objects',
                        label: 'Operational · Objects',
                        kind: 'operational',
                        engine: 'duckdb',
                        live: true,
                        tables: [{ name: 'inspecto_ops_objects' }],
                    },
                ],
            });
        }
        if (method === 'GET' && TABLE.test(url)) {
            if (isOps(params['group'])) return json(result(OPS_COLUMNS, OPS_ROWS));
            const name = (params['name'] ?? '').trim();
            if (!name) return error(400, "missing 'name'");
            const store = SAMPLE_SOURCES[name];
            if (!store) return error(404, `no store '${name}'`);
            return page(store, params);
        }
        if (method === 'POST' && QUERY.test(url)) {
            const b = (body ?? {}) as { group?: unknown; table?: unknown; sql?: unknown; limit?: unknown };
            if (isOps(b.group)) return json(result(OPS_COLUMNS, OPS_ROWS));
            const sql = typeof b.sql === 'string' ? b.sql : '';
            if (!sql.trim()) return error(422, "missing 'sql'");
            const table = String(b.table ?? '').trim();
            if (!table) return error(422, "missing 'table' (the store the SQL reads from)");
            const store = SAMPLE_SOURCES[table];
            if (!store) return error(404, `no store '${table}'`);
            const findings = guardFindings(sql);
            if (findings.length) {
                // Checked BEFORE the 501: a statement the server would refuse must never look merely
                // unsupported-offline, or the refusal goes unrehearsed.
                return error(422, 'SQL failed the read-only safety check', { findings });
            }
            return error(
                501,
                'Offline, SQL is not executed against a store. Run it in the table’s own SQL editor, ' +
                    'which uses the same engine in the browser.',
            );
        }
        return undefined;
    };
}
