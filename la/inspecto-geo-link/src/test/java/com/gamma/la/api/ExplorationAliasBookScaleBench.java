package com.gamma.la.api;

import com.gamma.entitystore.MaskTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;

/**
 * LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1: cost of resolving an alias against a LARGE alias book (the exploration alias book is
 * scanned on an in-memory miss). Never in the suite: needs {@code -Dinspecto.bench.dir=<dir>}; knobs {@code inspecto.bench.book}
 * (aliases in the book, 1000000) and {@code inspecto.bench.lookups} (distinct evicted aliases resolved, 200). Prints
 * {@code ALIAS-BOOK ...} lines.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class ExplorationAliasBookScaleBench {

    @Test
    void resolvingEvictedAliasesAgainstALargeBook() throws Exception {
        Path root = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath().resolve("alias-" + System.nanoTime());
        Files.createDirectories(root);
        int n = Integer.getInteger("inspecto.bench.book", 1_000_000);
        int k = Integer.getInteger("inspecto.bench.lookups", 200);
        byte[] key = MaskTokens.spaceKey(root);
        Path book = com.gamma.util.SpaceSecretKeys.keyFile(root, ".exploration-alias-book");
        Files.createDirectories(book.getParent());
        String[] sample = new String[k];
        Random rnd = new Random(7);
        long t0 = System.nanoTime();
        try (BufferedWriter w = Files.newBufferedWriter(book, StandardCharsets.UTF_8)) {
            for (int i = 0; i < n; i++) {
                String raw = "44170" + i;
                String alias = MaskTokens.token(key, raw);
                if (i % Math.max(1, n / k) == 0 && i / Math.max(1, n / k) < k) sample[i / Math.max(1, n / k)] = alias;
                w.write(alias + "\t" + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)) + "\n");
            }
        }
        System.out.printf("ALIAS-BOOK built %d lines, %d MB in %d ms%n", n, Files.size(book) >> 20, (System.nanoTime() - t0) / 1_000_000);
        ExplorationMasking.forgetInMemoryForTest();
        ExplorationMasking.SCANS.set(0);
        long t1 = System.nanoTime();
        int found = 0;
        for (String a : sample) if (a != null && ExplorationMasking.lookup(root, a) != null) found++;
        long ms = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf("ALIAS-BOOK resolved %d distinct evicted aliases in %d ms (%d scans, %.1f ms per alias)%n",
                found, ms, ExplorationMasking.SCANS.get(), found == 0 ? 0 : (double) ms / found);
        ExplorationMasking.forgetInMemoryForTest();
        long t2 = System.nanoTime();
        for (int i = 0; i < 20; i++) ExplorationMasking.lookup(root, "masked:" + String.format("%016x", rnd.nextLong()));
        System.out.printf("ALIAS-BOOK 20 never-minted aliases (one index pass, then cached): %d ms%n", (System.nanoTime() - t2) / 1_000_000);
    }
}
