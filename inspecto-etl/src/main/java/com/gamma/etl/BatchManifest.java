package com.gamma.etl;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Serializable record of everything a batch produced, used by
 * {@code ura reprocess <consignment_id>} to delete outputs/markers and restore members.
 *
 * <p>Plain mutable fields (not a record) for straightforward Gson (de)serialization.
 */
public final class BatchManifest {

    /**
     * The unit of work this manifest describes. Serialised as {@code "consignmentId"} and read from
     * <b>either</b> spelling (consignment-ELT plan §11.3, decision 2's mandated accept-both-on-read).
     *
     * <p>Gson had no annotation here before, so the on-disk key was the field name, camelCase
     * {@code "batchId"}. Renaming the field alone would have yielded {@code null} for every manifest already
     * on disk <b>with no exception thrown</b>, silently breaking {@code ReprocessCommand}. {@code alternate}
     * is what makes the old key keep working; the Java field name stays {@code batchId} because §3/§15 keep
     * the {@code Batch*} internals and their accessors out of this slice's scope.
     */
    @SerializedName(value = "consignmentId", alternate = {"batchId"})
    public String batchId;
    public String pipeline;
    public String schemaName;
    public String outputTable;     // null when writing directly to dirs.database
    public String createdAt;

    /**
     * SHA-256 fingerprint ({@link com.gamma.util.CanonicalHash}) of the resolved schema map — mapping rules
     * included — that wrote this Consignment (ELT amendment §3.4.3: data carries its schema identity, without
     * an id server). {@code null} on manifests written before the field existed, and for schema-less drafts;
     * readers must tolerate that, never require it.
     */
    public String schemaFingerprint;
    public List<MemberEntry> members;
    public List<OutputEntry> outputs;
    public List<String>      markers;

    /**
     * Phase 4 S4b (park/drain, D-13): the disabled Step (sink node) ids this Consignment PARKED at —
     * {@code null}/absent on every non-parked manifest, so pre-S4b readers and manifests are
     * untouched. A parked Consignment is <b>uncommitted</b>: no markers, no ledger stash, no
     * watermark; the committed branches' rows are durable but unregistered until the drain (S4c)
     * completes the batch through the normal commit tail.
     */
    public List<String> parkedAt;

    /**
     * nodeId → the durable Parquet file holding the parked branch's rows (the park table) — the
     * inspectable intermediate D-13 exists for, and the drain's re-seed input. {@code null} when
     * {@link #parkedAt} is.
     */
    public java.util.Map<String, String> parkedTables;

    /**
     * @param filename        member file name
     * @param srcId           0-based index within the batch
     * @param originalRelPath member path relative to the poll dir (for restore target)
     * @param backupPath      computed backup destination (where the source was moved)
     * @param status          a {@link MemberStatus} constant's {@code name()} — the wire form is the
     *                        constant name, verbatim. Kept as a {@code String} rather than the enum
     *                        so Gson keeps reading a manifest whose status this build does not know
     *                        (an enum component would silently deserialize to {@code null}).
     */
    public record MemberEntry(String filename, int srcId, String originalRelPath,
                              String backupPath, String status) {}

    /**
     * @param partition  partition path, e.g. {@code "year=2020/month=04/day=03"}
     * @param outputFile absolute path of the produced output file
     */
    public record OutputEntry(String partition, String outputFile) {}
}
