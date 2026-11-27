import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, concatMap, first, forkJoin, from, map, Observable, of, switchMap, tap } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { ShortReads } from './short-reads';

/**
 * A hosted space (project) manifest — GET /spaces returns these (camelCase, server-side).
 * One Inspecto server hosts many isolated spaces; the active space scopes every other API call
 * (see {@link spaceInterceptor}).
 */
export interface Space {
    id: string;
    displayName: string;
    description: string;
    createdAt: string;
    /** The Space lock's counts (LC-4), only when the caller may read that Space's `/integrity`. */
    integrity?: SpaceIntegritySummary;
    /** Space admission (`closed` when only creator, administrators, and Space-role holders are admitted). */
    admission?: string;
    /** Subject id of the signed-in person who created a closed Space. */
    createdBy?: string;
    /** Where the Space lives on disk (SPACE-LOCATIONS-1 D8): sent only to a caller who may administer Spaces. */
    location?: SpaceLocation;
}

/** `GET /spaces` row `location`: the Space folder and Data folder, and what may be changed about them. */
export interface SpaceLocation {
    spaceFolder?: string;
    dataFolder?: string;
    /** Kept outside the Spaces root and attached by reference: only such a Space can be detached. */
    referenced?: boolean;
    /** Whether the Space already holds data: its Data folder can be changed only while it holds none. */
    holdsData?: boolean;
}

/** What a folder is checked for: a Space folder or a Data folder. */
export type SpaceLocationKind = 'space' | 'data';

/** One check of a folder: its id (`allowed`, `install`, `overlap`, …), passed or not, and the server's reason. */
export interface SpaceLocationCheck {
    check: string;
    ok: boolean;
    reason?: string;
}

/** `POST /spaces/_locations/check` answer: nothing is written; `ok` is true only when every check passes. */
export interface SpaceLocationCheckResult {
    path?: string;
    kind?: SpaceLocationKind;
    ok: boolean;
    checks: SpaceLocationCheck[];
}

/** The plain words for each check id the server runs, in its order; an unknown id is shown as it comes. */
export const SPACE_LOCATION_CHECK_LABELS: Readonly<Record<string, string>> = {
    allowed: 'In a folder the server allows',
    install: 'Outside the installation',
    overlap: 'Not inside another Space',
    name: 'Named after a free Space id',
    reachable: 'Exists or can be created',
    writable: 'The server can write to it',
    'free-space': 'Enough free disk space',
    stamp: 'Not claimed by another Space',
    content: 'Holds what it should',
};

/** The plain label of a check id. */
export function spaceLocationCheckLabel(check: string): string {
    return SPACE_LOCATION_CHECK_LABELS[check] ?? check;
}

/** The error code the folder routes answer (409) when the server has no allowed folders configured. */
export const SPACE_LOCATIONS_DISABLED = 'SPACE_LOCATIONS_DISABLED';

/** What a person reads when folder choices are switched off on this server. */
export const SPACE_LOCATIONS_DISABLED_MESSAGE =
    'Choosing folders is switched off on this server. The administrator must set spaces.allowed.roots ' +
    '(or the SPACES_ALLOWED_ROOTS environment variable) to the folders Spaces may use, and restart the server.';

/** The server error code inside a v1 error body (`{ error: { errorCode } }`), else ''. */
export function spaceErrorCode(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return '';
    const body = err.error as { error?: { errorCode?: unknown; code?: unknown }; errorCode?: unknown } | null;
    const c = body?.error?.errorCode ?? body?.error?.code ?? body?.errorCode;
    return typeof c === 'string' ? c : '';
}

/** True when a folder route was refused because the server has no allowed folders (`spaces.allowed.roots` unset). */
export function isSpaceLocationsDisabled(err: unknown): boolean {
    return err instanceof HttpErrorResponse && err.status === 409 && spaceErrorCode(err) === SPACE_LOCATIONS_DISABLED;
}

/** `GET /spaces` row `integrity`: the totals are present only when `status` is `ready`. */
export interface SpaceIntegritySummary {
    status: 'computing' | 'ready' | 'disabled' | 'unavailable' | 'failed' | string;
    broken?: number;
    duplicates?: number;
    changedOutside?: number;
}

/** POST /spaces request body — note the snake_case keys the ControlApi expects. */
export interface CreateSpaceRequest {
    id: string;
    display_name?: string;
    description?: string;
    /** A Space-Template id ({@link SpaceTemplateInfo}) — the new space is seeded from its blueprint. */
    template?: string;
    /** Also load the template's sample data and run each Pipeline that received files, once (LA-ONBOARD-1 OB-12).
     *  Refused (422) when the template ships no samples ({@link SpaceTemplateInfo.samples}). */
    loadSamples?: boolean;
    /** The pre-created Space folder to create the Space in (SPACE-LOCATIONS-1 D6); omitted = under the Spaces root. */
    path?: string;
    /** The pre-created Data folder for the Space's data; omitted = inside the Space folder. */
    dataRoot?: string;
}

/** One Pipeline's outcome of a `loadSamples` create. */
export interface SampleLoadResult {
    pipeline: string;
    sampleFiles: number;
    status: 'loaded' | 'failed';
    filesLoaded: number;
    filesQuarantined: number;
    /** Why the Pipeline failed, in the server's words. */
    error?: string;
}

/** `POST /spaces {…, loadSamples: true}` answer: the manifest plus each Pipeline's sample run. */
export interface SampleSpace extends Space {
    samples?: { pipelines: SampleLoadResult[] };
}

/**
 * A **Space Template** manifest — GET /spaces/templates. A reusable blueprint bundle of Components
 * (Connections → Sources → Pipelines → Datasets → Widgets → Dashboards → Rules + sample data) that
 * instantiates a new Space (Template is the Type, the Space the Instance — docs/GLOSSARY.md).
 */
export interface SpaceTemplateInfo {
    id: string;
    name: string;
    /** One-line vertical pitch, shown on the gallery card (also the created space's default description). */
    tagline: string;
    description: string;
    /** Heroicons name for the gallery card. */
    icon: string;
    /** Human summary of the blueprint's contents, rendered as card chips. */
    contents: string[];
    /** Whether THIS server's edition can create a Space from it (`POST /spaces` would otherwise 422). */
    creatable?: boolean;
    /** The edition features the template uses that this build lacks — why it is not creatable. */
    missingFeatures?: { feature: string; message: string }[];
    /** Whether it ships sample data, so `loadSamples` can load and run it (LA-ONBOARD-1 OB-12). */
    samples?: boolean;
}

/** DELETE /spaces/{id} response. */
export interface DeleteSpaceResult {
    id: string;
    deleted: boolean;
    purged: boolean;
}

/**
 * One connection a bundle needs that the target space lacks (2026-09-25: the import no longer refuses —
 * every config needing it lands disabled, and this names them: "connect X to enable").
 */
export interface ConnectionWarning {
    connection: string;
    code: string;
    message: string;
    disabled: { kind: string; name: string; file: string }[];
}

/** POST /spaces/{id}/import success body — what was unpacked + made live. */
export interface BundleImportResult {
    kind: string;
    imported: string[];
    pipelines: string[];
    overwritten: boolean;
    connectionWarnings?: ConnectionWarning[];
}

/** One structural-validation finding for a bundle file (ERROR fails the import preview). */
export interface PreviewFinding {
    severity: string; // 'ERROR' | 'WARNING'
    fieldPath: string;
    message: string;
}

/**
 * POST /spaces/{id}/import/preview — a dry-run that parses a bundle and reports what *would* happen
 * without writing: the data sources + files it carries, id conflicts with the target space, and
 * per-file structural findings. `valid` is false iff any finding is an ERROR.
 */
export interface ImportPreview {
    kind: string;
    sourceSpace: string | null;
    dataSources: string[];
    files: string[];
    hasSpaceToon: boolean;
    conflicts: string[];
    findings: Record<string, PreviewFinding[]>;
    /** The same warnings the commit will return — the configs it will land disabled. */
    connectionWarnings?: ConnectionWarning[];
    valid: boolean;
}

/** `base`, or `base-2`, `base-3`, … — the first not in `taken`. */
export function uniqueSpaceId(base: string, taken: readonly string[]): string {
    const used = new Set(taken);
    if (!used.has(base)) return base;
    let n = 2;
    while (used.has(`${base}-${n}`)) n++;
    return `${base}-${n}`;
}

const STORAGE_KEY = 'inspecto.currentSpace';
/** Where the active space id persists — shared with DI-light consumers (e.g. `GridStateService`). */
export const SPACE_STORAGE_KEY = STORAGE_KEY;

/**
 * Holds the global multi-space state (active space + the hosted list) as signals, and wraps the
 * server-global `/spaces` CRUD + per-space bundle export/import endpoints.
 *
 * The active space scopes every other API call: {@link spaceInterceptor} reads {@link currentSpaceId}
 * and rewrites `/api/<path>` → `/api/spaces/<id>/<path>`. A `null` active space means "no prefix"
 * (single-tenant / default namespace) so single-space behaviour is byte-identical. The id is restored
 * from `localStorage` in the constructor — synchronously, before the first HTTP call — so the very
 * first request already carries the right space.
 *
 * The `/spaces`, `/spaces/_meta` and per-space `/spaces/{id}/…` endpoints here are all addressed
 * literally (they start with `/spaces`), so the interceptor leaves them untouched — no double-prefix.
 */
@Injectable({ providedIn: 'root' })
export class SpacesService {
    private http = inject(HttpClient);
    private reads = inject(ShortReads);

    /** Every hosted space's manifest (sorted by id, server-side). */
    readonly availableSpaces = signal<Space[]>([]);
    /** True once {@link refresh} has resolved at least once for the current session. */
    readonly loaded = signal(false);
    /** Whether this server is the CRUD-capable discover runtime (GET /spaces/_meta). */
    readonly multiSpace = signal(false);
    /** True when `GET /spaces/_meta` says `locationsEnabled: false`, or a folder route answered
     *  {@link SPACE_LOCATIONS_DISABLED}: the folder actions are hidden. */
    readonly locationsDisabled = signal(false);
    /** The active space id, or null for the un-prefixed default namespace. */
    readonly currentSpaceId = signal<string | null>(this.restore());

    /** The active space's manifest, when it is in the loaded list. */
    readonly currentSpace = computed<Space | null>(
        () => this.availableSpaces().find((s) => s.id === this.currentSpaceId()) ?? null,
    );
    /** Show the switcher only on a multi-space server that has spaces to switch between. */
    readonly showSwitcher = computed(() => this.multiSpace() && this.availableSpaces().length > 0);

    constructor() {
        this.reads.bindSpace(() => this.currentSpaceId() ?? '');
    }

    /**
     * Load the capability flag + space list and reconcile the active selection: keep a still-valid
     * saved id, otherwise fall back to `default` (if present) or the first space; clear it entirely on a
     * single-tenant server. Degrades to single-tenant on any error so the rest of the app keeps working.
     */
    refresh(): Observable<Space[]> {
        // N5: the shell's switcher and the landing both refresh at once - one pair of reads, not two (global URLs).
        return this.reads.read('spaces:refresh', () => this.loadAndReconcile(), false);
    }

    private loadAndReconcile(): Observable<Space[]> {
        return forkJoin({
            meta: this.http
                .get<{ multiSpace: boolean; locationsEnabled?: boolean }>(apiUrl('/spaces/_meta'))
                .pipe(catchError(() => of({ multiSpace: false, locationsEnabled: undefined }))),
            // `catchError` covers a FAILED request; a 200 carrying an unexpected shape used to reach
            // `spaces.some(...)` and throw during bootstrap, cascading into an NG0201 injector error.
            // ⚠ This is containment, NOT a fix — the root cause of an observed non-array is still
            // unknown (a stale deploy bundle is the leading suspect), so it warns loudly rather than
            // trading a crash for a silent wrong state. See docs/BACKLOG.md §6.
            spaces: this.http.get<Space[]>(apiUrl('/spaces')).pipe(
                map((s) => {
                    if (Array.isArray(s)) return s;
                    console.warn('[spaces] GET /spaces returned a non-array body; treating as no spaces', s);
                    return [] as Space[];
                }),
                catchError(() => of([] as Space[])),
            ),
        }).pipe(
            tap(({ meta, spaces }) => {
                this.multiSpace.set(meta.multiSpace);
                // SPACE-LOCATIONS-1: `spaces.allowed.roots` unset ⇒ the folder routes answer 409; say so up front.
                this.locationsDisabled.set(meta.locationsEnabled === false);
                this.availableSpaces.set(spaces);
                this.loaded.set(true);
            }),
            switchMap(({ meta, spaces }) =>
                this.fallbackSpace(meta.multiSpace, spaces).pipe(
                    tap((fallback) => this.reconcile(meta.multiSpace, spaces, fallback)),
                    map(() => spaces),
                ),
            ),
        );
    }

    /** Set the active space (or null for the default namespace) and persist it across reloads. */
    selectSpace(id: string | null): void {
        this.currentSpaceId.set(id);
        if (typeof localStorage === 'undefined') return;
        if (id) localStorage.setItem(STORAGE_KEY, id);
        else localStorage.removeItem(STORAGE_KEY);
    }

    /** Clear the previous session's Space roster so a newly signed-in user never inherits it before refresh. */
    resetForSession(clearSelection = false): void {
        this.availableSpaces.set([]);
        this.loaded.set(false);
        this.reads.clear();
        if (clearSelection) this.selectSpace(null);
    }

    // ── server-global CRUD (/spaces) ─────────────────────────────────────────────

    /** Create + boot a space (multi-space servers only). 400 bad id, 409 dup / single-tenant. */
    create(req: CreateSpaceRequest): Observable<Space> {
        return this.http
            .post<Space>(apiUrl('/spaces'), req)
            .pipe(tap({ next: () => this.reads.clear(), error: (e) => this.noteLocationsDisabled(e) }));
    }

    /**
     * Create a Space from a template that ships samples, load them and run each Pipeline once (OB-12). The id is
     * derived from `baseId`, made unique against the hosted Spaces (`sample`, `sample-2`, …).
     */
    createSample(template: string, displayName: string, baseId = 'sample'): Observable<SampleSpace> {
        const id = uniqueSpaceId(
            baseId,
            this.availableSpaces().map((s) => s.id),
        );
        return this.http
            .post<SampleSpace>(apiUrl('/spaces'), {
                id,
                display_name: displayName,
                template,
                loadSamples: true,
            } satisfies CreateSpaceRequest)
            .pipe(tap(() => this.reads.clear()));
    }

    /**
     * Set a Space's Domain Profile (the Industry pack Link Analysis reads it in; `null` = the neutral vocabulary).
     * Addressed to the named Space, so it works for a Space just created and not yet active.
     */
    setDomainProfile(spaceId: string, domainProfile: string | null): Observable<unknown> {
        return this.http
            .put(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/link-analysis/domain-profile`), {
                domainProfile,
            })
            .pipe(tap(() => this.reads.clear()));
    }

    /**
     * Turn Add data on or off in a Space (`PUT /spaces/{id}/settings/data`; the server asks `canAdminister`).
     * Addressed to the named Space, so it works for a Space just created and not yet active.
     */
    setSelfImport(spaceId: string, selfImport: boolean): Observable<unknown> {
        return this.http
            .put(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/data`), { selfImport })
            .pipe(tap(() => this.reads.clear()));
    }

    /** Update a space's display name / description (the id/folder is immutable). 400 for `default`, 404 unknown. */
    update(id: string, patch: { display_name?: string; description?: string }): Observable<Space> {
        return this.http
            .put<Space>(apiUrl(`/spaces/${encodeURIComponent(id)}`), patch)
            .pipe(tap(() => this.reads.clear()));
    }

    /** The Space-Template catalog (blueprint bundles "New space from template" instantiates). */
    templates(): Observable<SpaceTemplateInfo[]> {
        return this.http.get<SpaceTemplateInfo[]>(apiUrl('/spaces/templates'));
    }

    /** Deregister + drain a space; `purge` also deletes its files. 404 unknown, 409 single-tenant. */
    remove(id: string, purge: boolean): Observable<DeleteSpaceResult> {
        return this.http
            .delete<DeleteSpaceResult>(apiUrl(`/spaces/${encodeURIComponent(id)}`), {
                params: toParams({ purge: purge ? 'true' : '' }),
            })
            .pipe(tap(() => this.reads.clear()));
    }

    /** Create + boot a brand-new space seeded from a bundle zip (server-global). 400 bad id/bundle, 409 dup. */
    createFromBundle(id: string, zip: Blob): Observable<Space> {
        return this.http
            .post<Space>(apiUrl('/spaces/import'), zip, {
                params: toParams({ id }),
                headers: { 'Content-Type': 'application/zip' },
            })
            .pipe(tap(() => this.reads.clear()));
    }

    // ── Space folder and Data folder (SPACE-LOCATIONS-1) ──────────────────────────

    /** Check a folder before it is used; nothing is written. */
    checkLocation(path: string, kind: SpaceLocationKind, id?: string): Observable<SpaceLocationCheckResult> {
        return this.http
            .post<SpaceLocationCheckResult>(apiUrl('/spaces/_locations/check'), { path, kind, ...(id ? { id } : {}) })
            .pipe(tap({ error: (e) => this.noteLocationsDisabled(e) }));
    }

    /** Attach a Space folder kept outside the Spaces root and start the Space. */
    attach(path: string, id?: string): Observable<Space> {
        return this.http
            .post<Space>(apiUrl('/spaces/attach'), { path, ...(id ? { id } : {}) })
            .pipe(tap({ next: () => this.reads.clear(), error: (e) => this.noteLocationsDisabled(e) }));
    }

    /** Stop an attached Space and drop its reference; its Space folder and Data folder stay on disk. */
    detach(id: string): Observable<{ id: string; detached: boolean }> {
        return this.http
            .post<{ id: string; detached: boolean }>(apiUrl(`/spaces/${encodeURIComponent(id)}/detach`), {})
            .pipe(tap({ next: () => this.reads.clear(), error: (e) => this.noteLocationsDisabled(e) }));
    }

    /** Point a Space that holds no data yet at a Data folder; `null` puts the data back in the Space folder. */
    setDataRoot(id: string, path: string | null): Observable<Space> {
        return this.http
            .put<Space>(apiUrl(`/spaces/${encodeURIComponent(id)}/data-root`), { path })
            .pipe(tap({ next: () => this.reads.clear(), error: (e) => this.noteLocationsDisabled(e) }));
    }

    private noteLocationsDisabled(e: unknown): void {
        if (isSpaceLocationsDisabled(e)) this.locationsDisabled.set(true);
    }

    // ── per-space data sources + export/import (/spaces/{id}/…) ───────────────────

    /** The data-source (pipeline) ids hosted by a space. */
    dataSources(spaceId: string): Observable<string[]> {
        return this.http.get<string[]>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/datasources`));
    }

    /** Download one data source's config bundle as a zip. */
    exportDataSource(spaceId: string, ds: string): Observable<Blob> {
        return this.http.get(
            apiUrl(`/spaces/${encodeURIComponent(spaceId)}/datasources/${encodeURIComponent(ds)}/export`),
            { responseType: 'blob' },
        );
    }

    /** Download a whole space's config as a zip. */
    exportSpace(spaceId: string): Observable<Blob> {
        return this.http.get(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/export`), {
            responseType: 'blob',
        });
    }

    /** Dry-run a bundle import into a space — what would be written, conflicts, findings. No writes. */
    importPreview(spaceId: string, zip: Blob): Observable<ImportPreview> {
        return this.http.post<ImportPreview>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/import/preview`), zip, {
            headers: { 'Content-Type': 'application/zip' },
        });
    }

    /** Import a bundle into an existing space. 409 {error, conflicts} unless `overwrite`. */
    importBundle(spaceId: string, zip: Blob, overwrite: boolean): Observable<BundleImportResult> {
        return this.http
            .post<BundleImportResult>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/import`), zip, {
                params: toParams({ on_conflict: overwrite ? 'overwrite' : '' }),
                headers: { 'Content-Type': 'application/zip' },
            })
            .pipe(tap(() => this.reads.clear()));
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private restore(): string | null {
        if (typeof localStorage === 'undefined') return null;
        return localStorage.getItem(STORAGE_KEY);
    }

    /**
     * D7: with no still-valid saved choice and no `default` Space, the first Space alphabetically used to win even
     * when the signed-in subject holds nothing there. Ask each Space's own bootstrap (capped) and take the first
     * where the subject holds any capability; null = no preference (fall back to the first Space).
     */
    private fallbackSpace(multiSpace: boolean, spaces: Space[]): Observable<string | null> {
        const saved = this.currentSpaceId();
        if (!multiSpace || (saved && spaces.some((s) => s.id === saved))) return of(null);
        if (spaces.length < 2 || spaces.some((s) => s.id === 'default')) return of(null);
        // N5: one Space at a time, in order, stopping at the first the subject holds anything in - the other Spaces'
        // bootstraps (about 78 KB each) are not read at all. The current Space's read is shared with the session's.
        return from(spaces.slice(0, 12)).pipe(
            concatMap((s) =>
                this.spaceBootstrap<{ session?: { capabilities?: string[] } }>(s.id).pipe(
                    map((b) => ((b.session?.capabilities?.length ?? 0) > 0 ? s.id : null)),
                    catchError(() => of(null)),
                ),
            ),
            first((id) => id !== null, null),
        );
    }

    /**
     * One Space's own `GET /spaces/{id}/bootstrap`, shared between the D7 probe and the session's capability read.
     * The URL names its Space, so the answer does not depend on the active one (it does on the user).
     */
    spaceBootstrap<T>(id: string): Observable<T> {
        const url = apiUrl(`/spaces/${encodeURIComponent(id)}/bootstrap`);
        return this.reads.read(url, () => this.http.get<T>(url), false);
    }

    private reconcile(multiSpace: boolean, spaces: Space[], preferred: string | null = null): void {
        if (!multiSpace) {
            this.selectSpace(null); // single-tenant: no prefix, byte-identical default namespace
            return;
        }
        const saved = this.currentSpaceId();
        if (saved && spaces.some((s) => s.id === saved)) return; // still valid — keep it
        const fallback = spaces.find((s) => s.id === 'default')?.id ?? preferred ?? spaces[0]?.id ?? null;
        this.selectSpace(fallback);
    }
}
