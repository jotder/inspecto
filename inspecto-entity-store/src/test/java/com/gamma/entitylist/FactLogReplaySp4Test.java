package com.gamma.entitylist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SP4 fast checks: the bulk generator writes a log the REAL chain verification accepts, and the head-cache prototype's contract. */
class FactLogReplaySp4Test {

    @Test
    void generatedLogVerifiesAndTheRealAppendContinuesIt(@TempDir Path root) throws Exception {
        String head = Sp4FactLogs.generate(root, 300);
        EntityFactLog log = new EntityFactLog(root);
        EntityFactLog.Log read = log.read();
        assertEquals(300, read.headSeq());
        assertEquals(head, read.headHash());
        EntityFactLog.Log next = log.append(read, "a", "t", "list.member.added", "l3", Map.of("keys", java.util.List.of("K")));
        assertEquals(301, log.read().headSeq());
        assertEquals(next.headHash(), log.read().headHash());
        assertEquals(100, EntityRegistry.fold(log.read().facts(), 301).size(), "100 lists folded");
    }

    @Test
    void headCacheHitsAfterFirstReadSeesNewTailAndMissesDeepTamper(@TempDir Path root) throws Exception {
        Sp4FactLogs.generate(root, 200);
        EntityFactLog log = new EntityFactLog(root);
        Sp4FactLogs.HeadCached cache = new Sp4FactLogs.HeadCached(log);
        EntityFactLog.Log first = cache.read();
        assertSame(first, cache.read(), "second read is a hit: same instance");

        EntityFactLog.Log appended = log.append(first, "a", "t", "list.member.added", "l1", Map.of("keys", java.util.List.of("K")));
        assertEquals(201, cache.read().headSeq(), "a new tail is seen");
        assertEquals(appended.headHash(), cache.read().headHash());

        // Tamper a fact deep in the chain: the FULL verify refuses; the head cache does not notice. This is the documented
        // trade-off of caching on the head hash, which is why a periodic full verify must back it.
        Path deep = log.directory().resolve(String.format("%012d.json", 50));
        Files.writeString(deep, Files.readString(deep).replace("sp4", "xxx"));
        assertThrows(EntityFactLog.BrokenChainException.class, log::read);
        assertEquals(201, cache.read().headSeq());

        // Tamper the HEAD file: the cache falls back to the full verify and refuses.
        Path head = log.directory().resolve(String.format("%012d.json", 201));
        Files.writeString(head, Files.readString(head).replace("\"K\"", "\"Z\""));
        assertThrows(EntityFactLog.BrokenChainException.class, cache::read);
    }
}
