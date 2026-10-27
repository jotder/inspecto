package com.gamma.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * How many times a Run retries after a TRANSIENT failure, and how long it waits between attempts
 * (decision D8, "Jobs fail silently"). Authored on the Job as {@code retries:} (0..5) and
 * {@code retry_backoff_seconds:} (the first wait; it doubles per retry, capped at 10 minutes).
 *
 * <p>Retrying is never the default for a failure that cannot succeed: only an I/O or SQL error from the
 * run body is transient. A SQL-guard refusal, a config error, a missing parameter or a deadline expiry is
 * permanent — retrying it would repeat the refusal and only delay the alarm.
 *
 * <p>Default: {@value #DEFAULT_RETRIES} retries for the scheduled {@code sql.template}, {@code report},
 * {@code risk.score} and {@code anomaly.score} types; none for any other type. An authored
 * {@code retries: 0} switches it off.
 */
record RetryPolicy(int retries, Duration backoff) {

    static final String RETRIES = "retries";
    static final String BACKOFF_SECONDS = "retry_backoff_seconds";
    static final int DEFAULT_RETRIES = 2;
    static final int MAX_RETRIES = 5;
    static final long DEFAULT_BACKOFF_SECONDS = 30;
    static final long MAX_BACKOFF_SECONDS = 600;
    private static final Set<String> RETRIED_BY_DEFAULT = Set.of("sql.template", "report", "risk.score", "anomaly.score");

    static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO);

    /** The policy for a Job of {@code type} whose config carries {@code params}; a malformed value falls back to the default. */
    static RetryPolicy resolve(String type, Map<String, String> params) {
        int retries = RETRIED_BY_DEFAULT.contains(type) ? DEFAULT_RETRIES : 0;
        long backoff = DEFAULT_BACKOFF_SECONDS;
        if (params != null) {
            retries = parse(params.get(RETRIES), retries, 0, MAX_RETRIES);
            backoff = parse(params.get(BACKOFF_SECONDS), backoff, 0, MAX_BACKOFF_SECONDS);
        }
        return retries == 0 ? NONE : new RetryPolicy(retries, Duration.ofSeconds(backoff));
    }

    /** The wait before retry number {@code retry} (1-based): the base backoff doubled each time, capped. */
    Duration waitBefore(int retry) {
        long s = Math.min(MAX_BACKOFF_SECONDS, backoff.toSeconds() << Math.min(retry - 1, 20));
        return Duration.ofSeconds(s);
    }

    /** True when {@code failure} may succeed on a later attempt: an I/O or SQL error, here or in its cause chain. */
    static boolean transientFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof UncheckedIOException || t instanceof SQLException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static int parse(String raw, int fallback, int min, int max) {
        return (int) parse(raw, (long) fallback, min, max);
    }

    private static long parse(String raw, long fallback, long min, long max) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long v = Long.parseLong(raw.trim());
            return v < min || v > max ? fallback : v;
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
