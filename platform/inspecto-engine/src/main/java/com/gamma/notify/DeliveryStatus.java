package com.gamma.notify;

/**
 * What a provider told us happened to a message we sent (BACKLOG D8).
 *
 * <p>The hard/soft bounce split is deliberate and load-bearing: a <b>soft</b> bounce is retryable and
 * says nothing about the address being wrong (a full or briefly-unavailable mailbox is not a bad
 * address), whereas a <b>hard</b> bounce means the destination does not exist. Collapsing the two would
 * make any future suppression policy treat a full inbox as a dead address.
 *
 * <p>{@link #UNKNOWN} exists so an unrecognised provider event is <b>recorded with its raw payload</b>
 * rather than dropped silently or guessed at — a new provider event type must never look like a
 * delivery success.
 *
 * @since 4.0.0
 */
public enum DeliveryStatus {

    /** The provider accepted and delivered the message to the destination. */
    DELIVERED,

    /** Permanent failure — the destination does not exist. Not retryable. */
    BOUNCED_HARD,

    /** Transient failure (mailbox full, temporarily unavailable). Retryable; the address is still good. */
    BOUNCED_SOFT,

    /** The recipient marked the message as spam. The only class with a deliverability consequence. */
    COMPLAINED,

    /** An event the adapter recognised as ours but could not classify; the raw payload is kept. */
    UNKNOWN
}
