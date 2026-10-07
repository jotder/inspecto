package com.gamma.geolink;

import com.gamma.la.api.IndexStaleness;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-DAILY-INGEST-1} T6 / roadmap gap 6: does a changed Reference file make a Link Analysis Index stale? Runs the REAL
 * code path of {@code GET /inv/index}: {@link EngineDatasetProvider#inputFingerprint} + {@code relationSql} hashed by
 * {@link IndexBuilder#relationSqlHash}, compared by {@link IndexStaleness#compute}. A manifest here is what a build would
 * have recorded for the same Dataset.
 */
class ReferenceChangeIndexStalenessTest {

    private final EngineDatasetProvider provider = new EngineDatasetProvider();
    private static final String JOIN_SQL = "SELECT f.CALL_ID, f.SUBSCRIBER_ID, d.SEGMENT FROM read_parquet('facts/**/*.parquet') f "
            + "JOIN read_parquet('subscriber_dim/**/*.parquet') d USING (SUBSCRIBER_ID)";

    private static void file(Path root, String rel, int bytes, long mtime) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
    }

    /** The manifest an index build over {@code dataset} would record now. */
    private IndexManifest built(Map<String, Object> dataset, Path root) {
        InputFingerprint fp = provider.inputFingerprint(dataset, root, root);
        IndexMapping mp = new IndexMapping("s", "d", null, null, null, null, null);
        return new IndexManifest(1, "x", IndexManifest.Builder.FULL, "1.4", "hash_mod", 16, 1, mp, mp.hash(), "ds",
                IndexBuilder.relationSqlHash(provider.relationSql(dataset, root, root)), fp.value(), "UTC", Map.of(), 0, null, null,
                fp.files().stream().map(f -> new IndexManifest.InputFile(f.path(), f.size(), f.mtimeMillis())).toList());
    }

    private IndexStaleness.Result now(IndexManifest m, Map<String, Object> dataset, Path root) {
        return IndexStaleness.compute(m, IndexBuilder.relationSqlHash(provider.relationSql(dataset, root, root)),
                provider.inputFingerprint(dataset, root, root), "hash_mod", "1.4");
    }

    @Test
    void aReferenceChangeUnderAViewBackedJoinDatasetIsNotNoticed(@TempDir Path root) throws Exception {
        file(root, "facts/day1.parquet", 10, 1_000L);
        file(root, "subscriber_dim/b1.parquet", 5, 1_000L);
        new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_by_segment", "flow-x", List.of(), JOIN_SQL, "2026-10-06T00:00:00Z"));
        Map<String, Object> ds = Map.of("view", "calls_by_segment");
        IndexManifest m = built(ds, root);
        assertFalse(m.baseFingerprint().startsWith("files:"), "a view has no enumerable files: " + m.baseFingerprint());
        assertFalse(now(m, ds, root).stale());

        file(root, "subscriber_dim/b2__v_new.parquet", 7, 2_000L);          // the daily Reference file lands a new version
        file(root, "subscriber_dim/b1.parquet", 6, 3_000L);                  // and an existing one is rewritten
        IndexStaleness.Result r = now(m, ds, root);
        assertFalse(r.stale(), "GAP: the Reference changed and the index reports current - reasons=" + r.reasons());
        assertFalse(r.fingerprintKnown(), "it cannot even say whether the FACT files changed (no-files sentinel)");

        file(root, "facts/day2.parquet", 10, 4_000L);                        // a new FACT day is not seen either, for a view-backed Dataset
        assertFalse(now(m, ds, root).stale());
    }

    @Test
    void aPlainStoreDatasetIsStaleOnItsOwnFilesButBlindToAnyReference(@TempDir Path root) throws Exception {
        file(root, "facts/day1.parquet", 10, 1_000L);
        file(root, "subscriber_dim/b1.parquet", 5, 1_000L);
        Map<String, Object> ds = Map.of("physicalRef", "facts");
        IndexManifest m = built(ds, root);
        assertFalse(now(m, ds, root).stale());

        file(root, "subscriber_dim/b2.parquet", 7, 2_000L);                  // Reference changes: store 'facts' fingerprint is untouched
        assertFalse(now(m, ds, root).stale(), "the fingerprint is the Dataset's own store only");
        assertTrue(now(m, ds, root).fingerprintKnown());

        file(root, "facts/day2.parquet", 10, 3_000L);                        // control: a fact day IS noticed, and is an addition
        IndexStaleness.Result r = now(m, ds, root);
        assertEquals(List.of(IndexStaleness.INPUT_FILES_CHANGED), r.reasons());
        assertFalse(r.removedInput());
    }

    @Test
    void aVirtualSqlDatasetCannotJoinAReferenceAtAll(@TempDir Path root) throws Exception {
        file(root, "facts/day1.parquet", 10, 1_000L);
        file(root, "subscriber_dim/b1.parquet", 5, 1_000L);
        Map<String, Object> ds = Map.of("sourceName", "facts", "sql", JOIN_SQL);
        assertThrows(RuntimeException.class, () -> provider.relationSql(ds, root, root),
                "a virtual Dataset's SQL may name only its one source store; a read of another store is refused");
    }
}
