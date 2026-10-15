package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.IngestActivity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code LA-CONFIG-LIMITS-1} CL-12 on the REAL dispatcher: every Consignment of a cycle counts in {@link IngestActivity}
 * from before it asks the broker for a slot until its slot is released, so an index build that waits on the signal cannot
 * start while ingest holds - or is about to take - the host's memory. The pool is held by the test, so the Consignments are
 * deterministically parked in admission while the count is read.
 */
class CollectorProcessorIngestActivityTest {

    @AfterEach
    void restoreBroker() {
        ConcurrencyBroker.use(null);
    }

    @Test
    void consignmentsCountAsIngestWhileWaitingAndRunningAndStopCountingWhenDone(@TempDir Path dir) throws Exception {
        ConcurrencyBroker broker = new ConcurrencyBroker();
        broker.setPools(Map.of("tight", 1));
        ConcurrencyBroker.use(broker);
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, "  pool: tight").toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 3; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"), "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        int base = IngestActivity.inFlight();
        CompletableFuture<Void> cycle;
        try (ConcurrencyBroker.Permit held = broker.admit("other", "holder", "tight", 1, 1, "held")) {
            cycle = CompletableFuture.runAsync(() -> {
                try {
                    CollectorProcessor.run(cfg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (IngestActivity.inFlight() < base + 1) {   // parked in admission: the pool's one permit is held above
                if (System.nanoTime() > end) throw new AssertionError("no Consignment counted as ingest while waiting for a slot");
                Thread.sleep(5);
            }
            assertFalse(cycle.isDone(), "the cycle cannot finish while its pool is held");
        }
        cycle.get(60, TimeUnit.SECONDS);
        assertEquals(0, CollectorProcessor.countPending(cfg), "every Consignment must have run");
        assertEquals(base, IngestActivity.inFlight(), "every Consignment's ingest scope must be closed");
    }
}
