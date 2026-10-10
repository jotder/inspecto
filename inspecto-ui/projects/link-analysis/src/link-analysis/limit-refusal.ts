import { apiErrorMessage } from '@inspecto/core/api';

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
    if (status === 413)
        return (
            'That is over a size limit, so nothing was stored. ' +
            (server ? server + ' ' : '') +
            'Next step: narrow the step (fewer seeds, a shorter window) or fork a smaller Investigation; a Space ' +
            'administrator can raise max_set_bytes or max_investigation_bytes.'
        );
    return (
        'Too many requests. Link Analysis allows each user a burst of 20 requests, then one every 3 seconds. ' +
        'Wait a few seconds and try again.' +
        (server ? ' Server: ' + server : '')
    );
}
