package com.gamma.la.core;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The per-set size limit of the {@link InvestigationStore} port (D-IS2; operator decision 2026-10-10): the most UTF-8 bytes of sealed
 * Working Set text one step may store. Default 64 MiB, per-Space {@code max_set_bytes} in {@code link-analysis.toon}
 * ({@link LinkAnalysisSettings#effectiveMaxSetBytes()}). Each implementation calls {@link #enforce} on every write path that stores
 * a set ({@code append}, {@code createFork}, {@code promoteDraft}, {@code replaceDraft}), BEFORE anything is written, so a refusal
 * leaves the store as it was; a set exactly at the limit passes, one byte over is refused with {@code 413 PAYLOAD_TOO_LARGE}, never truncated.
 */
public final class WorkingSetSizeLimit {
    private WorkingSetSizeLimit() {}

    /** The shipped default, for a store built with no Space (tests). */
    public static final LongSupplier DEFAULT = () -> LinkAnalysisSettings.DEFAULT_MAX_SET_BYTES;

    /** The limit of the Space whose config root is {@code writeRoot}, re-read per write so a Settings change applies at once. */
    public static LongSupplier forRoot(Path writeRoot) {
        return () -> LinkAnalysisSettings.forRoot(writeRoot).effectiveMaxSetBytes();
    }

    /** Refuse {@code set} (the sealed text of {@code step}) when it is over {@code limit} bytes; {@code null} is "nothing to store" and passes. */
    public static void enforce(LongSupplier limit, String investigationId, int step, String set) {
        if (set == null) return;
        long max = limit.getAsLong();
        long chars = set.length();
        if (chars * 3 <= max) return;                                  // at most 3 UTF-8 bytes per UTF-16 char: cannot be over
        // a char is at least 1 byte, so more chars than the limit is over; otherwise count exactly
        if (chars <= max && set.getBytes(StandardCharsets.UTF_8).length <= max) return;
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_WORKING_SET_TOO_LARGE).source("inv")
                    .message("link.working_set.too_large - " + investigationId + " step " + step + " is over " + max + " bytes")
                    .action("link.working_set.too_large").actionCategory("analysis")
                    .attr("investigationId", investigationId).attr("step", step).attr("limitBytes", max));
        } catch (RuntimeException ignored) {
            // best effort - the refusal itself is what protects the store
        }
        throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "The Working Set of step " + step + " of Investigation '"
                + investigationId + "' is larger than this Space's limit of " + max + " bytes (" + (max >> 20) + " MiB) and was not stored. "
                + "Narrow the step, or raise the limit: set max_set_bytes in the Space's link-analysis.toon "
                + "(Settings > Link Analysis, maxSetBytes; at most 1073741824).");
    }

    /** {@link #enforce} over every set of a multi-step write; step {@code i} is {@code firstStep + i}. A {@code null} entry is skipped. */
    public static void enforceAll(LongSupplier limit, String investigationId, int firstStep, List<String> sets) {
        for (int i = 0; i < sets.size(); i++) enforce(limit, investigationId, firstStep + i, sets.get(i));
    }
}
