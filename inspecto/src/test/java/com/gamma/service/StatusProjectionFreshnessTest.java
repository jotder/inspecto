package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Freshness of the DB-backed status projection — the blocker that gated serving the ledgers from a
 * database (pipeline-spec §13 D4).
 *
 * <p>🔴 A {@link DbStatusStore} is a PROJECTION of the on-disk audit, not a second writer, so it is only
 * as fresh as its last {@code syncStatus()}. The poll cycle refreshes itself once its last run finishes
 * ({@code PipelineScheduler.runOne}), but every OTHER run path — a manual API trigger, a {@code notify},
 * an {@code on_commit} chained run, a dataset-write trigger — funnels through
 * {@link CollectorService#runPipeline} and refreshed NOTHING. A run therefore reported <b>no commits at
 * all</b> until the next poll cycle, which is how a DB-backed store read its own commit back as empty.
 *
 * <p>⚠ D4's recorded diagnosis ("projects exactly once, at boot") was already STALE when grounded on
 * 2026-08-31: the cycle path did refresh. The defect was narrower — the trigger paths. These tests pin
 * the trigger path specifically, because that is the half that was broken.
 */
class StatusProjectionFreshnessTest {

    /** A CSV pipeline with one inbox file ready to commit. */
    private Path seed(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n");
        return toon;
    }

    /** A real DuckDB-backed store, injected the way {@code ServiceStores.openStatusStore} would. */
    private DbStatusStore dbStore(Path dir) throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        return DbStatusStore.open("jdbc:duckdb:" + dir.resolve("status.duckdb"), null, null);
    }

    @Test
    void aTriggeredRunIsQueryableImmediately(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (DbStatusStore store = dbStore(dir);
             CollectorService svc = new CollectorService(List.of(toon), List.of(), 3600, 1, store)) {
            assertSame(store, svc.statusStore(), "the injected DB store must be the read surface");

            PipelineConfig cfg = PipelineConfig.load(toon.toString());
            assertTrue(store.committedBatches(cfg).isEmpty(), "nothing has run yet");

            // The trigger path — what a manual run / notify / on_commit chain all funnel through.
            assertEquals(0, svc.runPipeline(cfg.identity().pipelineName()).orElseThrow().failed());

            // 🔴 The whole point: no poll cycle has happened, so before the fix this was still empty.
            assertFalse(store.committedBatches(cfg).isEmpty(),
                    "a triggered run's commit must be queryable without waiting for a poll cycle");
            assertFalse(store.batches(cfg).isEmpty(), "the batches ledger must see it too");
        }
    }

    /** The projection is a read model — the on-disk audit stays the durable truth either way. */
    @Test
    void theFileStoreSeesTheSameCommit(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        // The store-less constructor always hands out the file store (no -Dstatus.backend is consulted —
        // the family default is `db` since 2026-08-31, and the reactor pins jdbc:duckdb:); syncStatus() is
        // a no-op there and must stay harmless.
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            assertInstanceOf(FileStatusStore.class, svc.statusStore());
            PipelineConfig cfg = PipelineConfig.load(toon.toString());
            assertEquals(0, svc.runPipeline(cfg.identity().pipelineName()).orElseThrow().failed());
            assertFalse(svc.statusStore().committedBatches(cfg).isEmpty(),
                    "the file store reads the audit directly, so it was never stale");
        }
    }

    /** The refresh is per run, not a one-shot latch — a second trigger must project too. */
    @Test
    void aSecondTriggeredRunProjectsAsWell(@TempDir Path dir) throws Exception {
        Path toon = seed(dir);
        try (DbStatusStore store = dbStore(dir);
             CollectorService svc = new CollectorService(List.of(toon), List.of(), 3600, 1, store)) {
            PipelineConfig cfg = PipelineConfig.load(toon.toString());
            String name = cfg.identity().pipelineName();
            svc.runPipeline(name);
            int afterFirst = store.batches(cfg).size();
            assertTrue(afterFirst > 0, "the first triggered run must project");

            Files.writeString(dir.resolve("inbox").resolve("more.csv"),
                    "ID,AMT,EVENT_DATE\n3,30,2020-03-01\n");
            // ⚠ Only assert growth if the second run actually ingested something — otherwise this
            // would be testing the collector's file selection, not the projection.
            var second = svc.runPipeline(name).orElseThrow();
            org.junit.jupiter.api.Assumptions.assumeTrue(second.total() > 0,
                    "the second run picked up no file — nothing to project, so the assertion is vacuous");
            assertTrue(store.batches(cfg).size() > afterFirst, "the second commit must appear as well");
        }
    }
}