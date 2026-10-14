package com.gamma.la.api;

import com.gamma.la.core.CollectorCoveragePort.FileEntry;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LA-DATA-TRUST-1 S1: how a lineage ledger's written path is matched to an index manifest's data-root-relative input path. */
class DatasetFilesRoutesTest {

    private static final Set<String> INDEXED = Set.of("calls/day=2026-09-01/a.parquet");
    private static final Path ROOT = Path.of("data-root").toAbsolutePath();

    private static FileEntry file(String... outputs) {
        return new FileEntry("a.csv", "2026-09-01", 1, 0, "t", "SUCCESS", "c", "p", "b1", List.of(outputs));
    }

    private static IndexManifest manifest() {
        IndexMapping mp = new IndexMapping("who", "other", null, null, null, null, List.of());
        return new IndexManifest(3, "x", IndexManifest.Builder.FULL, "1.4", "hash_mod", 16, 1, mp, mp.hash(), "ds", "sql", "fp", null, Map.of(), 0, List.of(), null, null);
    }

    @Test
    void theDataRootPrefixIsStrippedAndMatchedExactlyWhateverTheSeparators() {
        String abs = ROOT.toString().replace('\\', '/');
        assertTrue(DatasetFilesRoutes.covered(abs + "/calls/day=2026-09-01/a.parquet", INDEXED, ROOT));
        assertTrue(DatasetFilesRoutes.covered(ROOT + "\\calls\\day=2026-09-01\\a.parquet", INDEXED, ROOT));
        assertFalse(DatasetFilesRoutes.covered(abs + "/other/calls/day=2026-09-01/a.parquet", INDEXED, ROOT), "under the root, so exact: no suffix guess");
    }

    @Test
    void aRelocatedRootFallsBackToAPathSuffix() {
        assertTrue(DatasetFilesRoutes.covered("/elsewhere/srv/calls/day=2026-09-01/a.parquet", INDEXED, ROOT));
        assertTrue(DatasetFilesRoutes.covered("calls/day=2026-09-01/a.parquet", INDEXED, null));
        assertFalse(DatasetFilesRoutes.covered("/elsewhere/srv/calls/day=2026-09-02/a.parquet", INDEXED, ROOT));
        assertFalse(DatasetFilesRoutes.covered("", INDEXED, ROOT));
        assertFalse(DatasetFilesRoutes.covered(null, INDEXED, ROOT));
    }

    @Test
    void stateIsMatchedNeverGuessed() {
        String in = "/srv/calls/day=2026-09-01/a.parquet", out = "/srv/calls/day=2026-09-02/b.parquet";
        assertNull(DatasetFilesRoutes.state(file(), manifest(), INDEXED, ROOT), "a File that wrote no partition file has no index state");
        assertEquals("NO_INDEX", DatasetFilesRoutes.state(file(in), null, null, ROOT));
        assertEquals("UNKNOWN", DatasetFilesRoutes.state(file(in), manifest(), null, ROOT), "no recorded list: past the cap or an older build");
        assertEquals("INDEXED", DatasetFilesRoutes.state(file(in), manifest(), INDEXED, ROOT));
        assertEquals("WAITING", DatasetFilesRoutes.state(file(out), manifest(), INDEXED, ROOT));
        assertEquals("WAITING", DatasetFilesRoutes.state(file(in, out), manifest(), INDEXED, ROOT), "indexed only when EVERY partition file is");
    }
}
