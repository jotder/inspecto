package com.gamma.screening;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * D6 (SCREENING-1): how long a Run's dedupe pass takes over N stored hits, without the index (every document read)
 * and with it. Opt-in, it writes N files: {@code -Dscreening.measure=100000}. Prints the timings.
 */
class ScreeningHitsScaleMeasureTest {

    @Test
    void measureTheDedupePassAtScale(@TempDir Path root) throws Exception {
        String n = System.getProperty("screening.measure");
        Assumptions.assumeTrue(n != null, "opt-in: -Dscreening.measure=<hits>");
        int hits = Integer.parseInt(n);
        long t0 = System.nanoTime();
        for (int i = 0; i < hits; i++) {
            Map<String, Object> rec = ScreeningHits.raise(new Screener.Subject("c" + i, "masked:x", null),
                    new Screener.Match("sanctions", "sanctions", "entry " + i, "exact", 1.0), 0.85, Map.of(), "measure");
            rec.put("id", String.format("sh-20260101000000-%06x", i));
            ScreeningHits.save(root, rec);
        }
        long t1 = System.nanoTime();
        ScreeningHits.saveIndex(root, ScreeningHits.dedupeIndex(root));   // no index yet: reads every document
        long t2 = System.nanoTime();
        Set<Object> known = new HashSet<>(ScreeningHits.dedupeIndex(root).values());
        long t3 = System.nanoTime();
        System.out.printf("SCREENING-MEASURE hits=%d save=%d ms index-build=%d ms indexed-pass=%d ms keys=%d%n",
                hits, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, known.size());
        if (known.size() != hits) throw new AssertionError("expected " + hits + " keys, got " + known.size());
    }
}
