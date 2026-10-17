package com.gamma.analytics.sample;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.sample.Sampler.Spec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sampler's definitions: key text, the hash (golden figures computed outside Java), quotas, order and stability. */
class SamplerTest {

    @Test
    void keyTextLengthPrefixesEachValueSoDifferentKeysNeverShareAText() {
        assertEquals("5:alice", Sampler.keyText(List.of("alice")));
        assertEquals("1:a3:bcd", Sampler.keyText(List.of("a", "bcd")));
        assertEquals("-0:", Sampler.keyText(Arrays.asList(null, "")));
        assertEquals("5:été", Sampler.keyText(List.of("été")), "the length is UTF-8 bytes");
        assertNotEquals(Sampler.keyText(List.of("ab", "c")), Sampler.keyText(List.of("a", "bc")));
        assertNotEquals(Sampler.keyText(Arrays.asList((String) null)), Sampler.keyText(List.of("-")));
        assertNotEquals(Sampler.keyText(Arrays.asList((String) null)), Sampler.keyText(List.of("")));
    }

    /** md5_number_lower(seed ':' keyText), computed with Python's hashlib (digest bytes 8..15, little-endian). */
    @Test
    void hashIsTheMd5LowerReadingOfSeedAndKeyText() {
        assertEquals(Long.parseUnsignedLong("6777498567038576131"), Sampler.hash(7, "5:alice"));
        assertEquals(Long.parseUnsignedLong("18183855697291748115"), Sampler.hash(7, "-"));
        assertEquals(Long.parseUnsignedLong("9890906888949775064"), Sampler.hash(0, "1:a3:bcd"));
        assertEquals(Long.parseUnsignedLong("7908391706796638738"), Sampler.hash(-3, "5:été"));
    }

    @Test
    void quotaIsMinOfSizeOrTheShareRoundedHalfUp() {
        Spec k = Spec.ofSize(1, 3);
        assertEquals(0, k.quota(0));
        assertEquals(2, k.quota(2));
        assertEquals(3, k.quota(3));
        assertEquals(3, k.quota(1000));
        Spec f = Spec.ofFraction(1, 0.1);
        assertEquals(1000, f.fractionBp());
        assertEquals(0, f.quota(4));    // 0.4
        assertEquals(1, f.quota(5));    // 0.5 -> half up
        assertEquals(1, f.quota(14));   // 1.4
        assertEquals(2, f.quota(15));   // 1.5
        assertEquals(100, f.quota(1000));
        assertEquals(7, Spec.ofFraction(1, 1).quota(7));
    }

    @Test
    void refusesBadSpecs() {
        assertThrows(IllegalArgumentException.class, () -> Spec.ofSize(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Spec.ofSize(1, Sampler.MAX_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> Spec.ofFraction(1, 0));
        assertThrows(IllegalArgumentException.class, () -> Spec.ofFraction(1, 1.5));
        assertThrows(IllegalArgumentException.class, () -> Spec.ofFraction(1, 0.00001));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, 3, 100));
        assertThrows(IllegalArgumentException.class, () -> new Spec(1, null, null));
        assertThrows(IllegalArgumentException.class, () -> Sampler.keyTextSql(List.of()));
    }

    @Test
    void keepsTheLowestHashesPerGroupAndIsIndependentOfInputOrder() {
        List<List<String>> groups = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            groups.add(List.of(i % 3 == 0 ? "a" : i % 3 == 1 ? "b" : "c"));
            keys.add(Sampler.keyText(List.of("row" + i)));
        }
        Spec spec = Spec.ofSize(42, 5);
        List<Integer> kept = Sampler.select(spec, groups, keys);
        assertEquals(15, kept.size(), "five per group");
        for (String g : List.of("a", "b", "c")) {
            List<Long> all = new ArrayList<>(), sampled = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                if (!groups.get(i).get(0).equals(g)) continue;
                all.add(Sampler.hash(42, keys.get(i)));
                if (kept.contains(i)) sampled.add(Sampler.hash(42, keys.get(i)));
            }
            all.sort(Long::compareUnsigned);
            sampled.sort(Long::compareUnsigned);
            assertEquals(all.subList(0, 5), sampled, g + ": the five lowest unsigned hashes");
        }
        // the same rows whatever order they arrive in
        List<Integer> perm = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) perm.add(i);
        Collections.shuffle(perm, new java.util.Random(9));
        List<List<String>> g2 = new ArrayList<>();
        List<String> k2 = new ArrayList<>();
        for (int i : perm) { g2.add(groups.get(i)); k2.add(keys.get(i)); }
        List<String> again = Sampler.select(spec, g2, k2).stream().map(k2::get).sorted().toList();
        assertEquals(kept.stream().map(keys::get).sorted().toList(), again);
        // a new seed is a new sample
        assertNotEquals(kept, Sampler.select(Spec.ofSize(43, 5), groups, keys));
    }

    @Test
    void addingRowsNeverReshufflesTheRest() {
        List<List<String>> groups = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) { groups.add(List.of()); keys.add(Sampler.keyText(List.of("r" + i))); }
        Spec spec = Spec.ofSize(5, 10);
        List<String> before = Sampler.select(spec, groups, keys).stream().map(keys::get).toList();
        for (int i = 200; i < 260; i++) { groups.add(List.of()); keys.add(Sampler.keyText(List.of("r" + i))); }
        List<String> after = Sampler.select(spec, groups, keys).stream().map(keys::get).toList();
        // every row sampled after that was present before was sampled before as well
        for (String k : after) if (keys.indexOf(k) < 200) assertTrue(before.contains(k), k);
    }

    @Test
    void fractionIsPerGroupAndNullGroupsAreAGroup() {
        List<List<String>> groups = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 40; i++) { groups.add(List.of("big")); keys.add(Sampler.keyText(List.of("b" + i))); }
        for (int i = 0; i < 10; i++) { groups.add(Arrays.asList((String) null)); keys.add(Sampler.keyText(List.of("n" + i))); }
        List<Integer> kept = Sampler.select(Spec.ofFraction(1, 0.25), groups, keys);
        assertEquals(10, kept.stream().filter(i -> i < 40).count());
        assertEquals(3, kept.stream().filter(i -> i >= 40).count(), "2.5 rounds half up to 3");
    }
}
