package com.gamma.notify;

import com.gamma.api.PublicApi;

import java.util.Optional;

/**
 * Platform Service {@code notifications} (platform-services S1-3): emit into the appUser's in-app
 * feed, honoring the feed's dedupe-collapse contract. Granted to a Run via a Job Type's
 * {@code requires: [notifications]} declaration and looked up as
 * {@code ctx.services().get(NotificationAccess.class)}; the engine itself is the first consumer —
 * {@link NotificationService}'s event dispatch stores through this same path.
 *
 * <h3>Dry-run contract (plan §3.4)</h3>
 * Under a dry run the framework substitutes a recording stand-in: {@link #notify} logs the
 * would-be notification to the RunLog and stores nothing, returning empty.
 *
 * @since 5.1.0
 */
@PublicApi(since = "5.1.0")
public interface NotificationAccess {

    /**
     * Store {@code n} in the in-app feed and push it to live listeners — unless an active (unread)
     * notification with the same {@link Notification#dedupeKey()} already exists, in which case the
     * duplicate is collapsed.
     *
     * @return the stored notification, or empty when collapsed (or under a dry run)
     */
    Optional<Notification> notify(Notification n);
}
