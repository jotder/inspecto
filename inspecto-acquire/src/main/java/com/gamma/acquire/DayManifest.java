package com.gamma.acquire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-day delivery manifest of one file feed (LA-DAILY-INGEST-1 T8, operator 2026-10-06): every delivered day records
 * its parts and the row count of each part. It is the durable memory the duplicate / correction check and the gap check
 * read, because a processed file leaves the inbox (it is backed up) and a file listing alone forgets it.
 *
 * <p><b>What a recorded part can reveal</b> (each returned as an {@link Anomaly}, never a refusal - the data has already
 * landed; the feed contract says what is legitimate, this only makes the rest visible):
 * <ul>
 *   <li>{@link Kind#REDELIVERED} - a part arrives again under the SAME name (a same-name correction or a re-sent day). It
 *       replaces the earlier entry; the platform already overwrites the rows cleanly (T4).</li>
 *   <li>{@link Kind#RENAMED_CORRECTION} - a part with the SAME day and sequence number arrives under a DIFFERENT name. Both
 *       stay recorded, because both files landed: the day's rows are now doubled (T4 case b3).</li>
 *   <li>{@link Kind#PART_COUNT_CHANGED} - a NEW part arrives for a day that already had parts while a LATER day has also
 *       been delivered (the day had looked complete). A wholly late day is not an anomaly (the contract allows it).</li>
 * </ul>
 *
 * <p>Storage: one tab-separated file, rewritten atomically (temp + move) under a per-file lock, so two batches of one feed
 * never lose each other's parts. A name holding a tab or a line break cannot be recorded and is skipped by the caller.
 */
public final class DayManifest {

    public enum Kind { REDELIVERED, RENAMED_CORRECTION, PART_COUNT_CHANGED }

    /** One delivered part. {@code day} is the template's bucket key (e.g. {@code 20260901}); {@code seq} is 0 for a
     *  date-only file template (one file per day, operator 2026-10-06). */
    public record Part(String day, long seq, String name, long rows, long bytes, long atMillis) {}

    /** {@code previous} is the earlier name (RENAMED_CORRECTION) or the earlier row count as text (REDELIVERED). */
    public record Anomaly(Kind kind, String day, String name, String previous, String detail) {}

    private static final String HEADER = "day\tseq\tname\trows\tbytes\tat";
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final TreeMap<String, List<Part>> days = new TreeMap<>();

    private DayManifest() {}

    /** The recorded days, oldest first, each with its parts in delivery order. */
    public TreeMap<String, List<Part>> days() {
        TreeMap<String, List<Part>> out = new TreeMap<>();
        days.forEach((d, ps) -> out.put(d, List.copyOf(ps)));
        return out;
    }

    /** Every recorded part name. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        days.values().forEach(ps -> ps.forEach(p -> out.add(p.name())));
        return out;
    }

    /** Reads the manifest at {@code file}; an absent file is an empty manifest. */
    public static DayManifest read(Path file) throws IOException {
        DayManifest m = new DayManifest();
        if (file == null || !Files.isRegularFile(file)) return m;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.equals(HEADER)) continue;
            String[] f = line.split("\t", -1);
            if (f.length != 6) throw new IOException("day manifest " + file.getFileName() + " has a malformed line");
            try {
                m.days.computeIfAbsent(f[0], d -> new ArrayList<>()).add(new Part(f[0], Long.parseLong(f[1]), f[2],
                        Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5])));
            } catch (NumberFormatException bad) {
                throw new IOException("day manifest " + file.getFileName() + " has a malformed line", bad);
            }
        }
        return m;
    }

    /** Whether {@code name} can be stored (no tab or line break). */
    public static boolean recordable(String name) {
        return name != null && !name.isEmpty() && name.chars().noneMatch(c -> c == '\t' || c == '\n' || c == '\r');
    }

    /**
     * Records {@code parts} into the manifest at {@code file} (load, apply, atomic rewrite, under one lock per file) and
     * returns what they revealed, in order.
     */
    public static List<Anomaly> record(Path file, List<Part> parts) throws IOException {
        Object lock = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object());
        synchronized (lock) {
            DayManifest m = read(file);
            List<Anomaly> found = new ArrayList<>();
            for (Part p : parts) {
                if (!recordable(p.name())) throw new IllegalArgumentException("a part name holds a tab or a line break");
                m.apply(p, found);
            }
            m.write(file);
            return found;
        }
    }

    private void apply(Part p, List<Anomaly> found) {
        List<Part> day = days.computeIfAbsent(p.day(), d -> new ArrayList<>());
        for (int i = 0; i < day.size(); i++) {
            Part old = day.get(i);
            if (old.name().equals(p.name())) {
                found.add(new Anomaly(Kind.REDELIVERED, p.day(), p.name(), String.valueOf(old.rows()),
                        "part re-delivered under the same name: rows " + old.rows() + " -> " + p.rows()));
                day.set(i, p);
                return;
            }
        }
        for (Part old : day) {
            if (old.seq() == p.seq()) {
                found.add(new Anomaly(Kind.RENAMED_CORRECTION, p.day(), p.name(), old.name(),
                        "part " + p.seq() + " of day " + p.day() + " arrived again under a new name; both landed, the day's rows are doubled"));
                day.add(p);
                return;
            }
        }
        if (!day.isEmpty() && days.higherKey(p.day()) != null)
            found.add(new Anomaly(Kind.PART_COUNT_CHANGED, p.day(), p.name(), String.valueOf(day.size()),
                    "day " + p.day() + " had " + day.size() + " part(s) and a later day was already delivered; now " + (day.size() + 1)));
        day.add(p);
    }

    private void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (List<Part> ps : days.values())
            for (Part p : ps)
                sb.append(p.day()).append('\t').append(p.seq()).append('\t').append(p.name()).append('\t').append(p.rows())
                        .append('\t').append(p.bytes()).append('\t').append(p.atMillis()).append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
