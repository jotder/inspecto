import { MockFlags } from '../mock-flags';
import { error, json, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';

/**
 * The UI-settings mock domain — small per-space preference documents the backend will later serve
 * for real (the "APIs to fetch and save UI preferences" contract). Documents: `geo`
 * (`GET|PUT /settings/geo` → `{tileServerUrl}`), the Phase 4 tile-server config seam; and `branding`
 * (`GET|PUT /settings/branding` → `{logoDataUrl, caption, footerText}`), per-space product branding.
 */

export const SETTINGS_COLL = 'settings';

const GEO = /\/settings\/geo$/;
const BRANDING = /\/settings\/branding$/;

export interface GeoSettingsDoc {
    id: string;
    tileServerUrl: string | null;
}

/** Per-space branding overrides; each `null` field means "use the shipped default". */
export interface BrandingDoc {
    id: string;
    logoDataUrl: string | null;
    caption: string | null;
    footerText: string | null;
}

const clean = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v.trim() : null);

const SCHEDULER = /\/settings\/scheduler$/;

/** The server's bounds gate for a scheduler cap — mirrored verbatim (a mock must never be more lenient). */
export const SCHEDULER_MAX_CAP = 100_000;

/** The DuckDB memory-limit grammar, mirroring `SchedulerRoutes.MEMORY_LIMIT_PATTERN` **verbatim** — this
 *  is the one copy a mock legitimately keeps (it stands in for the server, so it must gate identically),
 *  and it is what the mock SERVES as `duckdbMemoryLimitPattern` so the offline form validates like the
 *  live one. Absolute size (`2GB`) or a proportion of host RAM (`80%`, bounded 1..100 inside the pattern
 *  so the bound travels with the grammar). ⚠ Anchored, no inline flag: the same portability contract the
 *  Java side documents. */
export const SCHEDULER_MEMORY_LIMIT_PATTERN =
    '^(\\d+(\\.\\d+)?\\s*(B|K|KB|KIB|M|MB|MIB|G|GB|GIB|T|TB|TIB)|([1-9][0-9]?(\\.\\d+)?|100)%)$';

/** D11's default Run bound, mirroring `JobService.DEFAULT_MAX_CONCURRENT_RUNS` — the pair ships ON, so
 *  offline must report a bound in force with `default` provenance, never "no cap at all". */
export const SCHEDULER_DEFAULT_JOB_RUNS = 4;

/** Per-tier scheduler document; every key — the cap included — is absent when it inherits the launch
 *  default. A stated cap of `0` = unbounded, matching `ConcurrencyBroker.UNBOUNDED`. */
export interface SchedulerDoc {
    id: string;
    maxConcurrentConsignments?: number;
    pollSeconds?: number;
    acquirePollSeconds?: number;
    /** System tier only: the IntakeGovernor fleet globals (absent = inherit `-Dingest.*`). */
    intakeMaxFilesPerCycle?: number;
    intakeMinFilesPerCycle?: number;
    intakeAdaptive?: boolean;
    /** System tier only: D11's resource pair (absent = inherit the `-D` bootstrap default). */
    duckdbMemoryLimit?: string;
    maxConcurrentJobRuns?: number;
}

/** The stored cap for one tier (`scheduler-system` | `scheduler-space`), or null when none is stored —
 *  a doc holding only other keys (cadences, intake) still answers null, so provenance follows the KEY. */
export function readSchedulerCap(store: MockStore, space: string, tier: string): number | null {
    return store.get<SchedulerDoc>(space, SETTINGS_COLL, tier)?.maxConcurrentConsignments ?? null;
}

/** Merge one tier's cap (the server's per-key rule: key absent = preserve stored, explicit `null` =
 *  clear, stated = bounds-gated); returns the refusal response or null when accepted. The real PUT
 *  422s on a non-integer, negative, or over-cap value — all mirrored here. A save that never mentions
 *  the cap must not write it (the provenance-seizure defect, fixed 2026-08-26). */
export function writeSchedulerCap(
    store: MockStore,
    space: string,
    tier: string,
    body: unknown,
): { refusal: ReturnType<typeof error> | null } {
    const b = (body ?? {}) as Record<string, unknown>;
    const existing = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    const doc: SchedulerDoc = { ...existing, id: tier };
    if ('maxConcurrentConsignments' in b) {
        const raw = b['maxConcurrentConsignments'];
        if (raw === null) {
            delete doc.maxConcurrentConsignments;
        } else {
            const cap = typeof raw === 'number' && Number.isInteger(raw) ? raw : Number.NaN;
            if (Number.isNaN(cap))
                return { refusal: error(422, `maxConcurrentConsignments must be an integer, got '${String(raw)}'`) };
            if (cap < 0 || cap > SCHEDULER_MAX_CAP)
                return { refusal: error(422, `maxConcurrentConsignments must be 0..${SCHEDULER_MAX_CAP}, got ${cap}`) };
            doc.maxConcurrentConsignments = cap;
        }
    }
    store.put(space, SETTINGS_COLL, tier, doc);
    return { refusal: null };
}

/** The server's cadence bounds gate, mirrored: a STATED cadence must be an int in 1..86400 (explicit
 *  `null` = clear is legal). Run this BEFORE any write — the server validates the whole body before
 *  persisting anything, and a mock that half-commits is more lenient than the backend. */
export function schedulerCadenceRefusal(body: unknown): ReturnType<typeof error> | null {
    const b = (body ?? {}) as Record<string, unknown>;
    for (const key of ['pollSeconds', 'acquirePollSeconds'] as const) {
        if (!(key in b) || b[key] === null) continue;
        const raw = b[key];
        const v = typeof raw === 'number' && Number.isInteger(raw) ? raw : Number.NaN;
        if (Number.isNaN(v) || v < 1 || v > 86_400)
            return error(422, `${key} must be 1..86400 seconds, got ${String(raw)}`);
    }
    return null;
}

/** Apply the cadence merge (key absent = preserve; explicit `null` = clear; stated = set) onto the
 *  tier's stored doc. Call {@link schedulerCadenceRefusal} first — this assumes a valid body. */
export function writeSchedulerCadence(store: MockStore, space: string, tier: string, body: unknown): void {
    const b = (body ?? {}) as Record<string, unknown>;
    const doc = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    if (!doc) return;
    for (const key of ['pollSeconds', 'acquirePollSeconds'] as const) {
        if (!(key in b)) continue;
        if (b[key] === null) delete doc[key];
        else doc[key] = b[key] as number;
    }
    store.put(space, SETTINGS_COLL, tier, doc);
}

/** The server's intake-globals bounds gate, mirrored: max ≥ 0, floor ≥ 1 (both ≤ 10,000,000),
 *  adaptive strictly boolean; explicit `null` = clear is legal. Run BEFORE any write. */
export function schedulerIntakeRefusal(body: unknown): ReturnType<typeof error> | null {
    const b = (body ?? {}) as Record<string, unknown>;
    const intBounds: [key: string, floor: number][] = [
        ['intakeMaxFilesPerCycle', 0],
        ['intakeMinFilesPerCycle', 1],
    ];
    for (const [key, floor] of intBounds) {
        if (!(key in b) || b[key] === null) continue;
        const raw = b[key];
        const v = typeof raw === 'number' && Number.isInteger(raw) ? raw : Number.NaN;
        if (Number.isNaN(v) || v < floor || v > 10_000_000)
            return error(422, `${key} must be ${floor}..10000000, got ${String(raw)}`);
    }
    if ('intakeAdaptive' in b && b['intakeAdaptive'] !== null && typeof b['intakeAdaptive'] !== 'boolean')
        return error(422, `intakeAdaptive must be true or false, got ${String(b['intakeAdaptive'])}`);
    return null;
}

/** Apply the intake-globals merge onto the tier's stored doc (same per-key rule as the cadence).
 *  Call {@link schedulerIntakeRefusal} first — this assumes a valid body. */
export function writeSchedulerIntake(store: MockStore, space: string, tier: string, body: unknown): void {
    const b = (body ?? {}) as Record<string, unknown>;
    const doc = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    if (!doc) return;
    for (const key of ['intakeMaxFilesPerCycle', 'intakeMinFilesPerCycle', 'intakeAdaptive'] as const) {
        if (!(key in b)) continue;
        if (b[key] === null) delete doc[key];
        else if (key === 'intakeAdaptive') doc[key] = b[key] as boolean;
        else doc[key] = b[key] as number;
    }
    store.put(space, SETTINGS_COLL, tier, doc);
}

/**
 * The server's gate for D11's resource pair, mirrored: a stated `duckdbMemoryLimit` must match the size
 * grammar (blank/null = clear), a stated `maxConcurrentJobRuns` must be an int in 0..100000. Run BEFORE
 * any write — the server validates the whole body before persisting anything.
 *
 * <p>⚠ Until this existed the mock accepted `"lots"` where the server 422s, so the offline preview
 * green-lit a value the backend refuses — exactly the "a mock must never be more lenient than the
 * server" failure that hid a broken adoption for two slices.
 */
export function schedulerResourceRefusal(body: unknown): ReturnType<typeof error> | null {
    const b = (body ?? {}) as Record<string, unknown>;
    if ('duckdbMemoryLimit' in b && b['duckdbMemoryLimit'] !== null) {
        const v = String(b['duckdbMemoryLimit'] ?? '').trim();
        if (v && !new RegExp(SCHEDULER_MEMORY_LIMIT_PATTERN, 'i').test(v))
            return error(
                422,
                'duckdbMemoryLimit must be a DuckDB size string (e.g. 2GB) or a percentage of host RAM in 1..100% (e.g. 80%)',
            );
    }
    if ('maxConcurrentJobRuns' in b && b['maxConcurrentJobRuns'] !== null) {
        const raw = b['maxConcurrentJobRuns'];
        const v = typeof raw === 'number' && Number.isInteger(raw) ? raw : Number.NaN;
        if (Number.isNaN(v) || v < 0 || v > SCHEDULER_MAX_CAP)
            return error(422, `maxConcurrentJobRuns must be 0..${SCHEDULER_MAX_CAP}, got ${String(raw)}`);
    }
    return null;
}

/** Apply the resource-pair merge onto the tier's stored doc (same per-key rule as the cadence: key
 *  absent = preserve, explicit `null` or blank = clear, stated = set). Call
 *  {@link schedulerResourceRefusal} first — this assumes a valid body. */
export function writeSchedulerResources(store: MockStore, space: string, tier: string, body: unknown): void {
    const b = (body ?? {}) as Record<string, unknown>;
    const doc = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    if (!doc) return;
    if ('duckdbMemoryLimit' in b) {
        const v = String(b['duckdbMemoryLimit'] ?? '').trim();
        if (v) doc.duckdbMemoryLimit = v;
        else delete doc.duckdbMemoryLimit; // blank clears — the server's isBlank() → null rule
    }
    if ('maxConcurrentJobRuns' in b) {
        if (b['maxConcurrentJobRuns'] === null) delete doc.maxConcurrentJobRuns;
        else doc.maxConcurrentJobRuns = b['maxConcurrentJobRuns'] as number;
    }
    store.put(space, SETTINGS_COLL, tier, doc);
}

/** The `/settings/scheduler` wire shape (the bound space's tier). Offline the launch default is 60s,
 *  so the effective cadences are stored-or-60 — mirroring the server's inherit semantics. */
export function schedulerSpaceShape(store: MockStore, space: string): Record<string, unknown> {
    const doc = store.get<SchedulerDoc>(space, SETTINGS_COLL, 'scheduler-space');
    const effPoll = doc?.pollSeconds ?? 60;
    return {
        id: space,
        effectivePollSeconds: effPoll,
        effectiveAcquirePollSeconds: doc?.acquirePollSeconds ?? effPoll,
        maxConcurrentConsignments: doc?.maxConcurrentConsignments ?? 0,
        pollSeconds: doc?.pollSeconds ?? null,
        acquirePollSeconds: doc?.acquirePollSeconds ?? null,
        // Provenance follows the cap KEY, never doc presence — a cadence-only doc leaves the cap default.
        source: doc?.maxConcurrentConsignments != null ? 'file' : 'default',
    };
}

/** An explicit `/spaces/<id>/settings/…` call targets that space; otherwise the active space applies. */
const spaceOf = (url: string, active: string): string => url.match(/\/spaces\/([^/]+)\/settings\//)?.[1] ?? active;

export function settingsHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockStudio) return undefined;
        const { method, url } = req;
        const space = spaceOf(url, req.space);

        if (method === 'GET' && GEO.test(url)) {
            return json(store.get<GeoSettingsDoc>(space, SETTINGS_COLL, 'geo') ?? { id: 'geo', tileServerUrl: null });
        }
        if (method === 'PUT' && GEO.test(url)) {
            const b = (req.body ?? {}) as Partial<GeoSettingsDoc>;
            const doc: GeoSettingsDoc = { id: 'geo', tileServerUrl: b.tileServerUrl?.trim() || null };
            return json(store.put(space, SETTINGS_COLL, doc.id, doc));
        }

        if (method === 'GET' && BRANDING.test(url)) {
            return json(
                store.get<BrandingDoc>(space, SETTINGS_COLL, 'branding') ?? {
                    id: 'branding',
                    logoDataUrl: null,
                    caption: null,
                    footerText: null,
                },
            );
        }
        if (method === 'PUT' && BRANDING.test(url)) {
            const b = (req.body ?? {}) as Partial<BrandingDoc>;
            const doc: BrandingDoc = {
                id: 'branding',
                logoDataUrl: clean(b.logoDataUrl),
                caption: clean(b.caption),
                footerText: clean(b.footerText),
            };
            return json(store.put(space, SETTINGS_COLL, doc.id, doc));
        }

        // Consignment-concurrency: the bound space's cap (scheduler-system-config plan Part B).
        if (method === 'GET' && SCHEDULER.test(url)) {
            return json(schedulerSpaceShape(store, space));
        }
        if (method === 'PUT' && SCHEDULER.test(url)) {
            // Validate the WHOLE body before any write — the server is atomic on 422.
            const cadenceRefusal = schedulerCadenceRefusal(req.body);
            if (cadenceRefusal) return cadenceRefusal;
            const { refusal } = writeSchedulerCap(store, space, 'scheduler-space', req.body);
            if (refusal) return refusal;
            writeSchedulerCadence(store, space, 'scheduler-space', req.body);
            return json(schedulerSpaceShape(store, space));
        }

        return undefined;
    };
}
