package com.gamma.la.storage;

import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** LA-INDEX-READER-POOL-1 - sealed readers are reused per version directory and dropped when a newer version is borrowed. */
class IndexReaderPoolTest {

    private static final String REL = "SELECT * FROM (VALUES ('A','B','call',TIMESTAMP '2026-01-01 10:00:00',1.5),"
            + "('A','C','sms',TIMESTAMP '2026-01-02 10:00:00',2.5)) AS v(s,t,k,ts,w)";

    @AfterEach
    void drain() {
        IndexReader.evictAll();
    }

    private static IndexBuilder.Result build(Path root) {
        IndexMapping m = new IndexMapping("s", "t", "k", "ts", null, "w", List.of());
        return IndexBuilder.build(new IndexBuilder.Request("g", m, REL, new IndexStore(root, "g", m.hash()), "fp", null));
    }

    private static IndexReader borrow(IndexBuilder.Result r) throws Exception {
        return IndexReader.borrow(r.directory(), r.manifest(), SqlSandboxPolicy.defaultPolicy());
    }

    @Test
    void twoConsecutiveReadsReuseTheOpenedHandle(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result v = build(tmp);
        long before = IndexReader.OPENS.get();
        for (int i = 0; i < 3; i++) {
            try (IndexReader r = borrow(v)) {
                assertEquals(2, r.edges(List.of("A"), List.of(Side.OUT), null, 10).get("A").size());
            }
        }
        assertEquals(1, IndexReader.OPENS.get() - before, "three reads, one sealed open");
        assertEquals(1, IndexReader.idleCount(v.directory()));
    }

    @Test
    void aNewerVersionEvictsTheOldVersionsHandles(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result v1 = build(tmp);
        try (IndexReader r = borrow(v1)) {
            r.edges(List.of("A"), List.of(Side.OUT), null, 10);
        }
        assertEquals(1, IndexReader.idleCount(v1.directory()));
        IndexBuilder.Result v2 = build(tmp);
        assertNotEquals(v1.directory(), v2.directory());
        try (IndexReader r = borrow(v2)) {
            r.edges(List.of("A"), List.of(Side.OUT), null, 10);
        }
        assertEquals(0, IndexReader.idleCount(v1.directory()), "the superseded version's handle is closed");
        assertEquals(1, IndexReader.idleCount(v2.directory()));
    }

    @Test
    void concurrentReadsAreSafe(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result v = build(tmp);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                fs.add(pool.submit(() -> {
                    try (IndexReader r = borrow(v)) {
                        return r.edges(List.of("A"), List.of(Side.OUT), null, 10).get("A").size();
                    }
                }));
            }
            for (Future<Integer> f : fs) assertEquals(2, f.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
