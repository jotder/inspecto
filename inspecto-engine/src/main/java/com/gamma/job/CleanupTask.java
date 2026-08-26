package com.gamma.job;

import com.gamma.config.safety.PathJail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code cleanup} maintenance task (default; MNT-2b/MNT-2c): retire files under {@code dir} that
 * breach ANY configured policy limit — older than {@code retention_days} (default 7), beyond the newest
 * {@code max_count} files, or beyond a newest-first {@code max_size} byte budget; optionally filtered by
 * a {@code glob} (default {@code *}). With {@code archive_instead_of_delete: true} affected files move
 * to the required {@code archive_dir} (relative structure preserved, never re-walked) instead of being
 * deleted. See {@link MaintenanceJob} class doc.
 */
final class CleanupTask {

    private static final Logger log = LoggerFactory.getLogger(CleanupTask.class);

    private CleanupTask() {}

    static JobResult run(JobConfig cfg, boolean dryRun) {
        List<Path> jailRoots = PathJail.allowedRoots();
        Path dir = PathJail.requireUnderAny(jailRoots, cfg.require("dir"), "dir");
        long days = Long.parseLong(cfg.opt("retention_days", "7"));
        String glob = cfg.opt("glob", "*");
        int maxCount = Integer.parseInt(cfg.opt("max_count", "0"));    // 0 = no count cap
        long maxSize = Long.parseLong(cfg.opt("max_size", "0"));       // bytes; 0 = no size cap
        // MNT-2c: the newest min_keep files are never retired, whatever the other limits say — so a
        // retention policy on a backup dir can never delete the last remaining backups.
        int minKeep = Integer.parseInt(cfg.opt("min_keep", "0"));
        boolean archive = Boolean.parseBoolean(cfg.opt("archive_instead_of_delete", "false"));
        // Archiving needs an explicit destination — no silent default that a later cleanup would re-walk.
        Path archiveDir = archive
                ? PathJail.requireUnderAny(jailRoots, cfg.require("archive_dir"), "archive_dir") : null;
        long t0 = System.nanoTime();
        if (!Files.isDirectory(dir)) {
            return JobResult.ok("cleanup: directory not present, nothing to do (" + dir + ")", 0L);
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
        List<Path> matches;
        try (Stream<Path> walk = Files.walk(dir)) {
            matches = new ArrayList<>(walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .filter(p -> archiveDir == null || !p.startsWith(archiveDir))   // never re-clean the archive
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException("cleanup walk failed under " + dir, e);
        }
        matches.sort(Comparator.comparing(MaintenanceJob::mtime).reversed());   // newest first
        // Policy dims combine as OR (MNT-2b): a file is affected when it breaches ANY configured limit —
        // older than retention, beyond the newest max_count files, or beyond the newest-first max_size budget.
        int affected = 0;
        long bytes = 0, kept = 0;
        for (int i = 0; i < matches.size(); i++) {
            Path p = matches.get(i);
            long size;
            try {
                size = Files.size(p);
            } catch (IOException e) {
                log.warn("cleanup: could not size {}: {}", p, e.getMessage());
                continue;
            }
            boolean victim = i >= minKeep
                    && (MaintenanceJob.olderThan(p, cutoff)
                        || (maxCount > 0 && i >= maxCount)
                        || (maxSize > 0 && kept + size > maxSize));
            if (!victim) {
                kept += size;
                continue;
            }
            try {
                if (!dryRun) {
                    if (archive) {
                        Path target = archiveDir.resolve(dir.relativize(p));
                        Files.createDirectories(target.getParent());
                        Files.move(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.delete(p);
                    }
                }
                affected++;
                bytes += size;
            } catch (IOException e) {
                log.warn("cleanup: could not {} {}: {}", archive ? "archive" : "delete", p, e.getMessage());
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        String action = archive ? "archive" : "delete";
        String verb = dryRun ? "cleanup[dry-run]: would " + action + " "
                             : "cleanup: " + action + "d ";
        return JobResult.ok(verb + affected + " file(s), " + bytes + " byte(s), older than " + days
                + "d under " + dir + " (glob=" + glob
                + (maxCount > 0 ? ", max_count=" + maxCount : "")
                + (maxSize > 0 ? ", max_size=" + maxSize : "")
                + (archive ? ", archive_dir=" + archiveDir : "") + ")", ms);
    }
}
