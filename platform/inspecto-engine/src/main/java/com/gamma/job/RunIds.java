package com.gamma.job;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place a Run id is minted.
 *
 * <p>🔴 <b>Exactly one generator, deliberately</b> (operator decision 2026-09-13,
 * {@code docs/superpower/run-model-plan.md} §6). The alternative considered was to mint a second id at
 * {@code CollectorProcessor} for the paths that never reach {@link JobService}; it was refused because
 * "run id" would then mean two different things depending on which path produced the row — and this
 * codebase has already paid for that once, on the enrichment path, where a single string served as both
 * the audit run id and the Consignment id until it was untangled.
 *
 * <p>Format is {@code <slug>-<yyyyMMdd_HHmmss>-<seq>}, unchanged from when {@link JobService} owned it.
 *
 * <p>⚠ <b>A Run id is the ATTEMPT and is NOT deterministic</b> — {@code GLOSSARY.md} §6-A:
 * {@code Run ⊇ Consignment ⊇ File}, and a reprocess is a new Run over the <em>same</em> Consignment. So
 * this is clock-and-counter derived on purpose, and two executors of the same work mint different ids.
 * ⛔ That is why a Run id can never make a write idempotent across pods: split-brain is stopped by the
 * fenced {@code RunLease}, not by this. Do not "fix" the non-determinism here.
 *
 * <p>⚠ The counter is process-wide, where it used to be per-{@code JobService}-instance. That is a
 * widening, not a narrowing: within one JVM ids stay distinct, and the timestamp dominates anyway. Across
 * JVMs the counter never disambiguated anything in the first place.
 */
public final class RunIds {

    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final AtomicLong SEQ = new AtomicLong();

    private RunIds() {}

    /** A fresh Run id for {@code name} — the job, pipeline or collector this attempt belongs to. */
    public static String next(String name) {
        String slug = name == null || name.isBlank() ? "run" : name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        return slug + "-" + LocalDateTime.now().format(RUN_TS) + "-" + SEQ.incrementAndGet();
    }
}
