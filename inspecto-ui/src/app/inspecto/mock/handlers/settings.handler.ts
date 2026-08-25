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

/** Per-tier scheduler document; cap `0` = unbounded, matching `ConcurrencyBroker.UNBOUNDED`.
 *  Cadences (space tier only) are absent when the space inherits the launch defaults. */
export interface SchedulerDoc {
    id: string;
    maxConcurrentConsignments: number;
    pollSeconds?: number;
    acquirePollSeconds?: number;
}

/** The stored cap for one tier (`scheduler-system` | `scheduler-space`), or 0 when never saved. */
export function readSchedulerCap(store: MockStore, space: string, tier: string): number | null {
    const doc = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    return doc ? doc.maxConcurrentConsignments : null;
}

/** Validate + store one tier's cap; returns the refusal response or null when accepted. The real PUT
 *  422s on a missing, non-integer, negative, or over-cap value — all four mirrored here. */
export function writeSchedulerCap(
    store: MockStore,
    space: string,
    tier: string,
    body: unknown,
): { refusal: ReturnType<typeof error> | null; cap: number } {
    const raw = (body as Record<string, unknown> | null)?.['maxConcurrentConsignments'];
    const cap = typeof raw === 'number' && Number.isInteger(raw) ? raw : Number.NaN;
    if (Number.isNaN(cap))
        return { refusal: error(422, 'maxConcurrentConsignments is required (0 = unbounded)'), cap: 0 };
    if (cap < 0 || cap > SCHEDULER_MAX_CAP)
        return { refusal: error(422, `maxConcurrentConsignments must be 0..${SCHEDULER_MAX_CAP}, got ${cap}`), cap: 0 };
    // MERGE with the stored doc — a cap-only PUT must not destroy a stored cadence (the server merges
    // per key; a mock that wipes rehearses the data-loss the backend refuses to commit).
    const existing = store.get<SchedulerDoc>(space, SETTINGS_COLL, tier);
    store.put(space, SETTINGS_COLL, tier, {
        ...existing,
        id: tier,
        maxConcurrentConsignments: cap,
    } satisfies SchedulerDoc);
    return { refusal: null, cap };
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
        source: doc == null ? 'default' : 'file',
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
