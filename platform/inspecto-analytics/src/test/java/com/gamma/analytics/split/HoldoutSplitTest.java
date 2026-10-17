package com.gamma.analytics.split;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.split.HoldoutSplit.HashSplit;
import com.gamma.analytics.split.HoldoutSplit.Role;
import com.gamma.analytics.split.HoldoutSplit.TimeSplit;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Golden figures for the time split and the entity-hash split. */
class HoldoutSplitTest {

    @Test
    void lastBucketsSplit() {
        TimeSplit s = TimeSplit.lastBuckets(2, 3);
        assertArrayEquals(new int[] {5, 2, 3}, s.counts(10));
        assertEquals(Role.TRAIN, s.role(0, 10));
        assertEquals(Role.TRAIN, s.role(4, 10));
        assertEquals(Role.VALIDATION, s.role(5, 10));
        assertEquals(Role.VALIDATION, s.role(6, 10));
        assertEquals(Role.TEST, s.role(7, 10));
        assertEquals(Role.TEST, s.role(9, 10));
        assertThrows(IllegalArgumentException.class, () -> s.counts(5), "no train bucket left");
        assertThrows(IllegalArgumentException.class, () -> s.role(10, 10));
    }

    @Test
    void fractionSplitRoundsDown() {
        TimeSplit s = TimeSplit.fractions(0.15, 0.2);
        assertArrayEquals(new int[] {7, 1, 2}, s.counts(10), "floor(1.5) = 1, floor(2.0) = 2");
        assertArrayEquals(new int[] {3, 0, 0}, s.counts(3), "a tiny series keeps everything in train");
        assertEquals(1500, s.validation());
        assertThrows(IllegalArgumentException.class, () -> TimeSplit.fractions(0.5, 0.5));
        assertThrows(IllegalArgumentException.class, () -> TimeSplit.fractions(-0.1, 0.2));
    }

    @Test
    void goldenHashBuckets() {
        // md5_number_lower("<seed>:<key>") % 10000, computed independently (Python hashlib)
        HashSplit s = new HashSplit(42, 7000, 1500);
        assertEquals(1308, s.bucket("alice"));
        assertEquals(6797, s.bucket("bob"));
        assertEquals(4860, new HashSplit(7, 7000, 1500).bucket("alice"), "another seed, another bucket");
        assertEquals(2889, new HashSplit(-1, 7000, 1500).bucket("été"), "UTF-8 bytes");
        assertEquals(Role.TRAIN, s.role("alice"));
        assertEquals(Role.TRAIN, s.role("bob"));
        assertEquals(Role.VALIDATION, new HashSplit(42, 1000, 1000).role("alice"));
        assertEquals(Role.TEST, new HashSplit(42, 1000, 200).role("alice"));
    }

    @Test
    void sharesAreNearTheFractionsAndStable() {
        HashSplit s = HashSplit.of(20261017, 0.7, 0.2, 0.1);
        Map<Role, Integer> n = new EnumMap<>(Role.class);
        for (int i = 0; i < 20_000; i++) n.merge(s.role("entity-" + i), 1, Integer::sum);
        assertEquals(0.7, n.get(Role.TRAIN) / 20_000.0, 0.02);
        assertEquals(0.2, n.get(Role.VALIDATION) / 20_000.0, 0.02);
        assertEquals(0.1, n.get(Role.TEST) / 20_000.0, 0.02);
        for (int i = 0; i < 100; i++) assertEquals(s.role("entity-" + i), HashSplit.of(20261017, 0.7, 0.2, 0.1).role("entity-" + i));
        assertThrows(IllegalArgumentException.class, () -> HashSplit.of(1, 0.7, 0.2, 0.2), "must sum to 1");
        assertThrows(IllegalArgumentException.class, () -> new HashSplit(1, 9000, 1001));
        assertThrows(NullPointerException.class, () -> s.role(null));
    }
}
