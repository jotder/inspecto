package com.gamma.util;

import org.slf4j.MDC;

/**
 * The space the calling thread is working in, as one statement, reachable from every module.
 *
 * <p><b>Why this lives in {@code inspecto-util} and not beside the event log.</b> The space id is carried on
 * the thread's MDC and was owned by {@code EventLog} in {@code inspecto-event}. Modules below that could not
 * read it: {@code inspecto-event} <b>depends on</b> {@code inspecto-etl}, so an {@code etl → event} edge
 * would be a dependency <b>cycle</b>. That is not a style objection — Maven refuses it. This class sits in
 * {@code inspecto-util}, which {@code inspecto-etl} and {@code inspecto-event} both already depend on, so
 * both reach the same value with no new edge and no second copy of the key.
 *
 * <p>⛔ <b>Keep this the ONLY definition of the key and the default.</b> {@code EventLog.SPACE_MDC_KEY} and
 * {@code EventLog.DEFAULT_SPACE_ID} stay as the public names many callers already use, but they are declared
 * <em>by reference to these</em> and {@code EventLog.currentSpaceId()} delegates here. A second literal
 * {@code "space"} anywhere is the mirrored-constant drift this repository has already paid for.
 *
 * <p>⚠ <b>Never returns null</b>, so it is safe as a map key — including in {@code computeIfAbsent}, which
 * throws on a null key. An unbound thread is the {@link #DEFAULT_SPACE_ID} space, which is what a
 * single-space deployment is.
 *
 * <p><b>Who binds it.</b> Three choke points, all above this class: the collector service's per-space run
 * wrapper, the control plane's space-binding middleware, and the job service's scoped blocks. Anything that
 * keys cross-cycle state by this value is per space; a registry holding such state that does <em>not</em> is
 * the bug to look for (that was {@code SPACE-UNKEYED-STATICS-1}).
 *
 * @since 2026-09-10 (SPACE-UNKEYED-STATICS-1)
 */
public final class CurrentSpace {

    private CurrentSpace() {}

    /** MDC key carrying the owning space id. The one definition; {@code EventLog} re-exports it. */
    public static final String SPACE_MDC_KEY = "space";

    /** The space a thread with no binding belongs to — what a single-space deployment always is. */
    public static final String DEFAULT_SPACE_ID = "default";

    /**
     * The space id the calling thread is in, or {@link #DEFAULT_SPACE_ID} when nothing bound one.
     *
     * @return a non-null, non-empty space id
     */
    public static String id() {
        String s = MDC.get(SPACE_MDC_KEY);
        return (s == null || s.isEmpty()) ? DEFAULT_SPACE_ID : s;
    }
}
