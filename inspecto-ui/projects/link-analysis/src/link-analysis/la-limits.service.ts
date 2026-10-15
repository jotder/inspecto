import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Observable, tap } from 'rxjs';
// eslint-disable-next-line no-restricted-imports -- only `actor`, to namespace the My browser values per user; LA_FEATURES carries module flags, not identity
import { SessionService, SpacesService, apiErrorMessage, apiUrl } from '@inspecto/core/api';
import {
    LA_LIMIT_FALLBACKS,
    LaLimitItem,
    LaLimitKey,
    LaLimitsResponse,
    clampLimit,
    configureLaLimits,
    resetLaLimits,
} from './la-limits';

/** The browser's own values, per Space and per user (plan §6.3 "My browser"). */
const PERSONAL_PREFIX = 'inspecto.la.limits.personal';

/**
 * The Link Analysis limits in force (LA-CONFIG-LIMITS-1 P1): `GET /settings/link-analysis/limits` per Space, the
 * analyst's own "My browser" layer over it, and the two saves.
 *
 * <p><b>Two layers.</b> The Space (and the install) set every limit; the server enforces them. For an item marked
 * `browser:true` the analyst may keep a personal value in this browser — `localStorage`, namespaced by Space and user,
 * clamped to `[min, the Space's value]` — so a slow laptop can draw less without asking an admin. {@link effective}
 * is that personal value, else the Space's; {@link inForce} is always the Space's.
 *
 * <p>Whatever this browser should use is pushed into the `la-limits` store ({@link configureLaLimits}), which the
 * pure modules read. ⚠ A Space change resets that store to the shipped fallbacks BEFORE the next answer, and a failed
 * GET leaves it there: keeping the previous Space's limits would tune one Space by another's settings.
 */
@Injectable({ providedIn: 'root' })
export class LaLimitsService {
    private readonly http = inject(HttpClient);
    private readonly spaces = inject(SpacesService);
    private readonly session = inject(SessionService);

    /** What the server last said for the active Space, in its order (empty until it answers, and after a failure). */
    readonly items = signal<LaLimitItem[]>([]);
    /** The server says its install items apply to this server (pod) only (`metadata.podScoped`). */
    readonly podScoped = signal(false);
    readonly loading = signal(false);
    /** Why the last GET failed; the values in use are then the shipped fallbacks. */
    readonly loadError = signal<string | null>(null);
    private readonly personalValues = signal<Record<string, number>>({});
    private readonly byKey = computed(() => new Map(this.items().map((i) => [i.key, i])));
    private readonly storageKey = computed(
        () => `${PERSONAL_PREFIX}.${this.spaces.currentSpaceId() ?? 'default'}.${this.session.actor() ?? 'local'}`,
    );

    constructor() {
        effect(() => {
            this.spaces.currentSpaceId(); // track: the limits are per Space
            untracked(() => this.reload());
        });
        effect(() => {
            const key = this.storageKey(); // track: per Space and per user
            untracked(() => {
                this.personalValues.set(readPersonal(key));
                this.publish();
            });
        });
    }

    /** Re-read the active Space's limits (after a settings save elsewhere changed one). */
    reload(): void {
        this.items.set([]);
        this.publish();
        this.loading.set(true);
        this.http.get<LaLimitsResponse>(apiUrl('/settings/link-analysis/limits')).subscribe({
            next: (r) => {
                this.loading.set(false);
                this.loadError.set(null);
                this.apply(r);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The Link Analysis limits did not load; the shipped defaults apply.'));
            },
        });
    }

    /** The item the server published under `key`, if any. */
    item(key: string): LaLimitItem | undefined {
        return this.byKey().get(key);
    }

    /** The Space's value in force (the server's), else the shipped fallback. */
    inForce(key: LaLimitKey): number {
        return this.item(key)?.inForce ?? LA_LIMIT_FALLBACKS[key];
    }

    /** The value this browser uses: the analyst's own for a `browser:true` item, clamped to the Space's; else the Space's. */
    effective(key: LaLimitKey): number {
        return this.personal(key) ?? this.inForce(key);
    }

    /** The analyst's own value for a `browser:true` item, clamped as it would be used; null = the Space value. */
    personal(key: string): number | null {
        const item = this.item(key);
        const p = this.personalValues()[key];
        if (!item?.browser || p === undefined || item.inForce === null) return null;
        return clampLimit(p, item.min, item.inForce);
    }

    /** Keep a personal value for a `browser:true` item (clamped to `[min, Space value]`); null clears it. */
    setPersonal(key: string, value: number | null): void {
        const item = this.item(key);
        if (value === null) return this.resetPersonal(key);
        if (!item?.browser || item.inForce === null || !Number.isFinite(value)) return;
        this.writePersonal({ ...this.personalValues(), [key]: clampLimit(Math.floor(value), item.min, item.inForce) });
    }

    /** Back to the Space value. */
    resetPersonal(key: string): void {
        const next = { ...this.personalValues() };
        delete next[key];
        this.writePersonal(next);
    }

    /** `PUT /settings/link-analysis/limits` — SPACE keys only, `null` = inherit; all or nothing (422 names the key). */
    saveSpace(body: Record<string, number | null>): Observable<LaLimitsResponse> {
        return this.http
            .put<LaLimitsResponse>(apiUrl('/settings/link-analysis/limits'), body)
            .pipe(tap((r) => this.apply(r)));
    }

    /** `PUT /system/link-analysis/limits` — INSTALL keys only, system admin; same rules. */
    saveInstall(body: Record<string, number | null>): Observable<LaLimitsResponse> {
        return this.http
            .put<LaLimitsResponse>(apiUrl('/system/link-analysis/limits'), body)
            .pipe(tap((r) => this.apply(r)));
    }

    private apply(r: LaLimitsResponse | null): void {
        this.items.set(r?.items ?? []);
        this.podScoped.set(r?.metadata?.podScoped === true);
        this.publish();
    }

    private writePersonal(next: Record<string, number>): void {
        this.personalValues.set(next);
        try {
            if (Object.keys(next).length) localStorage.setItem(this.storageKey(), JSON.stringify(next));
            else localStorage.removeItem(this.storageKey());
        } catch {
            // storage blocked or full: the value holds for this page only
        }
        this.publish();
    }

    /** Push what this browser uses into the store the pure modules read. */
    private publish(): void {
        resetLaLimits();
        const out: Partial<Record<LaLimitKey, number>> = {};
        for (const key of Object.keys(LA_LIMIT_FALLBACKS) as LaLimitKey[]) {
            const inForce = this.item(key)?.inForce;
            if (inForce != null) out[key] = this.personal(key) ?? inForce;
        }
        configureLaLimits(out);
    }
}

/** The stored personal map; anything unreadable or not a finite number is dropped (absent = the Space value). */
function readPersonal(key: string): Record<string, number> {
    try {
        const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '{}');
        const out: Record<string, number> = {};
        if (parsed && typeof parsed === 'object')
            for (const [k, v] of Object.entries(parsed)) if (typeof v === 'number' && Number.isFinite(v)) out[k] = v;
        return out;
    } catch {
        return {};
    }
}
