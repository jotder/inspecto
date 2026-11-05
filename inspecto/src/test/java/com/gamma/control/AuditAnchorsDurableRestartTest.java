package com.gamma.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.audit.Event;
import com.gamma.audit.EventStore;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.event.InMemoryEventStore;
import com.gamma.event.ParquetEventStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.http.ApiContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BASIC-EVENTS-DURABLE-1 (2026-11-06): Basic used to run the in-memory event store, so its audit chain restarted at
 * seq 1 on every boot and the day's anchor named a record the next boot no longer held — the roll then refused
 * (409) in every Space, forever. Now: every launcher keeps events on disk; anchors are never written over a memory
 * store and carry a MAC'd {@code store} tag; and an anchor file written over a memory store before the tag existed
 * gets ONE automatic signed break — only when all five conditions of {@code AuditAnchors.legacyBreakIfDue} hold.
 * One test per condition shows a failing condition means no break. Synthetic Spaces only.
 */
class AuditAnchorsDurableRestartTest {

    private static final long DAY = 86_400_000L;
    /** The day the old (memory-era) anchor covers, and the day the durable chain starts. */
    private static final long T_OLD = Instant.parse("2026-11-02T10:00:00Z").toEpochMilli();
    private static final long T_NEW = Instant.parse("2026-11-06T10:00:00Z").toEpochMilli();
    private static final LocalDate D_NEW = LocalDate.of(2026, 11, 6);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private static ParquetEventStore parquet(Path events) {
        return new ParquetEventStore(events, 1000, 0, 100);
    }

    private static void emit(EventStore store, long ts, String what) {
        EventLog log = EventLog.create();
        log.installStore(store);
        log.emit(Event.builder(EventType.AUDIT).ts(ts).source("audit").message(what)
                .actor("alice").action("pipeline.updated").target("pipeline", what));
        store.flush();
    }

    /** One boot: emit one audit row on day {@code i} after T_OLD, then run the roll as of the next day. */
    private static int boot(EventStore store, Path config, int i) throws Exception {
        try (store) {
            emit(store, T_OLD + i * DAY, "act " + i);
            return AuditAnchors.roll(store, config, LocalDate.of(2026, 11, 3).plusDays(i));
        }
    }

    // ── durable restart ─────────────────────────────────────────────────────────────────────────

    @Test
    void aDurableChainSurvivesARestartAndTheNextDayStillRolls(@TempDir Path dir) throws Exception {
        Path events = dir.resolve("events");
        Path config = Files.createDirectories(dir.resolve("config"));
        assertEquals(1, boot(parquet(events), config, 0), "day 1 anchored");
        assertEquals(1, boot(parquet(events), config, 1),
                "after a restart the chain still holds day 1's anchored record, so day 2 anchors onto it");
        assertEquals(1, boot(parquet(events), config, 2), "and again");
        var anchors = AuditAnchors.read(config);
        assertEquals(3, anchors.size());
        assertEquals(3, anchors.get(2).lastSeq(), "one chain across three boots: seq 1..3, never restarted");
        assertEquals("parquet", anchors.get(2).extra().get(AuditAnchors.STORE), "every new anchor names its store");
        assertTrue(anchors.stream().allMatch(AuditAnchors.Anchor::macValid), "the store tag is inside the MAC");
        assertNull(AuditAnchors.readFile(config).firstProblem());
        try (ParquetEventStore store = parquet(events)) {
            assertEquals(3, store.chainPage(1, 10).size(), "every audited record is still on disk");
        }
    }

    @Test
    void nothingIsAnchoredOverAMemoryStoreSoNoRestartCanStrandAnAnchor(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        assertEquals(0, boot(new InMemoryEventStore(), config, 0), "the roll skips a memory store");
        assertEquals(0, boot(new InMemoryEventStore(), config, 1), "and the next boot has nothing stranded to refuse");
        assertFalse(Files.exists(AuditAnchors.file(config)), "no anchor was written");
        try (InMemoryEventStore store = new InMemoryEventStore()) {
            emit(store, T_NEW, "x");
            AuditAnchors.resetRateLimit(config);
            ApiException e = assertThrows(ApiException.class, () -> AuditAnchors.onDemand(store, config));
            assertEquals(409, e.status);
            assertTrue(e.getMessage().contains("events are kept in memory; nothing to anchor"), e.getMessage());
            // nor a rebaseline break: over a memory chain it would strand the Space again at the next boot
            AuditAnchors.resetRebaselineLimit(config);
            ApiException r = assertThrows(ApiException.class, () -> AuditAnchors.rebaseline(store, config, "try",
                    new AuditVerifier.Policy(D_NEW, false, null), "admin-1"));
            assertEquals(409, r.status);
            assertTrue(r.getMessage().contains("events are kept in memory; nothing to rebaseline"), r.getMessage());
        }
        assertFalse(Files.exists(AuditAnchors.file(config)), "still no anchor of any kind");
    }

    // ── the legacy transition ───────────────────────────────────────────────────────────────────

    /**
     * A Space as a pre-2026-11-06 Basic install left it: one daily anchor over seq 1 of a chain that is gone. Built
     * by anchoring a throwaway chain, then re-signing the anchor as the old code wrote it — with no {@code store}
     * tag (unless {@code keepTag}) and the given {@code createdAt}.
     */
    private static Path legacySpace(Path dir, boolean keepTag, long createdAt) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        try (ParquetEventStore gone = parquet(dir.resolve("gone-events"))) {
            emit(gone, T_OLD, "memory era");
            assertEquals(1, AuditAnchors.roll(gone, config, LocalDate.of(2026, 11, 3)));
        }
        Path f = AuditAnchors.file(config);
        Map<String, Object> m = ApiContext.JSON.readValue(Files.readString(f, StandardCharsets.UTF_8).trim(), MAP);
        m.remove("mac");
        if (!keepTag) m.remove(AuditAnchors.STORE);
        m.put("createdAt", createdAt);
        String mac = AuditAnchors.mac(config, m);
        m.put("mac", mac);
        Files.writeString(f, ContentHash.canonicalJson(m) + "\n", StandardCharsets.UTF_8);
        Files.writeString(AuditAnchors.startedFile(config),
                "{\"firstSeq\":1,\"firstMac\":\"" + mac + "\",\"latestMac\":\"" + mac + "\"}", StandardCharsets.UTF_8);
        assertNull(AuditAnchors.readFile(config).firstProblem(), "the legacy file verifies on its own");
        return config;
    }

    private static List<Path> replacedFiles(Path config) throws Exception {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(AuditAnchors.file(config).getParent())) {
            s.filter(p -> p.getFileName().toString().endsWith(".replaced.jsonl")).forEach(out::add);
        }
        return out;
    }

    /** The durable chain after the switch: one audit row at {@code ts}, then the roll as of the day after D_NEW. */
    private static int rollDurable(Path dir, Path config, long ts) throws Exception {
        try (ParquetEventStore store = parquet(dir.resolve("events"))) {
            if (store.chainHead() == null) emit(store, ts, "durable era");
            return AuditAnchors.roll(store, config, D_NEW.plusDays(1));
        }
    }

    private static void assertNoBreak(Path dir, Path config, long ts) throws Exception {
        ApiException e = assertThrows(ApiException.class, () -> rollDurable(dir, config, ts));
        assertEquals(409, e.status, "without the break the roll refuses, as before");
        assertTrue(replacedFiles(config).isEmpty(), "no anchor file was set aside");
        assertFalse(AuditAnchors.read(config).get(0).isBreak(), "no break was written");
    }

    @Test
    void aLegacyMemoryAnchorGetsOneSignedBreakAndAnchoringResumesFromTheDurableChain(@TempDir Path dir)
            throws Exception {
        Path config = legacySpace(dir, false, T_OLD + DAY);
        byte[] legacy = Files.readAllBytes(AuditAnchors.file(config));
        assertEquals(1, rollDurable(dir, config, T_NEW), "the durable day is anchored after the break");

        List<AuditAnchors.Anchor> anchors = AuditAnchors.read(config);
        assertEquals(2, anchors.size(), anchors.toString());
        AuditAnchors.Anchor b = anchors.get(0);
        assertTrue(b.isBreak() && b.macValid());
        assertEquals("anchored over a non-durable store before 2026-11-06", b.extra().get("reason"));
        assertEquals("system", b.extra().get("by"));
        assertEquals(1, b.firstSeq(), "the new epoch starts at seq 1 of the durable chain");
        assertEquals(1, anchors.get(1).firstSeq());
        assertEquals("parquet", anchors.get(1).extra().get(AuditAnchors.STORE));

        List<Path> set = replacedFiles(config);
        assertEquals(1, set.size(), "the legacy file is kept, never deleted");
        assertArrayEquals(legacy, Files.readAllBytes(set.get(0)), "byte for byte");
        List<Map<String, Object>> breaks = new ArrayList<>();
        assertNull(AuditAnchors.priorEpochs(AuditAnchors.readFile(config), breaks), "pinned by digest");
        assertEquals(1, breaks.size(), "verify lists the break");
        assertNull(AuditAnchors.readFile(config).firstProblem());

        try (ParquetEventStore store = parquet(dir.resolve("events"))) {
            emit(store, T_NEW + DAY, "next day");
            assertEquals(1, AuditAnchors.roll(store, config, D_NEW.plusDays(2)), "the roll keeps going");
        }
        assertEquals(1, replacedFiles(config).size(), "the break is written ONCE");
        assertEquals(1, AuditAnchors.read(config).stream().filter(AuditAnchors.Anchor::isBreak).count());
    }

    @Test
    void condition1_noBreakAndNoAnchorOverAMemoryStore(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir, false, T_OLD + DAY);
        try (InMemoryEventStore store = new InMemoryEventStore()) {
            emit(store, T_NEW, "memory again");
            assertEquals(0, AuditAnchors.roll(store, config, D_NEW.plusDays(1)));
        }
        assertTrue(replacedFiles(config).isEmpty());
        assertEquals(1, AuditAnchors.read(config).size(), "the legacy file is untouched");
    }

    @Test
    void condition2_noBreakOverAnAnchorFileThatDoesNotVerify(@TempDir Path dir) throws Exception {
        Path config = legacySpace(dir, false, T_OLD + DAY);
        Path f = AuditAnchors.file(config);
        Files.writeString(f, Files.readString(f, StandardCharsets.UTF_8).replace("\"day\":\"2026-11-02\"",
                "\"day\":\"2026-11-01\""), StandardCharsets.UTF_8);
        assertNotNull(AuditAnchors.readFile(config).firstProblem());
        assertNoBreak(dir, config, T_NEW);
    }

    @Test
    void condition3_noBreakWhileTheChainStillHoldsTheAnchoredRecord(@TempDir Path dir) throws Exception {
        // an untagged anchor over a chain that IS still there — Preview's anchors over its Parquet store. Its
        // createdAt is set BEFORE the chain starts so the other four conditions all hold: only (3) stops the break.
        Path config = legacySpace(dir, false, T_OLD - DAY);
        try (ParquetEventStore same = parquet(dir.resolve("gone-events"))) {
            emit(same, T_NEW, "same chain");
            assertEquals(1, AuditAnchors.roll(same, config, D_NEW.plusDays(1)), "rolls on as before");
        }
        assertTrue(replacedFiles(config).isEmpty());
        assertFalse(AuditAnchors.read(config).get(0).isBreak());
    }

    @Test
    void condition4_noBreakWhenTheDurableChainIsNotNewerThanTheAnchor(@TempDir Path dir) throws Exception {
        // a durable chain whose first record predates the anchor cannot be the store that came after it
        Path config = legacySpace(dir, false, T_NEW + DAY);
        assertNoBreak(dir, config, T_NEW);
    }

    @Test
    void condition5_noBreakForAnAnchorThatCarriesTheStoreTag(@TempDir Path dir) throws Exception {
        // written since the tag exists: a missing record there is a real break, never the memory-era transition
        Path config = legacySpace(dir, true, T_OLD + DAY);
        assertNoBreak(dir, config, T_NEW);
    }
}
