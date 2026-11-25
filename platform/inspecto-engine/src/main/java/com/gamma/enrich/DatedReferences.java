package com.gamma.enrich;

import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Helpers for daily-versioned Reference Datasets ({@code reference.versions: daily},
 * {@code DATED-REFERENCE-RUNS-1} P1).
 *
 * <p>Each business day's rows live under {@code <dbDir>/ref_day=<yyyy-MM-dd>/}. A corrected file
 * for day {@code D} replaces only {@code ref_day=D}, leaving every other day intact.
 */
public final class DatedReferences {

    private static final Logger log = LoggerFactory.getLogger(DatedReferences.class);

    public static final String REF_DAY_COL = "ref_day";
    private static final String REF_DAY_PREFIX = REF_DAY_COL + "=";

    private static final Pattern ISO_DAY = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern COMPACT_DAY = Pattern.compile("(?<!\\d)(\\d{4})(\\d{2})(\\d{2})(?!\\d)");

    private DatedReferences() {}

    /**
     * Extract a calendar day ({@code yyyy-MM-dd}) from a file name or path, e.g.
     * {@code DIM_DIAL_DIGIT_20261122.csv} → {@code 2026-11-22} or {@code dim_2026-11-22.csv} → {@code 2026-11-22}.
     */
    public static Optional<String> extractDayFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        String base = Path.of(fileName).getFileName().toString();
        Matcher iso = ISO_DAY.matcher(base);
        while (iso.find()) {
            Optional<String> valid = validDate(iso.group(1));
            if (valid.isPresent()) return valid;
        }
        Matcher compact = COMPACT_DAY.matcher(base);
        while (compact.find()) {
            String candidate = compact.group(1) + "-" + compact.group(2) + "-" + compact.group(3);
            Optional<String> valid = validDate(candidate);
            if (valid.isPresent()) return valid;
        }
        return Optional.empty();
    }

    /** Normalize a date/timestamp string ({@code yyyy-MM-dd...} or {@code yyyyMMdd}) to {@code yyyy-MM-dd}. */
    public static Optional<String> normalizeDay(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String s = raw.trim();
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            return validDate(s.substring(0, 10));
        }
        if (s.length() == 8 && s.chars().allMatch(Character::isDigit)) {
            return validDate(s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8));
        }
        return Optional.empty();
    }

    private static Optional<String> validDate(String iso) {
        try {
            LocalDate.parse(iso);
            return Optional.of(iso);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Sorted ascending list of {@code yyyy-MM-dd} days that currently have at least one data file
     * under {@code <dbDir>/ref_day=<day>/}.
     */
    public static List<String> availableDays(Path dbDir, String ext) {
        if (dbDir == null || !Files.isDirectory(dbDir)) return List.of();
        String dotExt = "." + (ext == null ? "parquet" : ext.toLowerCase());
        List<String> days = new ArrayList<>();
        try (Stream<Path> children = Files.list(dbDir)) {
            for (Path p : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (!name.startsWith(REF_DAY_PREFIX)) continue;
                String day = name.substring(REF_DAY_PREFIX.length());
                if (validDate(day).isEmpty()) continue;
                try (Stream<Path> files = Files.walk(p)) {
                    boolean hasFile = files.anyMatch(f -> Files.isRegularFile(f)
                            && f.getFileName().toString().toLowerCase().endsWith(dotExt));
                    if (hasFile) days.add(day);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list daily reference partitions under {}: {}", dbDir, e.getMessage());
        }
        Collections.sort(days);
        return days;
    }

    /**
     * Resolve the Reference version day to read for target {@code day}, given {@code ifVersionMissing}
     * ({@code wait} | {@code previous} | {@code fail}).
     */
    public static String resolveDay(String refName, Path dbDir, String ext, String day, String ifVersionMissing) {
        List<String> days = availableDays(dbDir, ext);
        if (day == null || day.isBlank()) {
            if (days.isEmpty()) {
                throw new MissingVersionException(refName, "latest",
                        "Reference '" + refName + "' has no daily versions loaded yet");
            }
            return days.get(days.size() - 1);
        }
        String normDay = normalizeDay(day).orElse(day);
        if (days.contains(normDay)) return normDay;
        String policy = ifVersionMissing == null ? "wait" : ifVersionMissing.trim().toLowerCase();
        if ("previous".equals(policy)) {
            String prev = null;
            for (String d : days) {
                if (d.compareTo(normDay) <= 0) prev = d;
                else break;
            }
            if (prev != null) return prev;
            throw new MissingVersionException(refName, normDay,
                    "Reference '" + refName + "' has no version for day '" + normDay + "' or any earlier day");
        }
        throw new MissingVersionException(refName, normDay,
                "Reference '" + refName + "' has no version for day '" + normDay + "' (policy: " + policy + ")");
    }

    /**
     * After writing new output file(s) for a daily Reference, remove any older files in the same
     * {@code ref_day=<day>} directories that were not part of this write, and mark them {@code REPLACED}
     * in {@link DbConsignmentOutputStore} if registered. Also prunes days older than {@code keepDays}
     * (when {@code keepDays > 0}).
     */
    public static void replaceDayAndPrune(Path dbDir, List<Path> freshlyWritten, int keepDays) {
        if (dbDir == null || !Files.isDirectory(dbDir)) return;
        java.util.Set<Path> keep = new java.util.HashSet<>();
        java.util.Set<Path> touchedDirs = new java.util.HashSet<>();
        for (Path p : freshlyWritten) {
            Path norm = p.toAbsolutePath().normalize();
            keep.add(norm);
            Path parent = norm.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith(REF_DAY_PREFIX)) {
                touchedDirs.add(parent);
            }
        }
        List<String> replacedPaths = new ArrayList<>();
        for (Path dir : touchedDirs) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path existing : (Iterable<Path>) s::iterator) {
                    Path norm = existing.toAbsolutePath().normalize();
                    if (Files.isRegularFile(norm) && !keep.contains(norm)) {
                        Files.deleteIfExists(norm);
                        replacedPaths.add(norm.toString());
                    }
                }
            } catch (IOException e) {
                log.warn("Could not replace older daily reference files in {}: {}", dir, e.getMessage());
            }
        }
        if (keepDays > 0) {
            List<String> days = availableDays(dbDir, null);
            if (!days.isEmpty()) {
                LocalDate newest = LocalDate.parse(days.get(days.size() - 1));
                LocalDate cutoff = newest.minusDays(keepDays);
                for (String d : days) {
                    if (LocalDate.parse(d).isBefore(cutoff)) {
                        Path oldDir = dbDir.resolve(REF_DAY_PREFIX + d);
                        try (Stream<Path> walk = Files.walk(oldDir)) {
                            List<Path> toDelete = walk.sorted(java.util.Comparator.reverseOrder()).toList();
                            for (Path p : toDelete) {
                                if (Files.isRegularFile(p)) replacedPaths.add(p.toAbsolutePath().normalize().toString());
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException e) {
                            log.warn("Could not prune expired daily reference dir {}: {}", oldDir, e.getMessage());
                        }
                    }
                }
            }
        }
        if (!replacedPaths.isEmpty()) {
            DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
            if (store != null) store.markReplaced(replacedPaths);
        }
    }

    /** Thrown when a required daily Reference version does not exist for a day. */
    public static final class MissingVersionException extends IllegalArgumentException {
        private final String referenceName;
        private final String day;

        public MissingVersionException(String referenceName, String day, String message) {
            super(message);
            this.referenceName = referenceName;
            this.day = day;
        }

        public String referenceName() { return referenceName; }
        public String day() { return day; }
    }
}
