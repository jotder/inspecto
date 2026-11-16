package com.gamma.inspector;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.IntakeGovernor;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MoSCoW C8, the branch-aware residual (f): the per-cycle byte cap ({@code -Dingest.maxBytesPerCycle}) applied
 * <b>before</b> a remote fetch when the listing carries a size for every file ({@link RemoteFile#size()}, the
 * listing-with-sizes seam), so the deferred files cost no fetch bandwidth. An unknown size keeps the old
 * behaviour: the whole listing is fetched and the cap applies to the landed files.
 *
 * <p>Driven through {@link RemoteAcquisitionHandler#materializeRemote} with a connector that counts its fetches,
 * the idiom of {@link RemoteAcquisitionStagingTest}: a fetch that never happens is the only proof that the
 * bandwidth was saved.
 */
class RemotePreFetchByteCapTest {

    @AfterEach
    void restoreSystemPolicy() {
        IntakeGovernor.use(null);
    }

    private static void byteCap(long bytes) {
        IntakeGovernor.use(new IntakeGovernor(new IntakeGovernor.Policy(0, 1, true, bytes)));   // no file cap
    }

    private static PipelineConfig config(Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, """
              batch:
                max_files: 100
                max_bytes: 268435456
            """);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        return cfg;
    }

    /** {@code f<i>.csv}, {@code size} bytes ({@link RemoteFile#SIZE_UNKNOWN} allowed), aged by {@code i}: f0 is oldest. */
    private static RemoteFile listed(int i, long size) {
        String rel = "f" + i + ".csv";
        return new RemoteFile(rel, rel, size, Instant.ofEpochMilli(1_000_000L + i * 1000L), null, null, null);
    }

    /** A connector that records every fetch and writes as many bytes as the listing said (10 when unsized). */
    private static final class CountingConnector implements CollectorConnector {
        final List<String> fetched = Collections.synchronizedList(new ArrayList<>());

        @Override public String scheme() { return "counting"; }
        @Override public EnumSet<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }
        @Override public List<RemoteFile> discover(DiscoveryContext ctx) { return List.of(); }
        @Override public Readiness readiness(RemoteFile file) { return Readiness.READY; }
        @Override public InputStream open(RemoteFile file) { return new ByteArrayInputStream(new byte[0]); }
        @Override public void post(RemoteFile file, PostAction action) { }

        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            fetched.add(file.relativePath());
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.write(dest, new byte[(int) (file.hasSize() ? file.size() : 10)]);
            } catch (IOException e) {
                throw new AcquisitionException("counting fetch failed", e);
            }
            return dest;
        }
    }

    private static List<RemoteFile> acquire(PipelineConfig cfg, CountingConnector c, List<RemoteFile> ready) {
        return RemoteAcquisitionHandler.materializeRemote(cfg, c, ready, RetryPolicy.NONE, false);
    }

    @Test
    void aSizedListingOverTheCapIsLimitedBeforeAnyFetch(@TempDir Path dir) throws Exception {
        byteCap(1500);   // 600-byte files: two fit
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();
        // Listed newest-first, so a cap that kept listing order would pick f4 and f3.
        List<RemoteFile> ready = List.of(listed(4, 600), listed(3, 600), listed(2, 600), listed(1, 600), listed(0, 600));

        List<RemoteFile> landed = acquire(cfg, c, ready);

        assertEquals(List.of("f0.csv", "f1.csv"), List.copyOf(c.fetched),
                "only the two oldest files that fit the cap are fetched; the other three are never fetched");
        assertEquals(2, landed.size(), "and only they land in the inbox");
    }

    @Test
    void theDeferredFilesAreFetchedOnLaterCycles(@TempDir Path dir) throws Exception {
        byteCap(1500);
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();
        List<RemoteFile> remaining = new ArrayList<>(List.of(listed(0, 600), listed(1, 600), listed(2, 600)));

        acquire(cfg, c, remaining);
        assertEquals(2, c.fetched.size(), "first cycle: two of three");
        remaining.removeIf(rf -> c.fetched.contains(rf.relativePath()));   // the source re-lists what was not fetched
        acquire(cfg, c, remaining);

        assertEquals(List.of("f0.csv", "f1.csv", "f2.csv"), List.copyOf(c.fetched),
                "the deferred file stays on the source and is fetched next cycle, each file once");
    }

    @Test
    void aListedFileLargerThanTheCapIsFetchedAloneRatherThanStarved(@TempDir Path dir) throws Exception {
        byteCap(100);
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();

        acquire(cfg, c, List.of(listed(1, 50), listed(0, 4000)));

        assertEquals(List.of("f0.csv"), List.copyOf(c.fetched),
                "the oldest file is over the cap on its own: it is fetched ALONE, never deferred forever");
    }

    @Test
    void aKnownDuplicateDoesNotSpendTheBudget(@TempDir Path dir) throws Exception {
        byteCap(1300);   // two 600-byte files fit
        PipelineConfig cfg = config(dir);
        assertTrue(cfg.processing().duplicateCheckEnabled() && !cfg.collector().duplicate().contentBased(),
                "precondition: marker (path) dedup is on for this pipeline");
        Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
        // f0 and f1 were processed already and are re-listed by a RETAIN source.
        MarkerManager.createMarkerFile(poll.resolve("f0.csv").toFile(), cfg);
        MarkerManager.createMarkerFile(poll.resolve("f1.csv").toFile(), cfg);
        CountingConnector c = new CountingConnector();

        acquire(cfg, c, List.of(listed(0, 600), listed(1, 600), listed(2, 600), listed(3, 600), listed(4, 600)));

        assertEquals(List.of("f2.csv", "f3.csv"), List.copyOf(c.fetched),
                "the cap runs after the pre-fetch dedup, so the two oldest duplicates do not use up the budget, "
                        + "and f4 waits");
    }

    @Test
    void unknownSizesKeepTheOldBehaviourAndFetchTheWholeListing(@TempDir Path dir) throws Exception {
        byteCap(1500);
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();
        List<RemoteFile> ready = new ArrayList<>();
        for (int i = 0; i < 5; i++) ready.add(listed(i, RemoteFile.SIZE_UNKNOWN));

        assertEquals(5, acquire(cfg, c, ready).size());
        assertEquals(5, c.fetched.size(), "no listing size: every file is fetched; the cap applies after landing");
    }

    @Test
    void oneUnknownSizeFetchesTheWholeListing(@TempDir Path dir) throws Exception {
        byteCap(1500);
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();
        List<RemoteFile> ready = List.of(listed(0, 600), listed(1, 600), listed(2, 600), listed(3, 600),
                listed(4, RemoteFile.SIZE_UNKNOWN));

        acquire(cfg, c, ready);

        assertEquals(5, c.fetched.size(),
                "a partly sized listing is not capped before the fetch: an unsized file would otherwise overtake older ones");
    }

    @Test
    void noByteCapFetchesEverySizedFile(@TempDir Path dir) throws Exception {
        byteCap(IntakeGovernor.Policy.UNBOUNDED_BYTES);
        PipelineConfig cfg = config(dir);
        CountingConnector c = new CountingConnector();

        acquire(cfg, c, List.of(listed(0, 600), listed(1, 600), listed(2, 600)));

        assertEquals(3, c.fetched.size(), "the byte cap is off by default, and then nothing is deferred");
    }
}
