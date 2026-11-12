import { apiErrorMessage } from '@inspecto/core/api';

/** The longest `Retry-After` the client waits out on its own; a longer one is left to the analyst. */
const MAX_AUTO_RETRY_MS = 10_000;

/** N13: the wait a 429 asked for (its `Retry-After`, whole seconds), or null when it is not a 429, names none or names too long a wait. */
export function retryAfterMs(err: unknown): number | null {
    const e = err as { status?: number; headers?: { get(n: string): string | null } } | null;
    if (e?.status !== 429) return null;
    const secs = Number(e.headers?.get('Retry-After'));
    const ms = secs * 1000;
    return Number.isFinite(ms) && ms > 0 && ms <= MAX_AUTO_RETRY_MS ? ms : null;
}

/**
 * DR-D6 — the two resource-bound refusals every Link Analysis route can answer, in an analyst's words. Returns null
 * for any other status, so each surface's own message function asks this FIRST and carries on with its own table.
 *
 * - 413 (`PAYLOAD_TOO_LARGE`): a Working Set over the Space's `max_set_bytes`, or an Investigation over its
 *   `max_investigation_bytes`. The server's body names the limit and the setting; nothing was stored.
 * - 429 (`RATE_LIMITED`): the per-user Link Analysis rate limit — a burst of 20 requests, then one every 3 seconds.
 */
export function limitRefusalMessage(err: unknown): string | null {
    const status = (err as { status?: number } | null)?.status;
    if (status !== 413 && status !== 429) return null;
    const server = apiErrorMessage(err, '');
    if (status === 413) {
        // The server's tail ("Narrow the step, or raise the limit: set max_set_bytes in ...link-analysis.toon ...") is for an
        // administrator; the analyst's next step is the sentence below, so the file and the setting name stay out of it.
        const said = server.replace(/\s*(Narrow the step, or raise|Fork a smaller Investigation, or raise)[^]*$/, '').replace(/;\s*nothing was stored\.?$/, '.');
        return (
            'That is over a size limit, so nothing was stored. ' +
            (said ? said + ' ' : '') +
            'Next step: narrow the step (fewer seeds, a shorter window) or fork a smaller Investigation; a Space ' +
            'administrator can raise max_set_bytes or max_investigation_bytes.'
        );
    }
    return (
        'Too many requests. Link Analysis allows each user a burst of 20 requests, then one every 3 seconds. ' +
        'Wait a few seconds and try again.'
    );
}
