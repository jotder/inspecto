package com.gamma.recon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The result tier of {@link ReconDay.Cache} is bounded by estimated bytes as well as by count (RECON-UPLIFT-1 P4). */
class ReconDayCacheTest {

    private long savedBudget;

    @BeforeEach
    void save() { savedBudget = ReconDay.Cache.maxBytes; ReconDay.Cache.clear(); }

    @AfterEach
    void restore() { ReconDay.Cache.clear(); ReconDay.Cache.maxBytes = savedBudget; }

    /** A cache value of about {@code rows} x one small row: a {@code DayResult} with {@code rows} grain rows. */
    private static ReconService.DayResult day(int rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < rows; i++) list.add(Map.of("msisdn", "m" + i, "left", 1.0, "right", 2.0));
        return new ReconService.DayResult(list, new int[rows], rows, Map.of(), Map.of(), null, 1);
    }

    private static String key(int i) { return "grain|k" + i; }

    @Test
    void theEstimateGrowsWithTheRowCount() {
        long one = ReconDay.Cache.weigh(day(1));
        long thousand = ReconDay.Cache.weigh(day(1000));
        assertTrue(thousand > 500 * (thousand / 1000), "sanity");
        assertTrue(thousand > 100 * one / 2, "1000 rows weigh far more than 1: " + thousand + " vs " + one);
        assertEquals(0, ReconDay.Cache.weigh(null));
    }

    @Test
    void evictsLeastRecentlyUsedWhenTheByteBudgetIsExceeded() {
        long each = ReconDay.Cache.weigh(day(100));
        ReconDay.Cache.maxBytes = each * 2 + each / 2;   // room for two, not three
        ReconDay.Cache.put(key(1), day(100));
        ReconDay.Cache.put(key(2), day(100));
        ReconDay.Cache.put(key(3), day(100));
        assertNull(ReconDay.Cache.get(key(1)), "the eldest went");
        assertNotNull(ReconDay.Cache.get(key(2)));
        assertNotNull(ReconDay.Cache.get(key(3)));
        assertTrue(ReconDay.Cache.bytes() <= ReconDay.Cache.maxBytes, ReconDay.Cache.bytes() + " > " + ReconDay.Cache.maxBytes);
    }

    @Test
    void aReadRefreshesRecency() {
        long each = ReconDay.Cache.weigh(day(100));
        ReconDay.Cache.maxBytes = each * 2 + each / 2;
        ReconDay.Cache.put(key(1), day(100));
        ReconDay.Cache.put(key(2), day(100));
        assertNotNull(ReconDay.Cache.get(key(1)));        // 1 is now the youngest
        ReconDay.Cache.put(key(3), day(100));             // over the budget: 2, the eldest, goes
        assertNotNull(ReconDay.Cache.get(key(1)));
        assertNull(ReconDay.Cache.get(key(2)));
        assertNotNull(ReconDay.Cache.get(key(3)));
    }

    @Test
    void anEntryLargerThanTheBudgetIsServedAloneNotRefused() {
        long big = ReconDay.Cache.weigh(day(500));
        ReconDay.Cache.maxBytes = big / 4;
        ReconDay.Cache.put(key(1), day(10));
        ReconDay.Cache.put(key(2), day(500));
        assertNotNull(ReconDay.Cache.get(key(2)), "the entry just stored is never evicted");
        assertNull(ReconDay.Cache.get(key(1)), "everything older made room for it");
        assertEquals(big, ReconDay.Cache.bytes());
        ReconDay.Cache.put(key(3), day(10));              // the next put evicts the over-bound one
        assertNull(ReconDay.Cache.get(key(2)));
        assertNotNull(ReconDay.Cache.get(key(3)));
    }

    @Test
    void theEntryCountBoundStillApplies() {
        ReconDay.Cache.maxBytes = Long.MAX_VALUE;
        for (int i = 0; i < ReconDay.Cache.MAX_ENTRIES + 5; i++) ReconDay.Cache.put(key(i), day(1));
        assertEquals(ReconDay.Cache.MAX_ENTRIES, ReconDay.Cache.size());
        assertNull(ReconDay.Cache.get(key(0)));
        assertNotNull(ReconDay.Cache.get(key(ReconDay.Cache.MAX_ENTRIES + 4)));
    }

    @Test
    void replacingAKeyDoesNotDoubleCountItsBytes() {
        ReconDay.Cache.maxBytes = Long.MAX_VALUE;
        ReconDay.Cache.put(key(1), day(100));
        long once = ReconDay.Cache.bytes();
        ReconDay.Cache.put(key(1), day(100));
        assertEquals(once, ReconDay.Cache.bytes());
        ReconDay.Cache.put(key(1), day(10));
        assertTrue(ReconDay.Cache.bytes() < once);
        ReconDay.Cache.clear();
        assertEquals(0, ReconDay.Cache.bytes());
    }

    @Test
    void smallEntriesAreNotCountedAgainstTheByteBudget() {
        ReconDay.Cache.maxBytes = 1;
        ReconDay.Cache.put("days|abc", List.of("2026-10-01", "2026-10-02"));
        ReconDay.Cache.put("stats|abc", Map.of("a", "b"));
        assertEquals(0, ReconDay.Cache.bytes());
        assertNotNull(ReconDay.Cache.get("days|abc"));
        assertNotNull(ReconDay.Cache.get("stats|abc"));
    }

    @Test
    void theDefaultBudgetIsABoundedFractionOfTheHeap() {
        long b = ReconDay.Cache.defaultBudget();
        assertTrue(b >= 64L << 20 && b <= 1L << 30, "budget " + b);
    }

    @Test
    void concurrentUseKeepsTheAccountingExact() throws Exception {
        long each = ReconDay.Cache.weigh(day(50));
        ReconDay.Cache.maxBytes = each * 5;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < 8; t++) {
            int seed = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < 400; i++) {
                        ReconDay.Cache.put(key((seed * 7 + i) % 20), day(50));
                        ReconDay.Cache.get(key((seed + i) % 20));
                    }
                } catch (Throwable e) { failure.compareAndSet(null, e); }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertTrue(ReconDay.Cache.bytes() <= ReconDay.Cache.maxBytes, "bytes " + ReconDay.Cache.bytes());
        int retained = 0;
        for (int i = 0; i < 20; i++) if (ReconDay.Cache.get(key(i)) != null) retained++;
        assertEquals(retained * each, ReconDay.Cache.bytes(), "bytes() is exactly the retained entries' weight");
    }
}
