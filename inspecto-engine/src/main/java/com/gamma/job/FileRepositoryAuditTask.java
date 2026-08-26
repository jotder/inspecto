package com.gamma.job;

import com.gamma.signal.Severity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code file_repository_audit} maintenance task (MNT-12): read-only audit of the data root.
 * Finding classes: store directories with no owning Dataset component (informational — a sink may
 * legitimately write an unregistered store; checked only when a component registry is configured, never
 * guessed) and stale partial/temporary files left by interrupted writes ({@code *.tmp},
 * {@code *.compacting}, {@code .compact-journal}) older than {@code min_age_days} (default 1 —
 * the quiet window, so an in-flight write is never flagged). Findings go to the Run Log and one
 * {@code maintenance.filerepo.findings} WARNING signal.
 */
final class FileRepositoryAuditTask {

    private FileRepositoryAuditTask() {}

    static JobResult run(JobConfig cfg, String dataDir, JobContext ctx) {
        long t0 = System.nanoTime();
        if (dataDir == null || dataDir.isBlank() || !Files.isDirectory(Path.of(dataDir))) {
            return JobResult.ok("file_repository_audit: no data root — nothing to audit", 0L);
        }
        Path dataRoot = Path.of(dataDir);
        long minAgeDays = Long.parseLong(cfg.opt("min_age_days", "1"));
        Instant cutoff = Instant.now().minus(Duration.ofDays(minAgeDays));
        List<String> findings = new ArrayList<>();
        String writeRoot = System.getProperty("assist.write.root");
        if (writeRoot != null && !writeRoot.isBlank()) {
            Set<String> refs = new HashSet<>();
            var store = new com.gamma.pipeline.ComponentStore(Path.of(writeRoot).resolve("registry"));
            for (var d : store.list("dataset")) {
                Object ref = d.content().get("physicalRef");
                if (ref != null) refs.add(String.valueOf(ref));
            }
            try (Stream<Path> s = Files.list(dataRoot)) {
                for (Path p : s.filter(Files::isDirectory).toList()) {
                    String name = p.getFileName().toString();
                    if (!name.startsWith(".") && !refs.contains(name))
                        findings.add("store '" + name + "' has no owning dataset component");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("file_repository_audit list failed under " + dataRoot, e);
            }
        } else if (ctx != null) {
            ctx.log().info("unregistered-store check skipped (no component registry configured)");
        }
        try (Stream<Path> walk = Files.walk(dataRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                boolean partial = name.endsWith(".tmp") || name.endsWith(".compacting")
                        || name.equals(".compact-journal");
                if (partial && MaintenanceJob.olderThan(p, cutoff))
                    findings.add("stale partial file (older than " + minAgeDays + "d): " + dataRoot.relativize(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("file_repository_audit walk failed under " + dataRoot, e);
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty())
                ctx.signals().emit("maintenance.filerepo.findings", Severity.WARN,
                        Map.of("count", findings.size(), "findings", findings));
        }
        return JobResult.ok("file_repository_audit: " + findings.size() + " finding(s) under " + dataRoot
                + (findings.isEmpty() ? " — healthy" : ""), (System.nanoTime() - t0) / 1_000_000L);
    }
}
