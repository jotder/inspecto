package com.gamma.inspector;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KAFKA-OFFSET-REKEY-1: a remote connector stashes its slice frontier under the STAGING path it wrote, but the
 * commit takes it by the INBOX path. The land step must hand the stash over with the move, and must drop it when
 * the slice is quarantined or fails to land, so no stale frontier can ever commit.
 */
class RemoteSliceFrontierRekeyTest {

    private static final byte[] PAYLOAD = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n".getBytes();

    private static PipelineConfig config(Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
                max_bytes: 268435456
            """);
        return PipelineConfig.load(toon.toString());
    }

    private static RemoteFile listed(String relativePath, long size) {
        return new RemoteFile(relativePath, relativePath, size, null, null, null, null);
    }

    /** Writes PAYLOAD to dest and stashes a frontier under dest, exactly like KafkaConnector / DbExportConnector. */
    private static final class StashingConnector implements CollectorConnector {
        final AtomicReference<Path> lastDest = new AtomicReference<>();

        @Override public String scheme() { return "fake"; }
        @Override public EnumSet<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }
        @Override public List<RemoteFile> discover(DiscoveryContext ctx) { return List.of(); }
        @Override public void post(RemoteFile file, PostAction action) { }
        @Override public Readiness readiness(RemoteFile file) { return Readiness.READY; }
        @Override public InputStream open(RemoteFile file) { return new ByteArrayInputStream(PAYLOAD); }

        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            lastDest.set(dest);
            try {
                Files.createDirectories(dest.getParent());
                Files.write(dest, PAYLOAD);
            } catch (IOException e) {
                throw new AcquisitionException("fake fetch failed", e);
            }
            AcquisitionLedgers.stashDbWatermark(dest, "kafka:t:0", "42");
            return dest;
        }
    }

    @Test
    void aLandedSliceCarriesItsFrontierToTheInboxPath(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        StashingConnector c = new StashingConnector();

        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, c, List.of(listed("slice_1.jsonl", PAYLOAD.length)), RetryPolicy.NONE, false);

        assertEquals(1, out.size());
        Path landed = out.get(0).localPath();
        assertTrue(AcquisitionLedgers.takeDbWatermark(c.lastDest.get()).isEmpty(),
                "nothing is left under the staging path");
        var wm = AcquisitionLedgers.takeDbWatermark(landed);
        assertTrue(wm.isPresent(), "the commit side finds the frontier by the inbox path");
        assertEquals("kafka:t:0", wm.get().key());
        assertEquals("42", wm.get().value());
    }

    @Test
    void aQuarantinedSliceLeavesNoFrontier(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        StashingConnector c = new StashingConnector();

        // The listing promises more bytes than arrive: integrity fails and the slice is quarantined.
        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, c, List.of(listed("slice_2.jsonl", PAYLOAD.length + 100L)), RetryPolicy.NONE, false);

        assertTrue(out.isEmpty(), "the slice was rejected");
        assertTrue(AcquisitionLedgers.takeDbWatermark(c.lastDest.get()).isEmpty(),
                "a rejected slice's frontier is dropped, never left to commit later");
        assertTrue(AcquisitionLedgers.takeDbWatermark(Path.of(cfg.dirs().poll()).resolve("slice_2.jsonl")).isEmpty());
    }

    @Test
    void aSliceThatFailsToLandLeavesNoFrontier(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Path inbox = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
        // A non-empty directory squats on the landing target, so the move fails.
        Files.createDirectories(inbox.resolve("slice_3.jsonl").resolve("occupied"));
        Files.write(inbox.resolve("slice_3.jsonl").resolve("occupied").resolve("x"), new byte[]{1});
        StashingConnector c = new StashingConnector();

        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, c, List.of(listed("slice_3.jsonl", PAYLOAD.length)), RetryPolicy.NONE, false);

        assertTrue(out.isEmpty(), "the slice did not land");
        assertTrue(Files.exists(c.lastDest.get()), "its bytes stay staged");
        assertTrue(AcquisitionLedgers.takeDbWatermark(c.lastDest.get()).isEmpty(),
                "an unlanded slice's frontier is dropped (the next cycle re-fetches and re-stashes it)");
        assertTrue(AcquisitionLedgers.takeDbWatermark(inbox.resolve("slice_3.jsonl")).isEmpty());
    }
}
