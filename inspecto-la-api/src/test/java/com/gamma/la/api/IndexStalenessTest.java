package com.gamma.la.api;

import com.gamma.la.core.InputFingerprint;
import com.gamma.la.core.InputFingerprint.FileStamp;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link IndexStaleness}: every reason, their precedence, removedInput and the 'cannot tell' cases (D-3 design 5.3a). */
class IndexStalenessTest {

    private static final FileStamp A = new FileStamp("s/a.parquet", 10, 1000), B = new FileStamp("s/b.parquet", 20, 2000);

    private static IndexManifest manifest(InputFingerprint built, boolean recordList, String sqlHash, String bucketFn, String duck) {
        IndexMapping mp = new IndexMapping("s", "d", null, null, null, null, null);
        List<IndexManifest.InputFile> files = recordList
                ? built.files().stream().map(f -> new IndexManifest.InputFile(f.path(), f.size(), f.mtimeMillis())).toList() : null;
        return new IndexManifest(1, "x", IndexManifest.Builder.FULL, duck, bucketFn, 16, 1, mp, mp.hash(), "ds", sqlHash,
                built.value(), "UTC", Map.of(), 0, null, null, files);
    }

    private static IndexStaleness.Result at(IndexManifest m, InputFingerprint now) {
        return IndexStaleness.compute(m, "sql", now, "hash_mod", "1.4");
    }

    @Test
    void unchangedIsNotStaleAndKnown() {
        InputFingerprint built = InputFingerprint.ofFiles(List.of(A, B));
        IndexStaleness.Result r = at(manifest(built, true, "sql", "hash_mod", "1.4"), InputFingerprint.ofFiles(List.of(B, A)));
        assertFalse(r.stale());
        assertTrue(r.fingerprintKnown());
        assertFalse(r.removedInput());
        assertNull(r.reason());
    }

    @Test
    void anAddedFileIsStaleButNotRemoved() {
        IndexManifest m = manifest(InputFingerprint.ofFiles(List.of(A)), true, "sql", "hash_mod", "1.4");
        IndexStaleness.Result r = at(m, InputFingerprint.ofFiles(List.of(A, B)));
        assertEquals(List.of(IndexStaleness.INPUT_FILES_CHANGED), r.reasons());
        assertFalse(r.removedInput());
    }

    @Test
    void aDeletedChangedOrTouchedFileIsRemovedInput() {
        IndexManifest m = manifest(InputFingerprint.ofFiles(List.of(A, B)), true, "sql", "hash_mod", "1.4");
        assertTrue(at(m, InputFingerprint.ofFiles(List.of(A))).removedInput(), "deleted");
        assertTrue(at(m, InputFingerprint.ofFiles(List.of(A, new FileStamp(B.path(), 21, 2000)))).removedInput(), "size");
        assertTrue(at(m, InputFingerprint.ofFiles(List.of(A, new FileStamp(B.path(), 20, 2001)))).removedInput(), "mtime");
    }

    @Test
    void withoutARecordedListAChangeCannotBeClassifiedSoItIsTreatedAsRemoved() {
        IndexManifest m = manifest(InputFingerprint.ofFiles(List.of(A)), false, "sql", "hash_mod", "1.4");
        IndexStaleness.Result r = at(m, InputFingerprint.ofFiles(List.of(A, B)));
        assertTrue(r.stale());
        assertTrue(r.removedInput());
    }

    @Test
    void everyOtherReasonOnItsOwn() {
        InputFingerprint f = InputFingerprint.ofFiles(List.of(A));
        assertEquals(List.of(IndexStaleness.RELATION_SQL_CHANGED), at(manifest(f, true, "old", "hash_mod", "1.4"), f).reasons());
        assertEquals(List.of(IndexStaleness.BUCKET_FUNCTION_CHANGED), at(manifest(f, true, "sql", "other", "1.4"), f).reasons());
        assertEquals(List.of(IndexStaleness.DUCKDB_VERSION_CHANGED), at(manifest(f, true, "sql", "hash_mod", "1.3"), f).reasons());
        assertEquals(List.of(IndexStaleness.RELATION_UNRESOLVABLE),
                IndexStaleness.compute(manifest(f, true, "sql", "hash_mod", "1.4"), null, f, "hash_mod", "1.4").reasons());
        assertFalse(at(manifest(f, true, "old", "hash_mod", "1.4"), f).removedInput(), "a SQL change alone is not removed input");
    }

    @Test
    void precedenceIsInputThenSqlThenBucketThenDuckdb() {
        IndexManifest m = manifest(InputFingerprint.ofFiles(List.of(A)), true, "old", "other", "1.3");
        IndexStaleness.Result r = at(m, InputFingerprint.ofFiles(List.of(A, B)));
        assertEquals(List.of(IndexStaleness.INPUT_FILES_CHANGED, IndexStaleness.RELATION_SQL_CHANGED,
                IndexStaleness.BUCKET_FUNCTION_CHANGED, IndexStaleness.DUCKDB_VERSION_CHANGED), r.reasons());
        assertEquals(IndexStaleness.INPUT_FILES_CHANGED, r.reason());
        assertEquals(4, r.details().size());
    }

    @Test
    void unknownFingerprintsClaimNothingAndAreNotStale() {
        InputFingerprint files = InputFingerprint.ofFiles(List.of(A));
        InputFingerprint none = InputFingerprint.noFiles("SELECT 1");
        for (IndexManifest m : List.of(manifest(none, false, "sql", "hash_mod", "1.4"), manifest(files, true, "sql", "hash_mod", "1.4"))) {
            for (InputFingerprint now : new InputFingerprint[] {none, InputFingerprint.tooMany(100_000), null}) {
                IndexStaleness.Result r = at(m, now);
                assertFalse(r.stale(), String.valueOf(now));
                assertFalse(r.fingerprintKnown(), String.valueOf(now));
            }
        }
        // a pre-grounding manifest ("relation-sql-only:...") against real files: cannot compare
        IndexManifest old = manifest(new InputFingerprint("relation-sql-only:abc", List.of()), false, "sql", "hash_mod", "1.4");
        assertFalse(at(old, files).fingerprintKnown());
        assertFalse(at(old, files).stale());
    }

    @Test
    void theFingerprintIsOrderIndependentAndPathRelative() {
        assertEquals(InputFingerprint.ofFiles(List.of(A, B)).value(), InputFingerprint.ofFiles(List.of(B, A)).value());
        assertTrue(InputFingerprint.noFiles("x").value().startsWith("no-files:"));
        assertEquals("too-many-files:100000", InputFingerprint.tooMany(InputFingerprint.MAX_FILES).value());
    }
}
