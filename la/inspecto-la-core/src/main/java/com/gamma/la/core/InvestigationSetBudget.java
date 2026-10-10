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
 * The per-Investigation TOTAL set budget of the {@link InvestigationStore} port (D-IS12 (b); operator decision 2026-10-10): the
 * most UTF-8 bytes of sealed Working Set text one Investigation may hold - its main sets plus the sets of its live Drafts (a
 * promoted or discarded Draft's sets are gone, a rebase replaces them). Default 4 GiB, per-Space {@code max_investigation_bytes}
 * in {@code link-analysis.toon}. The sibling of {@link WorkingSetSizeLimit} (one set); each implementation keeps the running total
 * cheaply (a counter, never a scan per write) and calls {@link #enforce} with the total the write would leave, BEFORE it writes
 * anything, so a refusal changes nothing; a total exactly at the budget passes, one byte over is refused {@code 413 PAYLOAD_TOO_LARGE}.
 */
public final class InvestigationSetBudget {
    private InvestigationSetBudget() {}

    /** The shipped default, for a store built with no Space (tests). */
    public static final LongSupplier DEFAULT = () -> LinkAnalysisSettings.DEFAULT_MAX_INVESTIGATION_BYTES;

    /** The budget of the Space whose config root is {@code writeRoot}, re-read per write so a Settings change applies at once. */
    public static LongSupplier forRoot(Path writeRoot) {
        return () -> LinkAnalysisSettings.forRoot(writeRoot).effectiveMaxInvestigationBytes();
    }

    /** The UTF-8 size of a set; {@code null} (nothing to store) is 0. */
    public static long bytes(String set) {
        return set == null ? 0 : set.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The UTF-8 size of every non-null set. */
    public static long bytes(List<String> sets) {
        long n = 0;
        for (String s : sets) n += bytes(s);
        return n;
    }

    /**
     * Refuse a write that would leave {@code investigationId} holding {@code totalAfter} bytes of sets when that is over the budget.
     * {@code currentTotal} is what it holds now (for the message and the audit event).
     */
    public static void enforce(LongSupplier budget, String investigationId, long currentTotal, long totalAfter) {
        long max = budget.getAsLong();
        if (totalAfter <= max) return;
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_INVESTIGATION_TOO_LARGE).source("inv")
                    .message("link.investigation.too_large - " + investigationId + " holds " + currentTotal + " bytes of sets; the write would make it "
                            + totalAfter + ", over " + max)
                    .action("link.investigation.too_large").actionCategory("analysis")
                    .attr("investigationId", investigationId).attr("totalBytes", currentTotal)
                    .attr("addBytes", totalAfter - currentTotal).attr("limitBytes", max));
        } catch (RuntimeException ignored) {
            // best effort - the refusal itself is what protects the store
        }
        throw new ApiException(413, ErrorCodes.PAYLOAD_TOO_LARGE, "Investigation '" + investigationId + "' already holds " + currentTotal
                + " bytes of sealed Working Sets and this write would take it to " + totalAfter + ", over this Space's budget of " + max
                + " bytes (" + (max >> 20) + " MiB) per Investigation; nothing was stored. Fork a smaller Investigation, or raise the budget: "
                + "set max_investigation_bytes in the Space's link-analysis.toon (Settings > Link Analysis, maxInvestigationBytes; at most 1099511627776).");
    }
}
