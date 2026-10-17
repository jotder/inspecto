package com.gamma.la.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * The outbound PORT of Link Analysis third-party integration (LA-EDITIONS-1 phase 6, roadmap I3 "remote Case adapter"):
 * one JSON POST to a registered {@code https} Connection of the CURRENT Space, through the host's egress-checked wire.
 * The bridge ({@code inspecto-geo-link}'s {@code HostOutboundPort}) implements it with the platform's own outbound
 * machinery - the Connection rules of the webhook sink, the Space's egress allowlist, the pinned transport of
 * {@code inspecto-notify-channels} and the Collector {@code RetryPolicy} - so Link Analysis never opens an HTTP client of
 * its own. Registered through {@code META-INF/services/com.gamma.la.core.OutboundPort}; find it through
 * {@link OutboundPorts}. Unbound ⇒ "outbound integration is not installed".
 */
public interface OutboundPort {

    /** How often a send is retried: the Collector retry block's shape ({@code count} retries after the first attempt). */
    record Retry(int count, String backoff, long initialDelayMillis, long maxDelayMillis) {
        public static final Retry DEFAULT = new Retry(2, "EXPONENTIAL", 1_000L, 30_000L);
    }

    /**
     * What happened. {@code refused}: nothing was sent (no transport in this bundle, the Connection is unusable, a literal
     * token, or the egress policy denied every address) and {@code error} says why. Otherwise {@code status} is the last
     * answer ({@code null} when none came back), {@code body} at most the first few KiB of it, {@code attempts} how many
     * were made; {@code delivered} is a 2xx.
     */
    record Result(boolean delivered, boolean refused, Integer status, String body, int attempts, String error) {

        public static Result refusedBecause(String why) {
            return new Result(false, true, null, null, 0, why);
        }
    }

    /**
     * POST {@code json} to Connection {@code connection} of the current Space, whose config root is {@code configRoot} (its
     * egress allowlist is read there). The Connection's token (its {@code password},
     * a {@code ${…}} secret reference resolved at send time) travels as {@code Authorization: Bearer …}, or under
     * {@code authHeader} when that is set. {@code headers} are added as given. Never throws for a delivery failure.
     */
    Result send(Path configRoot, String connection, String json, Map<String, String> headers, String authHeader, Retry retry);
}
