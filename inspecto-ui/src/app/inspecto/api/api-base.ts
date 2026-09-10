import { HttpErrorResponse, HttpParams } from '@angular/common/http';
import { environment } from '../../../environments/environment';

/**
 * Prefix an API route path with the configured base ('' in prod, '/api' behind the dev proxy) plus
 * the `/v1` version segment (W7 — every route is dispatched under `/api/v1` with envelope shaping;
 * the `v1Interceptor` unwraps, so callers still see plain DTOs).
 */
export function apiUrl(path: string): string {
    return `${environment.apiBaseUrl}/v1${path}`;
}

/**
 * Extract a human-readable message from a failed API call.
 *
 * v1 routes report errors as `{ error: { errorCode, message, … } }` (the structured ErrorObject);
 * legacy routes as `{ "error": "…" }` — both shapes are handled (the legacy one survives in the
 * mock layer's history and any unversioned caller). When the response isn't JSON at all — e.g. the
 * static SPA fallback returns `index.html` (misrouted path, dev proxy off, or an auth redirect) —
 * Angular's HttpClient fails to parse it and sets `err.error` to
 * `{ error: <SyntaxError>, text: '<!doctype …' }`. Surfacing that raw SyntaxError
 * ("Unexpected token '<'…") to the user is meaningless, so we detect the non-JSON case and return
 * the caller's fallback instead.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
        // A status-0 / parse failure means we never got a real JSON body — use the fallback.
        if (err.status === 0) return fallback;
        const e = err.error?.error;
        if (typeof e === 'string' && e.trim()) return e; // legacy: { error: 'msg' }
        const msg = e?.message; // v1: { error: { message, … } }
        if (typeof msg === 'string' && msg.trim()) return msg;
        return fallback;
    }
    return fallback;
}

/**
 * True when a failed write was refused because the resource changed since it was read — the
 * `409 CONFLICT_STALE_VERSION` an `If-Match` precondition produces (`CLIENT-HALVES-1` (a)).
 *
 * ⚠ Matches on the **error code, not the status**: plain `409 CONFLICT` is a different refusal (the
 * existence check on a non-overwrite write, or a delete blocked by dependents) and must not be reported
 * as a concurrent edit. ⛔ There is no `412` in this product — do not test for one.
 */
export function isStaleVersionError(err: unknown): boolean {
    return (
        err instanceof HttpErrorResponse &&
        err.status === 409 &&
        err.error?.error?.errorCode === 'CONFLICT_STALE_VERSION'
    );
}

/** What to tell an author whose save lost a race — one wording, so every pane says the same thing. */
export const STALE_WRITE_MESSAGE =
    'This config changed underneath you — someone else saved it while you were editing. ' +
    'Reload to get their version before saving again.';

/** Build HttpParams from a plain object, skipping null/undefined/'' values. */
export function toParams(obj: Record<string, unknown>): HttpParams {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(obj)) {
        if (v !== undefined && v !== null && v !== '') {
            p = p.set(k, Array.isArray(v) ? v.join(',') : String(v));
        }
    }
    return p;
}
