package com.gamma.la.storage;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The golden proof that {@link BucketFunction#sql} (what DuckDB computes when the builder writes the index) and
 * {@link BucketFunction#bucketOf} (what the engine computes at read time) are the same function.
 */
class BucketFunctionTest {

    /** Several thousand ids: ASCII, unicode, empty-ish, very long, control characters, case pairs, emoji, normalisation pairs, random BMP text. */
    static List<String> ids() {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < 1500; i++) s.add("n" + i);
        for (int i = 0; i < 300; i++) s.add("7:" + (550_123_456_000L + i));              // typed keys
        s.addAll(List.of("", " ", "  ", "\t", "\n", "\r\n", "\u0001", "\u001F", "\u007F", "\u0085", " ", "a\u0001b\tc\nd",
                "A", "a", "ABC", "abc", "AbC", "É", "é", "é", "é", "ß", "SS", "ss", "İ", "ı", "日本", "日本語テキスト", "Ünï", "Größe",
                "😀", "a😀b", "🚀x", "�", "﻿", "x".repeat(100_000), "é".repeat(50_000), "0", "00", "-1", "1.0", "1e3"));
        Random r = new Random(20261002L);
        for (int i = 0; i < 2500; i++) {
            int len = 1 + r.nextInt(40);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                int cp;
                do {
                    cp = 1 + r.nextInt(r.nextBoolean() ? 0x7F : 0x1FFFF);
                } while (cp >= 0xD800 && cp <= 0xDFFF);                                      // no unpaired surrogates, no NUL
                sb.appendCodePoint(cp);
            }
            s.add(sb.toString());
        }
        return new ArrayList<>(s);
    }

    @Test
    void javaAndSqlAgreeOnEveryId() throws Exception {
        List<String> ids = ids();
        assertTrue(ids.size() > 4000, "corpus size " + ids.size());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE ids (id VARCHAR)");
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO ids VALUES (?)")) {
                for (String id : ids) {
                    ins.setString(1, id);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            for (int n : new int[] {1, 2, 16, 17, 64, 1000, 1024}) {
                int checked = 0;
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, " + BucketFunction.sql("id", n) + " FROM ids")) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        int sqlBucket = rs.getInt(2);
                        assertTrue(sqlBucket >= 0 && sqlBucket < n);
                        assertEquals(sqlBucket, BucketFunction.bucketOf(id, n), "N=" + n + " id=" + printable(id));
                        checked++;
                    }
                }
                assertEquals(ids.size(), checked);
            }
        }
    }

    @Test
    void caseAndNormalisationDifferencesAreDifferentIds() {
        // not a property of the function so much as a guard that nobody "helpfully" normalises before hashing: both sides hash raw bytes
        Set<Integer> seen = new LinkedHashSet<>();
        for (String id : List.of("A", "a", "É", "é", "é", "ß", "SS")) seen.add(BucketFunction.bucketOf(id, 1 << 20));
        assertEquals(7, seen.size());
    }

    @Test
    void bucketCountFormulaIsClampedPowerOfTwo() {
        assertEquals(16, BucketFunction.bucketsFor(0));
        assertEquals(16, BucketFunction.bucketsFor(1));
        assertEquals(16, BucketFunction.bucketsFor(64_000_000L));
        assertEquals(32, BucketFunction.bucketsFor(64_000_001L));
        assertEquals(32, BucketFunction.bucketsFor(100_000_000L));       // measured N at 10^8
        assertEquals(256, BucketFunction.bucketsFor(1_000_000_000L));
        assertEquals(1024, BucketFunction.bucketsFor(1_000_000_000_000L));
        assertEquals(1024, BucketFunction.bucketsFor(Long.MAX_VALUE / 2));
    }

    @Test
    void theSqlRenderingNamesTheDocumentedFunction() {
        assertEquals("md5_number_lower", BucketFunction.NAME);
        assertTrue(BucketFunction.sql("src", 32).contains(BucketFunction.NAME + "(src) % 32"));
        assertThrows(IllegalArgumentException.class, () -> BucketFunction.sql("src", 0));
        assertThrows(IllegalArgumentException.class, () -> BucketFunction.bucketOf("x", 0));
    }

    private static String printable(String id) {
        String s = id.length() > 40 ? id.substring(0, 40) + "...(" + id.length() + ")" : id;
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> sb.append(cp < 0x20 || cp > 0x7E ? String.format("\\u{%x}", cp) : String.valueOf((char) cp)));
        return sb.toString();
    }
}
