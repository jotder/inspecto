package com.gamma.inspector;

import com.gamma.acquire.AcquisitionException;
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
 * B3 — <b>stage, then land</b>: {@link RemoteAcquisitionHandler} fetches into a staging tree outside the inbox
 * and only moves a complete, verified file into {@code dirs.poll}.
 *
 * <p>Why this matters: fetch resumes by appending to its destination, so before B3 a half-downloaded file sat in
 * the inbox under its final name. That was safe only because acquisition and ingest ran in the same synchronous
 * call under one run-guard claim — the coupling B3 exists to remove. These tests pin the property that replaces
 * it, so acquisition can move to its own driver without a partial ever being ingestible.
 *
 * <p>The handler is driven directly with a fake connector: it is package-private and static, so no SFTP server
 * or network is involved, and {@link #fetchTo} can be made to observe the filesystem mid-fetch — which is the
 * only way to assert "the inbox was empty while bytes were still arriving".
 */
class RemoteAcquisitionStagingTest {

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

    /** A connector whose {@code fetchTo} writes {@code PAYLOAD} to wherever it is told, running {@code onFetch} first. */
    private static class FakeConnector implements CollectorConnector {
        private final Runnable onFetch;
        private final AtomicReference<Path> lastDest = new AtomicReference<>();

        FakeConnector(Runnable onFetch) { this.onFetch = onFetch; }

        @Override public String scheme() { return "fake"; }
        @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.RESUMABLE); }
        @Override public List<RemoteFile> discover(DiscoveryContext ctx) { return List.of(); }
        @Override public void post(RemoteFile file, PostAction action) { }
        @Override public Readiness readiness(RemoteFile file) { return Readiness.READY; }
        @Override public InputStream open(RemoteFile file) {
            return new ByteArrayInputStream(PAYLOAD);   // unused here: this path stages to disk, never streams
        }

        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            lastDest.set(dest);
            if (onFetch != null) onFetch.run();
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.write(dest, PAYLOAD);
            } catch (IOException e) {
                throw new AcquisitionException("fake fetch failed", e);
            }
            return dest;
        }
    }

    private static long fileCount(Path root) throws Exception {
        if (!Files.exists(root)) return 0;
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void bytesLandInTheInboxButAreFetchedOutsideIt(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);

        // Captured DURING the fetch: at that instant the bytes must not be anywhere under the inbox.
        AtomicReference<Long> inboxFilesDuringFetch = new AtomicReference<>();
        FakeConnector connector = new FakeConnector(() -> {
            try { inboxFilesDuringFetch.set(fileCount(inbox)); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, connector, List.of(listed("cdr_0001.csv", PAYLOAD.length)), RetryPolicy.NONE);

        assertEquals(0L, inboxFilesDuringFetch.get(),
                "the inbox must be empty while the bytes are still arriving — a partial is never ingestible");
        Path dest = connector.lastDest.get();
        assertNotNull(dest, "the connector was asked to fetch somewhere");
        assertFalse(dest.startsWith(inbox), "fetch destination must be outside the inbox, was " + dest);
        assertTrue(dest.startsWith(Path.of(cfg.dirs().temp()).toAbsolutePath().normalize()),
                "the default staging tree lives under dirs.temp, was " + dest);

        assertEquals(1, out.size(), "the file was materialised");
        Path landed = out.get(0).localPath();
        assertTrue(landed.startsWith(inbox), "the landed file is in the inbox, was " + landed);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(landed), "the landed bytes are the fetched bytes");
        assertFalse(Files.exists(dest), "the staging copy is moved, not left behind");
    }

    @Test
    void aPartialLeftInStagingIsResumedAndNeverLandedOnItsOwn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path staging = Path.of(cfg.dirs().temp()).toAbsolutePath().normalize().resolve("acquire");

        // Simulate a crash mid-fetch: a partial sits in staging under the file's real (deterministic) name.
        Files.createDirectories(staging);
        Path partial = staging.resolve("cdr_0002.csv");
        Files.write(partial, "ID,AMT".getBytes());

        assertEquals(0, fileCount(inbox), "the crashed partial is NOT in the inbox");

        // The next attempt must be handed that same path, so a resumable connector can continue it.
        FakeConnector connector = new FakeConnector(null);
        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, connector, List.of(listed("cdr_0002.csv", PAYLOAD.length)), RetryPolicy.NONE);

        assertEquals(partial, connector.lastDest.get(),
                "the staging path is deterministic, so the partial is resumed rather than restarted elsewhere");
        assertEquals(1, out.size(), "the completed file was materialised");
        assertTrue(out.get(0).localPath().startsWith(inbox), "and landed in the inbox once complete");
        assertEquals(1, fileCount(inbox), "exactly one file landed");
    }

    @Test
    void aPathEscapingTheInboxIsRejected(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        FakeConnector connector = new FakeConnector(null);

        List<RemoteFile> out = RemoteAcquisitionHandler.materializeRemote(
                cfg, connector, List.of(listed("../../escaped.csv", PAYLOAD.length)), RetryPolicy.NONE);

        assertTrue(out.isEmpty(), "a listing path that escapes its root is skipped, not fetched");
        assertNull(connector.lastDest.get(), "no bytes were requested at all");
        assertFalse(Files.exists(dir.resolve("escaped.csv")), "and nothing was written outside the roots");
    }

    @Test
    void stagingInsideTheInboxIsRefused(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
            """);
        // Point staging INSIDE the inbox — honouring it would put partial downloads back where ingest can see
        // them, so the handler must refuse before any bytes move.
        String poll = Path.of(PipelineConfig.load(toon.toString()).dirs().poll()).toString().replace("\\", "/");
        Files.writeString(toon, Files.readString(toon) + """
            collector:
              fetch:
                staging_dir: %s/inner
            """.formatted(poll));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        FakeConnector connector = new FakeConnector(null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                RemoteAcquisitionHandler.materializeRemote(
                        cfg, connector, List.of(listed("x.csv", PAYLOAD.length)), RetryPolicy.NONE));
        assertTrue(e.getMessage().contains("staging"), "the refusal names the offending setting: " + e.getMessage());
        assertNull(connector.lastDest.get(), "refused before any bytes moved");
    }
}
