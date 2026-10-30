/**
 * Post-sign-in return path. The guard / session-loss hooks record where the user was headed; sign-in
 * navigates back there. The value is attacker-influenceable (it comes from a URL), so it is only ever
 * used after {@link safeReturnUrl} proves it is a same-origin, app-relative path - never an open redirect.
 */
const MAX_LENGTH = 2048;
const PENDING_KEY = 'inspecto.signin.return';
const BASE = 'http://return-url.invalid';
const AUTH_PATHS = ['/sign-in', '/auth/callback'];

/** The app-relative path (+ query + fragment) when `raw` is safe to navigate to, else `null`. */
export function safeReturnUrl(raw: string | null | undefined): string | null {
    if (!raw || raw.length > MAX_LENGTH || !raw.startsWith('/') || raw.startsWith('//')) return null;
    let decoded: string;
    try {
        decoded = decodeURIComponent(raw);
    } catch {
        return null;
    }
    // Backslashes and control characters are normalised to '/' or stripped by browsers: `/\evil` becomes `//evil`.
    for (const v of [raw, decoded]) {
        // eslint-disable-next-line no-control-regex -- matching control characters is the point: they are an open-redirect vector
        if (v.includes('\\') || /[\u0000-\u001f\u007f]/.test(v) || v.startsWith('//')) return null;
    }
    let parsed: URL;
    try {
        parsed = new URL(raw, BASE);
    } catch {
        return null;
    }
    if (parsed.origin !== BASE) return null;
    const path = parsed.pathname.toLowerCase();
    if (AUTH_PATHS.some((p) => path === p || path.startsWith(p + '/'))) return null;
    return parsed.pathname + parsed.search + parsed.hash;
}

/** Remember the page the user was sent away from (ignored when unsafe). */
export function recordReturnUrl(url: string): void {
    const safe = safeReturnUrl(url);
    if (!safe) return;
    try {
        sessionStorage.setItem(PENDING_KEY, safe);
    } catch {
        /* storage blocked: the user simply lands on the default page */
    }
}

/** Read and clear the recorded page (consume-once). */
export function takePendingReturnUrl(): string | null {
    try {
        const v = sessionStorage.getItem(PENDING_KEY);
        sessionStorage.removeItem(PENDING_KEY);
        return safeReturnUrl(v);
    } catch {
        return null;
    }
}

/** sessionStorage key binding a return path to one PKCE `state` (never sent to the IdP). */
export const returnKeyForState = (state: string): string => `inspecto.pkce.return.${state}`;
