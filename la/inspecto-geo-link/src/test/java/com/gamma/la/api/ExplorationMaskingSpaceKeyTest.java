package com.gamma.la.api;

import com.gamma.entitystore.MaskTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BI-QUERY-MASKING-1: the exploration alias of an id is the token under the Space's ONE identity key
 * ({@link MaskTokens#spaceKey}), which BI result masking also uses, so a masked key in a Widget is the same alias as in
 * a Link Analysis read and an Entity List.
 */
class ExplorationMaskingSpaceKeyTest {

    @Test
    void theExplorationAliasIsTheSpaceKeyToken(@TempDir Path root) throws Exception {
        String alias = ExplorationMasking.alias(root, "441700900101");
        assertEquals(MaskTokens.token(MaskTokens.spaceKey(root), "441700900101"), alias);
        assertTrue(alias.startsWith("masked:") && alias.length() == 7 + 16, alias);
        assertTrue(Files.isRegularFile(com.gamma.util.SpaceSecretKeys.keyFile(root, ".risk-score-mask.key")),
                "the Space key lives in the secrets directory beside the root, outside every exported tree");
    }

    /** LA-EDITIONS-RESIDUALS-1 4c: an alias minted before a restart still resolves after it, from the Space's book file. */
    @Test
    void anAliasSurvivesARestart(@TempDir Path root, @TempDir Path other) {
        String alias = ExplorationMasking.alias(root, "441700900101");
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals("441700900101", ExplorationMasking.lookup(root, alias));
        assertEquals(null, ExplorationMasking.lookup(other, alias), "the book is per Space");
        assertTrue(Files.isRegularFile(com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book")),
                "the book lives in the secrets directory beside the root, outside every exported tree");
    }

    /** A line torn mid-append whose truncated base64 still decodes (to a shorter raw) is never believed. */
    @Test
    void aTornLineIsNotBelieved(@TempDir Path root) throws Exception {
        String alias = ExplorationMasking.alias(root, "441700900101");
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        String line = Files.readString(book).trim();
        String b64 = line.substring(line.indexOf('\t') + 1);
        String torn = alias + "\t" + b64.substring(0, 12) + "\n";   // 12 chars decode to 9 bytes: "441700900"
        Files.writeString(book, torn);
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals(null, ExplorationMasking.lookup(root, alias));
    }

    /** Concurrent first lookups after a restart all resolve. */
    @Test
    void concurrentFirstLookupsAfterARestartAllResolve(@TempDir Path root) throws Exception {
        String alias = ExplorationMasking.alias(root, "441700900101");
        for (int i = 0; i < 2000; i++) ExplorationMasking.alias(root, "n" + i);
        ExplorationMasking.forgetInMemoryForTest();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(40);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 40; i++) futures.add(pool.submit(() -> {
            start.await();
            return ExplorationMasking.lookup(root, alias);
        }));
        start.countDown();
        for (var f : futures) assertEquals("441700900101", f.get());
        pool.shutdown();
    }

    /** An alias evicted from the in-memory book resolves again, and again after a second eviction. */
    @Test
    void anEvictedAliasResolvesAgain(@TempDir Path root) {
        String a = ExplorationMasking.alias(root, "441700900101");
        String b = ExplorationMasking.alias(root, "441700900202");
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals("441700900202", ExplorationMasking.lookup(root, b));
        ExplorationMasking.evictForTest(root, a);
        assertEquals("441700900101", ExplorationMasking.lookup(root, a));
        ExplorationMasking.evictForTest(root, a);
        assertEquals("441700900101", ExplorationMasking.lookup(root, a));
    }

    @Test
    void anotherSpaceHasAnotherKey(@TempDir Path a, @TempDir Path b) {
        assertTrue(!ExplorationMasking.alias(a, "x").equals(ExplorationMasking.alias(b, "x")), "keys are per Space");
    }
}
