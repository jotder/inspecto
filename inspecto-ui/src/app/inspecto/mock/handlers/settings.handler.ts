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

/** Per-tier scheduler cap document; `0` = unbounded, matching `ConcurrencyBroker.UNBOUNDED`. */
export interface SchedulerDoc {
    id: string;
    maxConcurrentConsignments: number;
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
    store.put(space, SETTINGS_COLL, tier, { id: tier, maxConcurrentConsignments: cap } satisfies SchedulerDoc);
    return { refusal: null, cap };
}

/** The `/settings/scheduler` wire shape (the bound space's tier). */
export function schedulerSpaceShape(store: MockStore, space: string): Record<string, unknown> {
    const cap = readSchedulerCap(store, space, 'scheduler-space');
    return {
        id: space,
        maxConcurrentConsignments: cap ?? 0,
        source: cap == null ? 'default' : 'file',
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
            const { refusal } = writeSchedulerCap(store, space, 'scheduler-space', req.body);
            return refusal ?? json(schedulerSpaceShape(store, space));
        }

        return undefined;
    };
}
