package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SafetyPolicyTier;
import com.gamma.config.safety.StateRefusedException;
import com.gamma.etl.Consignment;
import com.gamma.etl.ConsignmentAuditWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T12 (policy-narrowing-design §7), the {@code advance_state} half: a Kafka slice (its offset stashed against the
 * landed file, exactly as {@code KafkaConnector.fetchTo} does) in a Pipeline with no markers and no duplicate check
 * - so only the frontier (M3) would move - is refused at batch start under {@code permit.advance_state false}:
 * no output is written and no offset is recorded. Without the batch-start check the outputs would be written
 * and then stranded by the M3 backstop, and the next run would re-ingest them.
 */
class KafkaSliceAdvanceStateTest {

    private static final String KEY = "kafka:t:0";
    private final InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();

    @AfterEach
    void restore() {
        AcquisitionLedgers.use(null);
    }

    private static PipelineConfig frontierOnlyPipeline(Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        String text = Files.readString(toon)
                .replaceAll("(?m)^\\s*markers: .*\\R", "")
                .replace("enabled: true", "enabled: false");
        Files.writeString(toon, text);
        return PipelineConfig.load(toon.toString());
    }

    private static <T> T underAdvance(Path root, Boolean advance, java.util.concurrent.Callable<T> r) {
        SafetyPolicyTier tier = new SafetyPolicyTier(null, null, null, advance, null,
                null, null, null, null, null, null, null, null, null, null);
        return SafetyPolicy.runWithPinned(new SafetyPolicy.Pin("default", SafetyPolicy.withRoots(root), tier), () -> {
            try { return r.call(); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static long filesUnder(String dir) throws Exception {
        Path root = Path.of(dir);
        if (!Files.exists(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) { return s.filter(Files::isRegularFile).count(); }
    }

    private static void run(PipelineConfig cfg, Path slice) {
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_mini_0001", "mini", null,
                List.of(new Consignment.Member(slice.toFile(), 0, slice.toFile().length(),
                        new SchemaSelector.Selection(cfg.schemas().single(), null))));
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(cfg.dirs().statusFilePath(),
                cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    @Test
    void advanceStateFalseRefusesAKafkaSliceBeforeAnyOutput(@TempDir Path dir) throws Exception {
        AcquisitionLedgers.use(ledger);
        PipelineConfig cfg = frontierOnlyPipeline(dir);
        assertFalse(cfg.processing().duplicateCheckEnabled(), "harness: no fingerprint ledger");
        assertNull(cfg.dirs().markers(), "harness: no markers - only the frontier would advance");
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path slice = Files.writeString(inbox.resolve("slice_1.csv"), "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n")
                .toAbsolutePath().normalize();
        AcquisitionLedgers.stashDbWatermark(slice, KEY, "42");

        Throwable refused = null;
        try {
            underAdvance(dir, false, () -> { run(cfg, slice); return null; });
        } catch (RuntimeException e) {
            refused = e;
        }
        boolean byGate = false;
        for (Throwable c = refused; c != null; c = c.getCause()) byGate |= c instanceof StateRefusedException;
        assertTrue(byGate, "refused by the state gate: " + refused);
        assertEquals(0, filesUnder(cfg.dirs().database()), "no output written");
        assertTrue(ledger.dbWatermark(KEY).isEmpty(), "no offset recorded");

        // the probe that succeeds: the same slice with advance permitted writes its output and records the offset
        AcquisitionLedgers.stashDbWatermark(slice, KEY, "42");
        underAdvance(dir, true, () -> { run(cfg, slice); return null; });
        assertTrue(filesUnder(cfg.dirs().database()) > 0, "output written");
        assertEquals(java.util.Optional.of("42"), ledger.dbWatermark(KEY), "offset recorded");
    }
}
