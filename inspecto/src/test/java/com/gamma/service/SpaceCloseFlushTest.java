package com.gamma.service;

import com.gamma.assist.spi.AssistAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DEMO-BAKE-WAL-RACE-1}: a graceful stop of a Space that is still busy when its close budget runs out must not
 * leave an unflushed DuckDB store. Before the fix {@link SpaceManager#close()} abandoned the whole close, so the store
 * closes at its end never ran and each Space store kept its {@code .wal} when the JVM exited.
 *
 * <p>"Busy" is made deterministic with an assist agent whose {@code close()} blocks: the Space's drain cannot finish
 * inside the budget, exactly as a long Job Run or a backed-up notification queue did in the Telco demo repro.
 */
class SpaceCloseFlushTest {

    /** An agent whose close blocks until released — the Space's drain is busy past the close budget. */
    private static final class BusyAgent implements AssistAgent {
        final CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public String name() { return "busy"; }
        @Override public void init(ReadModel service) {}
        @Override public void close() {
            closing.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<Path> walFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".wal")).toList();
        }
    }

    /** The production defaults of three Space stores the Telco repro left a {@code .wal} in; the reactor pom keeps
     *  them in memory (or in an unnamed DuckDB) for every other test. */
    private static final Map<String, String> PRODUCTION_BACKENDS = Map.of(
            "dedup.ledger.backend", "duckdb",
            "consignment.outputs.backend", "duckdb",
            "alerts.backend", "db");

    @Test
    void aQuietSpaceClosesItsStoresOnceAfterItsDrain(@TempDir Path root) throws Exception {
        Map<String, String> saved = new HashMap<>();
        PRODUCTION_BACKENDS.forEach((k, v) -> saved.put(k, System.setProperty(k, v)));
        try {
            Files.createDirectories(root.resolve("quiet").resolve("config"));
            SpaceManager mgr = SpaceManager.discover(root);
            CollectorService svc = mgr.space(SpaceId.of("quiet")).orElseThrow().service();
            assertFalse(walFiles(root).isEmpty(), "precondition: the booted Space's stores hold a WAL");
            mgr.close();
            assertEquals(List.of(), walFiles(root), "the drained close closes every store");
            assertDoesNotThrow(svc::closeStoresNow, "a late flush after a finished close is a no-op");
        } finally {
            restore(saved);
        }
    }

    private static void restore(Map<String, String> saved) {
        saved.forEach((k, v) -> {
            if (v == null) System.clearProperty(k);
            else System.setProperty(k, v);
        });
    }

    @Test
    void aSpaceBusyPastItsCloseBudgetStillFlushesItsStores(@TempDir Path root) throws Exception {
        Map<String, String> saved = new HashMap<>();
        PRODUCTION_BACKENDS.forEach((k, v) -> saved.put(k, System.setProperty(k, v)));
        Files.createDirectories(root.resolve("busy").resolve("config"));
        BusyAgent agent = new BusyAgent();
        try {
            SpaceManager mgr = SpaceManager.discover(root);
            SpaceContext busy = mgr.space(SpaceId.of("busy")).orElseThrow();
            busy.service().registerAgent(agent);
            assertFalse(walFiles(root).isEmpty(),
                    "precondition: the booted Space's DuckDB stores hold a WAL (schema DDL) until they are closed");

            mgr.close();   // the drain blocks in the agent's close; the budget lapses

            assertTrue(agent.closing.await(0, TimeUnit.SECONDS), "precondition: the close reached the busy drain");
            assertEquals(1, agent.release.getCount(), "precondition: the drain is still busy when close() returns");
            assertEquals(List.of(), walFiles(root),
                    "a graceful stop must flush every Space store even when the Space's drain is still busy");
        } finally {
            agent.release.countDown();   // let the abandoned close thread finish so @TempDir can be removed
            restore(saved);
        }
    }
}
