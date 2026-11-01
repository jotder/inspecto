package com.gamma.screening;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * D6 / D8 (SCREENING-1): over N stored hits, how long a Run's dedupe pass takes without the index (every document
 * read) and with it, how long one page of the listing takes (unfiltered, filtered to a rare state, and deep through a
 * cursor), and what a decision's index write adds. Opt-in, it writes N files: {@code -Dscreening.measure=100000}.
 * Prints the timings. Every 100th hit is escalated, so {@code state=escalated} matches 1 %.
 */
class ScreeningHitsScaleMeasureTest {

    @Test
    void measureTheIndexAndTheListingAtScale(@TempDir Path root) throws Exception {
        String n = System.getProperty("screening.measure");
        Assumptions.assumeTrue(n != null, "opt-in: -Dscreening.measure=<hits>");
        int hits = Integer.parseInt(n);
        long t0 = System.nanoTime();
        for (int i = 0; i < hits; i++) {
            Map<String, Object> rec = ScreeningHits.raise(new Screener.Subject("c" + i, "masked:x", null),
                    new Screener.Match("sanctions", "sanctions", "entry " + i, "exact", 1.0), 0.85, Map.of(), "measure");
            rec.put("id", String.format("sh-20260101000000-%06x", i));
            if (i % 100 == 0) ScreeningHits.transition(rec, "escalated", "measure", "r");
            ScreeningHits.save(root, rec);
        }
        long t1 = System.nanoTime();
        ScreeningHits.saveIndex(root, ScreeningHits.index(root));   // no index yet: reads every document
        long t2 = System.nanoTime();
        Set<Object> known = ScreeningHits.keys(ScreeningHits.index(root));
        long t3 = System.nanoTime();
        ScreeningHits.Page first = ScreeningHits.page(root, null, null, ScreeningRoutes.DEFAULT_LIMIT);
        long t4 = System.nanoTime();
        ScreeningHits.Page escalated = ScreeningHits.page(root, "escalated", null, ScreeningRoutes.DEFAULT_LIMIT);
        long t5 = System.nanoTime();
        ScreeningHits.Page deep = ScreeningHits.page(root, null, String.format("sh-20260101000000-%06x", hits / 2),
                ScreeningRoutes.MAX_LIMIT);
        long t6 = System.nanoTime();
        Map<String, Object> rec = ScreeningHits.read(root, String.format("sh-20260101000000-%06x", 1));
        ScreeningHits.transition(rec, "dismissed", "measure", "r");
        ScreeningHits.save(root, rec);
        synchronized (ScreeningHits.lock()) {
            ScreeningHits.reindex(root, rec);
        }
        long t7 = System.nanoTime();
        System.out.printf("SCREENING-MEASURE hits=%d save=%d ms index-build=%d ms indexed-pass=%d ms keys=%d "
                        + "page-first=%d ms (%d) page-escalated=%d ms (%d of %d) page-deep-500=%d ms (%d) decide-reindex=%d ms%n",
                hits, ms(t1 - t0), ms(t2 - t1), ms(t3 - t2), known.size(), ms(t4 - t3), first.hits().size(),
                ms(t5 - t4), escalated.hits().size(), escalated.total(), ms(t6 - t5), deep.hits().size(), ms(t7 - t6));
        if (known.size() != hits) throw new AssertionError("expected " + hits + " keys, got " + known.size());
        if (first.total() != hits || first.hits().size() != Math.min(hits, ScreeningRoutes.DEFAULT_LIMIT))
            throw new AssertionError("first page " + first.hits().size() + " of " + first.total());
        if (escalated.total() != (hits + 99) / 100) throw new AssertionError("escalated total " + escalated.total());
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
