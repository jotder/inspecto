package com.gamma.job;

import com.gamma.config.safety.PathJail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The {@code partition_prune} maintenance task (2026-09-06): per-date retention for a pipeline sink store.
 * Drops every {@code year=YYYY/month=MM/day=DD} partition under {@code dir} whose day is before today minus
 * {@code retention_days} (UTC, the frame {@code PartitionWriter} keys by). The {@code cleanup} task is
 * filename-glob + mtime and cannot express "data older than N days" — a partition's files are rewritten by
 * compaction and re-mtimed by a recompute, so file age is not data age; the partition path is.
 *
 * <p>Modelled on {@code event_prune}: a partition directory is removed WHOLE, never a row inside it, and empty
 * {@code month=} / {@code year=} parents go with it. {@code retention_days} is required with no default,
 * like every prune here — forgetting is deliberate. {@code dir} is path-jailed; hand it the store's
 * {@code database/} directory, never the store root, so quarantine/backup/errors siblings are never walked.
 *
 * <p>Catalog coupling follows {@code retire_superseded}'s rule — <b>files go, rows stay</b>: the Consignment
 * registry keeps its rows, and {@code ConsignmentSelector} re-enumerates the live tree at read time, so a
 * pruned partition simply stops appearing. A store partitioned by other columns ({@code partitions[]} on
 * the schema, e.g. {@code region=}) carries no {@code year=/month=/day=} tree and prunes nothing: this task
 * prunes by DATE and says so, rather than guessing what an arbitrary partition value means in time.
 */
final class PartitionPruneTask {

    private PartitionPruneTask() {}

    /** How deep a day partition may sit under {@code dir}: {@code year=/month=/day=} directly, or under a few grouping levels. */
    private static final int MAX_DEPTH = 6;

    static JobResult run(JobConfig cfg, boolean dryRun) throws IOException {
        Path dir = PathJail.requireUnderAny(PathJail.allowedRoots(), cfg.require("dir"), "dir");
        long days = Long.parseLong(cfg.require("retention_days"));   // required: forgetting is deliberate
        if (days < 1) throw new IllegalArgumentException("partition_prune retention_days must be >= 1");
        long t0 = System.nanoTime();
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        if (!Files.isDirectory(dir))
            return JobResult.ok("partition_prune: " + dir + " does not exist — nothing to prune",
                    (System.nanoTime() - t0) / 1_000_000L);

        int seen = 0, removed = 0;
        try (Stream<Path> walk = Files.walk(dir, MAX_DEPTH)) {
            for (Path day : walk.filter(PartitionPruneTask::isDayPartition).toList()) {
                LocalDate d = partitionDate(day);
                if (d == null) continue;
                seen++;
                if (!d.isBefore(cutoff)) continue;
                removed++;
                if (dryRun) continue;
                deleteTree(day);
                removeIfEmpty(day.getParent());               // month=
                removeIfEmpty(day.getParent().getParent());   // year=
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (seen == 0)
            return JobResult.ok("partition_prune: no year=/month=/day= partitions under " + dir
                    + " — this task prunes by date; a store partitioned by other columns is out of its reach", ms);
        if (dryRun)
            return JobResult.ok("partition_prune[dry-run]: would remove " + removed + " of " + seen
                    + " day-partition(s) before " + cutoff + " (retention " + days + "d) under " + dir, ms);
        return JobResult.ok("partition_prune: removed " + removed + " of " + seen + " day-partition(s) before "
                + cutoff + " (retention " + days + "d) under " + dir, ms);
    }

    private static boolean isDayPartition(Path p) {
        return Files.isDirectory(p) && p.getFileName().toString().startsWith("day=")
                && p.getParent() != null && p.getParent().getFileName().toString().startsWith("month=")
                && p.getParent().getParent() != null
                && p.getParent().getParent().getFileName().toString().startsWith("year=");
    }

    private static LocalDate partitionDate(Path day) {
        try {
            int d = Integer.parseInt(day.getFileName().toString().substring("day=".length()));
            int m = Integer.parseInt(day.getParent().getFileName().toString().substring("month=".length()));
            int y = Integer.parseInt(day.getParent().getParent().getFileName().toString().substring("year=".length()));
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void removeIfEmpty(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.delete(dir);
        }
    }
}
