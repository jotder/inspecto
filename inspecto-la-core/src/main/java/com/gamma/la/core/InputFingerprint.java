package com.gamma.la.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A stable fingerprint of the INPUT FILES a Dataset's relation reads (LA separation D-3, design 2.4 / 5.3a) - what an
 * index records at build time and compares with the files now, to tell a stale index from a current one.
 *
 * <p>Three shapes, told apart by prefix so that 'cannot know' is never mistaken for 'unchanged':
 * <ul>
 *   <li>{@code files:<sha256>} - sha256 over the files sorted by path, one line each:
 *       {@code path TAB size TAB mtimeMillis LF}. Paths are RELATIVE to the root the relation resolves against (never
 *       absolute), so a moved Space root does not change it. {@link #files()} carries the stamps.</li>
 *   <li>{@code no-files:<sha256 of the relation SQL>} - the relation has no enumerable files (a view over other views, a
 *       Postgres-backed store, inline VALUES). Comparing two of these says nothing about the data.</li>
 *   <li>{@code too-many-files:<n>} - more than {@code n} files (the cap); not listed, so not compared.</li>
 * </ul>
 *
 * @param value the fingerprint text (one of the three shapes)
 * @param files the stamps behind a {@code files:} fingerprint, sorted by path; empty for the sentinels
 */
public record InputFingerprint(String value, List<FileStamp> files) {

    /** Most files a fingerprint is taken over; above this the sentinel {@code too-many-files:<MAX_FILES>} is used. */
    public static final int MAX_FILES = 10_000;

    public static final String FILES = "files:";
    public static final String NO_FILES = "no-files:";
    public static final String TOO_MANY = "too-many-files:";

    /** One input file: path relative to the root the relation resolves against, size in bytes, mtime in epoch millis. */
    public record FileStamp(String path, long size, long mtimeMillis) { }

    public InputFingerprint {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** The fingerprint of these files (order does not matter; they are sorted here). */
    public static InputFingerprint ofFiles(List<FileStamp> files) {
        List<FileStamp> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(FileStamp::path));
        MessageDigest md = sha256();
        for (FileStamp f : sorted)
            md.update((f.path() + '\t' + f.size() + '\t' + f.mtimeMillis() + '\n').getBytes(StandardCharsets.UTF_8));
        return new InputFingerprint(FILES + HexFormat.of().formatHex(md.digest()), sorted);
    }

    /** The relation has no enumerable files: {@code no-files:<sha256 of relationSql>}. */
    public static InputFingerprint noFiles(String relationSql) {
        return new InputFingerprint(NO_FILES + HexFormat.of().formatHex(sha256().digest(relationSql.getBytes(StandardCharsets.UTF_8))), List.of());
    }

    /** More than {@code cap} files exist: {@code too-many-files:<cap>}. */
    public static InputFingerprint tooMany(int cap) {
        return new InputFingerprint(TOO_MANY + cap, List.of());
    }

    /** Whether this is a real file fingerprint, i.e. comparing it with another one says something about the data. */
    public boolean known() {
        return isKnown(value);
    }

    /** Whether {@code fingerprintValue} (a manifest's recorded text) is a real file fingerprint. */
    public static boolean isKnown(String fingerprintValue) {
        return fingerprintValue != null && fingerprintValue.startsWith(FILES);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
