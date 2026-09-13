package com.gamma.job;

import com.gamma.enrich.EnrichmentAuditWriter;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentEngine;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.ConsignmentEventBus;

import java.util.List;

/**
 * An {@link JobType#ENRICH} job: runs a Stage-2 enrichment once (full recompute),
 * writes run-level audit + lineage via {@link EnrichmentAuditWriter} (trigger {@code job}),
 * and publishes a chain {@link ConsignmentEvent} so downstream jobs/enrichments fire — the same
 * contract as {@code EnrichmentService} and the enrichment CLI.
 *
 * <p>Param: {@code config} — path to the enrichment {@code .toon}.
 */
final class EnrichJob implements Job {

    private final JobConfig cfg;
    private final ConsignmentEventBus bus;

    EnrichJob(JobConfig cfg, ConsignmentEventBus bus) {
        this.cfg = cfg;
        this.bus = bus;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "enrich"; }

    /**
     * The legacy no-arg entry point, kept because callers outside {@link JobService} still use it. It now
     * builds a {@link StandaloneRunContext} rather than leaving this path without a Run identity.
     */
    @Override
    public JobResult run() throws Exception {
        return run(StandaloneRunContext.forJob(cfg.name()));
    }

    /**
     * 🔴 <b>Two identities, deliberately not one.</b> This job mints {@code consignmentId} — the unit of
     * work — and takes the Run id (the ATTEMPT) from {@code ctx}. {@code GLOSSARY.md} §6-A separates them:
     * {@code Run ⊇ Consignment ⊇ File}, and a reprocess is a new Run over the <em>same</em> Consignment.
     *
     * <p>⚠ <b>The Consignment id keeps its exact previous value</b>, and that is the point. A single
     * string used to serve three roles here — the audit row's {@code runId} column, the
     * {@link ConsignmentEvent} correlation id, and the registry's {@code consignment_id}. Repurposing it
     * would have changed three observable values in order to fill one null column, so instead the Run id
     * is <b>added</b> alongside. ⛔ Do not "tidy" this by collapsing them back together.
     *
     * <p>⚠ The audit row's column is still named {@code runId} while holding the unit of work. That is a
     * pre-existing misnomer in a persisted CSV header, left alone on purpose: renaming it would rewrite an
     * operator-visible audit surface, which is a separate decision from filling the registry's column.
     */
    @Override
    public JobResult run(JobContext ctx) throws Exception {
        EnrichmentConfig job = EnrichmentConfig.load(cfg.require("config"));
        String consignmentId = cfg.name().toLowerCase().replace(' ', '_') + "-job-" + EnrichmentAuditWriter.runStamp();
        String start = EnrichmentAuditWriter.now();
        long t0 = System.nanoTime();

        // full recompute; decision rules match this job's name as well as the enrichment's
        EnrichmentEngine.Result res = EnrichmentEngine.runResult(job, null, List.of(),
                List.of(cfg.name()), consignmentId, ctx.runId());
        List<PartitionOutput> outs = res.outputs();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long bytes = outs.stream().mapToLong(PartitionOutput::bytes).sum();
        List<String> parts = outs.stream().map(PartitionOutput::partition).distinct().toList();

        EnrichmentAuditWriter audit =
                new EnrichmentAuditWriter(EnrichmentAuditWriter.auditDir(job), job.name());
        audit.record(new EnrichmentAuditWriter.RunRow(
                consignmentId, job.name(), "job", "job:" + cfg.name(), "full", 0,
                start, EnrichmentAuditWriter.now(), "SUCCESS",
                parts.size(), outs.size(), res.totalRows(), bytes, ms, ""), outs);

        // chain: a successful enrichment is a commit downstream jobs can subscribe to
        bus.publish(new ConsignmentEvent(job.name(), consignmentId, "SUCCESS", parts, res.totalRows(), ms, 0));

        return JobResult.ok(outs.size() + " partition file(s), " + res.totalRows() + " row(s)", ms);
    }
}
