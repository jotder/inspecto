package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.DraftCheckpoints;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.LinkEventTypes;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * D7-6 - admission and idle policy for Drafts (design {@code la-separation-d7-design.md} sections 7 and 11; decisions D7-Q5, D7-Q6).
 *
 * <ul>
 *   <li><b>Open-Draft cap (D21).</b> {@code drafts.max_open} of the Space's {@code link-analysis.toon} (default 50) open Drafts per Space, counted across its Investigations
 *       by directory names and marker files only. The 51st fork answers 409 with the way out; idle Drafts of the Space are swept first,
 *       so an expired one never holds a seat.</li>
 *   <li><b>Heavy-job cap (D7-Q6).</b> At most {@code min(4, cores/3)} (at least 1) heavy Draft jobs - a rebase / conflict-report replay, a
 *       cold Working Set relation build, an {@code expand} op - run at once. {@link #heavy} NEVER waits: when every permit is taken it
 *       answers 429 {@code RATE_LIMITED} with a sentence, so a caller retries instead of silently queueing. Override:
 *       {@code -Dinspecto.la.draft.heavy=N} or {@link #setHeavyLimit}.</li>
 *   <li><b>Idle policy (D7-Q5).</b> {@link #maintain} runs lazily on every Draft list / open / fork of an Investigation: it recovers the
 *       crash leftovers of a rebase, HIBERNATES a Draft idle for 1 h (marker + the in-memory checkpoint and cached relation released; log,
 *       sets and pins untouched; the next access takes one cold fold) and EXPIRES one idle for 30 d (closed exactly like a discard: marker
 *       with {@code expired:true}, log and sealed rows deleted, pins released, {@code LINK_DRAFT_EXPIRED}). No new scheduler is started.</li>
 * </ul>
 */
public final class DraftAdmission {

    private DraftAdmission() {}

    /** A unit of heavy work. */
    @FunctionalInterface
    public interface Job<T> {
        T run() throws IOException;
    }

    private static volatile Semaphore heavy = new Semaphore(defaultHeavyLimit());
    private static volatile int heavyLimit = defaultHeavyLimit();

    static int defaultHeavyLimit() {
        String o = System.getProperty("inspecto.la.draft.heavy");
        if (o != null && o.matches("[1-9][0-9]{0,2}")) return Integer.parseInt(o);
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
    }

    /** TEST / OPERATOR SEAM: replace the heavy-job limit (a fresh semaphore; jobs already running keep their old permit). */
    public static void setHeavyLimit(int n) {
        if (n < 1) throw new IllegalArgumentException("the heavy-job limit is at least 1");
        heavyLimit = n;
        heavy = new Semaphore(n);
    }

    public static int heavyLimit() {
        return heavyLimit;
    }

    /** Run {@code job} under a heavy-job permit, or refuse at once with 429 when all are taken. */
    public static <T> T heavy(String what, Job<T> job) throws IOException {
        Semaphore s = heavy;
        if (!s.tryAcquire())
            throw new ApiException(429, ErrorCodes.RATE_LIMITED, "all " + heavyLimit + " heavy Draft jobs are already running - '" + what
                    + "' was not started; retry in a few seconds");
        try {
            return job.run();
        } finally {
            s.release();
        }
    }


    // ── idle policy ────────────────────────────────────────────────────────────────────────────────────

    /** Recover rebase / promote crash leftovers, hibernate the Drafts idle for the hibernate period and expire those idle for the expiry period. */
    static void maintain(InvestigationRoutes.Inv inv) {
        InvestigationStore store = inv.store();
        try {
            store.recoverDrafts(inv.id());
            LinkAnalysisSettings.Drafts policy = LinkAnalysisSettings.forRoot(inv.writeRoot()).effectiveDrafts();
            for (String id : store.openDraftIds(inv.id())) {
                Duration idle = store.draftIdle(inv.id(), id);
                if (idle.compareTo(policy.expireAfterInForce()) >= 0) expire(inv, id, policy);
                else if (idle.compareTo(policy.hibernateAfterInForce()) >= 0
                        && store.draftState(inv.id(), id) != InvestigationStore.DraftState.HIBERNATED) hibernate(inv, id, policy);
            }
        } catch (IOException | RuntimeException e) {
            // housekeeping: it must never fail the request it rides on; the next call retries
        }
    }

    /** {@link #maintain} for every Investigation of the Space that has Drafts. */
    static void maintainSpace(InvestigationRoutes.Inv inv) {
        try {
            for (String id : inv.store().ids()) {
                if (inv.store().openDraftIds(id).isEmpty()) continue;
                String raw = inv.store().header(id).orElse(null);
                if (raw == null) continue;
                @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(raw, Map.class);
                maintain(new InvestigationRoutes.Inv(inv.store(), inv.writeRoot(), id, header));
            }
        } catch (IOException | RuntimeException e) {
            // housekeeping only
        }
    }

    /** Release what a Draft holds in memory: its checkpoint and its cached Working Set relations. */
    static void evictCaches(InvestigationRoutes.Inv inv, String draftId) {
        String key = inv.store().cacheKey(InvestigationStore.Scope.draft(inv.id(), draftId));
        DraftCheckpoints.forget(key);
        WorkingSetRoutes.evict(key);
    }

    private static void hibernate(InvestigationRoutes.Inv inv, String draftId, LinkAnalysisSettings.Drafts policy) throws IOException {
        if (inv.store().hibernateDraft(inv.id(), draftId, policy.hibernateAfterInForce())) evictCaches(inv, draftId);
    }

    private static void expire(InvestigationRoutes.Inv inv, String draftId, LinkAnalysisSettings.Drafts policy) throws IOException {
        InvestigationStore store = inv.store();
        String rawHeader = store.draftHeader(inv.id(), draftId).orElse(null);
        if (rawHeader == null) return;
        @SuppressWarnings("unchecked") Map<String, Object> header = ApiContext.JSON.readValue(rawHeader, LinkedHashMap.class);
        long idleDays = store.draftIdle(inv.id(), draftId).toDays();
        int[] head = new int[1];
        Optional<Boolean> closed = store.closeDraft(inv.id(), draftId, policy.expireAfterInForce(), own -> {
            head[0] = ((Number) header.get("baseStep")).intValue() + own.size();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("draftId", draftId);
            marker.put("discardedBy", "system:expiry");
            marker.put("discardedAt", DraftLifecycle.now().toString());
            marker.put("expired", true);
            marker.put("idleDays", idleDays);
            marker.put("headStep", head[0]);
            marker.put("logHash", DraftStore.prefixHash(own, own.size()));
            return canonical(marker);
        });
        if (closed.isEmpty()) return;   // no longer idle, or already closed by someone else
        evictCaches(inv, draftId);
        int unpinned = DraftRoutes.unpinAll(inv.writeRoot(), inv.dataset(), DraftRoutes.pinsOf(header), draftId);
        if (!closed.get()) return;
        try {
            Event.Builder b = Event.builder(LinkEventTypes.LINK_DRAFT_EXPIRED).source("inv")
                    .message("link.draft.expired - " + draftId + " of " + inv.id() + " idle " + idleDays + " days")
                    .actor("system").actorType("system").action("link.draft.expired").actionCategory("analysis")
                    .attr("investigationId", inv.id()).attr("draftId", draftId).attr("draftActor", header.get("actor"))
                    .attr("baseStep", header.get("baseStep")).attr("step", head[0]).attr("idleDays", idleDays).attr("unpinned", unpinned);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort, as every Draft event: the expiry is already recorded
        }
    }
}
