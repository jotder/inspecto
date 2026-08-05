package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * One output file a Consignment wrote, as recorded in the durable {@code consignment_outputs} registry
 * (consignment-elt-architecture plan §11.3). The registry is the catalog substitute the no-catalog decision
 * (§5) implies: it is the only artifact that can answer <em>"every file this Consignment wrote, across all
 * partitions"</em> with lifecycle state attached.
 *
 * <p><b>Why this is not {@link com.gamma.etl.PartitionOutput}.</b> That record is an ephemeral
 * {@code (partition, outputFile, bytes)} return value from {@code PartitionWriter.reveal()} — it carries no
 * row count (a multi-file partitioned {@code COPY} reports none back) and nothing persists it. The row count
 * here has to be summed from {@code LineageCollector}'s per-{@code (srcId, partition)} counts, which is why
 * this is a distinct type rather than a persisted alias.
 *
 * <p><b>Relationship to the JSON manifest (decided).</b> {@code BatchManifest}/{@code ManifestStore} remain
 * the crash-recovery artifact of record — a plain JSON file survives a corrupt DuckDB, and
 * {@code ServiceStores} deliberately degrades a failed store open to {@code null}. So <b>the JSON is
 * authoritative for existence, this table for state</b>. Never treat a missing row here as proof the file
 * does not exist.
 *
 * @param consignmentId the unit of work that wrote the file. Named {@code consignment_id}, not
 *                      {@code batch_id}: GLOSSARY §13 bans <i>Batch</i> for this concept, and a table born
 *                      after that decision starts correct rather than needing the migration the legacy
 *                      artifacts do.
 * @param runId         the execution the Consignment belonged to (GLOSSARY §6-A: {@code Run ⊇ Consignment ⊇ File}).
 * @param tableName     the logical target the rows landed in.
 * @param partitionKey  the partition the file sits under, as written (e.g. {@code dt=2026-08-04}).
 * @param recordDay     the event-time day this file's rows belong to, cut at load time with the <em>pinned</em>
 *                      timezone (§5.6, §10.1) — never recomputed from current config at read time.
 * @param path          the revealed final path on disk.
 * @param rows          row count, summed across every lineage row sharing this output file.
 * @param bytes         file size as observed on disk after the atomic reveal.
 * @param writtenAt     ISO-8601 instant the file was revealed.
 * @param generation    per-partition compaction generation (§6.3) — compaction stages a new generation and
 *                      flips it, so a crash between write and unlink cannot double-count.
 * @param state         lifecycle state; see {@link State}.
 * @param schemaFingerprint SHA-256 ({@code CanonicalHash}) of the resolved schema map — mapping rules
 *                      included — that wrote the file (ELT amendment §3.4.3: data carries its schema
 *                      identity). {@code null} for rows written before the column existed and for write
 *                      paths that carry no pipeline schema (enrichment, Pipeline sinks) — never required.
 */
@PublicApi(since = "5.0.0")
public record ConsignmentOutput(
        String consignmentId,
        String runId,
        String tableName,
        String partitionKey,
        String recordDay,
        String path,
        long rows,
        long bytes,
        String writtenAt,
        int generation,
        State state,
        String schemaFingerprint) {

    /** Fingerprint-less form — pre-§3.4.3 call sites and write paths with no pipeline schema. */
    public ConsignmentOutput(String consignmentId, String runId, String tableName, String partitionKey,
                             String recordDay, String path, long rows, long bytes, String writtenAt,
                             int generation, State state) {
        this(consignmentId, runId, tableName, partitionKey, recordDay, path, rows, bytes, writtenAt,
                generation, state, null);
    }

    /**
     * Lifecycle of a registered output file.
     *
     * <p>{@link #COMPACTED_AWAY} is the state that fixes a live bug: {@code PartitionCompactor} rewrites files
     * the JSON manifest still points at, so today a reprocess of a Consignment whose output was compacted
     * away degrades to a no-op delete followed by re-ingest — which <b>duplicates its rows</b> (documented in
     * {@code PartitionCompactor}'s own javadoc). A durable state lets compaction mark what it replaced instead
     * of stranding a path nothing can resolve.
     */
    public enum State {
        /** Current and readable. */
        LIVE,
        /** Replaced by a reprocess of the same Consignment (§5.3). */
        SUPERSEDED,
        /** Merged into a compacted file and unlinked (§6.3). */
        COMPACTED_AWAY
    }
}
