package com.gamma.la.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D7-6 - when a Draft was last used, and the two idle states that follow (design {@code la-separation-d7-design.md} sections 7, 11;
 * decisions D7-Q5: hibernate after 1 h idle, expire after 30 d idle). Host-free; the policy that ACTS on these facts
 * (release caches, close like a discard, audit) lives in {@code inspecto-la-api} {@code DraftAdmission}.
 *
 * <ul>
 *   <li><b>Access.</b> {@link #touch} records an authorised read or write: in memory at once, and in {@code accessed.json}
 *       at most every {@link #PERSIST_EVERY} (a read must not cost a file write), so a restart still knows how long a Draft
 *       has been idle. The persisted value can lag the real one by that interval, which is far under the 1 h threshold.</li>
 *   <li><b>Hibernated.</b> {@code hibernated.json} says the Draft released its in-memory checkpoint and cached relation. Log,
 *       sets and pins are untouched; the next access deletes the marker and the first read takes one cold fold.</li>
 *   <li><b>Precedence</b> ({@link #state}): {@code promoted} over {@code discarded} (an expiry is a discard with
 *       {@code expired:true}) over {@code hibernated} over {@code open}. Closing a Draft deletes the idle markers.</li>
 * </ul>
 * Time comes from {@link #clock}, a test seam: no test sleeps.
 */
public final class DraftLifecycle {

    private DraftLifecycle() {}

    public static final String HIBERNATED = "hibernated.json";
    public static final String ACCESSED = "accessed.json";

    /** TEST SEAM: the time source. */
    public static volatile Clock clock = Clock.systemUTC();
    public static volatile Duration hibernateAfter = Duration.ofHours(1);
    public static volatile Duration expireAfter = Duration.ofDays(30);
    /** The most open Drafts one Space holds (D21). */
    public static volatile int maxOpenDrafts = 50;
    static final Duration PERSIST_EVERY = Duration.ofMinutes(5);

    private static final Map<Path, Instant> ACCESS = new ConcurrentHashMap<>();
    private static final Map<Path, Instant> PERSISTED = new ConcurrentHashMap<>();

    public static Instant now() {
        return clock.instant();
    }

    /** Record an authorised use of the Draft. Best effort on disk: a failed write never fails the read it rides on. */
    public static void touch(Path draftDir) {
        Instant now = now();
        ACCESS.put(draftDir, now);
        Instant last = PERSISTED.get(draftDir);
        if (last != null && Duration.between(last, now).compareTo(PERSIST_EVERY) < 0) return;
        if (DraftStore.isClosed(draftDir) || !Files.isDirectory(draftDir)) return;
        try {
            Files.writeString(draftDir.resolve(ACCESSED), "{\"at\":\"" + now + "\"}", StandardCharsets.UTF_8);
            PERSISTED.put(draftDir, now);
        } catch (IOException ignored) {
            // idle accounting only; the next touch retries
        }
    }

    /** When the Draft was last used: memory, else {@code accessed.json}, else the header file's time (a Draft from before D7-6). */
    public static Instant lastAccess(Path draftDir) {
        Instant mem = ACCESS.get(draftDir);
        if (mem != null) return mem;
        try {
            Path f = draftDir.resolve(ACCESSED);
            if (Files.isRegularFile(f)) {
                Object at = InvestigationEvaluator.CANONICAL.readValue(Files.readString(f, StandardCharsets.UTF_8), Map.class).get("at");
                if (at != null) return Instant.parse(String.valueOf(at));
            }
            return Files.getLastModifiedTime(draftDir.resolve(DraftStore.HEADER)).toInstant();
        } catch (IOException | RuntimeException unreadable) {
            return now();   // unknown idleness is not evidence of idleness: never expire on a read failure
        }
    }

    public static boolean isHibernated(Path draftDir) {
        return Files.isRegularFile(draftDir.resolve(HIBERNATED));
    }

    /** {@code promoted > discarded > hibernated > open}; an expiry is a discard (see {@link #wasExpired}). */
    public static String state(Path draftDir) {
        if (DraftStore.isPromoted(draftDir)) return "promoted";
        if (DraftStore.isDiscarded(draftDir)) return "discarded";
        return isHibernated(draftDir) ? "hibernated" : "open";
    }

    /** True when the Draft's discard marker was written by the idle expiry, not by a person. */
    public static boolean wasExpired(Path draftDir) {
        try {
            String raw = DraftStore.readDiscarded(draftDir);
            return raw != null && Boolean.TRUE.equals(InvestigationEvaluator.CANONICAL.readValue(raw, Map.class).get("expired"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Write the hibernated marker (CREATE_NEW) and drop the in-memory checkpoint; false when it already hibernated. */
    public static boolean hibernate(Path draftDir) throws IOException {
        try {
            Files.writeString(draftDir.resolve(HIBERNATED), "{\"at\":\"" + now() + "\"}", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException already) {
            return false;
        }
        DraftCheckpoints.forget(draftDir);
        return true;
    }

    /** Delete the hibernated marker; true when the Draft WAS hibernated (the next read is then a cold fold). */
    public static boolean rehydrate(Path draftDir) throws IOException {
        return Files.deleteIfExists(draftDir.resolve(HIBERNATED));
    }

    /** A Draft closed (discarded, expired or promoted): its idle markers and memory go. */
    static void closed(Path draftDir) {
        ACCESS.remove(draftDir);
        PERSISTED.remove(draftDir);
        try {
            Files.deleteIfExists(draftDir.resolve(HIBERNATED));
            Files.deleteIfExists(draftDir.resolve(ACCESSED));
        } catch (IOException ignored) {
            // precedence (state) already hides a leftover marker
        }
    }

    /** Idle time of an open Draft against the clock. */
    public static Duration idle(Path draftDir) {
        Duration d = Duration.between(lastAccess(draftDir), now());
        return d.isNegative() ? Duration.ZERO : d;
    }
}
