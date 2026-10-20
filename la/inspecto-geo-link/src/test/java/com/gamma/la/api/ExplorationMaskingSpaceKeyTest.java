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

    /** A repeated miss on an unchanged book does not read it again; a mint (the book grows) makes it read again. */
    @Test
    void aRepeatedMissDoesNotRescan(@TempDir Path root) {
        ExplorationMasking.alias(root, "441700900101");
        String unknown = "masked:0123456789abcdef";
        assertEquals(null, ExplorationMasking.lookup(root, unknown));
        int scans = ExplorationMasking.SCANS.get();
        for (int i = 0; i < 50; i++) assertEquals(null, ExplorationMasking.lookup(root, unknown));
        assertEquals(scans, ExplorationMasking.SCANS.get(), "no rescan on a repeated miss");
        ExplorationMasking.alias(root, "441700900202");
        assertEquals(null, ExplorationMasking.lookup(root, unknown));
        assertEquals(scans + 1, ExplorationMasking.SCANS.get(), "a grown book is read again");
    }

    /** LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1: N evicted aliases cost ONE pass over the book, not one scan each. */
    @Test
    void manyEvictedAliasesCostOnePassOverTheBook(@TempDir Path root) {
        String[] aliases = new String[300];
        for (int i = 0; i < 3000; i++) {
            String a = ExplorationMasking.alias(root, "4417" + i);
            if (i % 10 == 0) aliases[i / 10] = a;
        }
        ExplorationMasking.forgetInMemoryForTest();
        int passes = ExplorationMasking.SCANS.get();
        for (int i = 0; i < 300; i++) assertEquals("4417" + (i * 10), ExplorationMasking.lookup(root, aliases[i]));
        assertEquals(passes + 1, ExplorationMasking.SCANS.get(), "300 distinct evicted aliases: one pass, then one line read each");
        String later = ExplorationMasking.alias(root, "4417-late");
        ExplorationMasking.forgetInMemoryForTest();   // keep the index cleared too: a restart
        assertEquals("4417-late", ExplorationMasking.lookup(root, later));
    }

    /** The index only reads the bytes appended since its last pass, and a book that was replaced by a shorter one is re-read. */
    @Test
    void theIndexFollowsAGrowingAndAReplacedBook(@TempDir Path root) throws Exception {
        String a = ExplorationMasking.alias(root, "441700900101");
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals("441700900101", ExplorationMasking.lookup(root, a));
        String b = ExplorationMasking.alias(root, "441700900202");
        ExplorationMasking.evictForTest(root, b);
        int passes = ExplorationMasking.SCANS.get();
        assertEquals("441700900202", ExplorationMasking.lookup(root, b), "a mint after the index pass is found by the next pass");
        assertEquals(passes + 1, ExplorationMasking.SCANS.get());
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        String lineB = Files.readAllLines(book).get(1);
        Files.writeString(book, lineB + "\n");   // replaced by a shorter book that holds only b
        ExplorationMasking.evictForTest(root, b);
        assertEquals("441700900202", ExplorationMasking.lookup(root, b));
        ExplorationMasking.evictForTest(root, a);
        assertEquals(null, ExplorationMasking.lookup(root, a), "a is gone with the old book");
    }

    /** A torn line for an alias that precedes a good line for the SAME alias does not hide it. */
    @Test
    void aTornFirstLineDoesNotHideALaterGoodLine(@TempDir Path root) throws Exception {
        String alias = ExplorationMasking.alias(root, "441700900101");
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        String good = Files.readString(book);
        String b64 = good.substring(good.indexOf('\t') + 1).trim();
        Files.writeString(book, alias + "\t" + b64.substring(0, 12) + "\n" + good);
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals("441700900101", ExplorationMasking.lookup(root, alias));
    }

    /** A line torn by a crash (no newline) does not swallow the next minted line. */
    @Test
    void aMintAfterATornLineResolves(@TempDir Path root) throws Exception {
        ExplorationMasking.alias(root, "441700900101");
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        Files.writeString(book, "masked:00", java.nio.file.StandardOpenOption.APPEND);   // torn, no newline
        String b = ExplorationMasking.alias(root, "441700900202");
        ExplorationMasking.forgetInMemoryForTest();
        assertEquals("441700900202", ExplorationMasking.lookup(root, b));
    }

    /** Case history tries the Investigation pseudonym first, so a pseudonym never reads the book. */
    @Test
    void caseHistoryRevealsBeforeTheBook(@TempDir Path root) {
        ExplorationMasking.alias(root, "441700900101");
        ExplorationMasking.forgetInMemoryForTest();
        int scans = ExplorationMasking.SCANS.get();
        String pseudonym = "masked:fedcba9876543210";
        assertTrue(CaseHistory.nodeHolds("441700900303", pseudonym, root, t -> t.equals(pseudonym) ? "441700900303" : null));
        assertTrue(!CaseHistory.nodeHolds("441700900999", pseudonym, root, t -> t.equals(pseudonym) ? "441700900303" : null));
        assertEquals(scans, ExplorationMasking.SCANS.get(), "a pseudonym the Investigation knows never reads the book");
    }

    /**
     * Two processes appending to one book share no lock: modelled by appending from many threads WITHOUT the in-process
     * lock (each thread is a process). Every line must survive.
     */
    @Test
    void appendsFromTwoProcessesLoseNoLine(@TempDir Path root) throws Exception {
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        int writers = 4, each = 250;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(writers);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int w = 0; w < writers; w++) {
            int id = w;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) ExplorationMasking.appendLine(book, "p" + id + "-" + i + "\tx\n");
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get();
        pool.shutdown();
        long lines = Files.readAllLines(book).stream().filter(l -> !l.isEmpty()).distinct().count();
        assertEquals(writers * each, lines, "no appended line is lost");
    }

    @Test
    void anotherSpaceHasAnotherKey(@TempDir Path a, @TempDir Path b) {
        assertTrue(!ExplorationMasking.alias(a, "x").equals(ExplorationMasking.alias(b, "x")), "keys are per Space");
    }
}
