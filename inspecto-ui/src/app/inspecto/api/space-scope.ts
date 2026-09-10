import { environment } from '../../../environments/environment';

/**
 * Server-global API paths that address the container/runtime, never a single space, so they must
 * NOT be space-prefixed. The `/spaces` group also covers `/spaces/_meta` and every per-space
 * `/spaces/{id}/…` call (export/import/datasources) — those already carry their space id explicitly.
 */
// '/public' (BI-6): share-token routes are anonymous and token-addressed — a space prefix would 404 them.
// '/exchange' (§3 sharing): installation-scope like '/spaces' — owner/consumer travel in the payload.
export const SERVER_GLOBAL = [
    '/health',
    '/ready',
    '/metrics',
    '/spaces',
    '/bootstrap',
    '/auth',
    '/public',
    '/exchange',
];

/**
 * Apply the active space's prefix to one already-built API URL: `/api/v1/<rest>` →
 * `/api/v1/spaces/<id>/<rest>`. Returns the URL UNCHANGED when there is no active space (so
 * single-tenant behaviour is byte-identical), when it is not a ControlApi call (assets, i18n), and for
 * the server-global paths above.
 *
 * 🔴 **Why this is a standalone function and not inlined in the interceptor.** `spaceInterceptor` is an
 * `HttpInterceptorFn`, so it only runs for `HttpClient` requests — and **`EventSource` does not go
 * through `HttpClient`**. Every server-sent-events consumer therefore bypasses the interceptor entirely
 * and must apply this rule itself, or it subscribes to the WRONG space's stream (the unprefixed path,
 * which the backend binds to its default space). Found 2026-09-10 while wiring the Events pane to
 * `/signals/stream`; the notifications stream had the same latent defect since it shipped, invisible in
 * single-space deployments because there the rule is a no-op.
 *
 * ⛔ Keep this the ONE statement of the rule. The interceptor and every stream URL call it, so a change
 * to what counts as server-global cannot drift between the two transports — the mirrored-map failure this
 * repository has already paid for four times.
 */
export function spaceScopedUrl(url: string, spaceId: string | null | undefined): string {
    if (!spaceId) return url;

    // W7: apiUrl() builds '/api/v1/…'; the space id goes AFTER the version segment (the backend
    // strips '/api/v1' at dispatch, then matches '/spaces/{id}/…'). Legacy '/api/…' callers keep
    // the unversioned rewrite.
    let base = environment.apiBaseUrl; // '/api'
    if (url.startsWith(base + '/v1/')) base += '/v1';
    else if (!url.startsWith(base + '/')) return url; // not a ControlApi call

    const rest = url.substring(base.length); // e.g. '/pipelines'
    if (SERVER_GLOBAL.some((p) => rest === p || rest.startsWith(p + '/'))) return url;

    return `${base}/spaces/${encodeURIComponent(spaceId)}${rest}`;
}
