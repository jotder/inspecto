package com.gamma.la.api;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.la.core.DraftCheckpoints;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * D7-6 - admission and idle policy for Drafts (design {@code la-separation-d7-design.md} sections 7 and 11; decisions D7-Q5, D7-Q6).
 *
 * <ul>
 *   <li><b>Open-Draft cap (D21).</b> {@link DraftLifecycle#maxOpenDrafts} (50) open Drafts per Space, counted across its Investigations
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

    /** Held while a fork checks the Space-wide cap and takes its seat. */
    static final Object CAP = new Object();

    private static final Duration SCRATCH_GRACE = Duration.ofHours(1);

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

    // ── the open-Draft cap ─────────────────────────────────────────────────────────────────────────────

    /** 409 when the Space already holds the maximum of open Drafts (after sweeping the idle ones that no longer count). */
    static void requireRoom(InvestigationRoutes.Inv inv) throws IOException {
        Path investigations = inv.dir().getParent();
        int max = DraftLifecycle.maxOpenDrafts;
        if (DraftStore.countOpen(investigations) < max) return;
        maintainSpace(inv);
        int open = DraftStore.countOpen(investigations);
        if (open >= max)
            throw new ApiException(409, ErrorCodes.CONFLICT, "this Space already holds " + open + " open Drafts (the limit is " + max
                    + ") - discard or promote one, or wait for an idle one to expire, then fork again");
    }

    // ── idle policy ────────────────────────────────────────────────────────────────────────────────────

    /** Recover rebase crash leftovers, hibernate the Drafts idle for the hibernate period and expire those idle for the expiry period. */
    static void maintain(InvestigationRoutes.Inv inv) {
        Path invDir = inv.dir();
        if (!Files.isDirectory(DraftStore.draftsDir(invDir))) return;
        try {
            DraftStore.recover(invDir, DraftLifecycle.now(), SCRATCH_GRACE, InvestigationRoutes::lock);
            for (String id : DraftStore.openIds(invDir)) {
                Path dir = DraftStore.draftDir(invDir, id);
                Duration idle = DraftLifecycle.idle(dir);
                if (idle.compareTo(DraftLifecycle.expireAfter) >= 0) expire(inv, id, dir);
                else if (idle.compareTo(DraftLifecycle.hibernateAfter) >= 0 && !DraftLifecycle.isHibernated(dir)) hibernate(dir);
            }
        } catch (IOException | RuntimeException e) {
            // housekeeping: it must never fail the request it rides on; the next call retries
        }
    }

    /** {@link #maintain} for every Investigation of the Space that has Drafts. */
    private static void maintainSpace(InvestigationRoutes.Inv inv) {
        try (var ds = Files.newDirectoryStream(inv.dir().getParent(), Files::isDirectory)) {
            for (Path d : ds) {
                String id = d.getFileName().toString();
                if (!SnapshotStore.SAFE_ID.matcher(id).matches() || !Files.isDirectory(DraftStore.draftsDir(d))) continue;
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
    static void evictCaches(Path draftDir) {
        DraftCheckpoints.forget(draftDir);
        WorkingSetRoutes.evict(draftDir);
    }

    private static void hibernate(Path dir) throws IOException {
        synchronized (InvestigationRoutes.lock(dir)) {
            if (DraftStore.isClosed(dir) || DraftLifecycle.idle(dir).compareTo(DraftLifecycle.hibernateAfter) < 0) return;
            if (DraftLifecycle.hibernate(dir)) evictCaches(dir);
        }
    }

    private static void expire(InvestigationRoutes.Inv inv, String draftId, Path dir) throws IOException {
        Map<String, Object> header;
        int head;
        long idleDays;
        boolean first;
        synchronized (InvestigationRoutes.lock(dir)) {
            if (DraftStore.isClosed(dir) || DraftLifecycle.idle(dir).compareTo(DraftLifecycle.expireAfter) < 0) return;
            String raw = DraftStore.readHeader(inv.dir(), draftId);
            if (raw == null) return;
            @SuppressWarnings("unchecked") Map<String, Object> h = ApiContext.JSON.readValue(raw, LinkedHashMap.class);
            header = h;
            List<String> own = SnapshotStore.readLogAt(dir);
            head = ((Number) header.get("baseStep")).intValue() + own.size();
            idleDays = DraftLifecycle.idle(dir).toDays();
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("draftId", draftId);
            marker.put("discardedBy", "system:expiry");
            marker.put("discardedAt", DraftLifecycle.now().toString());
            marker.put("expired", true);
            marker.put("idleDays", idleDays);
            marker.put("headStep", head);
            marker.put("logHash", DraftStore.prefixHash(own, own.size()));
            first = DraftStore.markDiscarded(dir, canonical(marker));
            evictCaches(dir);
        }
        int unpinned = DraftRoutes.unpinAll(inv.writeRoot(), inv.dataset(), DraftRoutes.pinsOf(header), draftId);
        if (!first) return;
        try {
            Event.Builder b = Event.builder(LinkEventTypes.LINK_DRAFT_EXPIRED).source("inv")
                    .message("link.draft.expired - " + draftId + " of " + inv.id() + " idle " + idleDays + " days")
                    .actor("system").actorType("system").action("link.draft.expired").actionCategory("analysis")
                    .attr("investigationId", inv.id()).attr("draftId", draftId).attr("draftActor", header.get("actor"))
                    .attr("baseStep", header.get("baseStep")).attr("step", head).attr("idleDays", idleDays).attr("unpinned", unpinned);
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort, as every Draft event: the expiry is already on disk
        }
    }
}
