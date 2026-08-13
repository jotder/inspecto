package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 5a — a bounded test run over real inbox files must parse through the real ingest path and
 * leave <b>zero</b> production side effects.
 *
 * <p>The contrast to hold in mind: {@code BatchProcessorTest.consolidatesGoodFilesQuarantinesBadOne}
 * asserts that a production run <em>consumes</em> the inbox — {@code assertFalse(Files.exists(a))},
 * markers written, ledgers written, the bad file moved to quarantine. <b>Every one of those
 * assertions is inverted here.</b> If a change makes this suite start agreeing with that one, the
 * feature has become destructive.
 */
class PipelineTestRunTest {

    /** A production tree with three files in the inbox: two good, one that fails field validation. */
    private record Fixture(PipelineConfig cfg, Path good1, Path good2, Path bad) {}

    private Fixture fixture(Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path a = inbox.resolve("a.csv");
        Path b = inbox.resolve("b.csv");
        Path bad = inbox.resolve("bad.csv");
        Files.writeString(a, "ID,AMT,EVENT_DATE\na1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        Files.writeString(b, "ID,AMT,EVENT_DATE\nb1,3.0,2020-04-03\nb2,4.0,2020-01-01\n");
        // Only one column on the data lines -> every row rejected -> production would QUARANTINE it,
        // which means Files.move OUT of the inbox. This file is the whole point of the suite.
        Files.writeString(bad, "ID,AMT,EVENT_DATE\njustonecolumn\nanotherbadline\n");
        return new Fixture(cfg, a, b, bad);
    }

    private static boolean isEmptyDir(String path) throws Exception {
        Path p = Path.of(path);
        if (!Files.exists(p)) return true;
        try (Stream<Path> s = Files.walk(p)) {
            return s.noneMatch(Files::isRegularFile);
        }
    }

    @Test
    void parsesRealFilesAndWritesOutputUnderScratchOnly(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path scratch = dir.resolve("scratch");

        PipelineTestRun.Result r = PipelineTestRun.run(
                f.cfg(), List.of(f.good1(), f.good2(), f.bad()), scratch);

        // It really parsed — this is a real ingest, not a stub.
        assertEquals("SUCCESS", r.status(), "two good files should yield a SUCCESS batch");
        assertEquals(4, r.totalInputRows(), "a.csv (2) + b.csv (2) accepted rows");
        assertTrue(r.rowsWritten() > 0, "rows should have been written to the scratch sink");
        assertFalse(r.outputs().isEmpty(), "partition outputs should be reported");

        // Every output path is under the scratch root, never under the production database dir.
        for (var out : r.outputs())
            assertTrue(Path.of(out.outputFile()).toAbsolutePath().startsWith(scratch.toAbsolutePath()),
                    "output escaped the scratch root: " + out.outputFile());

        // The per-file audit still reports the bad file as quarantined — the user is told the truth
        // about what would happen, even though nothing was actually moved.
        assertTrue(r.files().stream().anyMatch(x -> x.filename().equals("bad.csv")
                        && x.status().startsWith("QUARANTINED")),
                "the malformed file should be reported as quarantined: " + r.files());
    }

    /**
     * The regression test for the trap this feature was nearly shipped with:
     * {@code QuarantineManager.quarantine} does {@code Files.move} on the SOURCE file, from inside the
     * ingest half. Without staging copies, testing a malformed file would delete it from the inbox.
     */
    @Test
    void leavesEveryPickedInboxFileWhereItWasIncludingTheMalformedOne(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        String badBefore = Files.readString(f.bad());
        Path scratch = dir.resolve("scratch");

        PipelineTestRun.run(f.cfg(), List.of(f.good1(), f.good2(), f.bad()), scratch);

        assertTrue(Files.exists(f.good1()), "a.csv must not be consumed by a TEST run");
        assertTrue(Files.exists(f.good2()), "b.csv must not be consumed by a TEST run");
        assertTrue(Files.exists(f.bad()), "bad.csv must NOT be quarantined out of the user's inbox");
        assertEquals(badBefore, Files.readString(f.bad()), "the picked file must be byte-identical after");

        // ⚠ The three assertions above are ALSO true if quarantine never ran at all — which is exactly
        // how a falsification probe (staging that hands over the ORIGINAL paths) slipped past an earlier
        // version of this test: QuarantineManager's own poll-root guard threw instead of moving, so the
        // file survived for an unrelated reason. Pin the containment POSITIVELY as well: the move must
        // have happened, and it must have landed on our copy inside the scratch tree.
        try (Stream<Path> q = Files.walk(scratch)) {
            assertTrue(q.anyMatch(p -> p.getFileName().toString().equals("bad.csv")
                            && p.toString().contains("quarantine")),
                    "the staged COPY should have been quarantined inside the scratch root — if it was "
                            + "not, quarantine never ran and this test proves nothing about containment");
        }
    }

    @Test
    void writesNothingAnywhereUnderTheProductionConfigsPaths(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        var d = f.cfg().dirs();

        PipelineTestRun.run(f.cfg(), List.of(f.good1(), f.good2(), f.bad()), dir.resolve("scratch"));

        assertTrue(isEmptyDir(d.database()), "no output may land in the production database dir");
        assertTrue(isEmptyDir(d.quarantine()), "no file may land in the production quarantine dir");
        assertTrue(isEmptyDir(d.backup()), "no file may land in the production backup dir");
        assertTrue(isEmptyDir(d.markers()),
                "no processed-marker may be written — that would hide the file from a later real run");
        // The three audit ledgers + manifests: written by writeAudit/commit, neither of which we call.
        assertFalse(Files.exists(Path.of(d.statusFilePath())), "status ledger must not be written");
        assertFalse(Files.exists(Path.of(d.batchesFilePath())), "batches ledger must not be written");
        assertFalse(Files.exists(Path.of(d.lineageFilePath())), "lineage ledger must not be written");
        assertTrue(isEmptyDir(d.manifestsDir()), "no manifest may be written");
    }

    @Test
    void scratchIsFullyRemovableAfterTheRun(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        Path scratch = dir.resolve("scratch");

        PipelineTestRun.run(f.cfg(), List.of(f.good1()), scratch);
        assertTrue(Files.exists(scratch), "the run should have populated the scratch root");

        PipelineTestRun.deleteScratch(scratch);
        assertFalse(Files.exists(scratch), "deleteScratch must leave nothing behind");
        // ...and the caller's real files are still untouched by the cleanup.
        assertTrue(Files.exists(f.good1()));
    }

    @Test
    void refusesARunWithNoFiles(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTestRun.run(f.cfg(), List.of(), dir.resolve("scratch")));
    }

    @Test
    void twoPickedFilesSharingANameBothSurviveStaging(@TempDir Path dir) throws Exception {
        Fixture f = fixture(dir);
        // A second a.csv from a different directory — staging must not collapse them into one.
        Path other = Files.createDirectories(dir.resolve("elsewhere")).resolve("a.csv");
        Files.writeString(other, "ID,AMT,EVENT_DATE\nc1,9.0,2020-04-03\n");

        PipelineTestRun.Result r = PipelineTestRun.run(
                f.cfg(), List.of(f.good1(), other), dir.resolve("scratch"));

        assertEquals(3, r.totalInputRows(), "2 rows from the inbox a.csv + 1 from the other a.csv");
        assertEquals(2, r.files().size(), "both picked files should appear in the audit");
    }
}
