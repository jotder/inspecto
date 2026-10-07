package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The supervisor-takes-no-slot rule ({@code DUCKLE-C10-ADMISSION-POOLS-1}) on the REAL dispatcher:
 * {@link CollectorProcessor} submits every Consignment and then waits on them, so if that thread ever
 * held a pool permit, a pool of one would wedge the first cycle. Four Consignments through a pool of
 * one must drain within the timeout.
 */
class CollectorProcessorPoolTest {

    @AfterEach
    void restoreBroker() {
        ConcurrencyBroker.use(null);
    }

    @Test
    void aPipelineInAPoolOfOneDrainsEveryConsignmentWithoutDeadlock(@TempDir Path dir) throws Exception {
        ConcurrencyBroker broker = new ConcurrencyBroker();
        broker.setPools(Map.of("tight", 1));
        ConcurrencyBroker.use(broker);

        PipelineConfig cfg = PipelineConfig.load(
                PipelineConfigBatchTestRef.writePipeline(dir, "  pool: tight").toString());
        assertEquals("tight", cfg.processing().pool());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 4; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"), "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> CollectorProcessor.run(cfg),
                "the dispatcher deadlocked on a pool of one");
        assertEquals(0, CollectorProcessor.countPending(cfg), "every Consignment must have run");
        assertEquals(1, broker.freePermits("tight"), "every pool permit must be released");
    }
}
