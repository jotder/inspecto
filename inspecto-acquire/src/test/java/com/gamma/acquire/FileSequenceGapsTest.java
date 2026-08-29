package com.gamma.acquire;

import com.gamma.acquire.FileSequenceGaps.Report;
import com.gamma.acquire.FileSequenceGaps.SeqScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FileSequenceGaps} — "how many files are missing for this pipeline yesterday?" over
 * {@code CDR_{yyyyMMddHH}_{seq}_*} names.
 */
class FileSequenceGapsTest {

    private static final String T = "CDR_{yyyyMMddHH}_{seq}_*";
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 8, 29, 0, 0);
    private static final LocalDateTime DAY_END = LocalDateTime.of(2026, 8, 29, 23, 0);

    private static String f(int hour, int seq) {
        return String.format("CDR_20260829%02d_%03d_raw.gz", hour, seq);
    }

    /** Every hour present and dense — the day is clean. */
    @Test
    void aCompleteDayReportsNothingMissing() {
        List<String> names = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) for (int s = 1; s <= 3; s++) names.add(f(h, s));

        Report r = FileSequenceGaps.analyze(T, names, DAY_START, DAY_END, SeqScope.PER_BUCKET);

        assertEquals(72, r.observedFiles());
        assertEquals(0, r.missingFiles());
        assertTrue(r.emptyBuckets().isEmpty());
        assertFalse(r.hasGaps());
    }

    @Test
    void anInteriorHoleInAnHourIsCountedExactly() {
        List<String> names = List.of(f(9, 1), f(9, 2), f(9, 4), f(9, 5));

        Report r = FileSequenceGaps.analyze(T, names,
                LocalDateTime.of(2026, 8, 29, 9, 0), LocalDateTime.of(2026, 8, 29, 9, 0), SeqScope.PER_BUCKET);

        assertEquals(1, r.missingFiles());
        assertEquals(List.of(3L), r.buckets().get(0).missing());
    }

    /**
     * 🔴 The reason this class exists rather than a call into {@link GapDetector}, which bounds its series by
     * the lowest and highest OBSERVED key. If a day stops producing at 20:00, the observed maximum simply
     * moves earlier and an observed-bounded scan reports a clean day — hiding the hole exactly when it is
     * biggest. Enumerating the WINDOW finds it.
     */
    @Test
    void hoursThatProducedNothingAtTheEndOfTheWindowAreStillFound() {
        List<String> names = new java.util.ArrayList<>();
        for (int h = 0; h <= 20; h++) names.add(f(h, 1));

        Report r = FileSequenceGaps.analyze(T, names, DAY_START, DAY_END, SeqScope.PER_BUCKET);

        assertEquals(List.of("2026082921", "2026082922", "2026082923"), r.emptyBuckets(),
                "the tail of the window is enumerated even though nothing was observed there");
        assertTrue(r.hasGaps());
    }

    /**
     * 🔴 An empty bucket contributes NO file count. How many files a silent hour should have produced is
     * unknowable from names alone, and folding a guess into an exact count would make the exact half
     * untrustworthy. It is reported as a bucket, separately.
     */
    @Test
    void anEmptyBucketIsNotCountedAsMissingFiles() {
        List<String> names = List.of(f(0, 1), f(0, 2), f(2, 1), f(2, 2));

        Report r = FileSequenceGaps.analyze(T, names,
                LocalDateTime.of(2026, 8, 29, 0, 0), LocalDateTime.of(2026, 8, 29, 2, 0), SeqScope.PER_BUCKET);

        assertEquals(0, r.missingFiles(), "hour 01 is empty, not short by some number of files");
        assertEquals(List.of("2026082901"), r.emptyBuckets());
        assertTrue(r.hasGaps(), "an empty bucket is still a gap, just not a countable one");
    }

    /**
     * 🔴 The undetectable tail, pinned so nobody later 'fixes' it into a false guarantee. Sequences 1,2,3 are
     * reported complete because the highest sequence received is not knowably the highest sent.
     */
    @Test
    void aTruncatedTailWithinABucketIsNotDetectable() {
        Report r = FileSequenceGaps.analyze(T, List.of(f(9, 1), f(9, 2), f(9, 3)),
                LocalDateTime.of(2026, 8, 29, 9, 0), LocalDateTime.of(2026, 8, 29, 9, 0), SeqScope.PER_BUCKET);

        assertEquals(0, r.missingFiles(),
                "1,2,3 with 4 and 5 never sent looks identical to a complete hour — a property of the naming");
    }

    @Test
    void zeroPaddingIsNotSignificant() {
        // _007_ and _7_ are the same sequence number and must not read as two.
        Report r = FileSequenceGaps.analyze(T,
                List.of("CDR_2026082909_007_a.gz", "CDR_2026082909_7_b.gz", "CDR_2026082909_009_c.gz"),
                LocalDateTime.of(2026, 8, 29, 9, 0), LocalDateTime.of(2026, 8, 29, 9, 0), SeqScope.PER_BUCKET);

        assertEquals(List.of(8L), r.buckets().get(0).missing(), "7 and 9 seen once each; only 8 is missing");
    }

    /**
     * ⚠ Scope cannot be inferred: the same names mean different things depending on whether the counter
     * restarts. Per-bucket sees two clean hours; continuous sees the boundary as a hole.
     */
    @Test
    void scopeChangesTheAnswerForTheSameNames() {
        List<String> names = List.of(f(9, 1), f(9, 2), f(10, 5), f(10, 6));
        var from = LocalDateTime.of(2026, 8, 29, 9, 0);
        var to = LocalDateTime.of(2026, 8, 29, 10, 0);

        assertEquals(0, FileSequenceGaps.analyze(T, names, from, to, SeqScope.PER_BUCKET).missingFiles(),
                "each hour is internally dense");
        assertEquals(2, FileSequenceGaps.analyze(T, names, from, to, SeqScope.CONTINUOUS).missingFiles(),
                "one series 1,2,5,6 across the window is missing 3 and 4");
    }

    /** A wrong template silently matching nothing would read as 'nothing missing' — so it is reported. */
    @Test
    void namesThatDoNotMatchTheTemplateAreReportedNotIgnored() {
        Report r = FileSequenceGaps.analyze(T,
                List.of(f(9, 1), f(9, 2), "OTHER_FEED_20260829.csv", "CDR_notadate_001_x.gz"),
                LocalDateTime.of(2026, 8, 29, 9, 0), LocalDateTime.of(2026, 8, 29, 9, 0), SeqScope.PER_BUCKET);

        assertEquals(2, r.observedFiles());
        assertEquals(2, r.unmatched(), "a mostly-unmatched set means the template is wrong, not the feed");
    }

    @Test
    void filesOutsideTheWindowDoNotParticipate() {
        Report r = FileSequenceGaps.analyze(T, List.of(f(9, 1), f(9, 3), f(23, 1)),
                LocalDateTime.of(2026, 8, 29, 9, 0), LocalDateTime.of(2026, 8, 29, 9, 0), SeqScope.PER_BUCKET);

        assertEquals(2, r.observedFiles(), "hour 23 is outside the asked-about window");
        assertEquals(1, r.missingFiles());
    }

    @Test
    void malformedTemplatesAndWindowsRefuse() {
        var from = LocalDateTime.of(2026, 8, 29, 9, 0);
        assertThrows(IllegalArgumentException.class,
                () -> FileSequenceGaps.analyze("CDR_{yyyyMMddHH}", List.of(), from, from, SeqScope.PER_BUCKET),
                "one token is GapDetector's grammar, not this one");
        assertThrows(IllegalArgumentException.class,
                () -> FileSequenceGaps.analyze("CDR_{yyyyMMddHH}_{n}_*", List.of(), from, from, SeqScope.PER_BUCKET),
                "the second token must be {seq}");
        assertThrows(IllegalArgumentException.class,
                () -> FileSequenceGaps.analyze(T, List.of(), from, from.minusHours(1), SeqScope.PER_BUCKET),
                "a window that ends before it starts");
    }
}
