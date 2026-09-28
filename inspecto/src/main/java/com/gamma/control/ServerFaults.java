package com.gamma.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * ERR-5XX-ROUTE-BODIES (operator, 2026-09-28): a route-built 5xx never puts exception text on the wire —
 * no driver message, hostname, path, SQL or stack. The full detail goes to the ERROR log under the
 * request's correlation id, which the body names so an operator can find it.
 *
 * <ul>
 *   <li>{@link #internal} — a 500, the same generic body {@code ControlApi.errorBoundary} answers with.</li>
 *   <li>{@link #curated} — a 502/503 that keeps a short reason chosen from a fixed list by exception
 *       type ("connection refused", "timed out", "authentication failed", "host not found",
 *       "sandbox busy"), else the caller's fallback. The reason is picked, never copied.</li>
 * </ul>
 */
public final class ServerFaults {

    private static final Logger log = LoggerFactory.getLogger(ServerFaults.class);

    private ServerFaults() {}

    /** The generic 500 body; shared with {@code ControlApi.fail500}. */
    static String internalMessage(Object correlationId) {
        return "Internal error — correlation id " + correlationId;
    }

    /** Log {@code t} at ERROR and answer a generic 500. {@code what} is a fixed, route-authored phrase. */
    public static ApiException internal(String what, Throwable t) {
        String cid = MDC.get("correlationId");
        log.error("{} (Correlation-ID {})", what, cid, t);
        return new ApiException(500, ErrorCodes.INTERNAL, internalMessage(cid));
    }

    /** Log {@code t} at ERROR and answer {@code status} with "{@code what}: reason — correlation id …". */
    public static ApiException curated(int status, String code, String what, String fallback, Throwable t) {
        String cid = MDC.get("correlationId");
        log.error("{} (Correlation-ID {})", what, cid, t);
        return new ApiException(status, code, what + ": " + reason(t, fallback) + " — correlation id " + cid);
    }

    /** A reason from the fixed list, by walking the cause chain; the text is inspected, never returned. */
    static String reason(Throwable t, String fallback) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof UnknownHostException) return "host not found";
            if (c instanceof ConnectException) return "connection refused";
            if (c instanceof SocketTimeoutException || c instanceof TimeoutException
                    || c instanceof java.net.http.HttpTimeoutException) return "timed out";
            String name = c.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String msg = String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
            if (name.contains("auth") || msg.contains("auth fail") || msg.contains("authentication")
                    || msg.contains("permission denied") || msg.contains("access denied")) return "authentication failed";
            if (name.contains("timeout") || msg.contains("timed out")) return "timed out";
            if (msg.contains("connection refused")) return "connection refused";
            if (msg.contains("lock on file") || msg.contains("conflicting lock") || msg.contains("busy")) return "sandbox busy";
        }
        return fallback;
    }
}
